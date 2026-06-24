package com.carnelia.vpn.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.carnelia.vpn.core.NetworkLockdownManager
import com.carnelia.vpn.core.SecurityAlertNotifier
import com.carnelia.vpn.core.SecurityScanner
import com.carnelia.vpn.utils.AppLogger

/**
 * Проверяет каждое новое/обновлённое приложение эвристикой SecurityScanner сразу после
 * установки — это самый реалистичный момент поймать шпионское ПО без root и без
 * непрерывного фонового мониторинга, который Android всё равно не позволяет делать глубоко.
 */
class PackageInstallReceiver : BroadcastReceiver() {
    // Анти-шпион временно выключен целиком (нет видимого в UI способа это объяснить/отключить) —
    // приёмник зарегистрирован, но ничего не делает, пока флаг не вернут в true.
    private val antiSpyEnabled = false

    override fun onReceive(context: Context, intent: Intent) {
        if (!antiSpyEnabled) return
        if (intent.action != Intent.ACTION_PACKAGE_ADDED && intent.action != Intent.ACTION_PACKAGE_REPLACED) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        if (pkg == context.packageName) return

        try {
            val risk = SecurityScanner.scanOne(context, pkg)
            if (risk != null && risk.score >= 30) {
                AppLogger.log("PackageInstallReceiver: risky app installed — $pkg (score=${risk.score})")
                SecurityAlertNotifier.notify(context, risk)
                if (risk.score >= 60) {
                    AppLogger.log("PackageInstallReceiver: ВЫСОКИЙ РИСК — активирую режим защиты (lockdown)")
                    NetworkLockdownManager.engage(context, "после установки ${risk.appName}")
                }
            }
        } catch (e: Exception) {
            AppLogger.error("PackageInstallReceiver: scan failed for $pkg", e)
        }
    }
}
