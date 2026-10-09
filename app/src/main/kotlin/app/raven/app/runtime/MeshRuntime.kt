package app.raven.app.runtime

import android.os.Handler
import android.os.HandlerThread
import app.raven.core.crypto.pairing.PairedContact
import app.raven.core.mesh.DeliveryStatus
import app.raven.core.mesh.MeshConfig
import app.raven.core.mesh.MeshListener
import app.raven.core.mesh.MeshNode
import app.raven.core.mesh.packet.Content
import app.raven.core.mesh.packet.InnerPacket
import app.raven.core.model.DeviceId
import app.raven.core.model.ImageId
import app.raven.core.model.MessageId
import app.raven.core.model.Nickname
import app.raven.core.transport.Scheduler
import app.raven.core.transport.Transport
import app.raven.data.Storage
import app.raven.data.db.MessageEntity
import app.raven.data.db.MessageStatus
import app.raven.transport.ble.BleStatus
import app.raven.transport.ble.BleTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The radio as the chat list shows it (build plan 5.5, D96). */
data class RadioState(
    val paused: Boolean = false,
    val on: Boolean = false,
    /** Null until the Bluetooth layer has reported (and always in tests on the fake network). */
    val ble: BleStatus? = null,
)

/** What the runtime needs from Android to keep the radio running in the background (D50, D96, D97). */
interface RadioHost {
    var paused: Boolean

    /** Starts the background service if Raven should run (permissions granted, not paused). */
    fun startService()

    fun stopService()
}

sealed interface RuntimeState {
    data object Loading : RuntimeState

    data object NeedsOnboarding : RuntimeState

    data class Ready(
        val me: DeviceId,
    ) : RuntimeState
}

/**
 * The running app's mesh: one background thread that owns the [MeshNode], the encrypted [Storage] and the
 * [PairingController]. Every engine and storage call happens on that thread (the engine is single-threaded);
 * the UI posts work with [post] and reads data through Room's live queries.
 */
class MeshRuntime(
    private val openStorage: () -> Storage,
    /** Builds the transport on the mesh thread; `post` runs work there (Bluetooth callbacks hop through it). */
    private val transportFactory: (Scheduler, (() -> Unit) -> Unit) -> Transport,
    private val notifier: Notifier?,
    /** Null in tests: the radio is simply on, with no background service. */
    private val host: RadioHost? = null,
    private val config: MeshConfig = MeshConfig(),
) : MeshListener {
    private val thread = HandlerThread("mesh").apply { start() }
    private val handler = Handler(thread.looper)
    val scheduler: Scheduler = HandlerScheduler(handler)

    private val mutableState = MutableStateFlow<RuntimeState>(RuntimeState.Loading)
    val state: StateFlow<RuntimeState> = mutableState

    private val mutableNearby = MutableStateFlow(0)

    /** Raven phones directly connected right now (spec §3). */
    val nearby: StateFlow<Int> = mutableNearby

    private val mutableRadio = MutableStateFlow(RadioState(paused = host?.paused ?: false, on = host == null))

    /** Paused, on, and what Bluetooth can do right now (for the banners). */
    val radioState: StateFlow<RadioState> = mutableRadio

    lateinit var storage: Storage
        private set
    private var node: MeshNode? = null
    private lateinit var transport: Transport
    private lateinit var radioSwitch: RadioSwitch

    private val pairingController =
        PairingController(
            scheduler,
            send = { to, body -> node?.sendHandshake(to, body) },
            save = ::onPaired,
        )

    /** The chat on screen right now: its messages are marked read and get no notification. */
    @Volatile
    var visibleChat: DeviceId? = null

    fun start() =
        post {
            storage = openStorage()
            radioSwitch = RadioSwitch(transportFactory(scheduler, ::post), on = host == null)
            transport = CountingTransport(radioSwitch) { mutableNearby.value = it }
            val identity = storage.identity()
            if (identity == null) {
                mutableState.value = RuntimeState.NeedsOnboarding
            } else {
                startNode(DeviceId(identity.deviceId))
            }
        }

    fun post(work: () -> Unit) {
        handler.post(work)
    }

    // ---------------------------------------------------------------- actions (all run on the mesh thread)

    /** Onboarding (spec §3): creates the identity and starts the mesh. */
    fun createIdentity(
        nickname: Nickname,
        avatar: ByteArray?,
    ) = post {
        storage.createIdentity(nickname)
        if (avatar != null) storage.setOwnAvatar(avatar)
        startNode(DeviceId(checkNotNull(storage.identity()).deviceId))
        if (host?.paused == false) host.startService()
    }

    /** The radio: on/off, pause, foreground (D96, D98). */
    val radio = Radio()

    /** Chat actions (all run on the mesh thread). */
    val chat = ChatActions()

    /** Contact and profile actions (all run on the mesh thread). */
    val contacts = ContactActions()

    /** Pairing screens: [Pairing.state] to show, actions to call. */
    val pairing = Pairing()

    inner class Radio {
        /** Called by the background service: the radio runs exactly while the service does. */
        fun setOn(on: Boolean) =
            post {
                radioSwitch.setOn(on)
                mutableRadio.value = mutableRadio.value.copy(on = on)
            }

        /** The app is on screen: scan continuously; otherwise duty-cycle (D98). */
        fun setForeground(visible: Boolean) = post { ble()?.setForeground(visible) }

        /**
         * Re-checks Bluetooth, permissions and Location (the user may have changed them) and starts the
         * background service if Raven should be running.
         */
        fun refresh() =
            post {
                ble()?.retry()
                val host = host ?: return@post
                if (state.value is RuntimeState.Ready && !host.paused) host.startService()
            }

        /** "Pause Raven" (D96): stops the service and with it the radio, also across reboots. */
        fun pause() {
            val host = host ?: return
            host.paused = true
            host.stopService()
            mutableRadio.value = mutableRadio.value.copy(paused = true)
        }

        fun resume() {
            host?.paused = false
            mutableRadio.value = mutableRadio.value.copy(paused = false)
            refresh()
        }

        /** From the Bluetooth layer, on the mesh thread. */
        fun onStatus(status: BleStatus) {
            mutableRadio.value = mutableRadio.value.copy(ble = status)
        }

        private fun ble(): BleTransport? = radioSwitch.inner as? BleTransport
    }

    inner class ChatActions {
        fun sendText(
            to: DeviceId,
            text: String,
        ) = post {
            val node = node ?: return@post
            val id = node.sendText(to, text)
            saveOutgoing(to, id, Content.TEXT, text = text)
        }

        fun sendReaction(
            to: DeviceId,
            target: MessageId,
            emoji: String,
        ) = post {
            val node = node ?: return@post
            val id = node.sendReaction(to, target, emoji)
            saveOutgoing(to, id, Content.REACTION, text = emoji, target = target)
        }

        /** [photo] must already be re-encoded and within limits (see ImageProcessing). */
        fun sendPhoto(
            to: DeviceId,
            photo: ByteArray,
        ) = post {
            val node = node ?: return@post
            val local = ImageId.random()
            storage.images().save(local, photo)
            val id = node.sendImage(to, photo)
            saveOutgoing(to, id, Content.IMAGE_MANIFEST, image = local)
        }

        /** Marks a chat read and tells the sender (best effort read receipts, D18). */
        fun markRead(peer: DeviceId) =
            post {
                val db = storage.database.messages()
                val ids = db.unreadIds(peer.toByteArray()).map(::MessageId)
                db.markIncomingRead(peer.toByteArray())
                if (ids.isNotEmpty()) node?.markRead(peer, ids)
            }

        /** "Not confirmed yet — retry?" (D82). */
        fun retry(
            peer: DeviceId,
            id: MessageId,
        ) = post {
            if (node?.retry(id) ==
                true
            ) {
                storage.database.messages().setStatus(peer.toByteArray(), id.toByteArray(), MessageStatus.PENDING)
            }
        }
    }

    inner class ContactActions {
        fun setBlocked(
            peer: DeviceId,
            blocked: Boolean,
        ) = post { storage.setBlocked(peer, blocked) }

        fun deleteContact(peer: DeviceId) = post { storage.deleteContact(peer) }

        /** Profile change (D42): saved, then pushed to every contact with the avatar first. */
        fun updateProfile(
            nickname: Nickname,
            avatar: ByteArray?,
        ) = post {
            storage.setOwnProfile(nickname, avatar)
            storage.database
                .contacts()
                .all()
                .filterNot { it.blocked }
                .forEach { sendProfileTo(DeviceId(it.peerId)) }
        }
    }

    inner class Pairing {
        val state: StateFlow<PairingState> get() = pairingController.state

        fun showQr() =
            post {
                val identity = storage.identity() ?: return@post
                pairingController.showQr(DeviceId(identity.deviceId), checkNotNull(Nickname.strict(identity.nickname)))
            }

        fun scanned(qrText: String) =
            post {
                val identity = storage.identity() ?: return@post
                pairingController.scanned(
                    qrText,
                    DeviceId(identity.deviceId),
                    checkNotNull(Nickname.strict(identity.nickname)),
                )
            }

        fun accept() = post { pairingController.accept() }

        fun decline() = post { pairingController.decline() }

        fun cancel() = post { pairingController.cancel() }
    }

    // ---------------------------------------------------------------- engine callbacks (mesh thread)

    override fun onMessage(
        from: DeviceId,
        message: InnerPacket,
    ) {
        when (val content = message.content) {
            is Content.Text -> {
                saveIncoming(from, message, Content.TEXT, text = content.text)
                notifyIfHidden(from)
            }

            is Content.Reaction -> {
                saveIncoming(from, message, Content.REACTION, text = content.emoji, target = content.target)
            }

            is Content.Profile -> {
                storage.database.contacts().setNickname(from.toByteArray(), content.nickname.value)
            }

            else -> {
                // photos arrive through onImage; receipts never reach the app as messages
            }
        }
    }

    override fun onImage(
        from: DeviceId,
        message: InnerPacket,
        manifest: Content.ImageManifest,
        bytes: ByteArray,
    ) {
        if (manifest.purpose == Content.Purpose.AVATAR) {
            storage.database.contacts().setAvatar(from.toByteArray(), bytes)
            return
        }
        val local = ImageId.random()
        storage.images().save(local, bytes)
        saveIncoming(from, message, Content.IMAGE_MANIFEST, image = local)
        notifyIfHidden(from)
    }

    override fun onStatus(
        peer: DeviceId,
        messageId: MessageId,
        status: DeliveryStatus,
    ) {
        val db = storage.database.messages()
        val current = db.status(peer.toByteArray(), messageId.toByteArray()) ?: return
        val next =
            when (status) {
                DeliveryStatus.SENT -> MessageStatus.SENT
                DeliveryStatus.DELIVERED -> MessageStatus.DELIVERED
                DeliveryStatus.READ -> MessageStatus.READ
                DeliveryStatus.NOT_DELIVERED -> MessageStatus.NOT_CONFIRMED
            }
        if (MessageStatus.isUpgrade(current, next)) db.setStatus(peer.toByteArray(), messageId.toByteArray(), next)
    }

    override fun onHandshake(
        from: DeviceId,
        body: ByteArray,
    ) = pairingController.onHandshake(from, body)

    // ---------------------------------------------------------------- helpers

    private fun startNode(me: DeviceId) {
        val created =
            MeshNode(
                me = me,
                transport = transport,
                scheduler = scheduler,
                contacts = storage.contactDirectory(),
                listener = this,
                config = config,
                carryStore = storage.newCarryStore(config.carryMaxBytes),
                replayGuard = storage.replayGuard,
                counters = storage.counters,
                outbox = storage.outbox,
            )
        node = created
        created.start()
        mutableState.value = RuntimeState.Ready(me)
    }

    /** A new contact gets our profile right away (D31). */
    private fun onPaired(contact: PairedContact) {
        storage.addContact(contact, System.currentTimeMillis())
        sendProfileTo(contact.peerId)
    }

    private fun sendProfileTo(peer: DeviceId) {
        val node = node ?: return
        val identity = storage.identity() ?: return
        val avatar = identity.avatar
        val avatarId =
            avatar?.let { bytes ->
                ImageId.random().also { node.sendImage(peer, bytes, Content.Purpose.AVATAR, it) }
            }
        node.sendProfile(peer, checkNotNull(Nickname.strict(identity.nickname)), avatarId)
    }

    private fun notifyIfHidden(from: DeviceId) {
        if (visibleChat == from) {
            chat.markRead(from)
            return
        }
        val name =
            storage.database
                .contacts()
                .get(from.toByteArray())
                ?.nickname ?: return
        notifier?.newMessage(from, name)
    }

    private fun saveOutgoing(
        to: DeviceId,
        id: MessageId,
        type: Int,
        text: String? = null,
        target: MessageId? = null,
        image: ImageId? = null,
    ) {
        val now = System.currentTimeMillis()
        storage.database.messages().insert(
            MessageEntity(
                messageId = id.toByteArray(),
                peerId = to.toByteArray(),
                outgoing = true,
                type = type,
                text = text,
                reactionTarget = target?.toByteArray(),
                imageId = image?.toByteArray(),
                counter = 0,
                sentAt = now,
                receivedAt = now,
                status = MessageStatus.PENDING,
            ),
        )
    }

    private fun saveIncoming(
        from: DeviceId,
        message: InnerPacket,
        type: Int,
        text: String? = null,
        target: MessageId? = null,
        image: ImageId? = null,
    ) {
        storage.database.messages().insert(
            MessageEntity(
                messageId = message.messageId.toByteArray(),
                peerId = from.toByteArray(),
                outgoing = false,
                type = type,
                text = text,
                reactionTarget = target?.toByteArray(),
                imageId = image?.toByteArray(),
                counter = message.counter,
                sentAt = message.timestampMillis,
                receivedAt = System.currentTimeMillis(),
                status = MessageStatus.PENDING,
            ),
        )
    }
}
