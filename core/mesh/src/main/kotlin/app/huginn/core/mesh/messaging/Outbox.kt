package app.huginn.core.mesh.messaging

import app.huginn.core.mesh.packet.InnerPacket
import app.huginn.core.model.DeviceId
import app.huginn.core.model.MessageId

/**
 * One message this phone sent and has no receipt for yet (spec D9, D43).
 * [queuedAtMillis] is wall-clock time, so the 3-day limit survives restarts (D78).
 */
class OutboxEntry(
    val to: DeviceId,
    val message: InnerPacket,
    image: ByteArray?,
    val queuedAtMillis: Long,
    /** True once it showed "Not delivered" (D43). Kept so "Retry" and late receipts still work (D75). */
    val gaveUp: Boolean,
) {
    private val imageBytes = image?.copyOf()

    /** The whole photo, for IMAGE_MANIFEST messages; null otherwise. */
    val image: ByteArray? get() = imageBytes?.copyOf()

    val id: MessageId get() = message.messageId

    fun gaveUp(): OutboxEntry = OutboxEntry(to, message, imageBytes, queuedAtMillis, gaveUp = true)

    fun retried(nowMillis: Long): OutboxEntry = OutboxEntry(to, message, imageBytes, nowMillis, gaveUp = false)
}

/**
 * Where unsent and given-up messages live, so the engine doesn't keep them in memory forever (D75) and they
 * survive restarts (D78). Phase 2: [InMemoryOutbox]. Phase 3: the encrypted database.
 */
interface Outbox {
    fun put(entry: OutboxEntry)

    fun get(id: MessageId): OutboxEntry?

    /** A receipt arrived: the message is delivered and leaves the outbox. */
    fun remove(id: MessageId)

    /** Entries still trying (not given up), oldest first. Loaded when the engine starts. */
    fun pending(): List<OutboxEntry>
}

class InMemoryOutbox : Outbox {
    private val entries = LinkedHashMap<MessageId, OutboxEntry>()

    override fun put(entry: OutboxEntry) {
        entries[entry.id] = entry
    }

    override fun get(id: MessageId): OutboxEntry? = entries[id]

    override fun remove(id: MessageId) {
        entries.remove(id)
    }

    override fun pending(): List<OutboxEntry> = entries.values.filterNot { it.gaveUp }
}
