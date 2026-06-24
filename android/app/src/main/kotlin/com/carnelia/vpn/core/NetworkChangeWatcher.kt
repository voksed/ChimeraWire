package com.carnelia.vpn.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.carnelia.vpn.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Если сеть сменилась (домашний Wi-Fi → мобильная, другой Wi-Fi и т.п.) пока VPN
 * подключён — сама тихо перекалибровывается под новую сеть, без участия пользователя.
 */
object NetworkChangeWatcher {
    private const val CHANNEL_ID = "calibration_auto"
    private const val NOTIFICATION_ID = 9002
    private var registered = false
    private var lastNetworkKey: String? = null
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    fun register(context: Context) {
        if (registered) return
        registered = true
        val appContext = context.applicationContext
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                onNetworkPotentiallyChanged(appContext)
            }
        })
        AppLogger.log("NetworkChangeWatcher: наблюдение запущено")
    }

    private fun onNetworkPotentiallyChanged(context: Context) {
        val key = CalibrationManager.currentNetworkKey(context)
        if (key == lastNetworkKey) return
        val previous = lastNetworkKey
        lastNetworkKey = key
        if (previous == null) return // первый запуск — не считаем сменой сети

        if (VpnGlobalState.connectionState.value != ConnectionState.CONNECTED) return

        AppLogger.log("NetworkChangeWatcher: сеть изменилась ($previous → $key) — тихая перекалибровка")
        scope.launch {
            val result = CalibrationManager.calibrate(context) { }
            if (result.success) {
                notify(context, "Carnelia подстроилась под сеть: ${result.serverName} · ${result.profileLabel}")
            } else {
                notify(context, "Сеть изменилась, но рабочую комбинацию подобрать не удалось — открой приложение")
            }
        }
    }

    private fun notify(context: Context, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Авто-калибровка", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Carnelia")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }
}
