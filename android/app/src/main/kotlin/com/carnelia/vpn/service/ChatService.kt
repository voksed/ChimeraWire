package com.carnelia.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.carnelia.vpn.ChatActivity
import com.carnelia.vpn.core.VpnServerConfig
import com.carnelia.vpn.core.chat.ChatRoomRegistry
import com.carnelia.vpn.utils.AppLogger

/**
 * Держит комнаты чата (см. [ChatRoomRegistry]) живыми в фоне, чтобы принимать сообщения,
 * даже когда экран чата закрыт. Запускается вручную с экрана комнаты — не автозапуск
 * при старте приложения.
 */
class ChatService : Service() {

    companion object {
        const val ACTION_START_ROOM = "com.carnelia.vpn.CHAT_START_ROOM"
        const val ACTION_STOP_ROOM = "com.carnelia.vpn.CHAT_STOP_ROOM"
        const val ACTION_STOP_ALL = "com.carnelia.vpn.CHAT_STOP_ALL"
        const val EXTRA_CONFIG = "config"
        private const val NOTIFICATION_ID = 77
        private const val CHANNEL_ID = "chat_channel"

        var isRunning: Boolean = false
            private set
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_ROOM -> {
                val config = intent.getSerializableExtra(EXTRA_CONFIG) as? VpnServerConfig
                if (config != null) {
                    isRunning = true
                    startForeground(NOTIFICATION_ID, buildNotification())
                    ChatRoomRegistry.get(this, config).start()
                    AppLogger.log("ChatService: комната запущена для ${config.name}")
                }
            }
            ACTION_STOP_ROOM -> {
                val config = intent.getSerializableExtra(EXTRA_CONFIG) as? VpnServerConfig
                config?.let { ChatRoomRegistry.get(this, it).stop() }
                if (ChatRoomRegistry.activeRooms().none { it.isRunning.value }) stopAll()
            }
            ACTION_STOP_ALL -> stopAll()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAll()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopAll() {
        isRunning = false
        ChatRoomRegistry.stopAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        AppLogger.log("ChatService: остановлен")
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Чат", NotificationManager.IMPORTANCE_LOW))
        }

        val stopIntent = Intent(this, ChatService::class.java).apply { action = ACTION_STOP_ALL }
        val stopPi = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val openIntent = Intent(this, ChatActivity::class.java)
        val openPi = PendingIntent.getActivity(this, 1, openIntent, PendingIntent.FLAG_IMMUTABLE)

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("Carnelia: чат активен")
            .setContentText("Ищу собеседников через DHT и слушаю сообщения")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Остановить",
                    stopPi
                ).build()
            )
            .build()
    }
}
