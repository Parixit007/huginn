package app.raven.core.mesh

import app.raven.core.mesh.messaging.ContactDirectory
import app.raven.core.mesh.messaging.CounterStore
import app.raven.core.mesh.messaging.InMemoryCounterStore
import app.raven.core.mesh.messaging.InMemoryOutbox
import app.raven.core.mesh.messaging.InMemoryReplayGuard
import app.raven.core.mesh.messaging.Messenger
import app.raven.core.mesh.messaging.Outbox
import app.raven.core.mesh.messaging.ReplayGuard
import app.raven.core.mesh.packet.Content
import app.raven.core.mesh.packet.InnerPacket
import app.raven.core.mesh.packet.OuterPacket
import app.raven.core.mesh.routing.CarryStore
import app.raven.core.mesh.routing.InMemoryCarryStore
import app.raven.core.mesh.routing.LocalDelivery
import app.raven.core.mesh.routing.Router
import app.raven.core.mesh.routing.RouterStats
import app.raven.core.model.DeviceId
import app.raven.core.model.ImageId
import app.raven.core.model.MessageId
import app.raven.core.model.Nickname
import app.raven.core.model.RandomBytes
import app.raven.core.transport.Cancellable
import app.raven.core.transport.LinkId
import app.raven.core.transport.Scheduler
import app.raven.core.transport.Transport
import app.raven.core.transport.TransportListener

/** Message ticks (spec D18): ⏳ pending is "no status yet". */
enum class DeliveryStatus {
    /** Left this phone (reached at least one neighbour). */
    SENT,
    DELIVERED,
    READ,

    /** No delivery receipt within 3 days (D43). A carrier may still deliver it later. */
    NOT_DELIVERED,
}

/** What the app hears from the mesh. All callbacks run on the engine's thread. */
interface MeshListener {
    /** Text, reaction or profile from a contact, shown once (repeats are filtered). */
    fun onMessage(
        from: DeviceId,
        message: InnerPacket,
    ) {}

    /** A complete, hash-checked photo or avatar. */
    fun onImage(
        from: DeviceId,
        message: InnerPacket,
        manifest: Content.ImageManifest,
        bytes: ByteArray,
    ) {}

    fun onStatus(
        peer: DeviceId,
        messageId: MessageId,
        status: DeliveryStatus,
    ) {}

    /** A pairing message for this phone, from a direct neighbour. */
    fun onHandshake(
        from: DeviceId,
        body: ByteArray,
    ) {}
}

/**
 * One phone's mesh engine: the [Router] (packets) plus the [Messenger] (messages). Single-threaded: call it
 * only from the thread the [Transport] and [Scheduler] use.
 */
class MeshNode(
    val me: DeviceId,
    private val transport: Transport,
    private val scheduler: Scheduler,
    contacts: ContactDirectory,
    listener: MeshListener,
    private val config: MeshConfig = MeshConfig(),
    wallClock: () -> Long = System::currentTimeMillis,
    random: RandomBytes = RandomBytes.secure,
    carryStore: CarryStore = InMemoryCarryStore(config.carryMaxBytes),
    replayGuard: ReplayGuard = InMemoryReplayGuard(),
    counters: CounterStore = InMemoryCounterStore(),
    outbox: Outbox = InMemoryOutbox(),
) : TransportListener {
    private val messenger: Messenger
    private val router =
        Router(
            me,
            transport,
            scheduler,
            config,
            carryStore,
            random,
            object : LocalDelivery {
                override fun onData(packet: OuterPacket) = messenger.onData(packet)

                override fun onHandshake(packet: OuterPacket) = messenger.onHandshake(packet)
            },
        )
    private var sweep: Cancellable? = null

    init {
        messenger =
            Messenger(
                me,
                router,
                scheduler,
                wallClock,
                contacts,
                replayGuard,
                counters,
                outbox,
                random,
                config,
                listener,
            )
    }

    val stats: RouterStats get() = router.stats

    internal val imagesInProgress: Int get() = messenger.imagesInProgress

    fun start() {
        messenger.restore()
        transport.start(this)
        scheduleSweep()
    }

    fun stop() {
        sweep?.cancel()
        transport.stop()
    }

    fun sendText(
        to: DeviceId,
        text: String,
    ): MessageId = messenger.send(to, Content.Text(text))

    fun sendReaction(
        to: DeviceId,
        target: MessageId,
        emoji: String,
    ): MessageId = messenger.send(to, Content.Reaction(target, emoji))

    fun sendProfile(
        to: DeviceId,
        nickname: Nickname,
        avatar: ImageId?,
    ): MessageId = messenger.send(to, Content.Profile(nickname, avatar))

    fun sendImage(
        to: DeviceId,
        bytes: ByteArray,
        purpose: Content.Purpose = Content.Purpose.CHAT_IMAGE,
        /** Give an avatar a known ID so a PROFILE message can refer to it (D42); otherwise random. */
        imageId: ImageId? = null,
    ): MessageId = messenger.sendImage(to, bytes, purpose, imageId)

    fun markRead(
        from: DeviceId,
        ids: List<MessageId>,
    ) = messenger.markRead(from, ids)

    fun retry(id: MessageId): Boolean = messenger.retry(id)

    /** Sends a pairing message to a direct neighbour. Returns how many neighbours it went out to. */
    fun sendHandshake(
        to: DeviceId,
        body: ByteArray,
    ): Int = router.sendHandshake(to, body)

    override fun onLinkUp(link: LinkId) {
        router.onLinkUp(link)
        messenger.onLinkUp()
    }

    override fun onLinkDown(link: LinkId) = router.onLinkDown(link)

    override fun onReceive(
        link: LinkId,
        packet: ByteArray,
    ) = router.onReceive(link, packet)

    /** Hourly clean-up: carried packets past their 3-day limit (D67) and stalled photo reassemblies (D74). */
    private fun scheduleSweep() {
        sweep =
            scheduler.schedule(MeshConfig.HOUR) {
                router.expireCarried()
                messenger.sweep()
                scheduleSweep()
            }
    }
}
