package app.huginn.data.store

import app.huginn.core.crypto.ContactCipher
import app.huginn.core.crypto.ContactRootKey
import app.huginn.core.crypto.StaticKeyV1
import app.huginn.core.mesh.messaging.ContactDirectory
import app.huginn.core.mesh.messaging.CounterStore
import app.huginn.core.mesh.messaging.Outbox
import app.huginn.core.mesh.messaging.OutboxEntry
import app.huginn.core.mesh.messaging.ReplayGuard
import app.huginn.core.mesh.packet.InnerPacket
import app.huginn.core.model.DeviceId
import app.huginn.core.model.MessageId
import app.huginn.data.db.MeshDatabase
import app.huginn.data.db.OutboxEntity
import app.huginn.data.db.ReceivedIdEntity

/** Replay protection in the encrypted database (spec D36, D77). */
class RoomReplayGuard(
    private val db: MeshDatabase,
) : ReplayGuard {
    override fun firstTime(
        peer: DeviceId,
        id: MessageId,
    ): Boolean = db.receivedIds().insert(ReceivedIdEntity(peer.toByteArray(), id.toByteArray())) != -1L

    override fun contains(
        peer: DeviceId,
        id: MessageId,
    ): Boolean = db.receivedIds().count(peer.toByteArray(), id.toByteArray()) > 0
}

class RoomCounterStore(
    private val db: MeshDatabase,
) : CounterStore {
    override fun next(peer: DeviceId): Long = db.counters().next(peer.toByteArray())
}

/** The outbox in the encrypted database: unsent messages survive restarts (D78). */
class RoomOutbox(
    private val db: MeshDatabase,
) : Outbox {
    override fun put(entry: OutboxEntry) {
        db.outbox().put(
            OutboxEntity(
                messageId = entry.id.toByteArray(),
                peerId = entry.to.toByteArray(),
                packet = entry.message.encode(),
                image = entry.image,
                queuedAt = entry.queuedAtMillis,
                gaveUp = entry.gaveUp,
            ),
        )
    }

    override fun get(id: MessageId): OutboxEntry? = db.outbox().get(id.toByteArray())?.toEntry()

    override fun remove(id: MessageId) = db.outbox().remove(id.toByteArray())

    override fun pending(): List<OutboxEntry> = db.outbox().pending().mapNotNull { it.toEntry() }

    private fun OutboxEntity.toEntry(): OutboxEntry? {
        val message = InnerPacket.decode(packet) ?: return null // only this app writes rows; never expected
        return OutboxEntry(DeviceId(peerId), message, image, queuedAt, gaveUp)
    }
}

/**
 * Contacts for the mesh engine. Blocked contacts get no cipher, so their packets are only relayed (D30).
 * Ciphers are cached; call [invalidate] after any change to a contact.
 */
class RoomContactDirectory(
    private val db: MeshDatabase,
    private val me: DeviceId,
) : ContactDirectory {
    private val cache = HashMap<DeviceId, ContactCipher?>()

    @Synchronized
    override fun cipherFor(peer: DeviceId): ContactCipher? =
        cache.getOrPut(peer) {
            db
                .contacts()
                .get(peer.toByteArray())
                ?.takeUnless { it.blocked }
                ?.let { StaticKeyV1.contactCipher(ContactRootKey(it.rootKey), me, peer) }
        }

    @Synchronized
    fun invalidate(peer: DeviceId) {
        cache.remove(peer)
    }
}
