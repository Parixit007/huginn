package app.raven.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import app.raven.core.transport.Cancellable
import app.raven.core.transport.LinkId
import app.raven.core.transport.Scheduler
import app.raven.core.transport.Transport
import app.raven.core.transport.TransportListener
import app.raven.core.transport.link.FragmentResult
import app.raven.core.transport.link.LinkConfig
import app.raven.core.transport.link.LinkPipe
import app.raven.core.transport.link.LinkPlanner
import app.raven.core.transport.link.LinkToken
import app.raven.core.transport.link.WriteOutcome

/**
 * The real Bluetooth LE transport (spec §6, PROTOCOL.md §8). Every phone advertises with a GATT server and
 * also scans and dials; [LinkPlanner] decides who dials and when, [LinkPipe] moves the fragments.
 *
 * Threading: Android delivers Bluetooth callbacks on its own threads; each one is handed to [post], which
 * runs it on the mesh engine's thread. All state below is touched only there, and every [TransportListener]
 * call happens there too (the [Transport] contract).
 */
@SuppressLint("MissingPermission") // the radio starts only once BlePermissions.granted(); see startRadio
class BleTransport(
    internal val context: Context,
    internal val scheduler: Scheduler,
    internal val post: (() -> Unit) -> Unit,
    internal val config: LinkConfig = LinkConfig(),
    internal val randomBytes: (Int) -> ByteArray,
    private val onStatus: (BleStatus) -> Unit,
) : Transport {
    internal enum class Role { DIALER, ADVERTISER }

    /** One live link. A dialer writes to the remote IN characteristic; an advertiser notifies on its own OUT. */
    internal inner class Link(
        val id: LinkId,
        val address: String,
        val role: Role,
        val device: BluetoothDevice,
        val gatt: BluetoothGatt?,
        val target: BluetoothGattCharacteristic,
        fragmentSize: Int,
    ) {
        val pipe = LinkPipe(config, fragmentSize, ::writeOut)
        var closing = false
        var retryScheduled = false

        private fun writeOut(bytes: ByteArray): WriteOutcome =
            guarded(WriteOutcome.FAILED) {
                when (role) {
                    Role.DIALER -> gatt?.writeFragment(target, bytes) ?: WriteOutcome.FAILED
                    Role.ADVERTISER -> server.gattServer?.notifyFragment(device, target, bytes) ?: WriteOutcome.FAILED
                }
            }
    }

    internal val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    internal val planner = LinkPlanner<String>(config)
    internal var token = LinkToken.random(randomBytes)
    internal var running = false
        private set
    internal var foreground = false
        private set

    private val active = linkedMapOf<LinkId, Link>()
    private val byAddress = mutableMapOf<String, Link>()
    private var nextLinkId = 1L
    private var listener: TransportListener? = null
    private val timers = mutableListOf<Cancellable>()
    private var status = BleStatus()

    internal val client = BleClient(this)
    internal val server = BleServer(this)
    internal val advertiser = BleAdvertiser(this)
    internal val scanner = BleScanner(this)

    private val stateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                post {
                    when (state) {
                        BluetoothAdapter.STATE_ON -> startRadio()
                        BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> stopRadio()
                    }
                    reportStatus()
                }
            }
        }

    // ---------------------------------------------------------------- Transport

    override val links: Set<LinkId> get() = active.keys.toSet()

    override fun start(listener: TransportListener) {
        this.listener = listener
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(stateReceiver, filter)
        }
        startRadio()
    }

    override fun stop() {
        stopRadio()
        runCatching { context.unregisterReceiver(stateReceiver) }
        listener = null
    }

    override fun send(
        link: LinkId,
        packet: ByteArray,
    ): Boolean {
        val current = active[link] ?: return false
        if (current.closing || !current.pipe.enqueue(packet)) return false
        pump(current)
        return true
    }

    override fun disconnect(link: LinkId) {
        active[link]?.let(::closeLink)
    }

    // ---------------------------------------------------------------- app controls

    /** The app's screen is visible: scan continuously; otherwise duty-cycle (D98). Call on the mesh thread. */
    fun setForeground(visible: Boolean) {
        if (foreground == visible) return
        foreground = visible
        if (running) scanner.restart()
    }

    /** Try again after the user granted permissions or switched Location on (build plan 5.5). */
    fun retry() {
        if (!running) startRadio()
        reportStatus()
    }

    // ---------------------------------------------------------------- radio lifecycle

    private fun startRadio() {
        if (running || listener == null) return
        val ready = adapter?.isEnabled == true && BlePermissions.granted(context)
        if (!ready) {
            reportStatus()
            return
        }
        running = true
        planner.paused = false
        guarded(Unit) {
            server.open()
            scanner.restart()
        }
        timers += every(MAINTENANCE_MILLIS) { maintain() }
        timers += every(config.tokenRotationMillis) { advertiser.rotateToken() }
        reportStatus()
    }

    private fun stopRadio() {
        if (!running) return
        running = false
        timers.forEach(Cancellable::cancel)
        timers.clear()
        guarded(Unit) {
            scanner.stop()
            advertiser.stop()
            client.closeAll()
        }
        active.values.toList().forEach(::linkDown)
        guarded(Unit) { server.close() }
        planner.clear()
        reportStatus()
    }

    private fun maintain() {
        val now = scheduler.now()
        planner.overdueDials(now).forEach(client::abandon)
        planner.rotationVictim(now)?.let { byAddress[it] }?.let(::closeLink)
        planner.forgetOld(now)
        active.values.filter { it.pipe.isStuck(now, STUCK_WRITE_MILLIS) }.forEach(::closeLink)
    }

    // ---------------------------------------------------------------- links (used by the client and server parts)

    internal fun linkUp(
        address: String,
        role: Role,
        device: BluetoothDevice,
        gatt: BluetoothGatt?,
        target: BluetoothGattCharacteristic,
        fragmentSize: Int,
    ) {
        val link = Link(LinkId(nextLinkId++), address, role, device, gatt, target, fragmentSize)
        active[link.id] = link
        byAddress[address] = link
        listener?.onLinkUp(link.id)
        advertiser.refresh()
    }

    /** The link is gone (closed by either side, or the radio stopped). Idempotent. */
    internal fun linkDown(link: Link) {
        if (active.remove(link.id) == null) return
        byAddress.remove(link.address)
        planner.onLinkDown(link.address)
        guarded(Unit) { link.gatt?.close() }
        listener?.onLinkDown(link.id)
        advertiser.refresh()
    }

    internal fun linkFor(address: String): Link? = byAddress[address]

    internal fun closeLink(link: Link) {
        if (link.closing) return
        link.closing = true
        guarded(Unit) {
            when (link.role) {
                Role.DIALER -> link.gatt?.disconnect()
                Role.ADVERTISER -> server.gattServer?.cancelConnection(link.device)
            }
        }
        // If Android never reports the disconnection, forget the link anyway.
        scheduler.schedule(CLOSE_GRACE_MILLIS) { linkDown(link) }
    }

    internal fun onFragment(
        link: Link,
        bytes: ByteArray,
    ) {
        when (val result = link.pipe.onFragment(bytes)) {
            is FragmentResult.Packet -> listener?.onReceive(link.id, result.bytes)
            FragmentResult.Hostile -> closeLink(link)
            FragmentResult.Pending, FragmentResult.Dropped -> Unit
        }
    }

    internal fun onWriteDone(
        link: Link,
        success: Boolean,
    ) {
        link.pipe.onWriteDone()
        if (success) pump(link) else closeLink(link)
    }

    private fun pump(link: Link) {
        if (link.closing) return
        when (link.pipe.pump(scheduler.now())) {
            WriteOutcome.STARTED -> {
                Unit
            }

            WriteOutcome.FAILED -> {
                closeLink(link)
            }

            WriteOutcome.BUSY -> {
                if (!link.retryScheduled) {
                    link.retryScheduled = true
                    scheduler.schedule(BUSY_RETRY_MILLIS) {
                        link.retryScheduled = false
                        if (link.id in active) pump(link)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** A permission can be revoked while running: Android then throws. Stop cleanly instead of crashing. */
    internal fun <T> guarded(
        fallback: T,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (_: SecurityException) {
            post { stopRadio() }
            fallback
        }

    internal fun reportStatus() {
        val now =
            BleStatus(
                bluetoothOn = adapter?.isEnabled == true,
                permissionsGranted = BlePermissions.granted(context),
                locationOn = BlePermissions.locationOn(context),
                canAdvertise = advertiser.supported,
                running = running,
            )
        if (now != status) {
            status = now
            onStatus(now)
        }
    }

    private fun every(
        periodMillis: Long,
        task: () -> Unit,
    ): Cancellable {
        var current: Cancellable? = null

        fun arm() {
            current =
                scheduler.schedule(periodMillis) {
                    if (running) {
                        task()
                        arm()
                    }
                }
        }
        arm()
        return Cancellable { current?.cancel() }
    }

    private companion object {
        const val MAINTENANCE_MILLIS = 5_000L
        const val STUCK_WRITE_MILLIS = 5_000L
        const val BUSY_RETRY_MILLIS = 10L
        const val CLOSE_GRACE_MILLIS = 3_000L
    }
}
