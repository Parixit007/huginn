package app.huginn.core.mesh.scenario

import app.huginn.core.mesh.DeliveryStatus
import app.huginn.transport.fake.LinkConditions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.random.Random

/**
 * Build plan 2.9: radio transmissions per delivered message with plain flooding (D45). This is the
 * baseline v1.1 smart routing must beat. Writes build/reports/mesh-cost.md.
 */
class CostReportTest {
    private val rows = mutableListOf<String>()

    @Test
    fun `measure the cost of plain flooding`() {
        line(lossRate = 0.0)
        crowdTexts(lossRate = 0.0)
        crowdTexts(lossRate = 0.2)
        crowdPhoto()
        carried()
        val report =
            buildString {
                appendLine("| Scenario | Delivered | Radio transmissions | Per delivered message |")
                appendLine("|---|---|---|---|")
                rows.forEach(::appendLine)
            }
        File("build/reports").mkdirs()
        File("build/reports/mesh-cost.md").writeText(report)
        println(report)
    }

    private fun record(
        name: String,
        delivered: Int,
        total: Int,
        transmissions: Long,
    ) {
        val per = if (delivered == 0) "—" else (transmissions / delivered).toString()
        rows += "| $name | $delivered / $total | $transmissions | $per |"
    }

    private fun line(lossRate: Double) {
        val world = MeshWorld(seed = 100, LinkConditions(lossRate = lossRate))
        val phones = world.phones(world.network.line(9))
        world.befriend(phones.first(), phones.last())
        world.start()
        world.run(MINUTE)
        val before = world.network.transmissions
        val id = phones.first().node.sendText(phones.last().id, "end to end")
        world.run(10 * MINUTE)
        val delivered = if (phones.first().status(id) == DeliveryStatus.DELIVERED) 1 else 0
        record("Line of 9 phones (8 hops), text + receipt", delivered, 1, world.network.transmissions - before)
    }

    private fun crowdTexts(lossRate: Double) {
        val world = MeshWorld(seed = 101, LinkConditions(lossRate = lossRate))
        val crowd = world.phones(world.network.crowd(count = 30, size = 100.0, range = 35.0))
        val random = Random(101)
        val pairs =
            generateSequence { crowd.shuffled(random).take(2).let { it[0] to it[1] } }
                .filter { (a, b) -> hopDistance(world, a, b) in 1..8 }
                .take(10)
                .toList()
        pairs.forEach { (a, b) -> world.befriend(a, b) }
        world.start()
        world.run(MINUTE)
        val before = world.network.transmissions
        val ids = pairs.map { (a, b) -> a to a.node.sendText(b.id, "hello") }
        world.run(3 * HOUR)
        val delivered = ids.count { (a, id) -> a.status(id) == DeliveryStatus.DELIVERED }
        val loss = (lossRate * 100).toInt()
        record(
            "Crowd of 30, 10 texts + receipts, $loss% loss per link",
            delivered,
            ids.size,
            world.network.transmissions - before,
        )
    }

    private fun crowdPhoto() {
        val world = MeshWorld(seed = 102)
        val crowd = world.phones(world.network.crowd(count = 30, size = 100.0, range = 35.0))
        val alice = crowd[0]
        val (bob, hops) = farthestWithin(world, alice, maxHops = 8)
        world.befriend(alice, bob)
        world.start()
        world.run(MINUTE)
        val before = world.network.transmissions
        val beforeBytes = world.network.bytesSent
        val id = alice.node.sendImage(bob.id, Random(102).nextBytes(50 * 1024))
        world.run(HOUR)
        val delivered = if (alice.status(id) == DeliveryStatus.DELIVERED) 1 else 0
        val megabytes = "%.1f".format((world.network.bytesSent - beforeBytes) / 1_048_576.0)
        val limited = crowd.sumOf { it.node.stats.rateLimited }
        record(
            "Crowd of 30, one 50 KB photo, $hops hops ($megabytes MB on air, $limited packets rate-limited)",
            delivered,
            1,
            world.network.transmissions - before,
        )
    }

    private fun hopDistance(
        world: MeshWorld,
        from: MeshWorld.Phone,
        to: MeshWorld.Phone,
    ): Int = distances(world, from)[to] ?: -1

    private fun farthestWithin(
        world: MeshWorld,
        from: MeshWorld.Phone,
        maxHops: Int,
    ): Pair<MeshWorld.Phone, Int> = distances(world, from).filterValues { it <= maxHops }.maxBy { it.value }.toPair()

    private fun distances(
        world: MeshWorld,
        from: MeshWorld.Phone,
    ): Map<MeshWorld.Phone, Int> {
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
        return distance
    }

    /** The owner's A → B → C scenario: how many copies does the carrier end up holding? */
    private fun carried() {
        val world = MeshWorld(seed = 103)
        val (a, b, c) = List(3) { world.phone() }
        world.befriend(a, c)
        world.start()
        val id = a.node.sendText(c.id, "later")
        world.run(HOUR)
        world.connect(a, b)
        world.run(MINUTE)
        world.disconnect(a, b)
        val copies = b.carried.ids().size
        world.run(HOUR)
        world.connect(b, c)
        world.run(MINUTE)
        assertEquals(listOf("later"), c.texts())
        rows += "| Carried A → B → C: copies B carried for one message | 1 / 1 | — | $copies copies |"
        assertEquals(DeliveryStatus.SENT, a.status(id))
    }
}
