package app.huginn.core.mesh.messaging

import app.huginn.core.mesh.DeliveryStatus
import app.huginn.core.mesh.MeshConfig
import app.huginn.core.mesh.MeshListener
import app.huginn.core.mesh.packet.Content
import app.huginn.core.mesh.packet.DataPackets
import app.huginn.core.mesh.packet.InnerPacket
import app.huginn.core.mesh.packet.OuterPacket
import app.huginn.core.mesh.routing.LocalDelivery
import app.huginn.core.mesh.routing.Router
import app.huginn.core.model.DeviceId
import app.huginn.core.model.ImageId
import app.huginn.core.model.MessageId
import app.huginn.core.model.PacketId
import app.huginn.core.model.RandomBytes
import app.huginn.core.transport.Cancellable
import app.huginn.core.transport.Scheduler
import java.security.MessageDigest

/**
 * Message-level mesh (build plan 2.4–2.6, spec D9, D34–D36, D43, D64):
 * every attempt is encrypted with a new packet ID; no receipt within 30 s → resend (up to 3 times) → pending;
 * pending messages retry when a new neighbour appears or on a slow timer, and give up after 3 days.
 */
internal class Messenger(
    private val me: DeviceId,
    private val router: Router,
    private val scheduler: Scheduler,
    private val wallClock: () -> Long,
    private val contacts: ContactDirectory,
    private val replayGuard: ReplayGuard,
    private val counters: CounterStore,
    private val outbox: Outbox,
    private val random: RandomBytes,
    private val config: MeshConfig,
    private val listener: MeshListener,
) : LocalDelivery {
    private class OutgoingImage(
        val imageId: ImageId,
        val chunks: List<ByteArray>,
    ) {
        var chunksSent = false
    }

    /** In-memory working state for one message that is still trying. The message itself lives in the [Outbox]. */
    private class Outgoing(
        val to: DeviceId,
        val inner: InnerPacket,
        val image: OutgoingImage?,
        val queuedAtMillis: Long,
    ) {
        var sentOnce = false
        var inRound = false
        var resendsLeft = 0
        var receiptTimer: Cancellable? = null
        var expiryTimer: Cancellable? = null
    }

    private val pending = LinkedHashMap<MessageId, Outgoing>()
    private val images = ImageAssembler()
    private var retryTimer: Cancellable? = null
    private var retryDelay = config.timerRetryStartMillis

    /** Resumes messages that were still trying when the engine last stopped (D78). */
    fun restore() {
        outbox.pending().forEach { track(outgoingFor(it)) }
    }

    fun send(
        to: DeviceId,
        content: Content,
    ): MessageId = enqueue(to, content, image = null)

    fun sendImage(
        to: DeviceId,
        bytes: ByteArray,
        purpose: Content.Purpose,
        imageId: ImageId?,
    ): MessageId {
        require(bytes.size in 1..purpose.maxBytes) { "image must be 1..${purpose.maxBytes} bytes" }
        val chunkCount = (bytes.size + CHUNK_DATA_SIZE - 1) / CHUNK_DATA_SIZE
        val id = imageId ?: ImageId.random(random)
        val manifest = Content.ImageManifest(id, bytes.size, chunkCount, sha256(bytes), purpose)
        return enqueue(to, manifest, bytes)
    }

    /** Read receipts are best effort: sent once, never retried. */
    fun markRead(
        from: DeviceId,
        ids: List<MessageId>,
    ) {
        ids.chunked(Content.MAX_ACKS).forEach { sendControl(from, Content.AckRead(it)) }
    }

    /** "Not delivered — retry?": starts a new 3-day period for a message that gave up. */
    fun retry(id: MessageId): Boolean {
        val entry = outbox.get(id)?.takeIf { it.gaveUp } ?: return false
        val restarted = entry.retried(wallClock())
        outbox.put(restarted)
        track(outgoingFor(restarted))
        return true
    }

    /** Photos currently being reassembled (for tests and diagnostics). */
    val imagesInProgress: Int get() = images.inProgress

    /** Hourly clean-up: drops photo reassemblies that stalled past their limit (D74). */
    fun sweep() = images.dropStale()

    fun onLinkUp() {
        retryDelay = config.timerRetryStartMillis
        retryIdle()
    }

    // ---------------------------------------------------------------- sending

    private fun enqueue(
        to: DeviceId,
        content: Content,
        image: ByteArray?,
    ): MessageId {
        requireNotNull(contacts.cipherFor(to)) { "not a contact" }
        val now = wallClock()
        val entry =
            OutboxEntry(to, InnerPacket(MessageId.random(random), counters.next(to), now, content), image, now, false)
        outbox.put(entry)
        track(outgoingFor(entry))
        return entry.id
    }

    private fun outgoingFor(entry: OutboxEntry): Outgoing {
        val manifest = entry.message.content as? Content.ImageManifest
        val bytes = entry.image
        val image =
            if (manifest != null && bytes != null) {
                val chunks =
                    (bytes.indices step CHUNK_DATA_SIZE).map {
                        bytes.copyOfRange(
                            it,
                            minOf(it + CHUNK_DATA_SIZE, bytes.size),
                        )
                    }
                OutgoingImage(manifest.imageId, chunks)
            } else {
                null
            }
        return Outgoing(entry.to, entry.message, image, entry.queuedAtMillis)
    }

    private fun track(out: Outgoing) {
        pending[out.inner.messageId] = out
        // Wall-clock based, so the 3-day limit also counts time the phone was off (D78).
        val remaining = (config.pendingExpiryMillis - (wallClock() - out.queuedAtMillis)).coerceAtLeast(0)
        out.expiryTimer = scheduler.schedule(remaining) { giveUp(out) }
        startRound(out)
        ensureRetryTimer()
    }

    private fun startRound(out: Outgoing) {
        out.receiptTimer?.cancel()
        out.inRound = true
        out.resendsLeft = config.resendsPerRound
        transmit(out)
    }

    private fun transmit(out: Outgoing) {
        val id = out.inner.messageId
        val cipher = contacts.cipherFor(out.to)
        if (pending[id] !== out || cipher == null) return
        val packet = DataPackets.seal(cipher, PacketId.random(random), me, out.to, out.inner)
        if (router.sendOwn(packet) == 0) {
            out.inRound = false // nobody around: wait for a new neighbour or the timer
            return
        }
        if (!out.sentOnce) {
            out.sentOnce = true
            listener.onStatus(out.to, id, DeliveryStatus.SENT)
        }
        out.image?.takeUnless { it.chunksSent }?.let { image ->
            image.chunksSent = true
            image.chunks.indices.forEach { sendChunk(out, image, it) }
        }
        out.receiptTimer =
            scheduler.schedule(config.receiptTimeoutMillis) {
                if (out.resendsLeft > 0) {
                    out.resendsLeft--
                    transmit(out)
                } else {
                    out.inRound = false
                }
            }
    }

    private fun giveUp(out: Outgoing) {
        val id = out.inner.messageId
        if (pending.remove(id) !== out) return
        out.receiptTimer?.cancel()
        // The engine forgets it; the outbox keeps it for "Retry" and late receipts (D75).
        outbox.get(id)?.let { outbox.put(it.gaveUp()) }
        listener.onStatus(out.to, id, DeliveryStatus.NOT_DELIVERED)
    }

    private fun retryIdle() {
        pending.values.filter { !it.inRound }.forEach(::startRound)
    }

    /** Slow timer retry (D64): acts only while at least one neighbour is connected. */
    private fun ensureRetryTimer() {
        if (retryTimer != null || pending.isEmpty()) return
        retryTimer =
            scheduler.schedule(retryDelay) {
                retryTimer = null
                if (router.linkCount > 0) retryIdle()
                retryDelay = minOf(retryDelay * 2, config.timerRetryMaxMillis)
                ensureRetryTimer()
            }
    }

    private fun sendChunk(
        out: Outgoing,
        image: OutgoingImage,
        index: Int,
    ) {
        val chunk = Content.ImageChunk(image.imageId, index, image.chunks[index])
        sendControl(out.to, chunk, counter = out.inner.counter)
    }

    /** Receipts, chunks and chunk requests: sent once, not tracked. Only chat content advances the counter. */
    private fun sendControl(
        to: DeviceId,
        content: Content,
        counter: Long = CONTROL_COUNTER,
    ) {
        val cipher = contacts.cipherFor(to) ?: return
        val inner = InnerPacket(MessageId.random(random), counter, wallClock(), content)
        router.sendOwn(DataPackets.seal(cipher, PacketId.random(random), me, to, inner))
    }

    // ---------------------------------------------------------------- receiving

    override fun onHandshake(packet: OuterPacket) = listener.onHandshake(packet.sender, packet.body)

    override fun onData(packet: OuterPacket) {
        val from = packet.sender
        val cipher = contacts.cipherFor(from) ?: return // strangers and blocked contacts
        val inner = DataPackets.open(cipher, packet) ?: return
        when (val content = inner.content) {
            is Content.AckDelivered -> {
                content.messageIds.forEach { onDelivered(from, it) }
            }

            is Content.AckRead -> {
                content.messageIds.forEach { listener.onStatus(from, it, DeliveryStatus.READ) }
            }

            is Content.ImageManifest -> {
                images.onManifest(from, inner, content)
            }

            is Content.ImageChunk -> {
                images.onChunk(from, content)
            }

            is Content.ChunkRequest -> {
                onChunkRequest(from, content)
            }

            is Content.Text, is Content.Reaction, is Content.Profile -> {
                if (replayGuard.firstTime(from, inner.messageId)) listener.onMessage(from, inner)
                sendControl(from, Content.AckDelivered(listOf(inner.messageId))) // also for repeats, so retries stop
            }
        }
    }

    private fun onDelivered(
        from: DeviceId,
        id: MessageId,
    ) {
        // A late receipt (for example via a carrier) also counts after the sender gave up.
        if (outbox.get(id)?.to != from) return
        outbox.remove(id)
        pending.remove(id)?.let {
            it.receiptTimer?.cancel()
            it.expiryTimer?.cancel()
        }
        listener.onStatus(from, id, DeliveryStatus.DELIVERED)
    }

    private fun onChunkRequest(
        from: DeviceId,
        request: Content.ChunkRequest,
    ) {
        val out = pending.values.firstOrNull { it.to == from && it.image?.imageId == request.imageId } ?: return
        val image = out.image ?: return
        request.missing.filter { it < image.chunks.size }.forEach { sendChunk(out, image, it) }
    }

    /** Receiving side of photo transfers (D35): reassembly with a cap, missing-chunk requests, hash check. */
    private inner class ImageAssembler {
        private inner class Assembly(
            val from: DeviceId,
            val manifestPacket: InnerPacket,
            val manifest: Content.ImageManifest,
        ) {
            val chunks = arrayOfNulls<ByteArray>(manifest.chunkCount)
            var received = 0
            var lastProgress = scheduler.now()
            var requestTimer: Cancellable? = null
        }

        private val assemblies = LinkedHashMap<ImageId, Assembly>()

        val inProgress: Int get() = assemblies.size

        fun onManifest(
            from: DeviceId,
            inner: InnerPacket,
            manifest: Content.ImageManifest,
        ) {
            if (replayGuard.contains(from, inner.messageId)) {
                sendControl(from, Content.AckDelivered(listOf(inner.messageId))) // already have it: stop the retries
                return
            }
            dropStale()
            assemblies[manifest.imageId]?.let {
                scheduleRequest(it)
                return
            }
            if (assemblies.size >= config.maxConcurrentImages) return // the sender will retry the manifest later
            val assembly = Assembly(from, inner, manifest)
            assemblies[manifest.imageId] = assembly
            scheduleRequest(assembly)
        }

        fun onChunk(
            from: DeviceId,
            chunk: Content.ImageChunk,
        ) {
            val assembly = assemblies[chunk.imageId]?.takeIf { it.from == from } ?: return
            if (chunk.index >= assembly.chunks.size || assembly.chunks[chunk.index] != null) return
            assembly.chunks[chunk.index] = chunk.data
            assembly.received++
            assembly.lastProgress = scheduler.now()
            if (assembly.received == assembly.chunks.size) finish(assembly) else scheduleRequest(assembly)
        }

        private fun finish(assembly: Assembly) {
            assembly.requestTimer?.cancel()
            assemblies.remove(assembly.manifest.imageId)
            val bytes = assembly.chunks.filterNotNull().fold(ByteArray(0)) { acc, chunk -> acc + chunk }
            val manifest = assembly.manifest
            if (bytes.size != manifest.totalBytes || !sha256(bytes).contentEquals(manifest.sha256)) return
            val id = assembly.manifestPacket.messageId
            if (replayGuard.firstTime(assembly.from, id)) {
                listener.onImage(assembly.from, assembly.manifestPacket, manifest, bytes)
            }
            sendControl(assembly.from, Content.AckDelivered(listOf(id)))
        }

        /** After a quiet spell, ask for whatever is still missing (D35). */
        private fun scheduleRequest(assembly: Assembly) {
            assembly.requestTimer?.cancel()
            assembly.requestTimer =
                scheduler.schedule(config.chunkRequestDelayMillis) {
                    if (assemblies[assembly.manifest.imageId] !== assembly) return@schedule
                    val missing = assembly.chunks.indices.filter { assembly.chunks[it] == null }
                    sendControl(assembly.from, Content.ChunkRequest(assembly.manifest.imageId, missing))
                }
        }

        fun dropStale() {
            val cutoff = scheduler.now() - config.imageAssemblyTimeoutMillis
            assemblies.values.filter { it.lastProgress < cutoff }.forEach {
                it.requestTimer?.cancel()
                assemblies.remove(it.manifest.imageId)
            }
        }
    }

    private companion object {
        /** Control packets don't advance the chat counter; their counter field is 1 (docs/PROTOCOL.md §3). */
        const val CONTROL_COUNTER = 1L

        /** Photo bytes per chunk: the inner packet then fills exactly the 512-byte padding step (D72). */
        const val CHUNK_DATA_SIZE = 512 - InnerPacket.HEADER_SIZE - ImageId.SIZE - 2

        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
