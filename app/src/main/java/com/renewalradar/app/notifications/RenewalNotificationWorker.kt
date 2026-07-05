package com.renewalradar.app.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.renewalradar.app.MainActivity
import com.renewalradar.app.R
import com.renewalradar.app.data.RenewalDatabase
import com.renewalradar.app.data.RenewalStatus
import com.renewalradar.app.data.SettingsStore
import java.time.LocalDate

class RenewalNotificationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsStore(applicationContext).settings.value
        if (!settings.notificationsEnabled || !NotificationScheduler.canPostNotifications(applicationContext)) {
            return Result.success()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return Result.success()
        }

        val today = LocalDate.now()
        val urgentItems = RenewalDatabase.get(applicationContext)
            .renewalDao()
            .getNotifiableItems()
            .filter {
                val status = it.status(today)
                status == RenewalStatus.NeedsAttention || status == RenewalStatus.Overdue
            }

        if (urgentItems.isEmpty()) return Result.success()

        val overdueCount = urgentItems.count { it.status(today) == RenewalStatus.Overdue }
        val attentionCount = urgentItems.size - overdueCount
        val title = when {
            overdueCount > 0 -> "$overdueCount renewal${if (overdueCount == 1) "" else "s"} overdue"
            else -> "$attentionCount renewal${if (attentionCount == 1) "" else "s"} need attention"
        }
        val text = urgentItems.take(3).joinToString { "${it.title} (${it.dueDate})" }

        val intent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, NotificationScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setColor(ContextCompat.getColor(applicationContext, R.color.notification_color))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(1001, notification)
        return Result.success()
    }
}
