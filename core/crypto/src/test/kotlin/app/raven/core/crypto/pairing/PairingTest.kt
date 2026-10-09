package app.raven.core.crypto.pairing

import app.raven.core.crypto.StaticKeyV1
import app.raven.core.model.DeviceId
import app.raven.core.model.Nickname
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** One test per safety rule in docs/PROTOCOL.md §5, plus the happy path. */
class PairingTest {
    private val aliceId = DeviceId.random()
    private val bobId = DeviceId.random()
    private val malloryId = DeviceId.random()
    private val alice = Nickname.clean("Alice")!!
    private val bob = Nickname.clean("Bob")!!
    private val mallory = Nickname.clean("Bob")!! // pretends to be Bob
    private val start = 1_000_000L

    private fun invite() = InviteSession(aliceId, alice, start)

    /** B scans the QR text, which is how the invite really travels. */
    private fun scan(
        session: InviteSession,
        id: DeviceId,
        name: Nickname,
    ): ScanSession = ScanSession.start(QrInvite.fromQrText(session.invite.toQrText())!!, id, name, start)!!

    @Test
    fun `happy path - same code on both phones, then both get the same working key`() {
        val owner = invite()
        val scanner = scan(owner, bobId, bob)

        val challenge =
            assertInstanceOf(
                InviteSession.RequestResult.Challenge::class.java,
                owner.onMessage(
                    bobId,
                    scanner.request,
                    start + 1,
                ),
            )
        assertEquals(bobId, challenge.peerId)
        assertEquals(bob, challenge.peerNickname)

        val code =
            assertInstanceOf(
                ScanSession.Result.Code::class.java,
                scanner.onMessage(
                    aliceId,
                    challenge.reply,
                    start + 2,
                ),
            )
        assertEquals(challenge.code, code.code)
        assertEquals(6, code.code.length)

        val accepted = checkNotNull(owner.accept(start + 3))
        assertEquals(bobId, accepted.contact.peerId)
        val paired =
            assertInstanceOf(
                ScanSession.Result.Paired::class.java,
                scanner.onMessage(
                    aliceId,
                    accepted.reply,
                    start + 4,
                ),
            )
        assertEquals(aliceId, paired.contact.peerId)
        assertEquals(alice, paired.contact.peerNickname)

        val message = "first message".toByteArray()
        val sealed = StaticKeyV1.contactCipher(accepted.contact.root, aliceId, bobId).seal(message, byteArrayOf())
        assertArrayEquals(
            message,
            StaticKeyV1.contactCipher(paired.contact.root, bobId, aliceId).open(sealed, byteArrayOf()),
        )
    }

    @Test
    fun `rule 1 - a second phone pairing at the same time aborts the session`() {
        val owner = invite()
        val realBob = scan(owner, bobId, bob)
        val attacker = scan(owner, malloryId, mallory) // saw the QR code too

        assertInstanceOf(
            InviteSession.RequestResult.Challenge::class.java,
            owner.onMessage(
                malloryId,
                attacker.request,
                start + 1,
            ),
        )
        assertEquals(InviteSession.RequestResult.Aborted, owner.onMessage(bobId, realBob.request, start + 2))
        assertNull(owner.accept(start + 3))
    }

    @Test
    fun `rule 1 - a repeated identical request is answered with the same challenge, not an abort`() {
        val owner = invite()
        val scanner = scan(owner, bobId, bob)
        val first = owner.onMessage(bobId, scanner.request, start + 1) as InviteSession.RequestResult.Challenge
        val again = owner.onMessage(bobId, scanner.request, start + 2) as InviteSession.RequestResult.Challenge
        assertArrayEquals(first.reply, again.reply)
        assertEquals(first.code, again.code)
    }

    @Test
    fun `rule 2 - someone who changes the challenge on the way makes the codes differ`() {
        val owner = invite()
        val scanner = scan(owner, bobId, bob)
        val challenge = owner.onMessage(bobId, scanner.request, start + 1) as InviteSession.RequestResult.Challenge
        val tampered = challenge.reply.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val bobsCode = scanner.onMessage(aliceId, tampered, start + 2) as ScanSession.Result.Code
        assertNotEquals(challenge.code, bobsCode.code)
    }

    @Test
    fun `rule 3 - Bob saves nothing until a valid confirm, and a decline is final`() {
        val owner = invite()
        val scanner = scan(owner, bobId, bob)
        val challenge = owner.onMessage(bobId, scanner.request, start + 1) as InviteSession.RequestResult.Challenge
        scanner.onMessage(aliceId, challenge.reply, start + 2)

        // A forged confirm (right session, garbage contents) is ignored.
        val forged = challenge.reply.copyOf(9) + ByteArray(60)
        forged[0] = HandshakeType.CONFIRM.toByte()
        assertEquals(ScanSession.Result.Ignored, scanner.onMessage(aliceId, forged, start + 3))

        val decline = checkNotNull(owner.decline())
        assertEquals(ScanSession.Result.Declined, scanner.onMessage(aliceId, decline, start + 4))
        assertNull(owner.accept(start + 5))
    }

    @Test
    fun `rule 3 - a confirm from any other phone is ignored`() {
        val owner = invite()
        val scanner = scan(owner, bobId, bob)
        val challenge = owner.onMessage(bobId, scanner.request, start + 1) as InviteSession.RequestResult.Challenge
        scanner.onMessage(aliceId, challenge.reply, start + 2)
        val accepted = owner.accept(start + 3)!!
        assertEquals(ScanSession.Result.Ignored, scanner.onMessage(malloryId, accepted.reply, start + 4))
        assertInstanceOf(ScanSession.Result.Paired::class.java, scanner.onMessage(aliceId, accepted.reply, start + 5))
    }

    @Test
    fun `rule 4 - both sides expire after 5 minutes and a QR code works only once`() {
        val owner = invite()
        val scanner = scan(owner, bobId, bob)
        val late = start + PAIRING_TIMEOUT_MILLIS + 1
        assertEquals(InviteSession.RequestResult.Expired, owner.onMessage(bobId, scanner.request, late))
        assertEquals(ScanSession.Result.Expired, scanner.onMessage(aliceId, ByteArray(25), late))

        val reused = invite()
        val first = scan(reused, bobId, bob)
        val challenge = reused.onMessage(bobId, first.request, start + 1) as InviteSession.RequestResult.Challenge
        first.onMessage(aliceId, challenge.reply, start + 2)
        assertNotNull(reused.accept(start + 3))
        assertNull(reused.accept(start + 4))
        val second = scan(reused, malloryId, mallory)
        assertEquals(InviteSession.RequestResult.Ignored, reused.onMessage(malloryId, second.request, start + 5))
    }

    @Test
    fun `garbage or wrong-session requests are ignored and do not abort a real pairing`() {
        val owner = invite()
        val otherOwner = invite()
        val scanner = scan(owner, bobId, bob)
        assertEquals(InviteSession.RequestResult.Ignored, owner.onMessage(malloryId, ByteArray(80), start + 1))
        val wrongSession = scan(otherOwner, malloryId, mallory)
        assertEquals(InviteSession.RequestResult.Ignored, owner.onMessage(malloryId, wrongSession.request, start + 2))
        assertInstanceOf(
            InviteSession.RequestResult.Challenge::class.java,
            owner.onMessage(
                bobId,
                scanner.request,
                start + 3,
            ),
        )
    }

    @Test
    fun `scanning your own QR code is refused`() {
        val owner = invite()
        assertNull(ScanSession.start(owner.invite, aliceId, alice, start))
    }

    @Test
    fun `rule 5 - the QR code reveals no secret`() {
        // Everything in the QR code is public: session id, device id, public key, nickname.
        val text = invite().invite.toQrText()
        val payload = Base45.decode(text.removePrefix("MSH1:"))!!
        assertEquals(1 + 8 + 8 + 32 + 1 + "Alice".length, payload.size)
    }
}
