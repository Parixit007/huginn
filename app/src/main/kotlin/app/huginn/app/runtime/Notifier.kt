package app.huginn.app.runtime

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import app.huginn.app.MainActivity
import app.huginn.app.R
import app.huginn.core.model.DeviceId

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
                .setColor(context.getColor(R.color.theme_primary)) // Huginn's accent (D93)
                .setContentTitle(senderName)
                .setContentText("New message")
                .setContentIntent(open)
                .setAutoCancel(true)
                // D49: the name may show on the lock screen; the text never exists in the notification at all.
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build()
        manager.notify(from.hashCode(), notification)
    }

    private companion object {
        const val CHANNEL = "messages"
    }
}
