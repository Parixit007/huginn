package app.raven.core.mesh.scenario

import app.raven.core.mesh.DeliveryStatus
import app.raven.core.mesh.packet.InnerPacket
import app.raven.core.mesh.packet.OuterPacket
import app.raven.core.model.DeviceId
import app.raven.core.model.PacketId
import app.raven.core.transport.LinkId
import app.raven.core.transport.TransportListener
import app.raven.transport.fake.LinkConditions
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Build plan 2.8 scenarios. Every run is repeatable: fixed seeds, virtual clock. */
class ScenarioTest {
    @Test
    fun `two phones - message, delivered tick, read tick`() {
        val world = MeshWorld(seed = 1)
        val (alice, bob) = world.phones(world.network.line(2))
        world.befriend(alice, bob)
        world.start()
        world.run(SECOND)

        val id = alice.node.sendText(bob.id, "hi Bob")
        world.run(5 * SECOND)
        assertEquals(listOf("hi Bob"), bob.texts())
        assertEquals(listOf(DeliveryStatus.SENT, DeliveryStatus.DELIVERED), alice.statuses[id])

        bob.node.markRead(alice.id, listOf(id))
        world.run(5 * SECOND)
        assertEquals(DeliveryStatus.READ, alice.status(id))
        world.assertNoDuplicates()
    }

    @Test
    fun `the 8-hop limit holds exactly - 8 hops arrive, 9 never do`() {
        val world = MeshWorld(seed = 2)
        val line = world.phones(world.network.line(10))
        val sender = line[0]
        world.befriend(sender, line[8])
        world.befriend(sender, line[9])
        world.start()
        world.run(SECOND)

        sender.node.sendText(line[8].id, "8 hops away")
        val tooFar = sender.node.sendText(line[9].id, "9 hops away")
        world.run(2 * HOUR)

        assertEquals(listOf("8 hops away"), line[8].texts())
        assertEquals(emptyList<String>(), line[9].texts())
        assertEquals(DeliveryStatus.SENT, sender.status(tooFar))
        world.assertNoDuplicates()
    }

    @Test
    fun `crowd of 30 phones with 20 percent packet loss - everyone reachable gets their message, ticks follow`() {
        val world = MeshWorld(seed = 3, conditions = LinkConditions(lossRate = 0.2))
        val crowd = world.phones(world.network.crowd(count = 30, size = 100.0, range = 35.0))
        val random = Random(3)
        val pairs = List(20) { crowd.shuffled(random).take(2).let { it[0] to it[1] } }.distinct()
        pairs.forEach { (from, to) -> world.befriend(from, to) }
        world.start()
        world.run(SECOND)

        val sent =
            pairs.map { (from, to) ->
                Triple(
                    from,
                    to,
                    from.node.sendText(to.id, "from ${from.id} to ${to.id}"),
                )
            }
        world.run(HOUR)

        val reachable = sent.filter { (from, to, _) -> hopDistance(world, from, to) in 1..8 }
        assertTrue(reachable.size >= 15, "test layout should connect most pairs, got ${reachable.size}")
        for ((from, to, _) in reachable) {
            assertTrue("from ${from.id} to ${to.id}" in to.texts(), "$to didn't get the message from $from")
        }
        // A receipt only leaves when a copy gets through, and must then survive the same lossy path back;
        // on long thin paths with 20% loss per link the tick can lag the message by an hour or more.
        world.run(2 * HOUR)
        for ((from, _, id) in reachable) {
            assertEquals(DeliveryStatus.DELIVERED, from.status(id), "$from got no delivered tick")
        }
        world.assertNoDuplicates()
    }

    @Test
    fun `split network heals - the pending message arrives once a bridge appears`() {
        val world = MeshWorld(seed = 4)
        val left = world.phones(world.network.line(3))
        val right = world.phones(world.network.line(3))
        val alice = left.first()
        val carol = right.last()
        world.befriend(alice, carol)
        world.start()
        world.run(SECOND)

        val id = alice.node.sendText(carol.id, "across the gap")
        world.run(HOUR)
        assertEquals(emptyList<String>(), carol.texts())

        world.connect(left.last(), right.first()) // someone walks between the groups
        world.run(10 * MINUTE)
        assertEquals(listOf("across the gap"), carol.texts())
        assertEquals(DeliveryStatus.DELIVERED, alice.status(id))
        world.assertNoDuplicates()
    }

    @Test
    fun `a 50 KB photo arrives intact over 20 percent loss`() {
        val world = MeshWorld(seed = 5, conditions = LinkConditions(lossRate = 0.2))
        val (alice, relay, bob) = world.phones(world.network.line(3))
        world.befriend(alice, bob)
        world.start()
        world.run(SECOND)

        val photo = Random(5).nextBytes(50 * 1024)
        val id = alice.node.sendImage(bob.id, photo)
        world.run(10 * MINUTE)

        assertEquals(1, bob.images.size)
        assertArrayEquals(photo, bob.images.single().second)
        assertEquals(DeliveryStatus.DELIVERED, alice.status(id))
        assertTrue(relay.images.isEmpty())
    }

    @Test
    fun `a garbage-flooding attacker is rate limited and real messages still arrive`() {
        val world = MeshWorld(seed = 6)
        val alice = world.phone()
        val relay = world.phone()
        val bob = world.phone()
        val attacker = world.network.addNode()
        world.connect(alice, relay)
        world.connect(relay, bob)
        world.network.connect(attacker, relay.radio)
        world.befriend(alice, bob)
        world.start()
        val attackerLinks = mutableListOf<LinkId>()
        attacker.start(
            object : TransportListener {
                override fun onLinkUp(link: LinkId) {
                    attackerLinks += link
                }

                override fun onLinkDown(link: LinkId) {
                    // the attacker doesn't care
                }

                override fun onReceive(
                    link: LinkId,
                    packet: ByteArray,
                ) {
                    // the attacker ignores everything it receives
                }
            },
        )
        world.run(SECOND)

        val random = Random(6)
        repeat(5_000) {
            val junk = if (it % 2 == 0) random.nextBytes(200) else fakeDataPacket(random)
            attacker.send(attackerLinks.single(), junk)
        }
        val id = alice.node.sendText(bob.id, "still works")
        world.run(MINUTE)

        assertTrue(relay.node.stats.rateLimited > 4_000, "rate limited: ${relay.node.stats.rateLimited}")
        assertTrue(relay.node.stats.relayed < 300, "relay forwarded ${relay.node.stats.relayed} packets")
        assertEquals(listOf("still works"), bob.texts())
        assertEquals(DeliveryStatus.DELIVERED, alice.status(id))
    }

    @Test
    fun `store and forward - A to C through B, who meets them hours apart`() {
        // The owner's scenario (2026-10-08): A sends to C while alone; 10 h later B passes A;
        // 5 h after that B meets C. C should get the message; A's tick comes when B passes A again.
        val world = MeshWorld(seed = 7)
        val a = world.phone()
        val b = world.phone()
        val c = world.phone()
        world.befriend(a, c)
        world.start()

        val id = a.node.sendText(c.id, "see you later")
        world.run(10 * HOUR)
        assertEquals(null, a.status(id)) // nobody around yet: still pending, not even sent

        world.connect(a, b)
        world.run(MINUTE)
        world.disconnect(a, b)
        assertEquals(DeliveryStatus.SENT, a.status(id))
        assertTrue(b.carried.ids().isNotEmpty(), "B should be carrying the message")

        world.run(5 * HOUR)
        world.connect(b, c)
        world.run(MINUTE)
        assertEquals(listOf("see you later"), c.texts())
        world.disconnect(b, c)

        world.run(5 * HOUR)
        world.connect(a, b) // B carries C's receipt back
        world.run(MINUTE)
        assertEquals(DeliveryStatus.DELIVERED, a.status(id))
        world.assertNoDuplicates()
    }

    @Test
    fun `timer retry reaches a friend who appears two hops away (D64)`() {
        // Bob always has another neighbour (Dave), so he forwards instead of carrying: only Alice's
        // slow timer retry can get the message to Carol when she shows up next to Bob.
        val world = MeshWorld(seed = 8)
        val (alice, bob, dave) = world.phones(world.network.line(3))
        val carol = world.phone()
        world.befriend(alice, carol)
        world.start()
        world.run(SECOND)

        val id = alice.node.sendText(carol.id, "found you")
        world.run(10 * MINUTE)
        assertEquals(0, bob.node.stats.carried)
        world.connect(bob, carol) // carol appears next to bob, two hops from alice
        world.run(30 * MINUTE)

        assertEquals(listOf("found you"), carol.texts())
        assertEquals(DeliveryStatus.DELIVERED, alice.status(id))
        assertEquals(0, bob.node.stats.handedOver)
        assertTrue(dave.carried.sizeBytes > 0) // the dead end carries copies it can never deliver
    }

    @Test
    fun `carried packets are dropped after 3 days`() {
        val world = MeshWorld(seed = 9)
        val a = world.phone()
        val b = world.phone()
        val c = world.phone()
        world.befriend(a, c)
        world.start()
        a.node.sendText(c.id, "nobody will deliver this")
        world.connect(a, b)
        world.run(MINUTE)
        world.disconnect(a, b)
        assertTrue(b.carried.sizeBytes > 0)

        world.run(3 * DAY + 2 * HOUR)
        assertEquals(0, b.carried.sizeBytes)
    }

    @Test
    fun `not delivered after 3 days, retry works, and a late receipt still counts`() {
        val world = MeshWorld(seed = 10)
        val alice = world.phone()
        val carol = world.phone()
        world.befriend(alice, carol)
        world.start()

        val id = alice.node.sendText(carol.id, "hello?")
        world.run(3 * DAY + MINUTE)
        assertEquals(DeliveryStatus.NOT_DELIVERED, alice.status(id))

        assertTrue(alice.node.retry(id))
        world.connect(alice, carol)
        world.run(MINUTE)
        assertEquals(listOf("hello?"), carol.texts())
        assertEquals(DeliveryStatus.DELIVERED, alice.status(id))
    }

    @Test
    fun `strangers relay but can't read, and blocked contacts are ignored`() {
        val world = MeshWorld(seed = 11)
        val (alice, stranger, bob) = world.phones(world.network.line(3))
        world.befriend(alice, bob)
        world.start()
        world.run(SECOND)
        alice.node.sendText(bob.id, "private")
        world.run(MINUTE)
        assertEquals(listOf("private"), bob.texts())
        assertTrue(stranger.received.isEmpty())
        assertTrue(stranger.node.stats.relayed > 0)
    }

    @Test
    fun `clean-up - unannounced links are ignored and stalled photos are swept`() {
        val world = MeshWorld(seed = 12)
        val (alice, bob) = world.phones(world.network.line(2))
        world.befriend(alice, bob)
        world.start()
        world.run(SECOND)

        bob.node.onReceive(LinkId(Long.MAX_VALUE), ByteArray(200))
        assertEquals(1, bob.node.stats.unknownLink)

        alice.node.sendImage(bob.id, Random(12).nextBytes(20 * 1024))
        world.run(30) // the manifest has arrived, the pieces are still in flight
        world.disconnect(alice, bob) // ...and the link breaks for good
        world.run(MINUTE)
        assertEquals(1, bob.node.imagesInProgress)
        world.run(3 * DAY + 2 * HOUR)
        assertEquals(0, bob.node.imagesInProgress)
    }

    @Test
    fun `a queued message survives an app restart (D78) and a given-up one stays retryable (D75)`() {
        val world = MeshWorld(seed = 13)
        val alice = world.phone()
        val carol = world.phone()
        world.befriend(alice, carol)
        world.start()

        val queued = alice.node.sendText(carol.id, "after the restart")
        world.run(HOUR)
        alice.restart()
        world.connect(alice, carol)
        world.run(MINUTE)
        assertEquals(listOf("after the restart"), carol.texts())
        assertEquals(null, alice.outbox.get(queued)) // delivered: gone from the outbox

        world.disconnect(alice, carol)
        world.run(MINUTE)
        val lonely = alice.node.sendText(carol.id, "too late")
        world.run(3 * DAY + MINUTE)
        assertTrue(alice.outbox.get(lonely)?.gaveUp == true) // the engine let go; the outbox kept it
        alice.restart()
        assertTrue(alice.node.retry(lonely))
        world.connect(alice, carol)
        world.run(MINUTE)
        assertEquals(listOf("after the restart", "too late"), carol.texts())
    }

    private fun hopDistance(
        world: MeshWorld,
        from: MeshWorld.Phone,
        to: MeshWorld.Phone,
    ): Int {
        val distance = mutableMapOf(from to 0)
        val queue = ArrayDeque(listOf(from))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (next in world.phones) {
                if (next !in distance && world.network.isConnected(current.radio, next.radio)) {
                    distance[next] = distance.getValue(current) + 1
                    queue.addLast(next)
                }
            }
        }
        return distance[to] ?: -1
    }

    /** A structurally valid DATA packet with random IDs and random (undecryptable) contents. */
    private fun fakeDataPacket(random: Random): ByteArray =
        OuterPacket(
            OuterPacket.Kind.DATA,
            OuterPacket.MAX_HOPS,
            PacketId(random.nextBytes(PacketId.SIZE)),
            DeviceId(random.nextBytes(DeviceId.SIZE)),
            DeviceId(random.nextBytes(DeviceId.SIZE)),
            random.nextBytes(InnerPacket.PADDING_BUCKETS.first() + OuterPacket.DATA_OVERHEAD),
        ).encode()
}
