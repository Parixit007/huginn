package app.huginn.core.crypto

import app.huginn.core.model.DeviceId

/**
 * Encrypts and decrypts DATA packets for one contact. The mesh only sees this interface (spec §4,
 * "crypto behind an interface"), so v2 can replace static keys with a ratchet without touching it.
 */
interface ContactCipher {
    /** Extra bytes [seal] adds to a plaintext. */
    val overhead: Int

    fun seal(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray

    /** Null if [sealed] is not authentic for this contact and [associatedData]. */
    fun open(
        sealed: ByteArray,
        associatedData: ByteArray,
    ): ByteArray?
}

/** Creates [ContactCipher]s. v1 is [StaticKeyV1]; v2 adds identity keys and a ratchet. */
interface CryptoSuite {
    fun contactCipher(
        root: ContactRootKey,
        me: DeviceId,
        peer: DeviceId,
    ): ContactCipher
}

/**
 * The 32-byte secret shared with one contact, created at pairing (docs/PROTOCOL.md §4–5).
 * Never printed. [destroy] overwrites it in memory (best effort on the JVM).
 */
class ContactRootKey(
    bytes: ByteArray,
) {
    init {
        require(bytes.size == SIZE) { "root key must be $SIZE bytes" }
    }

    private val value = bytes.copyOf()

    /** For storing in the encrypted database (Phase 3). */
    fun toByteArray(): ByteArray = value.copyOf()

    fun destroy() = value.fill(0)

    override fun toString(): String = "ContactRootKey(****)"

    companion object {
        const val SIZE = 32
    }
}
