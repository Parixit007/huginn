package app.raven.core.crypto.pairing

import app.raven.core.model.DeviceId
import app.raven.core.model.Nickname
import app.raven.core.model.SessionId
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Base45Test {
    @Test
    fun `encodes the RFC 9285 examples`() {
        assertEquals("BB8", Base45.encode("AB".toByteArray()))
        assertEquals("%69 VD92EX0", Base45.encode("Hello!!".toByteArray()))
        assertEquals("UJCLQE7W581", Base45.encode("base-45".toByteArray()))
    }

    @Test
    fun `decodes the RFC 9285 example`() {
        assertArrayEquals("ietf!".toByteArray(), Base45.decode("QED8WEX0"))
    }

    @Test
    fun `rejects invalid input`() {
        assertNull(Base45.decode("a")) // lowercase is not in the alphabet
        assertNull(Base45.decode("BB8B")) // a dangling single character
        assertNull(Base45.decode("GGW")) // 65535 + 1: above two bytes
        assertNull(Base45.decode("::")) // above one byte
    }

    @Test
    fun `round-trips every byte value`() {
        val all = ByteArray(256) { it.toByte() }
        assertArrayEquals(all, Base45.decode(Base45.encode(all)))
    }
}

class QrInviteTest {
    private val invite =
        QrInvite(
            SessionId(ByteArray(8) { 1 }),
            DeviceId(ByteArray(8) { 2 }),
            X25519PublicKey(ByteArray(32) { 3 }),
            Nickname.clean("Ravi 🦉")!!,
        )

    @Test
    fun `round-trips through the QR text`() {
        val text = invite.toQrText()
        assertTrue(text.startsWith("MSH1:"))
        val decoded = checkNotNull(QrInvite.fromQrText(text))
        assertEquals(invite.sessionId, decoded.sessionId)
        assertEquals(invite.ownerId, decoded.ownerId)
        assertEquals(invite.ownerPublicKey, decoded.ownerPublicKey)
        assertEquals(invite.nickname, decoded.nickname)
    }

    @Test
    fun `QR text uses only QR alphanumeric characters and stays small`() {
        val text = invite.toQrText()
        assertTrue(text.all { it in "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:" })
        assertTrue(text.length < 200, "length ${text.length}")
    }

    @Test
    fun `rejects other prefixes, versions and trailing data`() {
        val payload = Base45.decode(invite.toQrText().removePrefix("MSH1:"))!!
        assertNull(QrInvite.fromQrText("http://example.com"))
        assertNull(QrInvite.fromQrText("MSH2:" + Base45.encode(payload)))
        assertNull(QrInvite.fromQrText("MSH1:" + Base45.encode(payload.copyOf().also { it[0] = 2 })))
        assertNull(QrInvite.fromQrText("MSH1:" + Base45.encode(payload + byteArrayOf(0))))
        assertNull(QrInvite.fromQrText("MSH1:" + Base45.encode(payload.copyOf(payload.size - 1))))
        assertNull(QrInvite.fromQrText("MSH1:"))
    }

    @Test
    fun `rejects a nickname that is not clean`() {
        val payload = Base45.decode(invite.toQrText().removePrefix("MSH1:"))!!
        val nicknameStart = 1 + 8 + 8 + 32 + 1
        val withZeroWidth = payload.copyOf(nicknameStart) + "a​b".toByteArray()
        withZeroWidth[nicknameStart - 1] = (withZeroWidth.size - nicknameStart).toByte()
        assertNull(QrInvite.fromQrText("MSH1:" + Base45.encode(withZeroWidth)))
    }
}
