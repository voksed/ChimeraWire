package com.carnelia.vpn.core

import android.content.Context
import android.content.Intent
import com.carnelia.vpn.data.ServerRepository
import com.carnelia.vpn.service.CarheliaVpnService
import com.carnelia.vpn.utils.AppLogger
import java.io.File

/**
 * Экстренная очистка: рвёт VPN, чистит логи и историю. Стирание сохранённых серверов —
 * отдельный флаг, по умолчанию выключен (это единственное по-настоящему безвозвратное
 * действие здесь, остальное — просто следы активности, а не сама конфигурация доступа).
 */
object PanicManager {

    fun trigger(context: Context, wipeServers: Boolean = false) {
        AppLogger.log("PanicManager: запущена экстренная очистка (wipeServers=$wipeServers)")

        // 1. Рвём VPN немедленно
        try {
            val intent = Intent(context, CarheliaVpnService::class.java).apply {
                action = CarheliaVpnService.ACTION_DISCONNECT
            }
            context.startService(intent)
        } catch (_: Exception) {
        }

        // 2. Чистим логи и следы активности
        AppLogger.clear()
        try {
            File(context.filesDir, "xray_access.log").delete()
        } catch (_: Exception) {
        }

        // 3. Опционально — стереть сами сохранённые серверы (необратимо)
        if (wipeServers) {
            try {
                val repo = ServerRepository(context)
                repo.getServers().forEach { repo.removeServer(it.id) }
            } catch (e: Exception) {
                AppLogger.error("PanicManager: не удалось стереть серверы", e)
            }
        }

        AppLogger.log("PanicManager: готово")
    }
}
