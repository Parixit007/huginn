package app.raven.app.runtime

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import app.raven.app.MainActivity
import app.raven.app.R
import app.raven.core.model.DeviceId

/** New-message notifications (spec D49): the sender's name only, never the message text. */
class Notifier(
    private val context: Context,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A contact sent you a message (shows their name only)"
            },
        )
    }

    fun newMessage(
        from: DeviceId,
        senderName: String,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            Notification
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(context.getColor(R.color.theme_primary)) // Raven's accent (D93)
                .setContentTitle(senderName)
                .setContentText("New message")
                .setContentIntent(open)
                .setAutoCancel(true)
                // D49: the name may show on the lock screen; the text never exists in the notification at all.
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build()
        manager.notify(from.hashCode(), notification)
    }

    companion object {
        private const val CHANNEL = "messages"
        private const val BACKGROUND_CHANNEL = "background"

        /** The background service's permanent notification (D97): low priority, no sound. */
        fun background(context: Context): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(BACKGROUND_CHANNEL, "Running in the background", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shown while Raven passes messages nearby" },
            )
            val open =
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            return Notification
                .Builder(context, BACKGROUND_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(context.getColor(R.color.theme_primary))
                .setContentTitle("Raven is on")
                .setContentText("Passing messages nearby")
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
        }
    }
}
