package app.raven.data.store

import app.raven.core.crypto.SecretBox
import app.raven.core.mesh.packet.OuterPacket
import app.raven.core.mesh.routing.CarryStore
import app.raven.core.model.ImageId
import app.raven.core.model.PacketId
import app.raven.core.model.toHex
import java.io.File

/**
 * Packets carried for others (spec D66–D69): on disk, encrypted with a key that exists **only in memory**.
 * A new instance (app restart, reboot, power-off) makes a new key and deletes whatever is left: the old files
 * could never be read again anyway. The list of what is carried also lives only in memory.
 */
class EncryptedFileCarryStore(
    private val directory: File,
    private val maxBytes: Long,
) : CarryStore {
    private class Entry(
        val file: File,
        val size: Long,
        val storedAt: Long,
    )

    private val box = SecretBox(SecretBox.newKey())
    private val entries = LinkedHashMap<PacketId, Entry>()

    init {
        directory.deleteRecursively()
        directory.mkdirs()
    }

    override var sizeBytes: Long = 0
        private set

    @Synchronized
    override fun put(
        packet: OuterPacket,
        nowMillis: Long,
    ) {
        if (packet.packetId in entries) return
        val sealed = box.seal(packet.encode(), packet.packetId.toByteArray())
        val size = sealed.size.toLong()
        if (size > maxBytes) return
        while (sizeBytes + size > maxBytes) remove(entries.keys.first())
        val file = File(directory, packet.packetId.toByteArray().toHex())
        file.writeBytes(sealed)
        entries[packet.packetId] = Entry(file, size, nowMillis)
        sizeBytes += size
    }

    @Synchronized
    override fun take(id: PacketId): OuterPacket? {
        val entry = entries[id] ?: return null
        val sealed = entry.file.readBytes()
        remove(id)
        return box.open(sealed, id.toByteArray())?.let(OuterPacket::decode)
    }

    @Synchronized
    override fun ids(): List<PacketId> = entries.keys.toList()

    @Synchronized
    override fun dropStoredBefore(cutoffMillis: Long) {
        entries.filterValues { it.storedAt < cutoffMillis }.keys.forEach(::remove)
    }

    private fun remove(id: PacketId) {
        val entry = entries.remove(id) ?: return
        entry.file.delete()
        sizeBytes -= entry.size
    }
}

/** Chat photos and avatars on disk, encrypted with the file key kept inside the encrypted database (spec §7). */
class EncryptedImageStore(
    private val directory: File,
    fileKey: ByteArray,
) {
    private val box = SecretBox(fileKey)

    init {
        directory.mkdirs()
    }

    fun save(
        id: ImageId,
        bytes: ByteArray,
    ) = file(id).writeBytes(box.seal(bytes, id.toByteArray()))

    fun load(id: ImageId): ByteArray? =
        file(id).takeIf { it.exists() }?.let { box.open(it.readBytes(), id.toByteArray()) }

    fun delete(id: ImageId) {
        file(id).delete()
    }

    private fun file(id: ImageId) = File(directory, id.toByteArray().toHex())
}
