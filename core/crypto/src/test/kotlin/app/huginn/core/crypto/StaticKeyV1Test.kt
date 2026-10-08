package app.huginn.core.crypto

import app.huginn.core.model.DeviceId
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StaticKeyV1Test {
    private val alice = DeviceId.random()
    private val bob = DeviceId.random()
    private val root = ContactRootKey(ByteArray(32) { it.toByte() })
    private val aliceCipher = StaticKeyV1.contactCipher(root, me = alice, peer = bob)
    private val bobCipher = StaticKeyV1.contactCipher(root, me = bob, peer = alice)
    private val aad = "header".toByteArray()
    private val message = "hello Bob".toByteArray()

    @Test
    fun `what Alice seals, Bob opens`() {
        val sealed = aliceCipher.seal(message, aad)
        assertEquals(message.size + 40, sealed.size)
        assertArrayEquals(message, bobCipher.open(sealed, aad))
    }

    @Test
    fun `a packet can't be reflected back to its sender`() {
        val sealed = aliceCipher.seal(message, aad)
        assertNull(aliceCipher.open(sealed, aad))
    }

    @Test
    fun `flipping any bit makes the packet invalid`() {
        val sealed = aliceCipher.seal(message, aad)
        for (byte in sealed.indices) {
            for (bit in 0 until 8) {
                val tampered = sealed.copyOf()
                tampered[byte] = (tampered[byte].toInt() xor (1 shl bit)).toByte()
                assertNull(bobCipher.open(tampered, aad), "bit $bit of byte $byte")
            }
        }
    }

    @Test
    fun `changed header (associated data) makes the packet invalid`() {
        val sealed = aliceCipher.seal(message, aad)
        assertNull(bobCipher.open(sealed, "header!".toByteArray()))
    }

    @Test
    fun `another contact's key can't open it`() {
        val carol = DeviceId.random()
        val otherRoot = ContactRootKey(ByteArray(32) { 9 })
        val sealed = aliceCipher.seal(message, aad)
        assertNull(StaticKeyV1.contactCipher(otherRoot, me = bob, peer = alice).open(sealed, aad))
        assertNull(StaticKeyV1.contactCipher(root, me = carol, peer = alice).open(sealed, aad))
    }

    @Test
    fun `each direction has its own key`() {
        val rootBytes = root.toByteArray()
        assertFalse(
            StaticKeyV1
                .directionKey(
                    rootBytes,
                    alice,
                    bob,
                ).contentEquals(StaticKeyV1.directionKey(rootBytes, bob, alice)),
        )
    }

    @Test
    fun `sealing twice gives different bytes (random nonce)`() {
        assertFalse(aliceCipher.seal(message, aad).contentEquals(aliceCipher.seal(message, aad)))
    }

    @Test
    fun `garbage and short inputs return null instead of throwing`() {
        assertNull(bobCipher.open(ByteArray(0), aad))
        assertNull(bobCipher.open(ByteArray(39), aad))
        assertNull(bobCipher.open(ByteArray(100), aad))
    }

    @Test
    fun `pairing with yourself is refused and keys never print`() {
        assertThrows<IllegalArgumentException> { StaticKeyV1.contactCipher(root, alice, alice) }
        assertEquals("ContactRootKey(****)", root.toString())
    }
}
