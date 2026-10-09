package app.raven.core.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.GeneralSecurityException

/** Known-answer tests against the official test vectors (fetched from the IETF documents). */
class PrimitivesTest {
    @Test
    fun `XChaCha20-Poly1305 matches draft-irtf-cfrg-xchacha-03 section A_3_1`() {
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("404142434445464748494a4b4c4d4e4f5051525354555657")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val plaintext =
            hex(
                """
                4c616469657320616e642047656e746c656d656e206f662074686520636c6173
                73206f66202739393a204966204920636f756c64206f6666657220796f75206f
                6e6c79206f6e652074697020666f7220746865206675747572652c2073756e73
                637265656e20776f756c642062652069742e
                """,
            )
        val ciphertext =
            hex(
                """
                bd6d179d3e83d43b9576579493c0e939572a1700252bfaccbed2902c21396cbb
                731c7f1b0b4aa6440bf3a82f4eda7e39ae64c6708c54c216cb96b72e1213b452
                2f8c9ba40db5d945b11b69b982c1bb9e3f3fac2bc369488f76b2383565d3fff9
                21f9664c97637da9768812f615c68b13b52e
                """,
            )
        val tag = hex("c0875924c1c7987947deafd8780acf49")
        // Tink's format is nonce ‖ ciphertext ‖ tag.
        val sealed = nonce + ciphertext + tag
        assertArrayEquals(plaintext, Primitives.aead(key).decrypt(sealed, aad))
    }

    @Test
    fun `X25519 matches RFC 7748 section 6_1`() {
        val alicePrivate = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPrivate = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val alicePublic = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val bobPublic = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val shared = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
        assertArrayEquals(alicePublic, Primitives.x25519PublicKey(alicePrivate))
        assertArrayEquals(bobPublic, Primitives.x25519PublicKey(bobPrivate))
        assertArrayEquals(shared, Primitives.x25519(alicePrivate, bobPublic))
        assertArrayEquals(shared, Primitives.x25519(bobPrivate, alicePublic))
    }

    @Test
    fun `X25519 rejects a low-order public key that forces an all-zero secret`() {
        val anyPrivate = Primitives.newX25519PrivateKey()
        assertThrows<GeneralSecurityException> { Primitives.x25519(anyPrivate, ByteArray(32)) }
    }

    @Test
    fun `HKDF-SHA256 matches RFC 5869 test case 1`() {
        val okm =
            Primitives.hkdf(
                ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
                salt = hex("000102030405060708090a0b0c"),
                info = hex("f0f1f2f3f4f5f6f7f8f9"),
                size = 42,
            )
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            okm,
        )
    }
}
