package app.raven.core.mesh.scenario

import app.raven.core.transport.LinkId
import app.raven.core.transport.TransportListener
import app.raven.transport.fake.SimTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Phase 5 link rules in the engine: the 10 s hello (D98) and duplicate links (PROTOCOL.md §8.2). */
class LinkHygieneTest {
    /** A radio with no Raven behind it: it accepts the connection and never says anything useful. */
    private fun silentRadio(radio: SimTransport) =
        radio.also {
            it.start(
                object : TransportListener {
                    override fun onLinkUp(link: LinkId) = Unit

                    override fun onLinkDown(link: LinkId) = Unit

                    override fun onReceive(
                        link: LinkId,
                        packet: ByteArray,
                    ) = Unit
                },
            )
        }

    @Test
    fun `a connection that never says hello is closed after 10 s, real neighbours stay`() {
        val world = MeshWorld(seed = 40)
        val alice = world.phone()
        val bob = world.phone()
        val stranger = silentRadio(world.network.addNode())
        world.connect(alice, bob)
        world.network.connect(alice.radio, stranger)
        world.start()

        world.run(9 * SECOND)
        assertTrue(world.network.isConnected(alice.radio, stranger), "closed too early")
        world.run(2 * SECOND)
        assertFalse(world.network.isConnected(alice.radio, stranger), "silent link still open")
        assertTrue(world.network.isConnected(alice.radio, bob.radio), "a real neighbour was dropped")
        assertEquals(1, alice.node.stats.silentLinksClosed)
    }

    @Test
    fun `a stranger sending garbage is closed too - only a valid hello counts`() {
        val world = MeshWorld(seed = 41)
        val alice = world.phone()
        val stranger = silentRadio(world.network.addNode())
        world.network.connect(alice.radio, stranger)
        world.start()
        val random = Random(41)
        repeat(20) {
            world.run(SECOND / 2)
            stranger.links.forEach { stranger.send(it, random.nextBytes(random.nextInt(1, 600))) }
        }
        assertFalse(world.network.isConnected(alice.radio, stranger))
        assertEquals(1, alice.node.stats.silentLinksClosed)
    }

    @Test
    fun `a second link to the same phone is closed and messages still arrive once`() {
        val world = MeshWorld(seed = 42)
        val alice = world.phone()
        val bob = world.phone()
        world.befriend(alice, bob)
        world.connect(alice, bob)
        world.start()
        world.run(SECOND)

        world.network.connect(alice.radio, bob.radio, allowSecond = true)
        assertEquals(2, alice.radio.links.size)
        world.run(SECOND)
        assertEquals(1, alice.radio.links.size, "duplicate link still open")
        assertEquals(1, bob.radio.links.size)
        assertTrue(alice.node.stats.duplicateLinksClosed + bob.node.stats.duplicateLinksClosed >= 1)

        alice.node.sendText(bob.id, "still here")
        world.run(5 * SECOND)
        assertEquals(listOf("still here"), bob.texts())
        world.run(MINUTE)
        assertEquals(1, alice.radio.links.size, "the remaining link must survive the hello timeout")
        world.assertNoDuplicates()
    }
}
