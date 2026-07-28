package com.projectmemento.app.reminder

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
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.projectmemento.app.MainActivity
import com.projectmemento.app.R
import com.projectmemento.app.data.ScreenshotDatabase

class WeeklyReminderWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result {
        val preferences = ReminderPreferences(applicationContext)
        if (!preferences.enabled) return Result.success()
        if (!notificationsAllowed()) return Result.success()

        val now = System.currentTimeMillis()
        val database = ScreenshotDatabase(applicationContext)
        return runCatching {
            val selected = ResurfacingPolicy.select(database.resurfacingCandidates(now), now)
            if (selected.isNotEmpty()) {
                showNotification(selected.size)
                database.markSurfaced(selected.map { it.id }, now)
                // Ana ekran ayni turu gostersin diye kimlikleri sakla; aksi halde
                // markSurfaced bu kayitlari politikanin disina itiyor ve bildirime
                // dokunan kullanici baska screenshotlar goruyordu.
                preferences.lastRoundIds = selected.map { it.id }
                preferences.lastRoundAt = now
            }
            Result.success()
        }.getOrElse {
            Result.retry()
        }.also {
            database.close()
        }
    }

    private fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun showNotification(count: Int) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        createChannel()
        val openApp = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val detail = if (count == 1) {
            "Kaydettiğin bir screenshot bugün yeniden bakmaya değer olabilir."
        } else {
            "Kaydettiğin $count screenshot bugün yeniden bakmaya değer olabilir."
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_memento)
            .setContentTitle("Bunları kaydetmiştin")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Haftalık hatırlatmalar",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Kaydettiğin screenshotlardan haftada en fazla üç tanesini geri getirir."
        }
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "weekly_resurfacing"
        const val NOTIFICATION_ID = 4103
    }
}
