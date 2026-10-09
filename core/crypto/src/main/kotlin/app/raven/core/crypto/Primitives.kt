package app.raven.core.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.aead.XChaCha20Poly1305Key
import com.google.crypto.tink.aead.XChaCha20Poly1305Parameters
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import com.google.crypto.tink.subtle.XChaCha20Poly1305
import com.google.crypto.tink.util.SecretBytes
import java.security.InvalidKeyException
import java.security.MessageDigest

/** Thin wrappers around Tink so the rest of the code never touches crypto APIs directly. */
internal object Primitives {
    const val KEY_SIZE = 32
    const val NONCE_SIZE = 24
    const val TAG_SIZE = 16

    /** Bytes XChaCha20-Poly1305 adds to every plaintext: random nonce in front, tag at the end. */
    const val AEAD_OVERHEAD = NONCE_SIZE + TAG_SIZE

    fun hkdf(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        size: Int,
    ): ByteArray = Hkdf.computeHkdf("HMACSHA256", ikm, salt, info, size)

    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach(digest::update)
        return digest.digest()
    }

    /** XChaCha20-Poly1305 with a Tink "no-prefix" key: output is nonce ‖ ciphertext ‖ tag. */
    fun aead(key: ByteArray): Aead {
        require(key.size == KEY_SIZE) { "AEAD key must be $KEY_SIZE bytes" }
        val tinkKey =
            XChaCha20Poly1305Key.create(
                XChaCha20Poly1305Parameters.Variant.NO_PREFIX,
                SecretBytes.copyFrom(key, InsecureSecretKeyAccess.get()),
                null,
            )
        return XChaCha20Poly1305.create(tinkKey)
    }

    fun newX25519PrivateKey(): ByteArray = X25519.generatePrivateKey()

    fun x25519PublicKey(privateKey: ByteArray): ByteArray = X25519.publicFromPrivate(privateKey)

    /**
     * X25519 key agreement. Rejects an all-zero result, which a malicious peer can force with a
     * low-order public key (RFC 7748 §6.1).
     */
    fun x25519(
        privateKey: ByteArray,
        peerPublicKey: ByteArray,
    ): ByteArray {
        val shared = X25519.computeSharedSecret(privateKey, peerPublicKey)
        if (shared.all { it == 0.toByte() }) throw InvalidKeyException("X25519 produced an all-zero shared secret")
        return shared
    }

    fun label(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)
}
