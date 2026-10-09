package app.raven.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import app.raven.core.transport.Cancellable
import app.raven.core.transport.link.LinkToken
import app.raven.core.transport.link.ScanBudget

/**
 * Advertising (PROTOCOL.md §8.2): legacy advert with the service UUID; the scan response carries the link
 * token. Never the device name or ID. Runs only while a slot is free (§8.6); restarted with a new token every
 * 15 minutes, which also gives the phone a new Bluetooth address.
 */
@SuppressLint("MissingPermission") // only used while BleTransport runs with the permissions granted
internal class BleAdvertiser(
    private val transport: BleTransport,
) {
    private var advertising = false
    private var retry: Cancellable? = null

    /** False once Android says this phone can't advertise (the UI warns, spec §6). */
    var supported = true
        private set

    /** Starts or stops advertising to match the planner (a free slot, not paused) and the server being ready. */
    fun refresh() {
        val wanted = transport.running && transport.server.ready && transport.planner.wantsAdvertising
        when {
            wanted && !advertising -> start()
            !wanted && advertising -> stop()
        }
    }

    fun rotateToken() {
        transport.token = LinkToken.random(transport.randomBytes)
        if (advertising) {
            stop()
            start()
        }
    }

    fun stop() {
        retry?.cancel()
        retry = null
        if (!advertising) return
        advertising = false
        transport.guarded(Unit) { transport.adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) }
    }

    private fun start() {
        val advertiser = transport.adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            markUnsupported()
            return
        }
        val settings =
            AdvertiseSettings
                .Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .setTimeout(0)
                .build()
        val data =
            AdvertiseData
                .Builder()
                .addServiceUuid(BleIds.SERVICE_PARCEL)
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build()
        val response =
            AdvertiseData
                .Builder()
                .addServiceData(BleIds.SERVICE_PARCEL, transport.token.toByteArray())
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build()
        advertising = true
        transport.guarded(Unit) { advertiser.startAdvertising(settings, data, response, callback) }
    }

    private fun markUnsupported() {
        advertising = false
        if (supported) {
            supported = false
            transport.reportStatus()
        }
    }

    private val callback =
        object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) = Unit

            override fun onStartFailure(errorCode: Int) {
                transport.post {
                    when (errorCode) {
                        ADVERTISE_FAILED_ALREADY_STARTED -> {
                            Unit
                        }

                        ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> {
                            markUnsupported()
                        }

                        else -> {
                            advertising = false
                            retry = transport.scheduler.schedule(RETRY_MILLIS) { refresh() }
                        }
                    }
                }
            }
        }

    private companion object {
        const val RETRY_MILLIS = 30_000L
    }
}

/**
 * Scanning (spec §6, D98): only with the service filter; continuous while the app is on screen, otherwise
 * 10 s every 60 s; never more than 5 starts per 30 s (Android ignores the rest silently).
 */
@SuppressLint("MissingPermission") // only used while BleTransport runs with the permissions granted
internal class BleScanner(
    private val transport: BleTransport,
) {
    private val budget = ScanBudget(transport.config.scanStartsPerWindow, transport.config.scanWindowMillis)
    private var scanning = false
    private var next: Cancellable? = null

    /** (Re)starts the scan plan for the current foreground/background mode. */
    fun restart() {
        stop()
        cycle()
    }

    fun stop() {
        next?.cancel()
        next = null
        if (!scanning) return
        scanning = false
        transport.guarded(Unit) { transport.adapter?.bluetoothLeScanner?.stopScan(callback) }
    }

    private fun cycle() {
        if (!transport.running) return
        val now = transport.scheduler.now()
        if (!budget.canStart(now)) {
            next = transport.scheduler.schedule(budget.nextStartAt(now) - now) { cycle() }
            return
        }
        start(now)
        val config = transport.config
        next =
            if (transport.foreground) {
                // Android quietly weakens scans that run for 30 minutes; restart well before that.
                transport.scheduler.schedule(FOREGROUND_RESTART_MILLIS) { restart() }
            } else {
                transport.scheduler.schedule(config.backgroundScanOnMillis) {
                    stop()
                    next =
                        transport.scheduler.schedule(
                            config.backgroundScanPeriodMillis - config.backgroundScanOnMillis,
                        ) {
                            cycle()
                        }
                }
            }
    }

    private fun start(now: Long) {
        val scanner = transport.adapter?.bluetoothLeScanner ?: return
        val filters = listOf(ScanFilter.Builder().setServiceUuid(BleIds.SERVICE_PARCEL).build())
        val mode = if (transport.foreground) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED
        val settings = ScanSettings.Builder().setScanMode(mode).build()
        budget.record(now)
        scanning = true
        transport.guarded(Unit) { scanner.startScan(filters, settings, callback) }
    }

    private fun onResult(result: ScanResult) {
        val device = result.device
        val token = LinkToken.fromServiceData(result.scanRecord?.getServiceData(BleIds.SERVICE_PARCEL))
        transport.post {
            if (!transport.running) return@post
            val dial = transport.planner.onSeen(device.address, token, transport.token, transport.scheduler.now())
            if (dial) transport.client.dial(device)
        }
    }

    private val callback =
        object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult,
            ) = onResult(result)

            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::onResult)

            override fun onScanFailed(errorCode: Int) {
                transport.post {
                    scanning = false
                    next?.cancel()
                    next = transport.scheduler.schedule(FAILED_RETRY_MILLIS) { cycle() }
                }
            }
        }

    private companion object {
        const val FOREGROUND_RESTART_MILLIS = 25 * 60_000L
        const val FAILED_RETRY_MILLIS = 10_000L
    }
}
