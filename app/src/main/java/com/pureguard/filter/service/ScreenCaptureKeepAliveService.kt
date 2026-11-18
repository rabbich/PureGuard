package com.pureguard.filter.service

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

/**
 * Required by Android 10+ when holding an active MediaProjection token in RAM.
 */
class ScreenCaptureKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != 0 && dataIntent != null) {
            Android10ScreenCapturer.initialize(applicationContext, resultCode, dataIntent)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Android10ScreenCapturer.stop()
    }

    private fun createNotification(): Notification {
        val channelId = "pureguard_vision_guard"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId,
            "PureGuard Visual AI Guard",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("PureGuard – סינון תמונות AI ו-OCR פעיל")
            .setContentText("שומר על קדושת העיניים בזמן אמת (חסכוני בסוללה)")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 7701
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
    }
}
