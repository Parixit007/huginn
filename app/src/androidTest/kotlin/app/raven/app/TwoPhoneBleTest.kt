package app.raven.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.raven.app.runtime.PairingState
import app.raven.app.runtime.RuntimeState
import app.raven.core.model.DeviceId
import app.raven.core.model.Nickname
import app.raven.data.db.MessageStatus
import org.junit.Test
import org.junit.runner.RunWith

/** Tests that need a second phone; normal test runs leave them out (see app/build.gradle.kts). */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class NeedsTwoPhones

/**
 * Build plan 5.7: two phones pair and chat over **real** Bluetooth LE (`BleTransport`), e.g. two emulators on
 * the emulator's virtual radio (Spike A). Pairing needs a camera, so `tools/two-phone-test.sh` hands the QR
 * text from one phone to the other and runs this class on both.
 */
@NeedsTwoPhones
@RunWith(AndroidJUnit4::class)
class TwoPhoneBleTest {
    private val args = InstrumentationRegistry.getArguments()
    private val role: String? = args.getString("role")
    private val runtime =
        (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RavenApplication)
            .runtime

    @Test
    fun pairAndChatOverBluetooth() {
        when (role) {
            "owner" -> owner()
            "scanner" -> scanner(String(hexToBytes(checkNotNull(args.getString("qr")))))
            else -> error("unknown role $role")
        }
    }

    /** Shows the QR code, accepts the request, then chats with whoever paired. */
    private fun owner() {
        startAs("Alice")
        runtime.pairing.showQr()
        val qr = await("my QR code") { (runtime.pairing.state.value as? PairingState.ShowingQr)?.qrText }
        Log.i(TAG, "QR:" + qr.toByteArray().joinToString("") { "%02x".format(it) })
        val request =
            await("a pairing request", PAIRING_WAIT) { runtime.pairing.state.value as? PairingState.ConfirmRequest }
        Log.i(TAG, "CODE:${request.code} from ${request.peerNickname}")
        runtime.pairing.accept()
        val bob = await("the new contact") { contactNamed("Bob") }
        await("Bob's message", CHAT_WAIT) { incoming(bob, "hi Alice").takeIf { it } }
        runtime.chat.sendText(bob, "hi Bob")
        await("a delivered tick on my reply", CHAT_WAIT) {
            (outgoingStatus(bob, "hi Bob") >= MessageStatus.DELIVERED).takeIf { it }
        }
        Log.i(TAG, "PASS owner")
    }

    /** Scans the owner's QR text, then says hello and waits for the answer. */
    private fun scanner(qr: String) {
        startAs("Bob")
        runtime.pairing.scanned(qr)
        val code =
            await("the 6-digit code", PAIRING_WAIT) { (runtime.pairing.state.value as? PairingState.ShowingCode)?.code }
        Log.i(TAG, "CODE:$code")
        val alice = await("the owner's accept", PAIRING_WAIT) { contactNamed("Alice") }
        runtime.chat.sendText(alice, "hi Alice")
        await(
            "a delivered tick",
            CHAT_WAIT,
        ) { (outgoingStatus(alice, "hi Alice") >= MessageStatus.DELIVERED).takeIf { it } }
        await("Alice's reply", CHAT_WAIT) { incoming(alice, "hi Bob").takeIf { it } }
        Log.i(TAG, "PASS scanner")
    }

    // ---------------------------------------------------------------- helpers

    private fun startAs(name: String) {
        await("start-up", STARTUP_WAIT) { (runtime.state.value !is RuntimeState.Loading).takeIf { it } }
        if (runtime.state.value is RuntimeState.NeedsOnboarding) runtime.createIdentity(Nickname.clean(name)!!, null)
        await("the identity", STARTUP_WAIT) { runtime.state.value as? RuntimeState.Ready }
        runtime.radio.setOn(true)
        runtime.radio.setForeground(true) // scan continuously, like an open app
        Log.i(TAG, "radio on as $name")
    }

    private fun contactNamed(name: String): DeviceId? =
        runtime.storage.database
            .contacts()
            .all()
            .firstOrNull { it.nickname == name }
            ?.let { DeviceId(it.peerId) }

    private fun incoming(
        peer: DeviceId,
        text: String,
    ): Boolean =
        runtime.storage.database
            .messages()
            .chat(peer.toByteArray())
            .any { !it.outgoing && it.text == text }

    private fun outgoingStatus(
        peer: DeviceId,
        text: String,
    ): Int =
        runtime.storage.database
            .messages()
            .chat(peer.toByteArray())
            .firstOrNull { it.outgoing && it.text == text }
            ?.status ?: -1

    private fun <T : Any> await(
        what: String,
        timeoutMillis: Long = STEP_WAIT,
        probe: () -> T?,
    ): T {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            probe()?.let { return it }
            Thread.sleep(POLL_MILLIS)
        }
        Log.i(TAG, "FAIL waiting for $what; nearby=${runtime.nearby.value} radio=${runtime.radioState.value}")
        error("timed out waiting for $what")
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) {
            hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }

    private companion object {
        const val TAG = "TwoPhone"
        const val POLL_MILLIS = 250L
        const val STEP_WAIT = 30_000L
        const val STARTUP_WAIT = 60_000L
        const val PAIRING_WAIT = 120_000L
        const val CHAT_WAIT = 90_000L
    }
}
