package com.carnelia.vpn.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object NetworkUtils {

    suspend fun pingServer(host: String, port: Int): Long {
        return withContext(Dispatchers.IO) {
            try {
                val start = System.currentTimeMillis()
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 2000) // 2s timeout
                socket.close()
                val end = System.currentTimeMillis()
                return@withContext (end - start)
            } catch (e: Exception) {
                return@withContext -1L // Error/Timeout
            }
        }
    }

    /** Ключ текущей сети (Wi-Fi SSID / "мобильная") — используется для кэшей, привязанных к сети. */
    @Suppress("DEPRECATION")
    fun currentNetworkKey(context: Context): String {
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> {
                    val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
                    val ssid = wifi?.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
                    "wifi:${ssid ?: "?"}"
                }
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "mobile"
                else -> "other"
            }
        } catch (_: Exception) { "unknown" }
    }
}
