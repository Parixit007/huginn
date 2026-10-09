package app.raven.core.mesh.packet

import app.raven.core.crypto.ContactRootKey
import app.raven.core.crypto.StaticKeyV1
import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull

/**
 * Relays decode packets from strangers. Whatever arrives: no crash, and anything accepted re-encodes
 * to exactly the same bytes (one valid encoding per packet).
 */
class PacketFuzzTest {
    @FuzzTest(maxDuration = "5m")
    fun `outer packet decoding never crashes and round-trips`(data: FuzzedDataProvider) {
        val bytes = data.consumeRemainingAsBytes()
        val packet = OuterPacket.decode(bytes) ?: return
        assertArrayEquals(bytes, packet.encode())
    }

    @FuzzTest(maxDuration = "5m")
    fun `inner packet decoding never crashes and round-trips`(data: FuzzedDataProvider) {
        val bytes = data.consumeRemainingAsBytes()
        val packet = InnerPacket.decode(bytes) ?: return
        assertArrayEquals(bytes, packet.encode())
    }

    @FuzzTest(maxDuration = "5m")
    fun `random packets never open and never crash`(data: FuzzedDataProvider) {
        val bytes = data.consumeRemainingAsBytes()
        val packet = OuterPacket.decode(bytes) ?: return
        if (packet.sender == packet.recipient) return
        val cipher = StaticKeyV1.contactCipher(ContactRootKey(ByteArray(32) { 4 }), packet.recipient, packet.sender)
        assertNull(DataPackets.open(cipher, packet))
    }
}
