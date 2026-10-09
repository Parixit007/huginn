package app.raven.core.mesh.packet

import app.raven.core.model.DeviceId
import app.raven.core.model.PacketId
import app.raven.core.model.wire.ByteReader
import app.raven.core.model.wire.ByteWriter
import app.raven.core.model.wire.MalformedInputException
import app.raven.core.model.wire.decodeOrNull

/**
 * What relays see (docs/PROTOCOL.md §2, spec D46):
 * `version ‖ kind ‖ hops_left ‖ packet_id ‖ sender_id ‖ recipient_id ‖ body`.
 */
class OuterPacket(
    val kind: Kind,
    val hopsLeft: Int,
    val packetId: PacketId,
    val sender: DeviceId,
    val recipient: DeviceId,
    body: ByteArray,
) {
    init {
        require(hopsLeft in 1..MAX_HOPS) { "hops_left must be 1..$MAX_HOPS, was $hopsLeft" }
        require(kind.bodySizeIsValid(body.size)) { "invalid ${kind.name} body size ${body.size}" }
    }

    private val bodyBytes = body.copyOf()

    val body: ByteArray get() = bodyBytes.copyOf()

    @Suppress("MagicNumber") // wire codes from docs/PROTOCOL.md §2
    enum class Kind(
        val code: Int,
    ) {
        /** Encrypted message for a contact; relayed. */
        DATA(1),

        /** Pairing message; direct link only, never relayed. */
        HANDSHAKE(2),

        /** Neighbour-to-neighbour OFFER/WANT exchange (D71); direct link only, never relayed. */
        LINK(3),
        ;

        fun bodySizeIsValid(size: Int): Boolean =
            when (this) {
                DATA -> size - DATA_OVERHEAD in InnerPacket.PADDING_BUCKETS
                HANDSHAKE, LINK -> size in 1..MAX_HANDSHAKE_BODY
            }
    }

    fun encode(): ByteArray =
        ByteWriter()
            .u8(VERSION)
            .u8(kind.code)
            .u8(hopsLeft)
            .bytes(packetId)
            .bytes(sender)
            .bytes(recipient)
            .bytes(bodyBytes)
            .toByteArray()

    fun associatedData(): ByteArray = associatedData(kind, packetId, sender, recipient)

    companion object {
        const val VERSION = 1

        /** A packet crosses at most this many links (spec D8). */
        const val MAX_HOPS = 8
        const val HEADER_SIZE = 3 + PacketId.SIZE + DeviceId.SIZE + DeviceId.SIZE

        /** Bytes v1 encryption adds to the inner packet: 24-byte nonce + 16-byte tag. */
        const val DATA_OVERHEAD = 40
        const val MAX_HANDSHAKE_BODY = 512
        const val MAX_SIZE = HEADER_SIZE + DATA_OVERHEAD + InnerPacket.MAX_SIZE

        /** Authenticated by the encryption: the header without hops_left, which changes at each hop. */
        fun associatedData(
            kind: Kind,
            packetId: PacketId,
            sender: DeviceId,
            recipient: DeviceId,
        ): ByteArray =
            ByteWriter()
                .u8(VERSION)
                .u8(kind.code)
                .bytes(packetId)
                .bytes(sender)
                .bytes(recipient)
                .toByteArray()

        /** Null for anything malformed: wrong version, unknown kind, bad hop count or body size. Never throws. */
        fun decode(bytes: ByteArray): OuterPacket? {
            if (bytes.size > MAX_SIZE) return null
            return decodeOrNull {
                val reader = ByteReader(bytes)
                reader.check(reader.u8() == VERSION) { "unsupported version" }
                val kindCode = reader.u8()
                val kind =
                    Kind.entries.firstOrNull { it.code == kindCode } ?: throw MalformedInputException("unknown kind")
                val hopsLeft = reader.u8()
                reader.check(hopsLeft in 1..MAX_HOPS) { "bad hops_left $hopsLeft" }
                val packetId = PacketId(reader.bytes(PacketId.SIZE))
                val sender = DeviceId(reader.bytes(DeviceId.SIZE))
                val recipient = DeviceId(reader.bytes(DeviceId.SIZE))
                val body = reader.rest()
                reader.check(kind.bodySizeIsValid(body.size)) { "bad body size ${body.size}" }
                OuterPacket(kind, hopsLeft, packetId, sender, recipient, body)
            }
        }
    }
}
