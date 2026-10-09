package app.raven.app.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raven.app.runtime.MeshRuntime
import app.raven.app.runtime.PairingState
import app.raven.app.ui.camera.QrScanner
import app.raven.app.ui.camera.qrBitmap
import app.raven.app.ui.common.IgnoreTouchesWhenObscured
import app.raven.app.ui.common.MainButton
import app.raven.app.ui.common.formatCode
import kotlinx.coroutines.delay

private const val QR_PIXELS = 720
private const val SECONDS_PER_MINUTE = 60
private const val TICK_MILLIS = 1000L

/** "Show my QR" (spec §5): QR → someone's request → compare codes → Accept/Reject. */
@Composable
fun ShowQrScreen(
    runtime: MeshRuntime,
    done: () -> Unit,
) {
    val state by runtime.pairing.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { runtime.pairing.showQr() }
    DisposableEffect(Unit) { onDispose { runtime.pairing.cancel() } }
    IgnoreTouchesWhenObscured() // H5: the Accept button must not be tappable through an overlay
    PairingFrame {
        when (val current = state) {
            is PairingState.ShowingQr -> {
                Text("Let your friend scan this", style = MaterialTheme.typography.headlineSmall)
                val bitmap = remember(current.qrText) { qrBitmap(current.qrText, QR_PIXELS).asImageBitmap() }
                Image(
                    bitmap,
                    contentDescription = "Your pairing QR code",
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).testTag("qr"),
                )
                Countdown(runtime, current.expiresAt)
                Text(
                    "It works once and only for a few minutes. There's no secret in it, " +
                        "so it's fine if someone else sees it.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }

            is PairingState.ConfirmRequest -> {
                Text(
                    "${current.peerNickname} wants to connect",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Code(current.code)
                Text(
                    "Does ${current.peerNickname}'s phone show exactly the same code? Only accept if it does.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(
                        onClick = { runtime.pairing.decline() },
                        modifier = Modifier.testTag("reject"),
                    ) { Text("Reject") }
                    MainButton(
                        onClick = { runtime.pairing.accept() },
                        modifier = Modifier.testTag("accept"),
                    ) { Text("Codes match — accept") }
                }
            }

            else -> {
                Outcome(current, done)
            }
        }
    }
}

/** "Scan a QR code" (spec §5): camera (permission asked here, D89) → request → code → wait for their Accept. */
@Composable
fun ScanScreen(
    runtime: MeshRuntime,
    done: () -> Unit,
) {
    val context = LocalContext.current
    val state by runtime.pairing.state.collectAsStateWithLifecycle()
    var cameraAllowed by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var scanning by remember { mutableStateOf(true) }
    val askCamera =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraAllowed = it }
    LaunchedEffect(Unit) { runtime.pairing.cancel() }
    DisposableEffect(Unit) { onDispose { runtime.pairing.cancel() } }
    PairingFrame {
        when (val current = state) {
            PairingState.Idle -> {
                if (!cameraAllowed) {
                    Text("Camera", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Raven needs the camera to read your friend's QR code. " +
                            "It's only used on this screen and nothing is recorded.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    MainButton(onClick = { askCamera.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                } else if (scanning) {
                    Text("Scan your friend's code", style = MaterialTheme.typography.headlineSmall)
                    QrScanner(
                        onQrText = {
                            scanning = false
                            runtime.pairing.scanned(it)
                        },
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    )
                }
            }

            is PairingState.Requesting -> {
                Text(
                    "Asking ${current.ownerNickname} to connect…",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Text("Keep both phones close together.", style = MaterialTheme.typography.bodyLarge)
            }

            is PairingState.ShowingCode -> {
                Text("Compare this code", style = MaterialTheme.typography.headlineSmall)
                Code(current.code)
                Text(
                    "${current.ownerNickname}'s phone should show exactly the same code. " +
                        "They'll accept on their phone.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }

            else -> {
                Outcome(current, done)
            }
        }
    }
}

@Composable
private fun PairingFrame(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) { content() }
    }
}

@Composable
private fun Code(code: String) {
    Text(
        formatCode(code),
        fontSize = 44.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 4.sp,
        modifier = Modifier.testTag("code"),
    )
}

@Composable
private fun Countdown(
    runtime: MeshRuntime,
    expiresAt: Long,
) {
    var now by remember { mutableLongStateOf(runtime.scheduler.now()) }
    LaunchedEffect(expiresAt) {
        while (now < expiresAt) {
            delay(TICK_MILLIS)
            now = runtime.scheduler.now()
        }
    }
    val seconds = ((expiresAt - now) / TICK_MILLIS).coerceAtLeast(0)
    Text(
        "Valid for %d:%02d".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE),
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun Outcome(
    state: PairingState,
    done: () -> Unit,
) {
    val (title, body) =
        when (state) {
            is PairingState.Paired -> {
                "Connected with ${state.peerNickname}" to "You can message each other now."
            }

            PairingState.Declined -> {
                "Not connected" to "The pairing was rejected. Nothing was saved."
            }

            PairingState.TwoPhones -> {
                "Pairing stopped" to
                    "Two phones tried to use the same code. Someone else may have seen it. Start again, just the two of you."
            }

            PairingState.Expired -> {
                "Code expired" to "Pairing takes at most 5 minutes. Start again."
            }

            PairingState.InvalidCode -> {
                "Not a Raven code" to "That QR code isn't a pairing code."
            }

            else -> {
                "" to ""
            }
        }
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag("outcome"),
    )
    Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    Spacer(Modifier.padding(8.dp))
    MainButton(onClick = done, modifier = Modifier.testTag("done")) { Text("Done") }
}
