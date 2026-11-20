package de.mirranet.ondura.mail

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.mirranet.ondura.core.CoreEvent

object Notifications {
    private const val CHANNEL_MAIL = "new-mail"
    private const val CHANNEL_SEND = "send-failed"
    const val EXTRA_MESSAGE_ID = "de.mirranet.ondura.mail.MESSAGE_ID"
    const val EXTRA_FOLDER_ID = "de.mirranet.ondura.mail.FOLDER_ID"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MAIL, context.getString(R.string.channel_new_mail), NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SEND, context.getString(R.string.channel_send_failed), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun newMail(context: Context, event: CoreEvent.NewMail) {
        if (!allowed(context)) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (event.count == 1) putExtra(EXTRA_MESSAGE_ID, event.messageId)
            putExtra(EXTRA_FOLDER_ID, event.folderId)
        }
        val pending = PendingIntent.getActivity(
            context, event.folderId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (event.count == 1) event.from else context.getString(R.string.notification_new_count, event.count)
        val text = if (event.count == 1) event.subject else context.getString(R.string.notification_latest, event.from, event.subject)
        val notification = NotificationCompat.Builder(context, CHANNEL_MAIL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.ondura_tint))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(event.folderId.hashCode(), notification)
        } catch (_: SecurityException) {
        }
    }

    fun sendFailed(context: Context, event: CoreEvent.SendFailed) {
        if (!allowed(context)) return
        val intent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        val pending = PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_SEND)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_send_failed, event.subject))
            .setContentText(event.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.message))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(("send" + event.subject).hashCode(), notification)
        } catch (_: SecurityException) {
        }
    }
}
