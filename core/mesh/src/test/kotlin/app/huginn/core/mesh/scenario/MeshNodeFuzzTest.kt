package app.huginn.core.mesh.scenario

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest

/** A live mesh node fed raw bytes from a stranger's link: whatever arrives, it must never crash. */
class MeshNodeFuzzTest {
    @FuzzTest(maxDuration = "5m")
    fun randomBytesFromANeighbourNeverCrashAMeshNode(data: FuzzedDataProvider) {
        val world = MeshWorld(seed = data.consumeLong())
        val (alice, bob) = world.phones(world.network.line(2))
        world.befriend(alice, bob)
        world.start()
        world.run(SECOND)
        val neighbour = alice.radio.links.single() // bytes arrive on a real, announced link
        while (data.remainingBytes() > 0) {
            alice.node.onReceive(neighbour, data.consumeBytes(data.consumeInt(0, 600)))
            world.run(data.consumeLong(0, 10 * SECOND))
        }
    }
}
