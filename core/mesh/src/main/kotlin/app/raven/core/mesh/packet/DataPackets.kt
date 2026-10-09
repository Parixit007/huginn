package app.raven.core.mesh.packet

import app.raven.core.crypto.ContactCipher
import app.raven.core.model.DeviceId
import app.raven.core.model.PacketId

/** Puts an [InnerPacket] into an encrypted DATA [OuterPacket] and back (docs/PROTOCOL.md §2–3). */
object DataPackets {
    /**
     * Encrypts [inner] for one contact. Every call gives a new nonce; a retry should also use a new
     * [packetId] (spec D34) so observers can't link it to the first attempt.
     */
    fun seal(
        cipher: ContactCipher,
        packetId: PacketId,
        sender: DeviceId,
        recipient: DeviceId,
        inner: InnerPacket,
        hopsLeft: Int = OuterPacket.MAX_HOPS,
    ): OuterPacket {
        val kind = OuterPacket.Kind.DATA
        val body = cipher.seal(inner.encode(), OuterPacket.associatedData(kind, packetId, sender, recipient))
        return OuterPacket(kind, hopsLeft, packetId, sender, recipient, body)
    }

    /** Null if [packet] isn't a DATA packet that is authentic for [cipher], or its inner packet is malformed. */
    fun open(
        cipher: ContactCipher,
        packet: OuterPacket,
    ): InnerPacket? {
        if (packet.kind != OuterPacket.Kind.DATA) return null
        val plaintext = cipher.open(packet.body, packet.associatedData()) ?: return null
        return InnerPacket.decode(plaintext)
    }
}
