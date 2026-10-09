package app.raven.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import app.raven.core.transport.link.WriteOutcome

// Android 13 replaced the "set value, then write" calls; the owner's Android 9 phone needs the old ones.

/** Writes one fragment to the advertiser's IN characteristic, without waiting for a response (Spike A). */
@SuppressLint("MissingPermission") // BleTransport only runs with the permissions granted
internal fun BluetoothGatt.writeFragment(
    characteristic: BluetoothGattCharacteristic,
    bytes: ByteArray,
): WriteOutcome =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        when (writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)) {
            BluetoothStatusCodes.SUCCESS -> WriteOutcome.STARTED
            BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> WriteOutcome.BUSY
            else -> WriteOutcome.FAILED
        }
    } else {
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        @Suppress("DEPRECATION")
        characteristic.value = bytes
        @Suppress("DEPRECATION")
        if (writeCharacteristic(characteristic)) WriteOutcome.STARTED else WriteOutcome.BUSY
    }

/** Notifies one fragment to a subscribed dialer on our OUT characteristic. */
@SuppressLint("MissingPermission")
internal fun BluetoothGattServer.notifyFragment(
    device: BluetoothDevice,
    characteristic: BluetoothGattCharacteristic,
    bytes: ByteArray,
): WriteOutcome =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        when (notifyCharacteristicChanged(device, characteristic, false, bytes)) {
            BluetoothStatusCodes.SUCCESS -> WriteOutcome.STARTED
            BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY, BluetoothStatusCodes.ERROR_UNKNOWN -> WriteOutcome.BUSY
            else -> WriteOutcome.FAILED // Bluetooth off, permission gone, not allowed
        }
    } else {
        @Suppress("DEPRECATION")
        characteristic.value = bytes
        @Suppress("DEPRECATION")
        if (notifyCharacteristicChanged(device, characteristic, false)) WriteOutcome.STARTED else WriteOutcome.BUSY
    }

/** Subscribes to the advertiser's OUT notifications (PROTOCOL.md §8.3 step 3). */
@SuppressLint("MissingPermission")
internal fun BluetoothGatt.enableNotifications(
    characteristic: BluetoothGattCharacteristic,
    cccd: BluetoothGattDescriptor,
): Boolean {
    if (!setCharacteristicNotification(characteristic, true)) return false
    val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        writeDescriptor(cccd, enable) == BluetoothStatusCodes.SUCCESS
    } else {
        @Suppress("DEPRECATION")
        cccd.value = enable
        @Suppress("DEPRECATION")
        writeDescriptor(cccd)
    }
}
