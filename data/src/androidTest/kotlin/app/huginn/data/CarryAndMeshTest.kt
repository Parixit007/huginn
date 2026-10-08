package app.huginn.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.huginn.core.crypto.ContactRootKey
import app.huginn.core.crypto.StaticKeyV1
import app.huginn.core.crypto.pairing.PairedContact
import app.huginn.core.mesh.MeshListener
import app.huginn.core.mesh.MeshNode
import app.huginn.core.mesh.packet.Content
import app.huginn.core.mesh.packet.InnerPacket
import app.huginn.core.mesh.packet.OuterPacket
import app.huginn.core.model.DeviceId
import app.huginn.core.model.Nickname
import app.huginn.core.model.PacketId
import app.huginn.data.security.DatabaseKey
import app.huginn.data.store.EncryptedFileCarryStore
import app.huginn.transport.fake.SimNetwork
import app.huginn.transport.fake.VirtualScheduler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class CarryAndMeshTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun packet(random: Random = Random.Default) =
        OuterPacket(
            OuterPacket.Kind.DATA,
            OuterPacket.MAX_HOPS,
            PacketId(random.nextBytes(PacketId.SIZE)),
            DeviceId(random.nextBytes(DeviceId.SIZE)),
            DeviceId(random.nextBytes(DeviceId.SIZE)),
            random.nextBytes(InnerPacket.PADDING_BUCKETS[2] + OuterPacket.DATA_OVERHEAD),
        )

    @Test
    fun carriedPacketsAreEncryptedOnDiskAndComeBackIntact() {
        val dir = File(context.noBackupFilesDir, "carry-test-${UUID.randomUUID()}")
        val store = EncryptedFileCarryStore(dir, maxBytes = 10_000_000)
        val original = packet()
        store.put(original, nowMillis = 0)
        val onDisk = dir.listFiles()!!.single().readBytes()
        assertFalse(onDisk.containsSequence(original.body.copyOf(64)))
        assertArrayEquals(original.encode(), store.take(original.packetId)?.encode())
        assertEquals(0, dir.listFiles()!!.size) // handed over = file gone
        dir.deleteRecursively()
    }

    @Test
    fun aRestartMakesCarriedPacketsUnreadableAndDeletesThem() {
        val dir = File(context.noBackupFilesDir, "carry-test-${UUID.randomUUID()}")
        val before = EncryptedFileCarryStore(dir, maxBytes = 10_000_000)
        repeat(5) { before.put(packet(), nowMillis = 0) }
        assertEquals(5, dir.listFiles()!!.size)

        val after = EncryptedFileCarryStore(dir, maxBytes = 10_000_000) // new process: new memory-only key
        assertTrue(after.ids().isEmpty())
        assertEquals(0, dir.listFiles()!!.size)
        dir.deleteRecursively()
    }

    @Test
    fun theCarryCapDropsTheOldestFirst() {
        val dir = File(context.noBackupFilesDir, "carry-test-${UUID.randomUUID()}")
        val first = packet()
        val sealedSize = 27 + 40 + 512 + 40L
        val store = EncryptedFileCarryStore(dir, maxBytes = sealedSize * 3)
        store.put(first, 0)
        repeat(3) { store.put(packet(), it + 1L) }
        assertEquals(3, store.ids().size)
        assertNull(store.take(first.packetId))
        dir.deleteRecursively()
    }

    /** The real engine on the real database: a queued message survives an app restart (D78). */
    @Test
    fun aQueuedMessageSurvivesARestartThroughTheRealDatabase() {
        val tag = UUID.randomUUID().toString()
        val dbName = "mesh-$tag.db"

        fun openStorage() = Storage.open(context, DatabaseKey(context, alias = tag, fileName = "$tag.key"), dbName)

        val scheduler = VirtualScheduler()
        val network = SimNetwork(scheduler, seed = 1)
        val aliceRadio = network.addNode()
        val carolRadio = network.addNode()
        val root = ContactRootKey(ByteArray(32) { 9 })

        var storage = openStorage()
        val alice = DeviceId(storage.createIdentity(Nickname.clean("Alice")!!).deviceId)
        val carolId = DeviceId.random()
        storage.addContact(PairedContact(carolId, Nickname.clean("Carol")!!, root), 0)
        val received = mutableListOf<String>()
        val carol =
            MeshNode(
                carolId,
                carolRadio,
                scheduler,
                { peer -> if (peer == alice) StaticKeyV1.contactCipher(root, carolId, alice) else null },
                object : MeshListener {
                    override fun onMessage(
                        from: DeviceId,
                        message: InnerPacket,
                    ) {
                        received += (message.content as Content.Text).text
                    }
                },
            )
        carol.start()

        fun aliceNode(storage: Storage) =
            MeshNode(
                alice,
                aliceRadio,
                scheduler,
                storage.contactDirectory(),
                object : MeshListener {},
                carryStore = storage.newCarryStore(10_000_000),
                replayGuard = storage.replayGuard,
                counters = storage.counters,
                outbox = storage.outbox,
            )

        var node = aliceNode(storage).also { it.start() }
        val id = node.sendText(carolId, "written before the restart")
        scheduler.advanceBy(60_000)
        node.stop()
        storage.close() // the app is killed

        storage = openStorage()
        node = aliceNode(storage).also { it.start() }
        network.connect(aliceRadio, carolRadio)
        scheduler.advanceBy(60_000)

        assertEquals(listOf("written before the restart"), received)
        assertNull(storage.outbox.get(id)) // delivered: gone from the outbox
        node.stop()
        carol.stop()
        storage.close()
        context.deleteDatabase(dbName)
        File(context.noBackupFilesDir, "$tag.key").delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(tag)
    }
}
