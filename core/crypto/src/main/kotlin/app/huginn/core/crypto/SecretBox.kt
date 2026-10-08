package app.huginn.core.crypto

import app.huginn.core.model.RandomBytes
import java.security.GeneralSecurityException

/**
 * Encryption for data at rest: photos on disk and carried packets (spec §7, D69). The same
 * XChaCha20-Poly1305 as messages: output is nonce ‖ ciphertext ‖ tag; [open] gives null if anything was changed.
 */
class SecretBox(
    key: ByteArray,
) {
    private val aead = Primitives.aead(key)

    fun seal(
        plaintext: ByteArray,
        associatedData: ByteArray = ByteArray(0),
    ): ByteArray = aead.encrypt(plaintext, associatedData)

    fun open(
        sealed: ByteArray,
        associatedData: ByteArray = ByteArray(0),
    ): ByteArray? =
        try {
            aead.decrypt(sealed, associatedData)
        } catch (expected: GeneralSecurityException) {
            null // wrong key, tampered bytes or wrong associated data
        }

    companion object {
        const val KEY_SIZE = Primitives.KEY_SIZE

        fun newKey(random: RandomBytes = RandomBytes.secure): ByteArray = random.next(KEY_SIZE)
    }
}
