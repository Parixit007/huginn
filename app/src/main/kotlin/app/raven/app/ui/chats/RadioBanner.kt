package app.raven.app.ui.chats

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raven.app.runtime.MeshRuntime
import app.raven.app.runtime.RadioState
import app.raven.transport.ble.BlePermissions

private class Problem(
    val text: String,
    val action: String?,
    val onAction: () -> Unit = {},
)

/**
 * Tells the user why Raven can't reach anyone, with a button that fixes it (build plan 5.5): paused (D96),
 * missing permission, Bluetooth off, Location off (Android 8–11), or a phone that can't advertise (spec §6).
 */
@Composable
fun RadioBanner(runtime: MeshRuntime) {
    val radio by runtime.radioState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val askPermissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            asked = true
            runtime.radio.refresh()
        }
    val problem =
        problemFor(radio, context, runtime) {
            // A second refusal means Android won't ask again: send the user to the app's settings instead.
            if (asked) openAppSettings(context) else askPermissions.launch(BlePermissions.required().toTypedArray())
        } ?: return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("radioBanner"),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(problem.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            problem.action?.let { TextButton(onClick = problem.onAction) { Text(it) } }
        }
    }
}

private fun problemFor(
    radio: RadioState,
    context: Context,
    runtime: MeshRuntime,
    askPermissions: () -> Unit,
): Problem? {
    val ble = radio.ble
    return when {
        radio.paused -> {
            Problem("Raven is paused. Nothing is sent, received or passed on.", "Resume") { runtime.radio.resume() }
        }

        !BlePermissions.granted(context) -> {
            val what = if (BlePermissions.locationNeeded) "Location" else "Nearby devices"
            Problem("Raven needs the $what permission to find phones over Bluetooth.", "Allow", askPermissions)
        }

        ble != null && !ble.bluetoothOn -> {
            Problem("Bluetooth is off, so Raven can't reach anyone.", "Turn on") { turnOnBluetooth(context) }
        }

        ble != null && !ble.locationOn -> {
            Problem(
                "Location is off. On this Android version, Bluetooth can't find nearby phones without it.",
                "Settings",
            ) {
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        }

        ble != null && !ble.canAdvertise -> {
            Problem(
                "This phone can't advertise over Bluetooth: others can't find it, but it can still find them.",
                null,
            )
        }

        else -> {
            null
        }
    }
}

@SuppressLint("MissingPermission") // only offered once BlePermissions.granted() (checked first in problemFor)
private fun turnOnBluetooth(context: Context) {
    context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
}
