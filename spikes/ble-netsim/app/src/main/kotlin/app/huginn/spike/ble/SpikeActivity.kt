package app.huginn.spike.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID

/**
 * THROWAWAY EXPERIMENT (Phase 0, Spike A/B). Not part of the Huginn app and not production code.
 *
 * Questions it answers: can two devices find each other over BLE, connect, agree on a larger packet
 * size (MTU), switch to the faster 2M radio mode, and how fast can data flow?
 *
 * Start with:  adb shell am start -n app.huginn.spike.ble/.SpikeActivity --es mode peripheral
 *              adb shell am start -n app.huginn.spike.ble/.SpikeActivity --es mode central
 * Results are logged under the logcat tag "HuginnSpike".
 */
@SuppressLint("MissingPermission")
class SpikeActivity : Activity() {
    private lateinit var output: TextView
    private val bluetooth by lazy { getSystemService(BluetoothManager::class.java) }

    // Peripheral state
    private var server: BluetoothGattServer? = null
    private var subscriber: BluetoothDevice? = null
    private var rxExpected = 0
    private var rxCount = 0
    private var rxStart = 0L

    // Central state
    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var pingSentAt = 0L
    private var sendQueue = ArrayDeque<ByteArray>()
    private var sendStart = 0L
    private var sendBytes = 0
    private var phase = ""
    private var busyRetries = 0
    private var writeCallbacks = 0
    private val results = linkedMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply { setPadding(32, 96, 32, 32); textSize = 13f }
        setContentView(ScrollView(this).apply { addView(output) })
        log("SDK ${Build.VERSION.SDK_INT}, device ${Build.MANUFACTURER} ${Build.MODEL}")
        val missing = requiredPermissions().filter { checkSelfPermission(it) != 0 }
        if (missing.isNotEmpty()) {
            log("Missing permissions $missing — requesting; start the mode again after granting.")
            requestPermissions(missing.toTypedArray(), 1)
            return
        }
        val adapter = bluetooth.adapter
        log("Bluetooth enabled=${adapter?.isEnabled} multiAdvertising=${adapter?.isMultipleAdvertisementSupported}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            log("LE 2M PHY=${adapter?.isLe2MPhySupported} extendedAdvertising=${adapter?.isLeExtendedAdvertisingSupported}")
        }
        when (intent.getStringExtra("mode")) {
            "peripheral" -> startPeripheral()
            "central" -> startCentral()
            else -> log("Pass --es mode peripheral|central")
        }
    }

    override fun onDestroy() {
        bluetooth.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        bluetooth.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        server?.close()
        gatt?.close()
        super.onDestroy()
    }

    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    // ---------------------------------------------------------------- peripheral

    private fun startPeripheral() {
        log("PERIPHERAL: opening GATT server")
        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            CHAR_RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val tx = BluetoothGattCharacteristic(
            CHAR_TX,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        tx.addDescriptor(
            BluetoothGattDescriptor(
                CCCD,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(rx)
        service.addCharacteristic(tx)
        server = bluetooth.openGattServer(this, serverCallback)
        server?.addService(service)
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            log("PERIPHERAL: service added status=$status, starting advertising")
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build()
            val data = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(SERVICE))
                .setIncludeDeviceName(false)
                .build()
            bluetooth.adapter.bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
                ?: log("PERIPHERAL: no advertiser available on this device")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            log("PERIPHERAL: connection state=$newState status=$status")
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            log("PERIPHERAL: MTU changed to $mtu")
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (descriptor.uuid == CCCD) subscriber = device
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            log("PERIPHERAL: central subscribed to notifications")
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            val text = if (value.size < 32) value.decodeToString() else ""
            when {
                text == "PING" -> notify(device, "PONG".encodeToByteArray())
                text.startsWith("START:") -> {
                    rxExpected = text.removePrefix("START:").toInt()
                    log("PERIPHERAL: START received, expecting $rxExpected bytes (response needed=$responseNeeded)")
                    rxCount = 0
                    rxStart = SystemClock.elapsedRealtime()
                }
                else -> {
                    rxCount += value.size
                    if (rxCount <= value.size * 3 || rxCount % (value.size * 25) < value.size) {
                        log("PERIPHERAL: … $rxCount bytes so far (response needed=$responseNeeded)")
                    }
                    if (rxExpected > 0 && rxCount >= rxExpected) {
                        val ms = SystemClock.elapsedRealtime() - rxStart
                        log("PERIPHERAL: received $rxCount bytes in $ms ms (${kbps(rxCount, ms)} KB/s)")
                        rxExpected = 0
                        notify(device, "DONE".encodeToByteArray())
                    }
                }
            }
        }
    }

    private fun notify(device: BluetoothDevice, bytes: ByteArray) {
        val characteristic = server?.getService(SERVICE)?.getCharacteristic(CHAR_TX) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server?.notifyCharacteristicChanged(device, characteristic, false, bytes)
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            server?.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) = log("PERIPHERAL: advertising started")

        override fun onStartFailure(errorCode: Int) = log("PERIPHERAL: advertising FAILED error=$errorCode")
    }

    // ---------------------------------------------------------------- central

    private fun startCentral() {
        log("CENTRAL: scanning for the spike service")
        results["discovered"] = "no"
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        bluetooth.adapter.bluetoothLeScanner?.startScan(filters, settings, scanCallback)
            ?: log("CENTRAL: no scanner available (is Bluetooth on?)")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (gatt != null) return // a second result can arrive before stopScan takes effect
            bluetooth.adapter.bluetoothLeScanner?.stopScan(this)
            results["discovered"] = "yes (rssi ${result.rssi})"
            log("CENTRAL: found peer rssi=${result.rssi}, connecting")
            gatt = result.device.connectGatt(this@SpikeActivity, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) = log("CENTRAL: scan FAILED error=$errorCode")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            log("CENTRAL: connection state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                results["connected"] = "yes"
                gatt.requestMtu(517)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            this@SpikeActivity.mtu = mtu
            results["mtu"] = "$mtu"
            log("CENTRAL: MTU=$mtu status=$status, requesting 2M PHY")
            gatt.setPreferredPhy(
                BluetoothDevice.PHY_LE_2M_MASK,
                BluetoothDevice.PHY_LE_2M_MASK,
                BluetoothDevice.PHY_OPTION_NO_PREFERRED,
            )
            gatt.discoverServices()
        }

        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            results["phy"] = "tx=$txPhy rx=$rxPhy"
            log("CENTRAL: PHY tx=$txPhy rx=$rxPhy status=$status (2 = LE 2M)")
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(SERVICE) ?: return log("CENTRAL: spike service missing")
            rxChar = service.getCharacteristic(CHAR_RX)
            val tx = service.getCharacteristic(CHAR_TX)
            gatt.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(CCCD)
            val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, enable)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = enable
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            log("CENTRAL: notifications enabled, sending PING")
            pingSentAt = SystemClock.elapsedRealtime()
            write("PING".encodeToByteArray(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = onNotification(value)

        @Deprecated("Needed below Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) onNotification(characteristic.value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            writeCallbacks++
            if (status != BluetoothGatt.GATT_SUCCESS || writeCallbacks <= 4 || writeCallbacks % 25 == 0) {
                log("CENTRAL: write callback #$writeCallbacks status=$status phase=$phase queued=${sendQueue.size}")
            }
            if (phase.isNotEmpty()) sendNext()
        }
    }

    private fun onNotification(value: ByteArray) {
        when (value.decodeToString()) {
            "PONG" -> {
                val rtt = SystemClock.elapsedRealtime() - pingSentAt
                results["ping round trip"] = "$rtt ms"
                log("CENTRAL: PONG after $rtt ms")
                startTransfer("no-response")
            }
            "DONE" -> if (phase == "no-response") startTransfer("with-response") else reportResults()
        }
    }

    private fun startTransfer(mode: String) {
        phase = mode
        // ATT caps one attribute value at 512 bytes, even when MTU - 3 is larger (MTU 517 -> 514).
        val chunk = minOf(mtu - 3, 512)
        val payload = ByteArray(TRANSFER_BYTES) { (it % 251).toByte() }
        sendQueue = ArrayDeque(payload.toList().chunked(chunk).map { it.toByteArray() })
        sendBytes = 0
        log("CENTRAL: sending $TRANSFER_BYTES bytes ($mode writes, ${sendQueue.size} chunks of $chunk)")
        write("START:$TRANSFER_BYTES".encodeToByteArray(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        sendStart = SystemClock.elapsedRealtime()
    }

    private fun sendNext() {
        val next = sendQueue.removeFirstOrNull()
        if (next == null) {
            val ms = SystemClock.elapsedRealtime() - sendStart
            results["$phase throughput"] = "${kbps(sendBytes, ms)} KB/s ($sendBytes bytes in $ms ms, $busyRetries busy retries)"
            busyRetries = 0
            log("CENTRAL: $phase sent $sendBytes bytes in $ms ms (${kbps(sendBytes, ms)} KB/s)")
            return
        }
        sendBytes += next.size
        val type = if (phase == "no-response") {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        write(next, type)
    }

    private fun write(bytes: ByteArray, type: Int) {
        val characteristic = rxChar ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt?.writeCharacteristic(characteristic, bytes, type)
            if (phase.isNotEmpty() && sendBytes <= bytes.size * 3) log("CENTRAL: write(${bytes.size} B, type=$type) -> status $status")
            if (status == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY) {
                busyRetries++
                output.postDelayed({ write(bytes, type) }, 2)
            } else if (status != BluetoothStatusCodes.SUCCESS) {
                log("CENTRAL: write failed status=$status")
            }
        } else {
            characteristic.writeType = type
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            gatt?.writeCharacteristic(characteristic)
        }
    }

    private fun reportResults() {
        phase = ""
        log("RESULT " + results.entries.joinToString(" | ") { "${it.key}: ${it.value}" })
        gatt?.disconnect()
    }

    // ---------------------------------------------------------------- helpers

    private fun kbps(bytes: Int, ms: Long) = if (ms == 0L) "∞" else "%.1f".format(bytes / 1024.0 / (ms / 1000.0))

    private fun log(message: String) {
        Log.i(TAG, message)
        runOnUiThread { output.append(message + "\n") }
    }

    private companion object {
        const val TAG = "HuginnSpike"
        const val TRANSFER_BYTES = 50 * 1024
        val SERVICE: UUID = UUID.fromString("5d1b0b7e-2f43-4c8e-9a3b-6c1f0e4a9d10")
        val CHAR_RX: UUID = UUID.fromString("5d1b0b7e-2f43-4c8e-9a3b-6c1f0e4a9d11")
        val CHAR_TX: UUID = UUID.fromString("5d1b0b7e-2f43-4c8e-9a3b-6c1f0e4a9d12")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
