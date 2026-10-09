package app.raven.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.os.Build
import app.raven.core.transport.link.LinkConfig

/**
 * The dialing side of links (PROTOCOL.md §8.3): connect → MTU 517 → 2M PHY if possible → find the service →
 * subscribe to OUT → link up. Any failure abandons the dial and backs off from that phone (LinkPlanner).
 */
@SuppressLint("MissingPermission") // only used while BleTransport runs with the permissions granted
internal class BleClient(
    private val transport: BleTransport,
) {
    private class Dial(
        val device: BluetoothDevice,
        val gatt: BluetoothGatt,
    ) {
        var fragmentSize = LinkConfig.FALLBACK_FRAGMENT
        var target: BluetoothGattCharacteristic? = null
    }

    private val dials = mutableMapOf<String, Dial>()

    fun isDialing(address: String): Boolean = address in dials

    fun dial(device: BluetoothDevice) {
        val now = transport.scheduler.now()
        transport.planner.onDialStarted(device.address, now)
        val gatt =
            transport.guarded(null) {
                device.connectGatt(transport.context, false, callback, BluetoothDevice.TRANSPORT_LE)
            }
        if (gatt == null) {
            transport.planner.onDialFailed(device.address, now)
            return
        }
        dials[device.address] = Dial(device, gatt)
    }

    /** Gives up on a dial (timeout or error) and backs off from that phone. */
    fun abandon(address: String) {
        val dial = dials.remove(address)
        transport.planner.onDialFailed(address, transport.scheduler.now())
        dial ?: return
        transport.guarded(Unit) {
            dial.gatt.disconnect()
            dial.gatt.close()
        }
    }

    fun closeAll() {
        dials.keys.toList().forEach(::abandon)
    }

    private fun afterMtu(
        dial: Dial,
        mtu: Int,
    ) {
        dial.fragmentSize = LinkConfig.fragmentSizeFor(mtu)
        transport.guarded(Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && transport.adapter?.isLe2MPhySupported == true) {
                dial.gatt.setPreferredPhy(
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_OPTION_NO_PREFERRED,
                )
            }
            if (!dial.gatt.discoverServices()) abandon(dial.device.address)
        }
    }

    private fun onServices(
        dial: Dial,
        ok: Boolean,
    ) {
        val service = dial.gatt.getService(BleIds.SERVICE)
        val target = service?.getCharacteristic(BleIds.IN)
        val out = service?.getCharacteristic(BleIds.OUT)
        val subscribed =
            ok && target != null && out != null &&
                transport.guarded(false) {
                    out.getDescriptor(BleIds.CCCD)?.let { dial.gatt.enableNotifications(out, it) } ?: false
                }
        dial.target = target
        if (!subscribed) abandon(dial.device.address) // not a Raven phone, or something went wrong
    }

    private fun onSubscribed(
        dial: Dial,
        ok: Boolean,
    ) {
        val address = dial.device.address
        val target = dial.target
        dials.remove(address)
        if (!ok || target == null || !transport.planner.onLinkUp(address, transport.scheduler.now())) {
            transport.planner.onDialFailed(address, transport.scheduler.now())
            transport.guarded(Unit) {
                dial.gatt.disconnect()
                dial.gatt.close()
            }
            return
        }
        transport.linkUp(address, BleTransport.Role.DIALER, dial.device, dial.gatt, target, dial.fragmentSize)
    }

    private fun onConnectionChange(
        gatt: BluetoothGatt,
        ok: Boolean,
        connected: Boolean,
    ) {
        val address = gatt.device.address
        val dial = dials[address]
        if (dial != null && dial.gatt === gatt) {
            when {
                ok && connected -> {
                    val asked = transport.guarded(false) { gatt.requestMtu(LinkConfig.REQUESTED_MTU) }
                    if (!asked) afterMtu(dial, DEFAULT_MTU)
                }

                !connected || !ok -> {
                    abandon(address)
                }
            }
            return
        }
        val link = transport.linkFor(address)
        if (!connected && link != null && link.gatt === gatt) transport.linkDown(link)
    }

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                val ok = status == BluetoothGatt.GATT_SUCCESS
                val connected = newState == BluetoothProfile.STATE_CONNECTED
                transport.post { onConnectionChange(gatt, ok, connected) }
            }

            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int,
            ) {
                val agreed = if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_MTU
                transport.post { dials[gatt.device.address]?.let { afterMtu(it, agreed) } }
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) {
                transport.post {
                    dials[gatt.device.address]?.let { onServices(it, status == BluetoothGatt.GATT_SUCCESS) }
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                transport.post {
                    dials[gatt.device.address]?.let { onSubscribed(it, status == BluetoothGatt.GATT_SUCCESS) }
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (characteristic.uuid != BleIds.OUT) return
                val copy = value.copyOf()
                transport.post { transport.linkFor(gatt.device.address)?.let { transport.onFragment(it, copy) } }
            }

            @Deprecated("Needed below Android 13")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || characteristic.uuid != BleIds.OUT) return
                // The value lives in a shared object that the next notification overwrites: copy it now.
                @Suppress("DEPRECATION")
                val copy = characteristic.value?.copyOf() ?: return
                transport.post { transport.linkFor(gatt.device.address)?.let { transport.onFragment(it, copy) } }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                val ok = status == BluetoothGatt.GATT_SUCCESS
                transport.post { transport.linkFor(gatt.device.address)?.let { transport.onWriteDone(it, ok) } }
            }
        }

    private companion object {
        /** The ATT default when no MTU was agreed. */
        const val DEFAULT_MTU = 23
    }
}
