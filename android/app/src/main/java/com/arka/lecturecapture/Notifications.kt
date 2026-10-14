package com.arka.lecturecapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object Notifications {
    const val RECORDING_ID = 1
    private const val INTERRUPTED_ID = 2
    private const val CHANNEL_RECORDING = "recording"
    private const val CHANNEL_ALERTS = "alerts"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, "Запись", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Сбои записи", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
            },
        )
    }

    fun recording(context: Context, open: OpenLecture?): Notification {
        val text = open?.let { "${it.state.subject} — ${it.state.title}" } ?: "Лекция"
        return Notification.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Идёт запись")
            .setContentText(text)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openApp(context))
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    /** Loud, so a recording that silently died is noticed during the lecture, not at home. */
    fun interrupted(context: Context, reason: String) {
        val notification = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Запись прервалась")
            .setContentText("Нажми, чтобы открыть приложение и продолжить")
            .setStyle(Notification.BigTextStyle().bigText("Нажми, чтобы открыть приложение и продолжить.\n$reason"))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ERROR)
            .setContentIntent(openApp(context))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(INTERRUPTED_ID, notification)
    }

    fun clearInterrupted(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(INTERRUPTED_ID)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
