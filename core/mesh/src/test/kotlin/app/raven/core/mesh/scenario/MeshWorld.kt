package app.raven.core.mesh.scenario

import app.raven.core.crypto.ContactCipher
import app.raven.core.crypto.ContactRootKey
import app.raven.core.crypto.StaticKeyV1
import app.raven.core.mesh.DeliveryStatus
import app.raven.core.mesh.MeshConfig
import app.raven.core.mesh.MeshListener
import app.raven.core.mesh.MeshNode
import app.raven.core.mesh.messaging.InMemoryCounterStore
import app.raven.core.mesh.messaging.InMemoryOutbox
import app.raven.core.mesh.messaging.InMemoryReplayGuard
import app.raven.core.mesh.packet.Content
import app.raven.core.mesh.packet.InnerPacket
import app.raven.core.mesh.routing.InMemoryCarryStore
import app.raven.core.model.DeviceId
import app.raven.core.model.MessageId
import app.raven.core.model.RandomBytes
import app.raven.transport.fake.LinkConditions
import app.raven.transport.fake.SimNetwork
import app.raven.transport.fake.SimTransport
import app.raven.transport.fake.VirtualScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.random.Random

/** A repeatable world of simulated phones, each running a real [MeshNode]. */
class MeshWorld(
    seed: Long,
    conditions: LinkConditions = LinkConditions(),
    private val config: MeshConfig = MeshConfig(),
) {
    val scheduler = VirtualScheduler()
    val network = SimNetwork(scheduler, seed, conditions)
    private val random = Random(seed)
    private val randomBytes = RandomBytes { random.nextBytes(it) }
    val phones = mutableListOf<Phone>()

    inner class Phone(
        val radio: SimTransport,
    ) : MeshListener {
        val id = DeviceId.random(randomBytes)
        private val roots = mutableMapOf<DeviceId, ContactRootKey>()
        private val ciphers = mutableMapOf<DeviceId, ContactCipher>()
        var carried = InMemoryCarryStore(config.carryMaxBytes)
            private set

        // These survive a restart (Phase 3: the encrypted database); carried packets don't (D69).
        val outbox = InMemoryOutbox()
        private val replayGuard = InMemoryReplayGuard()
        private val counters = InMemoryCounterStore()
        val received = mutableListOf<Pair<DeviceId, InnerPacket>>()
        val images = mutableListOf<Pair<DeviceId, ByteArray>>()
        val statuses = mutableMapOf<MessageId, MutableList<DeliveryStatus>>()

        var node = newNode()
            private set

        private fun newNode() =
            MeshNode(
                me = id,
                transport = radio,
                scheduler = scheduler,
                contacts = { peer -> ciphers[peer] },
                listener = this,
                config = config,
                wallClock = { 1_760_000_000_000 + scheduler.now() },
                random = randomBytes,
                carryStore = carried,
                replayGuard = replayGuard,
                counters = counters,
                outbox = outbox,
            )

        /** The app is killed and started again: memory is lost, stored data stays. */
        fun restart() {
            node.stop()
            carried = InMemoryCarryStore(config.carryMaxBytes)
            node = newNode()
            node.start()
        }

        fun addContact(
            peer: DeviceId,
            root: ContactRootKey,
        ) {
            roots[peer] = root
            ciphers[peer] = StaticKeyV1.contactCipher(root, id, peer)
        }

        fun texts(): List<String> = received.mapNotNull { (it.second.content as? Content.Text)?.text }

        fun status(id: MessageId): DeliveryStatus? = statuses[id]?.lastOrNull()

        override fun onMessage(
            from: DeviceId,
            message: InnerPacket,
        ) {
            received += from to message
        }

        override fun onImage(
            from: DeviceId,
            message: InnerPacket,
            manifest: Content.ImageManifest,
            bytes: ByteArray,
        ) {
            images += from to bytes
        }

        override fun onStatus(
            peer: DeviceId,
            messageId: MessageId,
            status: DeliveryStatus,
        ) {
            statuses.getOrPut(messageId) { mutableListOf() } += status
        }

        override fun toString(): String = radio.toString()
    }

    fun phone(radio: SimTransport = network.addNode()): Phone = Phone(radio).also { phones += it }

    fun phones(radios: List<SimTransport>): List<Phone> = radios.map(::phone)

    fun start() = phones.forEach { it.node.start() }

    /** Pairs two phones directly (pairing itself is tested in :core:crypto). */
    fun befriend(
        a: Phone,
        b: Phone,
    ) {
        val root = ContactRootKey(randomBytes.next(ContactRootKey.SIZE))
        a.addContact(b.id, root)
        b.addContact(a.id, root)
    }

    fun connect(
        a: Phone,
        b: Phone,
    ) = network.connect(a.radio, b.radio)

    fun disconnect(
        a: Phone,
        b: Phone,
    ) = network.disconnect(a.radio, b.radio)

    fun run(millis: Long) = scheduler.advanceBy(millis)

    /** Build plan 2.8: no message is ever shown twice, anywhere. */
    fun assertNoDuplicates() {
        for (phone in phones) {
            val ids = phone.received.map { it.second.messageId }
            assertEquals(ids.size, ids.toSet().size, "$phone showed a message twice")
        }
    }
}

const val SECOND = MeshConfig.SECOND
const val MINUTE = MeshConfig.MINUTE
const val HOUR = MeshConfig.HOUR
const val DAY = MeshConfig.DAY
