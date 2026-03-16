package com.stargazed.assistant

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class StarGazedForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, MainActivity.CHANNEL_ID)
            .setContentTitle("★ Star Gazed")
            .setContentText("AI Assistant is active and listening")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1, notification)

        // Also start the voice recognition service
        startService(Intent(this, VoiceService::class.java))

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
