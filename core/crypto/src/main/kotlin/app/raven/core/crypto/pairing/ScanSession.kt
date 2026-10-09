package app.raven.core.crypto.pairing

import app.raven.core.crypto.ContactCipher
import app.raven.core.crypto.Primitives
import app.raven.core.crypto.StaticKeyV1
import app.raven.core.model.DeviceId
import app.raven.core.model.Nickname
import app.raven.core.model.wire.ByteReader
import app.raven.core.model.wire.ByteWriter
import app.raven.core.model.wire.decodeOrNull
import java.security.GeneralSecurityException

/**
 * The phone that scanned the QR code ("B" in docs/PROTOCOL.md §5). Send [request] to
 * `invite.ownerId` over a direct link, then feed every HANDSHAKE body from that phone to [onMessage].
 * B saves the contact only after a valid CONFIRM (safety rule 3).
 */
class ScanSession private constructor(
    private val invite: QrInvite,
    private val myId: DeviceId,
    private val createdAtMillis: Long,
    private val privateKey: ByteArray,
    private val shared: ByteArray,
    private val publicKey: X25519PublicKey,
    /** The REQUEST body to send. */
    val request: ByteArray,
) {
    private var state: State = State.WaitingForChallenge

    sealed interface Result {
        /** Show this 6-digit code; the other phone shows the same one if nobody interfered. */
        class Code(
            val code: String,
        ) : Result

        /** The other phone accepted: save [contact]. */
        class Paired(
            val contact: PairedContact,
        ) : Result

        data object Declined : Result

        data object Ignored : Result

        data object Expired : Result
    }

    fun onMessage(
        senderId: DeviceId,
        body: ByteArray,
        nowMillis: Long,
    ): Result {
        val age = nowMillis - createdAtMillis
        if (age < 0 || age > PAIRING_TIMEOUT_MILLIS) return finish(State.Finished, Result.Expired)
        val message = HandshakeMessage.decode(body)
        if (senderId != invite.ownerId || message == null || message.sessionId != invite.sessionId) {
            return Result.Ignored
        }
        val current = state
        return when {
            current == State.WaitingForChallenge && message.type == HandshakeType.CHALLENGE -> onChallenge(message)
            current is State.Challenged && message.type == HandshakeType.CONFIRM -> onConfirm(current, message)
            current is State.Challenged && message.type == HandshakeType.DECLINE -> onDecline(current, message)
            else -> Result.Ignored
        }
    }

    private fun onChallenge(message: HandshakeMessage): Result {
        if (message.payload.size != NONCE_SIZE) return Result.Ignored
        val secrets =
            PairingCrypto.derive(
                shared,
                invite.sessionId,
                invite.ownerId,
                myId,
                invite.ownerPublicKey,
                publicKey,
                message.payload,
            )
        shared.fill(0)
        privateKey.fill(0)
        state = State.Challenged(secrets, StaticKeyV1.contactCipher(secrets.root, myId, invite.ownerId))
        return Result.Code(secrets.code)
    }

    private fun onConfirm(
        current: State.Challenged,
        message: HandshakeMessage,
    ): Result {
        val nicknameBytes = open(current.cipher, HandshakeType.CONFIRM, message) ?: return Result.Ignored
        val nickname =
            decodeOrNull { ByteReader(nicknameBytes).utf8(nicknameBytes.size) }?.let(Nickname::strict)
                ?: return Result.Ignored
        state = State.Finished
        return Result.Paired(PairedContact(invite.ownerId, nickname, current.secrets.root))
    }

    private fun onDecline(
        current: State.Challenged,
        message: HandshakeMessage,
    ): Result {
        open(current.cipher, HandshakeType.DECLINE, message) ?: return Result.Ignored
        current.secrets.root.destroy()
        return finish(State.Finished, Result.Declined)
    }

    private fun open(
        cipher: ContactCipher,
        type: Int,
        message: HandshakeMessage,
    ): ByteArray? {
        val associatedData = PairingCrypto.associatedData(type, invite.sessionId, invite.ownerId, myId)
        return cipher.open(message.payload, associatedData)
    }

    private fun finish(
        newState: State,
        result: Result,
    ): Result {
        shared.fill(0)
        privateKey.fill(0)
        state = newState
        return result
    }

    private sealed interface State {
        data object WaitingForChallenge : State

        class Challenged(
            val secrets: PairingSecrets,
            val cipher: ContactCipher,
        ) : State

        data object Finished : State
    }

    companion object {
        /**
         * Starts pairing with a scanned [invite]. Null if the invite is unusable (it is our own code,
         * or its public key is invalid).
         */
        fun start(
            invite: QrInvite,
            myId: DeviceId,
            myNickname: Nickname,
            nowMillis: Long,
        ): ScanSession? {
            if (invite.ownerId == myId) return null
            val privateKey = Primitives.newX25519PrivateKey()
            val publicKey = X25519PublicKey(Primitives.x25519PublicKey(privateKey))
            val shared =
                try {
                    Primitives.x25519(privateKey, invite.ownerPublicKey.toByteArray())
                } catch (expected: GeneralSecurityException) {
                    privateKey.fill(0)
                    return null
                }
            val requestKey = PairingCrypto.requestKey(shared, invite.sessionId)
            val associatedData =
                PairingCrypto.associatedData(HandshakeType.REQUEST, invite.sessionId, invite.ownerId, myId)
            val sealedNickname =
                Primitives.aead(requestKey).encrypt(myNickname.value.toByteArray(Charsets.UTF_8), associatedData)
            requestKey.fill(0)
            val payload = ByteWriter().bytes(publicKey).bytes(sealedNickname).toByteArray()
            val request = HandshakeMessage(HandshakeType.REQUEST, invite.sessionId, payload).encode()
            return ScanSession(invite, myId, nowMillis, privateKey, shared, publicKey, request)
        }
    }
}
