package com.carnelia.vpn.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.carnelia.vpn.service.CarheliaVpnService
import com.carnelia.vpn.utils.AppLogger
import com.carnelia.vpn.utils.NetworkUtils

/**
 * Если сеть сменилась (домашний Wi-Fi → мобильная, другой Wi-Fi и т.п.) пока VPN
 * подключён — соединение принудительно рвётся (fail closed), вместо того чтобы
 * тихо продолжать работать на новой сети без ведома пользователя.
 */
object NetworkChangeWatcher {
    private const val CHANNEL_ID = "network_change"
    private const val NOTIFICATION_ID = 9002
    private var registered = false
    private var lastNetworkKey: String? = null

    fun register(context: Context) {
        if (registered) return
        registered = true
        val appContext = context.applicationContext
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return

        // registerDefaultNetworkCallback (а не generic-запрос по capability!) — только так
        // приходит onAvailable именно при смене СИСТЕМНОЙ дефолтной сети (Wi-Fi -> мобильная
        // и обратно). Generic-запрос по NET_CAPABILITY_INTERNET не всплывает в таких случаях,
        // если сеть, ставшая новой дефолтной, уже была "видна" системе и её capabilities не менялись.
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onNetworkPotentiallyChanged(appContext)
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                onNetworkPotentiallyChanged(appContext)
            }
        })
        AppLogger.log("NetworkChangeWatcher: наблюдение запущено")
    }

    private fun onNetworkPotentiallyChanged(context: Context) {
        val key = NetworkUtils.currentNetworkKey(context)
        if (key == lastNetworkKey) return
        val previous = lastNetworkKey
        lastNetworkKey = key
        if (previous == null) return // первый запуск — не считаем сменой сети

        if (VpnGlobalState.connectionState.value != ConnectionState.CONNECTED) return

        AppLogger.log("NetworkChangeWatcher: сеть изменилась ($previous → $key) — рву соединение")
        val intent = Intent(context, CarheliaVpnService::class.java).apply {
            action = CarheliaVpnService.ACTION_DISCONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        notify(context, "Сеть изменилась — VPN отключён")
    }

    private fun notify(context: Context, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Смена сети", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Carnelia")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }
}
