package app.huginn.core.crypto.pairing

import app.huginn.core.crypto.Primitives
import app.huginn.core.crypto.StaticKeyV1
import app.huginn.core.model.DeviceId
import app.huginn.core.model.Nickname
import app.huginn.core.model.RandomBytes
import app.huginn.core.model.SessionId
import app.huginn.core.model.wire.ByteReader
import app.huginn.core.model.wire.decodeOrNull
import java.security.GeneralSecurityException

/**
 * The phone that shows the QR code ("A" in docs/PROTOCOL.md §5). Create one each time the QR screen
 * opens (fresh key pair, spec D27); it is single use and expires after [PAIRING_TIMEOUT_MILLIS].
 *
 * Times are milliseconds from a clock that never jumps backwards (Android: elapsedRealtime).
 */
class InviteSession(
    private val myId: DeviceId,
    myNickname: Nickname,
    private val createdAtMillis: Long,
    private val random: RandomBytes = RandomBytes.secure,
) {
    val sessionId: SessionId = SessionId.random(random)
    private val privateKey = Primitives.newX25519PrivateKey()
    private val publicKey = X25519PublicKey(Primitives.x25519PublicKey(privateKey))
    private val myNicknameBytes = myNickname.value.toByteArray(Charsets.UTF_8)

    /** Show this as a QR code: `invite.toQrText()`. */
    val invite = QrInvite(sessionId, myId, publicKey, myNickname)

    private var state: State = State.Waiting

    sealed interface RequestResult {
        /** Send [reply] (a CHALLENGE) back to [peerId] and show [code] next to [peerNickname]. */
        class Challenge(
            val reply: ByteArray,
            val code: String,
            val peerId: DeviceId,
            val peerNickname: Nickname,
        ) : RequestResult

        /** Not a valid request for this session: do nothing. */
        data object Ignored : RequestResult

        /** A second, different phone tried to pair: warn the user and start again (safety rule 1). */
        data object Aborted : RequestResult

        data object Expired : RequestResult
    }

    /** Send [reply] (a CONFIRM) to the peer and save [contact]. */
    class Accepted(
        val reply: ByteArray,
        val contact: PairedContact,
    )

    /** Handles a HANDSHAKE body received over a direct link from [senderId]. */
    fun onMessage(
        senderId: DeviceId,
        body: ByteArray,
        nowMillis: Long,
    ): RequestResult {
        if (isExpired(nowMillis)) return finish(State.Expired, RequestResult.Expired)
        val message = HandshakeMessage.decode(body)
        if (message == null || message.type != HandshakeType.REQUEST || message.sessionId != sessionId) {
            return RequestResult.Ignored
        }
        return when (val current = state) {
            State.Waiting -> {
                handleFirstRequest(senderId, body, message)
            }

            is State.Challenged -> {
                if (senderId == current.peerId && body.contentEquals(current.requestBody)) {
                    current.result // the same request again (a Bluetooth retry): answer the same way
                } else if (decryptRequest(senderId, message)?.also { it.shared.fill(0) } != null) {
                    finish(State.Aborted, RequestResult.Aborted)
                } else {
                    RequestResult.Ignored
                }
            }

            else -> {
                RequestResult.Ignored
            }
        }
    }

    /** The user compared the codes and tapped Accept. Null if there is nothing to accept. */
    fun accept(nowMillis: Long): Accepted? {
        val current = state as? State.Challenged ?: return null
        if (isExpired(nowMillis)) return finish(State.Expired, null)
        val cipher = StaticKeyV1.contactCipher(current.secrets.root, myId, current.peerId)
        val reply = sealedReply(HandshakeType.CONFIRM, current.peerId, myNicknameBytes, cipher::seal)
        state = State.Done
        wipe()
        return Accepted(reply, PairedContact(current.peerId, current.peerNickname, current.secrets.root))
    }

    /** The user tapped Reject: send the returned DECLINE. Null if there is nothing to decline. */
    fun decline(): ByteArray? {
        val current = state as? State.Challenged ?: return null
        val cipher = StaticKeyV1.contactCipher(current.secrets.root, myId, current.peerId)
        val reply = sealedReply(HandshakeType.DECLINE, current.peerId, ByteArray(0), cipher::seal)
        current.secrets.root.destroy()
        return finish(State.Done, reply)
    }

    private fun handleFirstRequest(
        senderId: DeviceId,
        body: ByteArray,
        message: HandshakeMessage,
    ): RequestResult {
        val request = decryptRequest(senderId, message) ?: return RequestResult.Ignored
        // Safety rule 2: the nonce is chosen only after the peer's public key arrived.
        val nonce = random.next(NONCE_SIZE)
        val secrets =
            PairingCrypto.derive(request.shared, sessionId, myId, senderId, publicKey, request.peerKey, nonce)
        request.shared.fill(0)
        val reply = HandshakeMessage(HandshakeType.CHALLENGE, sessionId, nonce).encode()
        val result = RequestResult.Challenge(reply, secrets.code, senderId, request.nickname)
        state = State.Challenged(senderId, request.nickname, body, secrets, result)
        return result
    }

    private class DecryptedRequest(
        val peerKey: X25519PublicKey,
        val nickname: Nickname,
        val shared: ByteArray,
    )

    /** Parses and authenticates a REQUEST: B_pub ‖ sealed nickname. Null if anything is off. */
    private fun decryptRequest(
        senderId: DeviceId,
        message: HandshakeMessage,
    ): DecryptedRequest? {
        if (senderId == myId) return null
        val parts =
            decodeOrNull {
                val reader = ByteReader(message.payload)
                X25519PublicKey(reader.bytes(X25519PublicKey.SIZE)) to reader.rest()
            } ?: return null
        val (peerKey, sealedNickname) = parts
        return try {
            val shared = Primitives.x25519(privateKey, peerKey.toByteArray())
            val key = PairingCrypto.requestKey(shared, sessionId)
            val associatedData = PairingCrypto.associatedData(HandshakeType.REQUEST, sessionId, myId, senderId)
            val nicknameBytes = Primitives.aead(key).decrypt(sealedNickname, associatedData)
            val nickname = decodeOrNull { ByteReader(nicknameBytes).utf8(nicknameBytes.size) }?.let(Nickname::strict)
            if (nickname == null) null else DecryptedRequest(peerKey, nickname, shared)
        } catch (expected: GeneralSecurityException) {
            null // invalid key or not authentic: treat as noise
        }
    }

    private fun sealedReply(
        type: Int,
        peerId: DeviceId,
        plaintext: ByteArray,
        seal: (ByteArray, ByteArray) -> ByteArray,
    ): ByteArray {
        val associatedData = PairingCrypto.associatedData(type, sessionId, myId, peerId)
        return HandshakeMessage(type, sessionId, seal(plaintext, associatedData)).encode()
    }

    private fun isExpired(nowMillis: Long): Boolean {
        val age = nowMillis - createdAtMillis
        return age < 0 || age > PAIRING_TIMEOUT_MILLIS
    }

    private fun <T> finish(
        newState: State,
        result: T,
    ): T {
        (state as? State.Challenged)?.let { if (newState != State.Done) it.secrets.root.destroy() }
        state = newState
        wipe()
        return result
    }

    /** Temporary private key is wiped once the session ends (safety rule 4). */
    private fun wipe() = privateKey.fill(0)

    private sealed interface State {
        data object Waiting : State

        class Challenged(
            val peerId: DeviceId,
            val peerNickname: Nickname,
            val requestBody: ByteArray,
            val secrets: PairingSecrets,
            val result: RequestResult.Challenge,
        ) : State

        data object Done : State

        data object Aborted : State

        data object Expired : State
    }
}
