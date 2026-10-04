package ru.cw.launcher.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ru.cw.launcher.R

/** Держит загрузку сборки, пока система не остановит процесс в фоне. */
class WorkService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: getString(R.string.work_title)
        val notification = build(this, text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTE_ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val NOTE_ID = 47621
        private const val CHANNEL = "cw-work"
        const val EXTRA_TEXT = "text"

        fun show(context: Context, text: String) {
            val intent = Intent(context, WorkService::class.java).putExtra(EXTRA_TEXT, text)
            try {
                context.startForegroundService(intent)
            } catch (_: Exception) {
            }
        }

        fun hide(context: Context) {
            context.stopService(Intent(context, WorkService::class.java))
        }

        fun build(context: Context, text: String): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.work_channel), NotificationManager.IMPORTANCE_LOW)
                )
            }
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.work_title))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }
    }
}
