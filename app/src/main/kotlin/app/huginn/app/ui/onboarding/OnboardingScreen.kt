package app.huginn.app.ui.onboarding

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.huginn.app.media.ImageProcessing
import app.huginn.app.runtime.MeshRuntime
import app.huginn.app.ui.common.MainButton
import app.huginn.app.ui.contact.AvatarCamera
import app.huginn.app.ui.contact.AvatarChooser
import app.huginn.core.model.Nickname
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Step { WELCOME, NAME, PERMISSIONS, BATTERY }

/** First run (spec §3): name and optional avatar, permissions with explanations (D89), battery guide. */
@Composable
fun OnboardingScreen(runtime: MeshRuntime) {
    var step by rememberSaveable { mutableStateOf(Step.WELCOME) }
    var name by rememberSaveable { mutableStateOf("") }
    var avatar by remember { mutableStateOf<ByteArray?>(null) }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (step) {
                Step.WELCOME -> {
                    Welcome { step = Step.NAME }
                }

                Step.NAME -> {
                    NameStep(name, { name = it }, avatar, { avatar = it }) { step = Step.PERMISSIONS }
                }

                Step.PERMISSIONS -> {
                    PermissionsStep { step = Step.BATTERY }
                }

                Step.BATTERY -> {
                    BatteryStep {
                        runtime.createIdentity(checkNotNull(Nickname.clean(name)), avatar)
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Welcome(next: () -> Unit) {
    Spacer(Modifier.height(48.dp))
    Text("Huginn", style = MaterialTheme.typography.displaySmall)
    Text(
        "Message your friends without the internet. Phones pass encrypted messages to each other over " +
            "Bluetooth, so no server, account or phone number is ever involved.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        "You add a friend by scanning their QR code, in person.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.weight(1f))
    MainButton(onClick = next, modifier = Modifier.fillMaxWidth().testTag("start")) { Text("Get started") }
}

@Composable
private fun ColumnScope.NameStep(
    name: String,
    onName: (String) -> Unit,
    avatar: ByteArray?,
    onAvatar: (ByteArray?) -> Unit,
    next: () -> Unit,
) {
    var camera by remember { mutableStateOf(false) }
    if (camera) {
        AvatarCamera(onDone = { camera = false }, onAvatar = onAvatar)
        return
    }
    val nickname = Nickname.clean(name)
    Text("What should friends see?", style = MaterialTheme.typography.headlineSmall)
    AvatarChooser(name, avatar, onAvatar, openCamera = { camera = true })
    OutlinedTextField(
        value = name,
        onValueChange = onName,
        label = { Text("Nickname") },
        supportingText = { Text("${name.trim().codePointCount(0, name.trim().length)} / ${Nickname.MAX_CHARACTERS}") },
        isError = name.isNotBlank() && nickname == null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("nickname"),
    )
    Text(
        "Your photo is optional. It's shared only with people you pair with.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.weight(1f))
    MainButton(
        onClick = next,
        enabled = nickname != null,
        modifier = Modifier.fillMaxWidth().testTag("next"),
    ) { Text("Next") }
}

@Composable
private fun ColumnScope.PermissionsStep(next: () -> Unit) {
    val permissions =
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { next() }
    Text("Two permissions", style = MaterialTheme.typography.headlineSmall)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Text(
            "Nearby devices: lets Huginn find other Huginn phones over Bluetooth and pass messages along. " +
                "Huginn never uses this to work out where you are.",
            style = MaterialTheme.typography.bodyLarge,
        )
    } else {
        Text(
            "Location: on this Android version, Bluetooth scanning needs the location permission. Huginn " +
                "never uses or stores your location.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Text(
            "Notifications: tells you when a message arrives. It shows the sender's name only, never the text.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    Text(
        "The camera is only asked for later, the first time you scan a QR code.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.weight(1f))
    MainButton(
        onClick = { request.launch(permissions.toTypedArray()) },
        modifier = Modifier.fillMaxWidth().testTag("allow"),
    ) {
        Text("Allow")
    }
    TextButton(onClick = next, modifier = Modifier.fillMaxWidth().testTag("skipPermissions")) { Text("Not now") }
}

@Composable
private fun ColumnScope.BatteryStep(done: () -> Unit) {
    val context = LocalContext.current
    Text("Keep the mesh running", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Huginn passes messages along for others even when it's closed. Some phones stop apps in the " +
            "background to save battery, which would cut you off from the mesh.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        "Open the battery settings, find Huginn, and choose \"Don't optimise\" or \"Unrestricted\".",
        style = MaterialTheme.typography.bodyLarge,
    )
    OutlinedButton(
        onClick = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Open battery settings") }
    Spacer(Modifier.weight(1f))
    MainButton(onClick = done, modifier = Modifier.fillMaxWidth().testTag("finish")) { Text("Done") }
}
