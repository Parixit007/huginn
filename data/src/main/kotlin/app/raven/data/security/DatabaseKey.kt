package app.raven.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.raven.core.model.RandomBytes
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The database key (spec D80): 32 random bytes, stored only in wrapped form. The wrapping key is an
 * AES-256-GCM key inside the Android Keystore (hardware-backed where the phone supports it), so a copy of
 * the app's files is useless without this exact phone. Usable after the first unlock since boot (audit S9),
 * so messages can arrive while the screen is locked.
 *
 * The wrapped key lives in `noBackupFilesDir`, which is never backed up (hardening H1).
 */
class DatabaseKey(
    private val context: Context,
    private val alias: String = DEFAULT_ALIAS,
    private val random: RandomBytes = RandomBytes.secure,
    private val fileName: String = WRAPPED_FILE,
) {
    private val wrappedFile: File get() = File(context.noBackupFilesDir, fileName)

    /** The database key, created on first use. The caller should overwrite it when done. */
    fun load(): ByteArray {
        val file = wrappedFile
        if (file.exists()) return unwrap(file.readBytes())
        val key = random.next(KEY_SIZE)
        file.writeBytes(wrap(key))
        return key
    }

    private fun wrap(key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        return cipher.iv + cipher.doFinal(key)
    }

    private fun unwrap(wrapped: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = wrapped.copyOfRange(0, IV_SIZE)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(wrapped, IV_SIZE, wrapped.size - IV_SIZE)
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE * Byte.SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        /** Brand-neutral on purpose (D47): renaming the app must not lose the key. */
        const val DEFAULT_ALIAS = "mesh-db-wrap-v1"
        const val WRAPPED_FILE = "db-key.wrapped"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE = 32
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
    }
}
