package app.huginn.core.crypto.pairing

import app.huginn.core.model.DeviceId
import app.huginn.core.model.FixedBytes
import app.huginn.core.model.Nickname
import app.huginn.core.model.SessionId
import app.huginn.core.model.wire.ByteReader
import app.huginn.core.model.wire.ByteWriter
import app.huginn.core.model.wire.MalformedInputException
import app.huginn.core.model.wire.decodeOrNull

/** An X25519 public key. Public by design: it is printed in QR codes. */
class X25519PublicKey(
    bytes: ByteArray,
) : FixedBytes(bytes, SIZE) {
    companion object {
        const val SIZE = 32
    }
}

/**
 * What a pairing QR code contains (docs/PROTOCOL.md §5). Only public data: a photo of the code
 * reveals nothing secret (spec D37).
 */
class QrInvite(
    val sessionId: SessionId,
    val ownerId: DeviceId,
    val ownerPublicKey: X25519PublicKey,
    val nickname: Nickname,
) {
    /** The QR code text: `MSH1:` + Base45 (spec D60). */
    fun toQrText(): String {
        val nicknameBytes = nickname.value.toByteArray(Charsets.UTF_8)
        val bytes =
            ByteWriter()
                .u8(VERSION)
                .bytes(sessionId)
                .bytes(ownerId)
                .bytes(ownerPublicKey)
                .u8(nicknameBytes.size)
                .bytes(nicknameBytes)
                .toByteArray()
        return PREFIX + Base45.encode(bytes)
    }

    companion object {
        const val PREFIX = "MSH1:"
        const val VERSION = 1

        /** Null for anything that isn't a valid v1 invite. Never throws. */
        fun fromQrText(text: String): QrInvite? {
            if (!text.startsWith(PREFIX)) return null
            val bytes = Base45.decode(text.substring(PREFIX.length)) ?: return null
            return decodeOrNull {
                val reader = ByteReader(bytes)
                reader.check(reader.u8() == VERSION) { "unsupported QR version" }
                val sessionId = SessionId(reader.bytes(SessionId.SIZE))
                val ownerId = DeviceId(reader.bytes(DeviceId.SIZE))
                val publicKey = X25519PublicKey(reader.bytes(X25519PublicKey.SIZE))
                val nicknameLength = reader.u8()
                reader.check(nicknameLength <= Nickname.MAX_BYTES) { "nickname too long" }
                val nickname =
                    Nickname.strict(reader.utf8(nicknameLength)) ?: throw MalformedInputException("invalid nickname")
                reader.requireEnd()
                QrInvite(sessionId, ownerId, publicKey, nickname)
            }
        }
    }
}
