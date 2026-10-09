package app.raven.core.crypto.pairing

import app.raven.core.model.DeviceId
import app.raven.core.model.Nickname
import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals

/** Pairing decoders read data from strangers: they must never crash, whatever arrives. */
class PairingFuzzTest {
    private val aliceId = DeviceId(ByteArray(8) { 1 })
    private val bobId = DeviceId(ByteArray(8) { 2 })
    private val alice = Nickname.clean("Alice")!!

    @FuzzTest(maxDuration = "5m")
    fun `Base45 decoding never crashes and round-trips`(data: FuzzedDataProvider) {
        val text = data.consumeRemainingAsString()
        val bytes = Base45.decode(text) ?: return
        assertEquals(text, Base45.encode(bytes))
    }

    @FuzzTest(maxDuration = "5m")
    fun `QR text decoding never crashes and round-trips`(data: FuzzedDataProvider) {
        val text = data.consumeRemainingAsString()
        val invite = QrInvite.fromQrText(text) ?: return
        assertEquals(text, invite.toQrText())
    }

    @FuzzTest(maxDuration = "5m")
    fun `random handshake messages never crash either side or abort a fresh session`(data: FuzzedDataProvider) {
        val owner = InviteSession(aliceId, alice, createdAtMillis = 0)
        val first = data.consumeBytes(600)
        // Random bytes can't be a valid REQUEST (it needs the owner's key), so nothing may happen.
        assertEquals(InviteSession.RequestResult.Ignored, owner.onMessage(bobId, first, nowMillis = 1))

        val scanner = ScanSession.start(owner.invite, bobId, Nickname.clean("Bob")!!, nowMillis = 0)!!
        val result = scanner.onMessage(aliceId, data.consumeRemainingAsBytes(), nowMillis = 1)
        assertNotEquals(ScanSession.Result.Expired, result)
        if (result !is ScanSession.Result.Code) assertEquals(ScanSession.Result.Ignored, result)
    }

    @FuzzTest(maxDuration = "5m")
    fun `handshake message framing never crashes and round-trips`(data: FuzzedDataProvider) {
        val bytes = data.consumeRemainingAsBytes()
        val message = HandshakeMessage.decode(bytes) ?: return
        assertArrayEquals(bytes, message.encode())
    }
}
