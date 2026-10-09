package app.huginn.app

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.huginn.app.runtime.MeshRuntime
import app.huginn.app.runtime.PairingState
import app.huginn.app.runtime.RuntimeState
import app.huginn.app.ui.HuginnRoot
import app.huginn.app.ui.common.formatCode
import app.huginn.core.crypto.ContactCipher
import app.huginn.core.crypto.StaticKeyV1
import app.huginn.core.crypto.pairing.InviteSession
import app.huginn.core.crypto.pairing.QrInvite
import app.huginn.core.crypto.pairing.ScanSession
import app.huginn.core.mesh.MeshListener
import app.huginn.core.mesh.MeshNode
import app.huginn.core.mesh.packet.Content
import app.huginn.core.mesh.packet.InnerPacket
import app.huginn.core.model.DeviceId
import app.huginn.core.model.Nickname
import app.huginn.data.Storage
import app.huginn.data.db.MessageStatus
import app.huginn.data.security.DatabaseKey
import app.huginn.transport.fake.SimNetwork
import app.huginn.transport.fake.SimTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Build plan 4 "Done when": UI tests of pairing and chat. The app runs on the test-only fake network; a scripted
 * friend runs a real mesh engine on the other end of it (no simulated friends in the app itself, P4).
 */
@RunWith(AndroidJUnit4::class)
class UiFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tag = UUID.randomUUID().toString()
    private lateinit var peerRadio: SimTransport
    private val runtime =
        MeshRuntime(
            openStorage = {
                Storage.open(
                    context,
                    DatabaseKey(context, alias = tag, fileName = "$tag.key"),
                    "ui-$tag.db",
                )
            },
            transportFactory = { scheduler ->
                val network = SimNetwork(scheduler, seed = 1)
                val app = network.addNode()
                peerRadio = network.addNode()
                network.connect(app, peerRadio)
                app
            },
            notifier = null,
        )

    @After
    fun cleanUp() {
        context.deleteDatabase("ui-$tag.db")
        File(context.noBackupFilesDir, "$tag.key").delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(tag)
    }

    @Test
    fun onboardingCreatesAnIdentityAndShowsTheEmptyChatList() {
        runtime.start()
        compose.setContent { HuginnRoot(runtime) }
        waitFor { compose.onAllNodesWithTagExists("start") }
        compose.onNodeWithTag("start").performClick()
        compose.onNodeWithTag("nickname").performTextInput("Alice")
        compose.onNodeWithTag("next").performClick()
        compose.onNodeWithTag("skipPermissions").performClick()
        compose.onNodeWithTag("finish").performClick()
        waitFor { runtime.state.value is RuntimeState.Ready }
        waitFor { compose.onAllNodesWithTextExists("No contacts yet") }
        assertEquals("Alice", runtime.storage.identity()?.nickname)
    }

    @Test
    fun pairingByShowingMyQrThenChatting() {
        startAs("Alice")
        val bob = TestPeer(runtime, peerRadio, "Bob")
        compose.onNodeWithTag("add").performClick()
        compose.onNodeWithTag("showQr").performClick()
        waitFor { runtime.pairing.state.value is PairingState.ShowingQr }
        bob.scan((runtime.pairing.state.value as PairingState.ShowingQr).qrText)

        // Both phones show the same 6-digit code; Alice accepts.
        waitFor { bob.code != null && compose.onAllNodesWithTagExists("code") }
        compose.onNodeWithTag("code").assertTextEquals(formatCode(bob.code!!))
        compose.onNodeWithTag("accept").performClick()
        waitFor { bob.paired && compose.onAllNodesWithTextExists("Connected with Bob") }
        compose.onNodeWithTag("done").performClick()

        // Chat: send, receive, ticks, reaction.
        waitFor { compose.onAllNodesWithTagExists("chat-Bob") }
        compose.onNodeWithTag("chat-Bob").performClick()
        typeMessage("hello Bob")
        compose.onNodeWithTag("send").performClick()
        waitFor { "hello Bob" in bob.texts }
        waitFor { outgoingStatus(bob.id, "hello Bob") >= MessageStatus.DELIVERED }

        val alice = DeviceId(runtime.storage.identity()!!.deviceId)
        bob.sendText(alice, "hi Alice")
        waitFor { compose.onAllNodesWithTextExists("hi Alice") }
        waitFor { incomingStatus(bob.id, "hi Alice") == MessageStatus.READ } // Alice's chat is open: marked read
        compose.onNodeWithText("hi Alice").performTouchInput { longClick() }
        compose.onNodeWithTag("react-🔥").performClick()
        waitFor { "🔥" in bob.reactions }
    }

    @Test
    fun pairingByScanningTheirQr() {
        startAs("Alice")
        val carol = TestPeer(runtime, peerRadio, "Carol")
        val qr = carol.showQr()
        compose.onNodeWithTag("add").performClick()
        compose.onNodeWithTag("scanQr").performClick()
        // The scan screen clears old pairing state when it opens; hand in the QR text only after that.
        waitFor {
            compose.onAllNodesWithTextExists("Allow camera") ||
                compose.onAllNodesWithTextExists("Scan your friend")
        }
        compose.waitForIdle()
        runtime.pairing.scanned(qr) // the camera would deliver this text
        waitFor { carol.code != null && compose.onAllNodesWithTagExists("code") }
        compose.onNodeWithTag("code").assertTextEquals(formatCode(carol.code!!))
        carol.acceptInvite()
        waitFor { compose.onAllNodesWithTextExists("Connected with Carol") }
        compose.onNodeWithTag("done").performClick()
        waitFor { compose.onAllNodesWithTagExists("chat-Carol") }
    }

    // ---------------------------------------------------------------- helpers

    private fun startAs(name: String) {
        runtime.start()
        runtime.createIdentity(Nickname.clean(name)!!, null)
        compose.setContent { HuginnRoot(runtime) }
        // The first start opens the encrypted database cold, which is slow on old phones and fresh emulators.
        compose.waitUntil(STARTUP_TIMEOUT_MILLIS) { runtime.state.value is RuntimeState.Ready }
        waitFor { compose.onAllNodesWithTagExists("add") }
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MILLIS) { condition() }

    private fun outgoingStatus(
        peer: DeviceId,
        text: String,
    ): Int =
        runtime.storage.database
            .messages()
            .chat(peer.toByteArray())
            .firstOrNull { it.outgoing && it.text == text }
            ?.status ?: -1

    private fun incomingStatus(
        peer: DeviceId,
        text: String,
    ): Int =
        runtime.storage.database
            .messages()
            .chat(peer.toByteArray())
            .firstOrNull { !it.outgoing && it.text == text }
            ?.status ?: -1

    /** The message field is a classic EditText (incognito keyboard, H4), so type into it directly. */
    private fun typeMessage(text: String) {
        waitFor { compose.onAllNodesWithTagExists("messageInput") }
        compose.waitForIdle()
        compose.runOnUiThread { findEditText(compose.activity.window.decorView)!!.setText(text) }
        compose.waitForIdle()
    }

    private fun findEditText(view: View): EditText? =
        when (view) {
            is EditText -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findEditText(view.getChildAt(it)) }
            else -> null
        }

    private fun AndroidComposeTestRule<*, *>.onAllNodesWithTagExists(tag: String) =
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun AndroidComposeTestRule<*, *>.onAllNodesWithTextExists(text: String) =
        onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun SemanticsNodeInteraction.assertTextEquals(text: String) = assert(hasText(text))

    private companion object {
        const val TIMEOUT_MILLIS = 15_000L
        const val STARTUP_TIMEOUT_MILLIS = 60_000L
    }
}

/** A scripted friend: a real mesh engine on the other end of the fake network, run on the app's mesh thread. */
class TestPeer(
    private val runtime: MeshRuntime,
    radio: SimTransport,
    name: String,
) : MeshListener {
    val id = DeviceId.random()
    private val nickname = Nickname.clean(name)!!
    private val ciphers = ConcurrentHashMap<DeviceId, ContactCipher>()
    val texts = CopyOnWriteArrayList<String>()
    val reactions = CopyOnWriteArrayList<String>()

    @Volatile var code: String? = null

    @Volatile var paired = false
    private var scan: ScanSession? = null
    private var invite: InviteSession? = null
    private lateinit var node: MeshNode

    init {
        onMeshThread {
            node = MeshNode(id, radio, runtime.scheduler, { ciphers[it] }, this)
            node.start()
        }
    }

    fun scan(qrText: String) =
        onMeshThread {
            val parsed = QrInvite.fromQrText(qrText)!!
            val session = ScanSession.start(parsed, id, nickname, runtime.scheduler.now())!!
            scan = session
            node.sendHandshake(parsed.ownerId, session.request)
        }

    fun showQr(): String {
        var text = ""
        onMeshThread {
            val session = InviteSession(id, nickname, runtime.scheduler.now())
            invite = session
            text = session.invite.toQrText()
        }
        return text
    }

    fun acceptInvite() =
        onMeshThread {
            val accepted = invite!!.accept(runtime.scheduler.now())!!
            node.sendHandshake(accepted.contact.peerId, accepted.reply)
            ciphers[accepted.contact.peerId] =
                StaticKeyV1.contactCipher(accepted.contact.root, id, accepted.contact.peerId)
            paired = true
        }

    fun sendText(
        to: DeviceId,
        text: String,
    ) = onMeshThread { node.sendText(to, text) }

    override fun onHandshake(
        from: DeviceId,
        body: ByteArray,
    ) {
        invite?.let { session ->
            val result = session.onMessage(from, body, runtime.scheduler.now())
            if (result is InviteSession.RequestResult.Challenge) {
                node.sendHandshake(from, result.reply)
                code = result.code
            }
        }
        scan?.let { session ->
            when (val result = session.onMessage(from, body, runtime.scheduler.now())) {
                is ScanSession.Result.Code -> {
                    code = result.code
                }

                is ScanSession.Result.Paired -> {
                    ciphers[result.contact.peerId] =
                        StaticKeyV1.contactCipher(result.contact.root, id, result.contact.peerId)
                    paired = true
                }

                else -> {
                    Unit
                }
            }
        }
    }

    override fun onMessage(
        from: DeviceId,
        message: InnerPacket,
    ) {
        when (val content = message.content) {
            is Content.Text -> texts += content.text
            is Content.Reaction -> reactions += content.emoji
            else -> Unit
        }
    }

    /** Runs [work] on the mesh thread and waits for it (the engine is single-threaded). */
    private fun onMeshThread(work: () -> Unit) {
        val done = CountDownLatch(1)
        runtime.post {
            work()
            done.countDown()
        }
        check(done.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "mesh thread did not respond" }
    }

    private companion object {
        const val WAIT_SECONDS = 10L
    }
}
