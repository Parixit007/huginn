package app.raven.tools.macpeer

import app.raven.core.crypto.ContactCipher
import app.raven.core.crypto.StaticKeyV1
import app.raven.core.crypto.pairing.InviteSession
import app.raven.core.mesh.DeliveryStatus
import app.raven.core.mesh.MeshListener
import app.raven.core.mesh.MeshNode
import app.raven.core.mesh.messaging.ContactDirectory
import app.raven.core.mesh.packet.Content
import app.raven.core.mesh.packet.InnerPacket
import app.raven.core.model.DeviceId
import app.raven.core.model.MessageId
import app.raven.core.model.Nickname
import app.raven.core.transport.Transport
import java.io.File
import java.nio.file.Files

/**
 * A Raven "phone" on the Mac (build plan 5.6, D100): the real engine, one identity, contacts in memory only.
 * Everything here runs on the engine thread; [command] is called with each line typed in Terminal.
 */
class MacPeer(
    private val me: DeviceId,
    private val nickname: Nickname,
    private val engine: EngineThread,
    transportFor: (MacPeer) -> Transport,
    private val say: (String) -> Unit,
) : MeshListener {
    private class Contact(
        var name: String,
        val cipher: ContactCipher,
    )

    private val contacts = linkedMapOf<DeviceId, Contact>()
    private val node = MeshNode(me, transportFor(this), engine, ContactDirectory { contacts[it]?.cipher }, this)
    private val photos = Files.createTempDirectory("raven-mac-peer").toFile().apply { deleteOnExit() }
    private val sent = mutableMapOf<MessageId, String>()
    private var invite: InviteSession? = null
    private var pendingPeer: DeviceId? = null
    private var current: DeviceId? = null
    private var lastIncoming: Pair<DeviceId, MessageId>? = null
    private var lastPhoto: File? = null

    fun start() = node.start()

    fun command(line: String) {
        val text = line.trim()
        when {
            text.isEmpty() -> Unit
            text == "qr" -> showQr()
            text == "yes" -> accept()
            text == "no" -> decline()
            text == "/help" -> say(HELP)
            text == "/who" -> who()
            text.startsWith("/to ") -> switchTo(text.removePrefix("/to ").trim())
            text.startsWith("/react") -> react(text.removePrefix("/react").trim().ifEmpty { "❤️" })
            text.startsWith("/photo ") -> photo(File(text.removePrefix("/photo ").trim().removeSurrounding("'")))
            text == "/open" -> openLastPhoto()
            text.startsWith("/") -> say("Unknown command. Type /help.")
            else -> sendText(text)
        }
    }

    /** Deletes received photos (D100: nothing stays on the Mac). */
    fun cleanUp() {
        photos.listFiles()?.forEach(File::delete)
        photos.delete()
    }

    // ---------------------------------------------------------------- pairing (the phone scans our QR)

    private fun showQr() {
        val session = InviteSession(me, nickname, engine.now())
        invite = session
        pendingPeer = null
        say(Terminal.qr(session.invite.toQrText()))
        say("On your phone: Raven → + → Scan a QR code, and point it at this code. It is valid for 5 minutes.")
    }

    override fun onHandshake(
        from: DeviceId,
        body: ByteArray,
    ) {
        val session = invite ?: return
        when (val result = session.onMessage(from, body, engine.now())) {
            is InviteSession.RequestResult.Challenge -> {
                node.sendHandshake(result.peerId, result.reply)
                pendingPeer = result.peerId
                say(
                    "${result.peerNickname.value} wants to connect. Their phone shows a code: " +
                        "it must be ${Terminal.code(result.code)}. Type yes to accept or no to decline.",
                )
            }

            InviteSession.RequestResult.Aborted -> {
                invite = null
                say("Pairing stopped: two phones scanned at once. Type qr to try again.")
            }

            InviteSession.RequestResult.Expired -> {
                invite = null
                say("The QR code expired. Type qr for a new one.")
            }

            InviteSession.RequestResult.Ignored -> {
                Unit
            }
        }
    }

    private fun accept() {
        val accepted = invite?.accept(engine.now()) ?: return say("There's no pairing request to accept.")
        val contact = accepted.contact
        node.sendHandshake(contact.peerId, accepted.reply)
        contacts[contact.peerId] =
            Contact(contact.peerNickname.value, StaticKeyV1.contactCipher(contact.root, me, contact.peerId))
        node.sendProfile(contact.peerId, nickname, null)
        current = contact.peerId
        invite = null
        say("Connected with ${contact.peerNickname.value}. Type a message and press Enter.")
    }

    private fun decline() {
        val peer = pendingPeer
        val reply = invite?.decline() ?: return say("There's no pairing request to decline.")
        if (peer != null) node.sendHandshake(peer, reply)
        invite = null
        say("Declined.")
    }

    // ---------------------------------------------------------------- chatting

    private fun sendText(text: String) {
        val to = current ?: return say("Nobody to write to yet. Type qr to pair with your phone.")
        sent[node.sendText(to, text)] = text
    }

    private fun react(emoji: String) {
        val (peer, target) = lastIncoming ?: return say("No message to react to yet.")
        node.sendReaction(peer, target, emoji)
        say("Reacted $emoji to ${nameOf(peer)}'s last message.")
    }

    private fun photo(file: File) {
        val to = current ?: return say("Nobody to send to yet. Type qr to pair with your phone.")
        val bytes = Photos.forChat(file) ?: return say("Can't read ${file.path} as an image.")
        sent[node.sendImage(to, bytes)] = "📷 ${file.name}"
        say("Sending ${file.name} (${bytes.size / BYTES_PER_KB} KB)…")
    }

    private fun openLastPhoto() {
        val file = lastPhoto ?: return say("No photo received yet.")
        ProcessBuilder("open", file.path).start()
    }

    private fun who() {
        if (contacts.isEmpty()) return say("No contacts yet. Type qr to pair.")
        contacts.forEach { (id, contact) -> say("${if (id == current) "→" else " "} ${contact.name}") }
    }

    private fun switchTo(name: String) {
        val match = contacts.entries.firstOrNull { it.value.name.equals(name, ignoreCase = true) }
        if (match == null) return say("No contact called $name. Type /who.")
        current = match.key
        say("Now writing to ${match.value.name}.")
    }

    private fun nameOf(peer: DeviceId): String = contacts[peer]?.name ?: "someone"

    // ---------------------------------------------------------------- engine events

    override fun onMessage(
        from: DeviceId,
        message: InnerPacket,
    ) {
        when (val content = message.content) {
            is Content.Text -> {
                say("[${nameOf(from)}] ${content.text}")
                lastIncoming = from to message.messageId
                current = current ?: from
                node.markRead(from, listOf(message.messageId)) // shown here, so it's read
            }

            is Content.Reaction -> {
                say("[${nameOf(from)}] reacted ${content.emoji} to \"${sent[content.target] ?: "a message"}\"")
            }

            is Content.Profile -> {
                contacts[from]?.let {
                    if (it.name != content.nickname.value) say("${it.name} is now called ${content.nickname.value}.")
                    it.name = content.nickname.value
                }
            }

            else -> {
                Unit
            }
        }
    }

    override fun onImage(
        from: DeviceId,
        message: InnerPacket,
        manifest: Content.ImageManifest,
        bytes: ByteArray,
    ) {
        if (manifest.purpose != Content.Purpose.CHAT_IMAGE) return
        val file = File(photos, "photo-${System.currentTimeMillis()}.img").apply { writeBytes(bytes) }
        lastPhoto = file
        lastIncoming = from to message.messageId
        node.markRead(from, listOf(message.messageId))
        say("[${nameOf(from)}] 📷 photo (${bytes.size / BYTES_PER_KB} KB). Type /open to see it.")
    }

    override fun onStatus(
        peer: DeviceId,
        messageId: MessageId,
        status: DeliveryStatus,
    ) {
        val what = sent[messageId] ?: return // receipts for profiles etc.
        val mark =
            when (status) {
                DeliveryStatus.SENT -> "✓ sent"
                DeliveryStatus.DELIVERED -> "✓✓ delivered"
                DeliveryStatus.READ -> "👁 read"
                DeliveryStatus.NOT_DELIVERED -> "! not confirmed yet"
            }
        say("   \"$what\" $mark")
    }

    private companion object {
        const val BYTES_PER_KB = 1024
        val HELP =
            """
            qr               show a QR code for your phone to scan (pairing)
            yes / no         accept or decline a pairing request
            <text>           send a message
            /react [emoji]   react to the last message you received (default ❤️)
            /photo <file>    send a photo (re-encoded, metadata removed, ≤ 50 KB)
            /open            open the last photo you received
            /who, /to <name> list contacts, switch who you write to
            Ctrl-C           quit (the Mac forgets everything)
            """.trimIndent()
    }
}
