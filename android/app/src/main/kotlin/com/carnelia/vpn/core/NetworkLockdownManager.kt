package com.carnelia.vpn.core

import android.content.Context
import android.content.Intent
import android.os.Build
import com.carnelia.vpn.service.CarheliaVpnService

/**
 * Включает/выключает режим защиты (полная блокировка сети устройства) через
 * CarheliaVpnService — TUN забирает весь трафик и никуда его не выпускает.
 */
object NetworkLockdownManager {

    fun engage(context: Context, reason: String) {
        val intent = Intent(context, CarheliaVpnService::class.java).apply {
            action = CarheliaVpnService.ACTION_LOCKDOWN
            putExtra(CarheliaVpnService.EXTRA_LOCKDOWN_REASON, reason)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun disengage(context: Context) {
        val intent = Intent(context, CarheliaVpnService::class.java).apply {
            action = CarheliaVpnService.ACTION_DISCONNECT
        }
        context.startService(intent)
    }

    fun isEngaged(): Boolean = CarheliaVpnService.currentState == ConnectionState.LOCKDOWN
}
