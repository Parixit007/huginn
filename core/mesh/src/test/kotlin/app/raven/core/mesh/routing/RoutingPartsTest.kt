package app.raven.core.mesh.routing

import app.raven.core.mesh.packet.LinkMessage
import app.raven.core.mesh.packet.OuterPacket
import app.raven.core.model.DeviceId
import app.raven.core.model.PacketId
import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SeenCacheTest {
    @Test
    fun `remembers ids until they are too old or pushed out`() {
        val cache = SeenCache(maxEntries = 2, retentionMillis = 1_000)
        val a = PacketId.random()
        val b = PacketId.random()
        val c = PacketId.random()
        assertTrue(cache.add(a, now = 0))
        assertFalse(cache.add(a, now = 10))
        assertTrue(cache.add(b, now = 20))
        assertTrue(cache.add(c, now = 30)) // pushes out a (size limit)
        assertFalse(cache.contains(a, now = 30))
        assertTrue(cache.contains(b, now = 1_000))
        assertFalse(cache.contains(b, now = 1_021)) // too old
    }
}

class SeenCacheModelTest {
    /** Compares the compact cache with a simple reference over many random operations. */
    @Test
    fun `behaves exactly like a simple list, including eviction and expiry`() {
        val random = kotlin.random.Random(42)
        val cache = SeenCache(maxEntries = 100, retentionMillis = 5_000, salt = 7)
        val reference = LinkedHashMap<PacketId, Long>()
        // A small ID pool forces lots of repeats, evictions and table collisions.
        val pool = List(300) { PacketId(random.nextBytes(8)) } + PacketId(ByteArray(8))
        var now = 0L
        repeat(50_000) {
            now += random.nextLong(0, 40)
            reference.entries.removeAll { now - it.value > 5_000 }
            val id = pool.random(random)
            if (random.nextBoolean()) {
                val expected = id !in reference
                if (expected) {
                    if (reference.size == 100) reference.remove(reference.keys.first())
                    reference[id] = now
                }
                assertEquals(expected, cache.add(id, now))
            } else {
                assertEquals(id in reference, cache.contains(id, now))
            }
            assertEquals(reference.size, cache.size)
        }
    }
}

class RateLimiterTest {
    @Test
    fun `allows a burst, then the steady rate`() {
        val limiter = RateLimiter(perSecond = 100, startMillis = 0)
        assertEquals(100, (1..1_000).count { limiter.tryAcquire(now = 0) })
        assertEquals(10, (1..1_000).count { limiter.tryAcquire(now = 100) })
    }
}

class CarryStoreTest {
    private fun packet(size: Int = 128) =
        OuterPacket(
            OuterPacket.Kind.DATA,
            OuterPacket.MAX_HOPS,
            PacketId.random(),
            DeviceId.random(),
            DeviceId.random(),
            ByteArray(size + OuterPacket.DATA_OVERHEAD),
        )

    @Test
    fun `drops the oldest packets to stay under the cap`() {
        val wire = packet().encode().size
        val store = InMemoryCarryStore(maxBytes = wire * 2L)
        val first = packet()
        val second = packet()
        val third = packet()
        store.put(first, 0)
        store.put(second, 1)
        store.put(third, 2)
        assertEquals(listOf(second.packetId, third.packetId), store.ids())
        assertEquals(wire * 2L, store.sizeBytes)
    }

    @Test
    fun `take removes, and old packets expire`() {
        val store = InMemoryCarryStore(maxBytes = 1_000_000)
        val old = packet()
        val fresh = packet()
        store.put(old, nowMillis = 0)
        store.put(fresh, nowMillis = 500)
        store.dropStoredBefore(100)
        assertNull(store.take(old.packetId))
        assertNotNull(store.take(fresh.packetId))
        assertEquals(0, store.sizeBytes)
    }
}

class LinkMessageTest {
    @Test
    fun `round-trips, allows an empty offer, caps the list`() {
        val ids = List(63) { PacketId.random() }
        val offer = LinkMessage(LinkMessage.Type.OFFER, ids)
        val decoded = checkNotNull(LinkMessage.decode(offer.encode()))
        assertEquals(ids, decoded.packetIds)
        assertEquals(2 + 63 * 8, offer.encode().size)
        assertNotNull(LinkMessage.decode(LinkMessage(LinkMessage.Type.OFFER, emptyList()).encode()))
        assertThrows<IllegalArgumentException> { LinkMessage(LinkMessage.Type.WANT, List(64) { PacketId.random() }) }
        assertNull(LinkMessage.decode(byteArrayOf(3, 0)))
        assertNull(LinkMessage.decode(byteArrayOf(1, 1, 0)))
    }

    @FuzzTest(maxDuration = "5m")
    fun `link message decoding never crashes and round-trips`(data: FuzzedDataProvider) {
        val bytes = data.consumeRemainingAsBytes()
        val message = LinkMessage.decode(bytes) ?: return
        assertArrayEquals(bytes, message.encode())
    }

    @Test
    fun `LINK packets pass the outer codec, but not with an empty or oversized body`() {
        fun link(bodySize: Int) =
            OuterPacket(
                OuterPacket.Kind.HANDSHAKE,
                1,
                PacketId.random(),
                DeviceId.random(),
                DeviceId.random(),
                ByteArray(1),
            ).encode()
                .copyOf(OuterPacket.HEADER_SIZE)
                .also {
                    it[1] =
                        OuterPacket.Kind.LINK.code
                            .toByte()
                } + ByteArray(bodySize)
        assertNotNull(OuterPacket.decode(link(2)))
        assertNull(OuterPacket.decode(link(0)))
        assertNull(OuterPacket.decode(link(513)))
    }
}
