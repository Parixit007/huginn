package app.huginn.app.runtime

import android.os.Handler
import android.os.HandlerThread
import app.huginn.core.crypto.pairing.PairedContact
import app.huginn.core.mesh.DeliveryStatus
import app.huginn.core.mesh.MeshConfig
import app.huginn.core.mesh.MeshListener
import app.huginn.core.mesh.MeshNode
import app.huginn.core.mesh.packet.Content
import app.huginn.core.mesh.packet.InnerPacket
import app.huginn.core.model.DeviceId
import app.huginn.core.model.ImageId
import app.huginn.core.model.MessageId
import app.huginn.core.model.Nickname
import app.huginn.core.transport.Scheduler
import app.huginn.core.transport.Transport
import app.huginn.data.Storage
import app.huginn.data.db.MessageEntity
import app.huginn.data.db.MessageStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    private val transportFactory: (Scheduler) -> Transport,
    private val notifier: Notifier?,
    private val config: MeshConfig = MeshConfig(),
) : MeshListener {
    private val thread = HandlerThread("mesh").apply { start() }
    private val handler = Handler(thread.looper)
    val scheduler: Scheduler = HandlerScheduler(handler)

    private val mutableState = MutableStateFlow<RuntimeState>(RuntimeState.Loading)
    val state: StateFlow<RuntimeState> = mutableState

    private val mutableNearby = MutableStateFlow(0)

    /** Huginn phones directly connected right now (spec §3). */
    val nearby: StateFlow<Int> = mutableNearby

    lateinit var storage: Storage
        private set
    private var node: MeshNode? = null
    private lateinit var transport: Transport

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
            transport = CountingTransport(transportFactory(scheduler)) { mutableNearby.value = it }
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
    }

    /** Chat actions (all run on the mesh thread). */
    val chat = ChatActions()

    /** Contact and profile actions (all run on the mesh thread). */
    val contacts = ContactActions()

    /** Pairing screens: [Pairing.state] to show, actions to call. */
    val pairing = Pairing()

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
