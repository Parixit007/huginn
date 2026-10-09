package app.raven.app.runtime

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import app.raven.app.RavenApplication
import app.raven.transport.ble.BlePermissions

/**
 * Keeps Raven running in the background (spec §8): a foreground service of type `connectedDevice` with the
 * "Raven is on" notification (D97). The radio is on exactly while this service runs.
 */
class MeshService : Service() {
    private val runtime get() = (application as RavenApplication).runtime

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // Android requires the notification promptly after startForegroundService, so it goes first.
        val inForeground = runCatching { goForeground() }.isSuccess
        if (!inForeground || AndroidRadioHost.isPaused(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        runtime.radio.setOn(true)
        // Before onboarding there is nobody to run for; the runtime knows once its start-up has run.
        runtime.post {
            if (runtime.state.value is RuntimeState.NeedsOnboarding) Handler(Looper.getMainLooper()).post { stopSelf() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runtime.radio.setOn(false)
        super.onDestroy()
    }

    private fun goForeground() {
        val notification = Notifier.background(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 1
    }
}

/** Starts Raven after a reboot (once the phone has been unlocked, D50) and after an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AndroidRadioHost(context).startService()
        }
    }
}

/** The runtime's view of Android: the saved pause switch (D96) and the background service. */
class AndroidRadioHost(
    context: Context,
) : RadioHost {
    private val context = context.applicationContext

    override var paused: Boolean
        get() = isPaused(context)
        set(value) {
            prefs(context).edit().putBoolean(PAUSED, value).apply()
        }

    override fun startService() {
        if (paused || !BlePermissions.granted(context)) return
        // Android can refuse a start from the background; the next app start or reboot tries again.
        runCatching { context.startForegroundService(Intent(context, MeshService::class.java)) }
    }

    override fun stopService() {
        context.stopService(Intent(context, MeshService::class.java))
    }

    companion object {
        private const val PREFS = "radio"
        private const val PAUSED = "paused"

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun isPaused(context: Context): Boolean = prefs(context).getBoolean(PAUSED, false)
    }
}
