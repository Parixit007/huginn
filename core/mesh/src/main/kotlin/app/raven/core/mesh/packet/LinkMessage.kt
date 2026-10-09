package app.raven.core.mesh.packet

import app.raven.core.model.PacketId
import app.raven.core.model.wire.ByteReader
import app.raven.core.model.wire.ByteWriter
import app.raven.core.model.wire.MalformedInputException
import app.raven.core.model.wire.decodeOrNull

/**
 * Body of a LINK packet (docs/PROTOCOL.md §6, spec D71): `link_type ‖ count ‖ packet_ids`.
 * OFFER = "I'm carrying these", WANT = "send me these". An empty OFFER doubles as "hello" so a new
 * neighbour learns our device ID.
 */
class LinkMessage(
    val type: Type,
    val packetIds: List<PacketId>,
) {
    init {
        require(packetIds.size <= MAX_IDS) { "at most $MAX_IDS packet IDs per LINK message" }
    }

    enum class Type(
        val code: Int,
    ) {
        OFFER(1),
        WANT(2),
    }

    fun encode(): ByteArray {
        val writer = ByteWriter().u8(type.code).u8(packetIds.size)
        packetIds.forEach(writer::bytes)
        return writer.toByteArray()
    }

    companion object {
        const val MAX_IDS = 63

        fun decode(body: ByteArray): LinkMessage? =
            decodeOrNull {
                val reader = ByteReader(body)
                val code = reader.u8()
                val type =
                    Type.entries.firstOrNull { it.code == code } ?: throw MalformedInputException("bad link type")
                val count = reader.u8()
                reader.check(count <= MAX_IDS) { "too many IDs" }
                val ids = List(count) { PacketId(reader.bytes(PacketId.SIZE)) }
                reader.requireEnd()
                LinkMessage(type, ids)
            }
    }
}
