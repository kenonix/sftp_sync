package com.sftpsync.app.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sftpsync.app.utils.exitApplicationProcess

class SyncForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "sftp_bisync_channel"
        const val NOTIFICATION_ID = 2026
        const val ACTION_SHUTDOWN = "com.sftpsync.app.ACTION_SHUTDOWN"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHUTDOWN) {
            // User clicked shutdown action in notification
            exitApplicationProcess()
            stopSelf()
            return START_NOT_STICKY
        }

        // Build premium cyberpunk notifications
        val shutdownIntent = Intent(this, SyncForegroundService::class.java).apply {
            action = ACTION_SHUTDOWN
        }
        val shutdownPendingIntent = PendingIntent.getService(
            this,
            0,
            shutdownIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        // Intent to open MainActivity when clicking notification
        val mainActivityClass = try {
            Class.forName("com.sftpsync.app.MainActivity")
        } catch (e: Exception) {
            null
        }
        val contentPendingIntent = mainActivityClass?.let {
            val mainIntent = Intent(this, it).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            PendingIntent.getActivity(
                this,
                0,
                mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SFTP BiSync")
            .setContentText("실시간 양방향 동기화 백그라운드 가동 중")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setColor(0xFF00F0FF.toInt()) // Cyan glow brand color
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "동기화 즉시 완전 종료",
                shutdownPendingIntent
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SFTP BiSync 서비스 알림",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "실시간 백그라운드 폴더 동기화를 유지하기 위한 알림입니다."
                enableLights(true)
                lightColor = 0xFF00F0FF.toInt()
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
