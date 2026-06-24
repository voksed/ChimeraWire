package com.carnelia.vpn.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.carnelia.vpn.BlackWallActivity

object SecurityAlertNotifier {
    private const val CHANNEL_ID = "security_alerts"
    private const val NOTIFICATION_ID = 9001

    fun notify(context: Context, risk: AppRisk) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Предупреждения безопасности", NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(channel)
        }

        val openIntent = Intent(context, BlackWallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, risk.packageName.hashCode(), openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val lockdownEngaged = risk.score >= 60
        val title = if (lockdownEngaged) "Carnelia: вас могли взломать — сеть заблокирована" else "Carnelia: подозрительное приложение"
        val text = if (lockdownEngaged)
            "«${risk.appName}» похоже на шпионское ПО. Carnelia изолировала устройство от сети."
        else
            "«${risk.appName}» вызывает подозрение — стоит проверить."

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(
                "«${risk.appName}» (${risk.packageName})\n" + risk.reasons.joinToString("\n") { "• $it" } +
                    (if (lockdownEngaged) "\n\nСеть устройства заблокирована Carnelia. Удали приложение, потом сними блокировку в Black Wall."
                     else "\n\nОткрой Carnelia → Black Wall, чтобы проверить и удалить.")
            ))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(Notification.PRIORITY_MAX)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }
}
