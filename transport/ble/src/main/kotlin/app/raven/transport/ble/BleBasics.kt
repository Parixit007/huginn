package app.raven.transport.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.ParcelUuid
import android.provider.Settings
import java.util.UUID

/** The GATT layout (PROTOCOL.md §8.1). Random, brand-neutral UUIDs (D47). */
internal object BleIds {
    val SERVICE: UUID = UUID.fromString("21f4aec6-c5b9-4784-86b5-d37334400940")

    /** The dialing phone writes fragments here. */
    val IN: UUID = UUID.fromString("3084ce15-1725-4635-b7bb-d9e9807a29ff")

    /** The advertising phone notifies fragments here. */
    val OUT: UUID = UUID.fromString("0ff2fcb6-7980-41db-845a-4f495c69ba63")

    /** Standard "client characteristic configuration" descriptor. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val SERVICE_PARCEL = ParcelUuid(SERVICE)
}

/** What the Bluetooth layer can do right now, for the app's banners (build plan 5.5). */
data class BleStatus(
    val bluetoothOn: Boolean = false,
    val permissionsGranted: Boolean = false,
    /** Android 8–11 only deliver scan results while Location is on (Spike B). Always true on 12+. */
    val locationOn: Boolean = true,
    /** False if this phone can't advertise: others can't find it, though it can still find them (spec §6). */
    val canAdvertise: Boolean = true,
    val running: Boolean = false,
)

/** The runtime permissions Bluetooth needs on this Android version (spec §8). */
object BlePermissions {
    fun required(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun granted(context: Context): Boolean =
        required().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Android 8–11 need Location switched on for Bluetooth scans to return anything. */
    val locationNeeded: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    fun locationOn(context: Context): Boolean {
        if (!locationNeeded) return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.getSystemService(LocationManager::class.java)?.isLocationEnabled ?: false
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, 0) !=
                Settings.Secure.LOCATION_MODE_OFF
        }
    }
}
