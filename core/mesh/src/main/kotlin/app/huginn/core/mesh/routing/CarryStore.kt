package app.huginn.core.mesh.routing

import app.huginn.core.mesh.packet.OuterPacket
import app.huginn.core.model.PacketId

/**
 * Packets this phone carries for others until it can pass them on (spec D66–D69).
 * Phase 2: [InMemoryCarryStore]. Phase 3: encrypted files with a memory-only key.
 */
interface CarryStore {
    val sizeBytes: Long

    /** Stores [packet], dropping the oldest packets if needed to stay under the cap. */
    fun put(
        packet: OuterPacket,
        nowMillis: Long,
    )

    /** Removes and returns a packet, or null if it isn't carried (any more). */
    fun take(id: PacketId): OuterPacket?

    /** Carried packet IDs, oldest first. */
    fun ids(): List<PacketId>

    /** Drops every packet stored before [cutoffMillis]. */
    fun dropStoredBefore(cutoffMillis: Long)
}

class InMemoryCarryStore(
    private val maxBytes: Long,
) : CarryStore {
    private class Entry(
        val packet: OuterPacket,
        val size: Int,
        val storedAt: Long,
    )

    private val entries = LinkedHashMap<PacketId, Entry>()

    override var sizeBytes: Long = 0
        private set

    override fun put(
        packet: OuterPacket,
        nowMillis: Long,
    ) {
        val size = packet.encode().size
        if (size > maxBytes || packet.packetId in entries) return
        while (sizeBytes + size > maxBytes) take(entries.keys.first())
        entries[packet.packetId] = Entry(packet, size, nowMillis)
        sizeBytes += size
    }

    override fun take(id: PacketId): OuterPacket? {
        val entry = entries.remove(id) ?: return null
        sizeBytes -= entry.size
        return entry.packet
    }

    override fun ids(): List<PacketId> = entries.keys.toList()

    override fun dropStoredBefore(cutoffMillis: Long) {
        entries.values
            .filter { it.storedAt < cutoffMillis }
            .forEach { take(it.packet.packetId) }
    }
}
