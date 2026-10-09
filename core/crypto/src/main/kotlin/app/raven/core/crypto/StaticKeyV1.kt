package app.raven.core.crypto

import app.raven.core.model.DeviceId
import com.google.crypto.tink.Aead
import java.security.GeneralSecurityException

/**
 * v1 encryption (spec D10): one static root key per contact. Each direction gets its own key
 * (hardening H8), so a packet A sent can never be accepted by A as if it came from B.
 */
object StaticKeyV1 : CryptoSuite {
    override fun contactCipher(
        root: ContactRootKey,
        me: DeviceId,
        peer: DeviceId,
    ): ContactCipher {
        require(me != peer) { "a contact can't be yourself" }
        val rootBytes = root.toByteArray()
        try {
            return DirectionalCipher(
                send = Primitives.aead(directionKey(rootBytes, from = me, to = peer)),
                receive = Primitives.aead(directionKey(rootBytes, from = peer, to = me)),
            )
        } finally {
            rootBytes.fill(0)
        }
    }

    /** k(X→Y) = HKDF-SHA256(root, salt = empty, info = "MSH1 dir" ‖ X ‖ Y) (docs/PROTOCOL.md §4). */
    internal fun directionKey(
        root: ByteArray,
        from: DeviceId,
        to: DeviceId,
    ): ByteArray =
        Primitives.hkdf(
            ikm = root,
            salt = ByteArray(0),
            info = Primitives.label("MSH1 dir") + from.toByteArray() + to.toByteArray(),
            size = Primitives.KEY_SIZE,
        )

    private class DirectionalCipher(
        private val send: Aead,
        private val receive: Aead,
    ) : ContactCipher {
        override val overhead: Int = Primitives.AEAD_OVERHEAD

        override fun seal(
            plaintext: ByteArray,
            associatedData: ByteArray,
        ): ByteArray = send.encrypt(plaintext, associatedData)

        override fun open(
            sealed: ByteArray,
            associatedData: ByteArray,
        ): ByteArray? =
            try {
                receive.decrypt(sealed, associatedData)
            } catch (expected: GeneralSecurityException) {
                null // not authentic: wrong key, tampered bytes or wrong header
            }
    }
}
