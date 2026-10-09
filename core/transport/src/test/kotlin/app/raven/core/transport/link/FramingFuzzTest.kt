package app.raven.core.transport.link

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue

/** Fragments arrive from any phone in range: reassembly must never crash or grow past the size limit. */
class FramingFuzzTest {
    private val maxPacket = LinkConfig().maxPacketSize

    @FuzzTest(maxDuration = "5m")
    fun `random fragments never crash and never produce an oversized packet`(data: FuzzedDataProvider) {
        val reassembler = Reassembler(maxPacket)
        while (data.remainingBytes() > 0) {
            val fragment = data.consumeBytes(data.consumeInt(0, LinkConfig.MAX_FRAGMENT))
            val result = reassembler.accept(fragment)
            if (result is FragmentResult.Packet) assertTrue(result.bytes.size in 1..maxPacket)
        }
    }

    @FuzzTest(maxDuration = "5m")
    fun `any packet survives a round trip, even with junk in front`(data: FuzzedDataProvider) {
        val fragmentSize = data.consumeInt(LinkConfig.FALLBACK_FRAGMENT, LinkConfig.MAX_FRAGMENT)
        val junk = data.consumeBytes(data.consumeInt(0, 600))
        val packet = data.consumeRemainingAsBytes().takeIf { it.isNotEmpty() && it.size <= maxPacket } ?: return
        val reassembler = Reassembler(maxPacket)
        if (junk.isNotEmpty()) reassembler.accept(junk)
        val results = Framing.split(packet, fragmentSize).map(reassembler::accept)
        assertArrayEquals(packet, (results.last() as FragmentResult.Packet).bytes)
    }
}
