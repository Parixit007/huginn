package app.raven.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import app.raven.core.transport.Cancellable
import app.raven.core.transport.link.LinkConfig

/**
 * The advertising side of links (PROTOCOL.md §8.1, §8.3): a GATT server with IN (written by dialers) and
 * OUT (notified to them). A dialer becomes a link when it subscribes to OUT. Anything that connects and
 * doesn't subscribe within the hello timeout is disconnected (Spike B: strangers' devices connect on their own).
 */
@SuppressLint("MissingPermission") // only used while BleTransport runs with the permissions granted
internal class BleServer(
    private val transport: BleTransport,
) {
    var gattServer: BluetoothGattServer? = null
        private set
    private var out: BluetoothGattCharacteristic? = null
    private val mtus = mutableMapOf<String, Int>()
    private val unsubscribed = mutableMapOf<String, Cancellable>()

    /** True once the service is registered; advertising waits for it. */
    var ready = false
        private set

    fun open() {
        val manager = transport.context.getSystemService(BluetoothManager::class.java) ?: return
        val server = manager.openGattServer(transport.context, callback) ?: return
        gattServer = server
        server.addService(service())
    }

    fun close() {
        unsubscribed.values.forEach(Cancellable::cancel)
        unsubscribed.clear()
        mtus.clear()
        ready = false
        out = null
        gattServer?.close()
        gattServer = null
    }

    private fun service(): BluetoothGattService {
        val service = BluetoothGattService(BleIds.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                BleIds.IN,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )
        val notify =
            BluetoothGattCharacteristic(
                BleIds.OUT,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
        notify.addDescriptor(
            BluetoothGattDescriptor(
                BleIds.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(notify)
        return service
    }

    private fun onConnected(device: BluetoothDevice) {
        val address = device.address
        // Our own outgoing dials show up here too (Android reports every LE connection to the server).
        if (transport.linkFor(address) != null || transport.client.isDialing(address)) return
        unsubscribed.remove(address)?.cancel()
        unsubscribed[address] =
            transport.scheduler.schedule(SUBSCRIBE_TIMEOUT_MILLIS) {
                unsubscribed.remove(address)
                if (transport.linkFor(address) == null) {
                    transport.guarded(Unit) { gattServer?.cancelConnection(device) }
                }
            }
    }

    private fun onDisconnected(address: String) {
        unsubscribed.remove(address)?.cancel()
        mtus.remove(address)
        transport.linkFor(address)?.takeIf { it.role == BleTransport.Role.ADVERTISER }?.let(transport::linkDown)
    }

    private fun onSubscribe(
        device: BluetoothDevice,
        enabled: Boolean,
    ) {
        val address = device.address
        unsubscribed.remove(address)?.cancel()
        val existing = transport.linkFor(address)
        val target = out
        when {
            !enabled -> {
                existing?.takeIf { it.role == BleTransport.Role.ADVERTISER }?.let(transport::closeLink)
            }

            // Already linked to this phone as its dialer: the same Bluetooth connection may carry both roles,
            // so leave it alone rather than tear down our own link.
            existing != null || target == null -> {
                Unit
            }

            !transport.planner.onLinkUp(address, transport.scheduler.now()) -> {
                transport.guarded(Unit) { gattServer?.cancelConnection(device) } // every slot is taken (D98)
            }

            else -> {
                val fragmentSize = LinkConfig.fragmentSizeFor(mtus[address] ?: DEFAULT_MTU)
                transport.linkUp(address, BleTransport.Role.ADVERTISER, device, null, target, fragmentSize)
            }
        }
    }

    private fun respond(
        device: BluetoothDevice,
        requestId: Int,
        responseNeeded: Boolean,
    ) {
        if (responseNeeded) {
            transport.guarded(Unit) { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) }
        }
    }

    private val callback =
        object : BluetoothGattServerCallback() {
            override fun onServiceAdded(
                status: Int,
                service: BluetoothGattService,
            ) {
                transport.post {
                    ready = status == BluetoothGatt.GATT_SUCCESS
                    out = gattServer?.getService(BleIds.SERVICE)?.getCharacteristic(BleIds.OUT)
                    transport.advertiser.refresh()
                }
            }

            override fun onConnectionStateChange(
                device: BluetoothDevice,
                status: Int,
                newState: Int,
            ) {
                val connected = newState == BluetoothProfile.STATE_CONNECTED
                transport.post { if (connected) onConnected(device) else onDisconnected(device.address) }
            }

            override fun onMtuChanged(
                device: BluetoothDevice,
                mtu: Int,
            ) {
                transport.post {
                    mtus[device.address] = mtu
                    transport.linkFor(device.address)?.pipe?.fragmentSize = LinkConfig.fragmentSizeFor(mtu)
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) {
                respond(device, requestId, responseNeeded)
                if (descriptor.uuid != BleIds.CCCD) return
                val enabled = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
                transport.post { onSubscribe(device, enabled) }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) {
                respond(device, requestId, responseNeeded)
                val whole = !preparedWrite && offset == 0 // fragments always fit one write (PROTOCOL.md §8.4)
                if (characteristic.uuid != BleIds.IN || !whole || value == null) return
                val copy = value.copyOf()
                transport.post {
                    transport
                        .linkFor(device.address)
                        ?.takeIf { it.role == BleTransport.Role.ADVERTISER }
                        ?.let { transport.onFragment(it, copy) }
                }
            }

            override fun onNotificationSent(
                device: BluetoothDevice,
                status: Int,
            ) {
                val ok = status == BluetoothGatt.GATT_SUCCESS
                transport.post {
                    transport
                        .linkFor(device.address)
                        ?.takeIf { it.role == BleTransport.Role.ADVERTISER }
                        ?.let { transport.onWriteDone(it, ok) }
                }
            }
        }

    private companion object {
        const val DEFAULT_MTU = 23

        /** Same as the engine's hello timeout (D98): a connection must subscribe within it. */
        const val SUBSCRIBE_TIMEOUT_MILLIS = 10_000L
    }
}
