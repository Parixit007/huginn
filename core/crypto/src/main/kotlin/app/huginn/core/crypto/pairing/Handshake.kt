package app.huginn.core.crypto.pairing

import app.huginn.core.crypto.ContactRootKey
import app.huginn.core.crypto.Primitives
import app.huginn.core.model.DeviceId
import app.huginn.core.model.Nickname
import app.huginn.core.model.SessionId
import app.huginn.core.model.wire.ByteReader
import app.huginn.core.model.wire.ByteWriter
import app.huginn.core.model.wire.decodeOrNull

/** How long a pairing QR code and its handshake stay valid (spec D27). */
const val PAIRING_TIMEOUT_MILLIS: Long = 5 * 60 * 1000

/** A contact created by a finished pairing. */
class PairedContact(
    val peerId: DeviceId,
    val peerNickname: Nickname,
    val root: ContactRootKey,
)

/** Handshake message types (docs/PROTOCOL.md §5). */
internal object HandshakeType {
    const val REQUEST = 1
    const val CHALLENGE = 2
    const val CONFIRM = 3
    const val DECLINE = 4
}

internal const val NONCE_SIZE = 16

/** One decoded handshake message. Everything after the session ID is kept as raw [payload]. */
internal class HandshakeMessage(
    val type: Int,
    val sessionId: SessionId,
    val payload: ByteArray,
) {
    fun encode(): ByteArray =
        ByteWriter()
            .u8(type)
            .bytes(sessionId)
            .bytes(payload)
            .toByteArray()

    companion object {
        /** Largest handshake body we accept: a REQUEST with the longest nickname is ~210 bytes. */
        const val MAX_SIZE = 512

        fun decode(body: ByteArray): HandshakeMessage? {
            if (body.size > MAX_SIZE) return null
            return decodeOrNull {
                val reader = ByteReader(body)
                val type = reader.u8()
                reader.check(type in HandshakeType.REQUEST..HandshakeType.DECLINE) { "unknown handshake type" }
                HandshakeMessage(type, SessionId(reader.bytes(SessionId.SIZE)), reader.rest())
            }
        }
    }
}

/** Values both phones derive once the challenge is known (docs/PROTOCOL.md §5). */
internal class PairingSecrets(
    val code: String,
    val root: ContactRootKey,
)

internal object PairingCrypto {
    private const val CODE_BYTES = 4
    private const val CODE_MODULUS = 1_000_000L
    private const val CODE_DIGITS = 6

    fun requestKey(
        shared: ByteArray,
        sessionId: SessionId,
    ): ByteArray = Primitives.hkdf(shared, sessionId.toByteArray(), Primitives.label("MSH1 req"), Primitives.KEY_SIZE)

    /** Associated data for the sealed parts of REQUEST, CONFIRM and DECLINE. */
    fun associatedData(
        type: Int,
        sessionId: SessionId,
        ownerId: DeviceId,
        scannerId: DeviceId,
    ): ByteArray =
        Primitives.label("MSH1 hs") +
            byteArrayOf(type.toByte()) +
            sessionId.toByteArray() +
            ownerId.toByteArray() +
            scannerId.toByteArray()

    fun derive(
        shared: ByteArray,
        sessionId: SessionId,
        ownerId: DeviceId,
        scannerId: DeviceId,
        ownerKey: X25519PublicKey,
        scannerKey: X25519PublicKey,
        nonce: ByteArray,
    ): PairingSecrets {
        val transcript =
            Primitives.sha256(
                Primitives.label("MSH1 pair"),
                sessionId.toByteArray(),
                ownerId.toByteArray(),
                scannerId.toByteArray(),
                ownerKey.toByteArray(),
                scannerKey.toByteArray(),
                nonce,
            )
        val codeBytes = Primitives.hkdf(shared, transcript, Primitives.label("MSH1 code"), CODE_BYTES)
        val codeNumber = codeBytes.fold(0L) { acc, b -> (acc shl Byte.SIZE_BITS) or (b.toLong() and 0xFF) }
        val code = (codeNumber % CODE_MODULUS).toString().padStart(CODE_DIGITS, '0')
        val root =
            ContactRootKey(Primitives.hkdf(shared, transcript, Primitives.label("MSH1 root"), ContactRootKey.SIZE))
        return PairingSecrets(code, root)
    }
}
