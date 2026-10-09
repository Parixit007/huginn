package app.raven.core.transport.link

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** PROTOCOL.md §8: fragmentation, the link token, the scan budget and the connection planner. */
class LinkLayerTest {
    private val config = LinkConfig()

    private fun reassemble(fragments: List<ByteArray>): List<FragmentResult> {
        val reassembler = Reassembler(config.maxPacketSize)
        return fragments.map(reassembler::accept)
    }

    // ---------------------------------------------------------------- framing

    @Test
    fun `packets of every size survive splitting and reassembly at every fragment size`() {
        val random = Random(1)
        for (fragmentSize in listOf(LinkConfig.FALLBACK_FRAGMENT, 23, 182, 244, 509, LinkConfig.MAX_FRAGMENT)) {
            for (size in listOf(1, 2, 16, 17, 18, 19, 20, 21, 509, 510, 1024, 4096, config.maxPacketSize)) {
                val packet = random.nextBytes(size)
                val fragments = Framing.split(packet, fragmentSize)
                assertTrue(fragments.all { it.size <= fragmentSize }, "fragment over $fragmentSize")
                val results = reassemble(fragments)
                assertTrue(results.dropLast(1).all { it == FragmentResult.Pending })
                assertArrayEquals(packet, (results.last() as FragmentResult.Packet).bytes, "size $size / $fragmentSize")
            }
        }
    }

    @Test
    fun `the largest packet needs 17 fragments at full MTU and 435 at the 20-byte fallback`() {
        val packet = ByteArray(config.maxPacketSize)
        assertEquals(17, Framing.split(packet, LinkConfig.MAX_FRAGMENT).size)
        assertEquals(435, Framing.split(packet, LinkConfig.FALLBACK_FRAGMENT).size)
    }

    @Test
    fun `fragment size follows the MTU, capped at 512 and never below 20`() {
        assertEquals(512, LinkConfig.fragmentSizeFor(517))
        assertEquals(182, LinkConfig.fragmentSizeFor(185))
        assertEquals(20, LinkConfig.fragmentSizeFor(23))
        assertEquals(20, LinkConfig.fragmentSizeFor(0))
    }

    @Test
    fun `a neighbour announcing an oversized packet is hostile`() {
        val reassembler = Reassembler(config.maxPacketSize)
        val tooBig = config.maxPacketSize + 1
        val fragment = byteArrayOf(Framing.FLAG_FIRST.toByte(), (tooBig shr 8).toByte(), tooBig.toByte(), 1)
        assertEquals(FragmentResult.Hostile, reassembler.accept(fragment))
    }

    @Test
    fun `broken fragments are dropped and the link recovers on the next packet`() {
        val reassembler = Reassembler(config.maxPacketSize)
        val good = Random(2).nextBytes(1200)
        val fragments = Framing.split(good, 100)

        assertEquals(FragmentResult.Dropped, reassembler.accept(byteArrayOf())) // empty
        assertEquals(FragmentResult.Dropped, reassembler.accept(byteArrayOf(0x01, 9))) // reserved bit
        assertEquals(FragmentResult.Dropped, reassembler.accept(fragments[1])) // continuation with no start
        assertEquals(FragmentResult.Dropped, reassembler.accept(byteArrayOf(0x80.toByte(), 0))) // short header
        assertEquals(FragmentResult.Dropped, reassembler.accept(byteArrayOf(0x80.toByte(), 0, 0))) // length 0
        // More data than the announced length of 1:
        assertEquals(FragmentResult.Dropped, reassembler.accept(byteArrayOf(0x80.toByte(), 0, 1, 7, 7)))

        // An unfinished packet is thrown away when a new one starts.
        reassembler.accept(fragments[0])
        reassembler.accept(fragments[1])
        val results = fragments.map(reassembler::accept)
        assertArrayEquals(good, (results.last() as FragmentResult.Packet).bytes)

        // Overrunning the announced length drops the packet.
        reassembler.accept(Framing.split(ByteArray(10), 100)[0].copyOf(8))
        assertEquals(FragmentResult.Dropped, reassembler.accept(byteArrayOf(0, 1, 2, 3, 4, 5, 6)))
        assertTrue(reassembler.dropped >= 8)
    }

    // ---------------------------------------------------------------- link token

    @Test
    fun `the lower token dials, a missing token can always be dialed`() {
        val low = LinkToken(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1))
        val high = LinkToken(byteArrayOf(0x80.toByte(), 0, 0, 0, 0, 0, 0, 0)) // unsigned: 0x80 > 0x00
        assertTrue(LinkToken.shouldDial(low, high))
        assertFalse(LinkToken.shouldDial(high, low))
        assertTrue(LinkToken.shouldDial(high, null))
        assertTrue(LinkToken.shouldDial(low, LinkToken(low.toByteArray())))
    }

    @Test
    fun `service data that isn't exactly 8 bytes is no token`() {
        assertNull(LinkToken.fromServiceData(null))
        assertNull(LinkToken.fromServiceData(ByteArray(7)))
        assertNull(LinkToken.fromServiceData(ByteArray(9)))
        assertEquals(LinkToken(ByteArray(8) { 3 }), LinkToken.fromServiceData(ByteArray(8) { 3 }))
    }

    // ---------------------------------------------------------------- scan budget

    @Test
    fun `at most 5 scan starts per 30 seconds`() {
        val budget = ScanBudget(config.scanStartsPerWindow, config.scanWindowMillis)
        repeat(5) {
            assertTrue(budget.canStart(it * 1000L))
            budget.record(it * 1000L)
        }
        assertFalse(budget.canStart(5_000))
        assertEquals(30_000, budget.nextStartAt(5_000))
        assertTrue(budget.canStart(30_000))
    }

    // ---------------------------------------------------------------- planner

    private val mine = LinkToken(ByteArray(8) { 0x10 })
    private val higher = LinkToken(ByteArray(8) { 0x20 })
    private val lower = LinkToken(ByteArray(8) { 0x01 })

    @Test
    fun `dial once per phone, only when our token is lower, while a slot is free`() {
        val planner = LinkPlanner<String>(config)
        assertTrue(planner.onSeen("a", higher, mine, now = 0))
        planner.onDialStarted("a", 0)
        assertFalse(planner.onSeen("a", higher, mine, now = 1), "duplicate scan result dialed twice")
        assertFalse(planner.onSeen("b", lower, mine, now = 1), "their token is lower: they dial")
        assertTrue(planner.onSeen("mac", null, mine, now = 1), "no token: always dialable")
        planner.paused = true
        assertFalse(planner.onSeen("c", higher, mine, now = 2))
        assertFalse(planner.wantsAdvertising)
    }

    @Test
    fun `when all 4 slots are full nothing is dialed or advertised, and extra accepted links are refused`() {
        val planner = LinkPlanner<String>(config)
        listOf("a", "b", "c", "d").forEach { assertTrue(planner.onLinkUp(it, now = 0)) }
        assertFalse(planner.wantsAdvertising)
        assertFalse(planner.onSeen("e", higher, mine, now = 1))
        assertFalse(planner.onLinkUp("e", now = 1), "a fifth accepted link must be closed")
        planner.onLinkDown("a")
        assertTrue(planner.wantsAdvertising)
        assertTrue(planner.onSeen("e", higher, mine, now = 2))
    }

    @Test
    fun `failed dials back off for 30 seconds and overdue dials are reported`() {
        val planner = LinkPlanner<String>(config)
        planner.onDialStarted("a", 0)
        assertEquals(listOf("a"), planner.overdueDials(config.dialTimeoutMillis))
        planner.onDialFailed("a", config.dialTimeoutMillis)
        assertFalse(planner.onSeen("a", higher, mine, now = config.dialTimeoutMillis + 1))
        assertTrue(planner.onSeen("a", higher, mine, now = config.dialTimeoutMillis + config.redialBackoffMillis))
    }

    @Test
    fun `rotation closes the oldest link only when full, someone waits, and it has had its 10 minutes`() {
        val planner = LinkPlanner<String>(config)
        val ten = config.rotationIntervalMillis
        planner.onLinkUp("old", now = 0)
        listOf("b", "c", "d").forEach { planner.onLinkUp(it, now = 60_000) }
        assertNull(planner.rotationVictim(ten), "nobody is waiting")
        planner.onSeen("new", higher, mine, now = ten - 1)
        assertNull(planner.rotationVictim(ten - 2), "the oldest link hasn't had its 10 minutes")
        assertEquals("old", planner.rotationVictim(ten))
        planner.onLinkDown("old")
        assertNull(planner.rotationVictim(ten), "a slot is free now")
        assertTrue(planner.onSeen("new", higher, mine, now = ten))
    }

    @Test
    fun `old sightings are forgotten`() {
        val planner = LinkPlanner<String>(config)
        listOf("a", "b", "c", "d").forEach { planner.onLinkUp(it, now = 0) }
        planner.onSeen("new", higher, mine, now = 0)
        planner.forgetOld(config.rotationIntervalMillis)
        assertNull(planner.rotationVictim(config.rotationIntervalMillis))
    }

    // ---------------------------------------------------------------- link pipe

    @Test
    fun `the pipe writes one fragment at a time and the other side gets the packets back`() {
        val written = mutableListOf<ByteArray>()
        val pipe =
            LinkPipe(config, fragmentSize = 20) {
                written += it
                WriteOutcome.STARTED
            }
        val receiver = LinkPipe(config, fragmentSize = 20) { WriteOutcome.STARTED }
        val packets = List(3) { Random(it).nextBytes(50 + it) }
        packets.forEach { assertTrue(pipe.enqueue(it)) }

        val received = mutableListOf<ByteArray>()
        var now = 0L
        while (true) {
            pipe.pump(now)
            assertEquals(1, written.size, "a second write started before the first finished")
            val result = receiver.onFragment(written.removeAt(0))
            if (result is FragmentResult.Packet) received += result.bytes
            pipe.onWriteDone()
            now++
            if (received.size == packets.size) break
        }
        packets.zip(received).forEach { (sent, got) -> assertArrayEquals(sent, got) }
        assertEquals(0, pipe.queuedPackets)
    }

    @Test
    fun `a busy radio gets the same fragment again, a failed one is reported`() {
        var answer = WriteOutcome.BUSY
        val attempts = mutableListOf<ByteArray>()
        val pipe =
            LinkPipe(config, fragmentSize = 100) {
                attempts += it
                answer
            }
        pipe.enqueue(ByteArray(10) { 1 })
        assertEquals(WriteOutcome.BUSY, pipe.pump(0))
        assertFalse(pipe.inFlight)
        answer = WriteOutcome.STARTED
        assertEquals(WriteOutcome.STARTED, pipe.pump(1))
        assertArrayEquals(attempts[0], attempts[1], "a different fragment was sent after BUSY")
        pipe.onWriteDone()
        pipe.enqueue(ByteArray(10))
        answer = WriteOutcome.FAILED
        assertEquals(WriteOutcome.FAILED, pipe.pump(2))
    }

    @Test
    fun `the queue holds at most 64 packets and a stuck write is noticed`() {
        val pipe = LinkPipe(config, fragmentSize = 100) { WriteOutcome.STARTED }
        repeat(config.queueMaxPackets) { assertTrue(pipe.enqueue(ByteArray(10))) }
        assertFalse(pipe.enqueue(ByteArray(10)))
        assertEquals(1, pipe.droppedPackets)
        pipe.pump(now = 1_000)
        assertFalse(pipe.isStuck(now = 5_000, limitMillis = 5_000))
        assertTrue(pipe.isStuck(now = 6_001, limitMillis = 5_000))
    }
}
