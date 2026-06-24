package com.carnelia.vpn.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.carnelia.vpn.utils.AppLogger
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

data class SniffFinding(val title: String, val detail: String)

/**
 * Эвристики против перехвата/прослушивания трафика без root:
 *  - новый доверенный корневой сертификат (классический MITM-вектор — Charles/mitmproxy/etc.)
 *  - смена MAC-адреса шлюза в той же сети (ARP-спуфинг — кто-то выдаёт себя за роутер)
 *  - системный HTTP-прокси, настроенный не нами (может перехватывать трафик в публичном Wi-Fi)
 */
object SniffingGuard {
    private const val PREFS = "sniffing_guard"
    private const val CHANNEL_ID = "sniffing_alerts"
    private const val NOTIFICATION_ID = 9003

    suspend fun checkAll(context: Context, onProgress: (String) -> Unit = {}): List<SniffFinding> {
        fun report(line: String) {
            onProgress(line)
            AppLogger.log("SniffingGuard: $line")
        }

        val findings = mutableListOf<SniffFinding>()

        report("Проверяю корневые сертификаты...")
        try {
            checkNewCaCertificates(context, ::report)?.let { findings.add(it) }
        } catch (e: Exception) {
            report("✗ Ошибка проверки сертификатов: ${e.message}")
            AppLogger.error("SniffingGuard: CA check failed", e)
        }

        report("Проверяю ARP-таблицу шлюза...")
        try {
            checkGatewaySpoofing(context, ::report)?.let { findings.add(it) }
        } catch (e: Exception) {
            report("✗ Ошибка проверки ARP: ${e.message}")
            AppLogger.error("SniffingGuard: ARP check failed", e)
        }

        report("Проверяю системный прокси...")
        try {
            checkSystemProxy(::report)?.let { findings.add(it) }
        } catch (e: Exception) {
            report("✗ Ошибка проверки прокси: ${e.message}")
            AppLogger.error("SniffingGuard: proxy check failed", e)
        }

        if (findings.isNotEmpty()) notify(context, findings)
        return findings
    }

    private fun checkNewCaCertificates(context: Context, report: (String) -> Unit): SniffFinding? {
        val current = caFingerprints()
        if (current.isEmpty()) {
            report("✗ Не удалось прочитать список доверенных CA (нет доступа к TrustManager)")
            return null
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val baseline = prefs.getStringSet("ca_fingerprints", null)
        if (baseline == null) {
            prefs.edit().putStringSet("ca_fingerprints", current).apply()
            report("✓ Базовая линия сохранена: ${current.size} доверенных CA")
            return null
        }
        val added = current - baseline
        prefs.edit().putStringSet("ca_fingerprints", current).apply()
        if (added.isEmpty()) {
            report("✓ ${current.size} доверенных CA, новых не появилось")
            return null
        }
        report("✗ Появилось ${added.size} новых CA из ${current.size}")
        return SniffFinding(
            "Новый корневой сертификат в системе",
            "Появилось ${added.size} новых доверенных CA-сертификатов — классический признак MITM-перехвата HTTPS (например, прокси для анализа трафика). Если это не твои действия — проверь Настройки → Безопасность → Учётные данные."
        )
    }

    private fun caFingerprints(): Set<String> {
        return try {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull() ?: return emptySet()
            val digest = MessageDigest.getInstance("SHA-256")
            tm.acceptedIssuers.map { cert ->
                digest.digest(cert.encoded).joinToString("") { "%02x".format(it) }
            }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    @Suppress("DEPRECATION")
    private fun checkGatewaySpoofing(context: Context, report: (String) -> Unit): SniffFinding? {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val gatewayInt = wifi?.dhcpInfo?.gateway
        if (wifi == null || gatewayInt == null || gatewayInt == 0) {
            report("✗ Не на Wi-Fi или нет данных DHCP — пропускаю проверку ARP")
            return null
        }
        val gatewayIp = "${gatewayInt and 0xff}.${gatewayInt shr 8 and 0xff}.${gatewayInt shr 16 and 0xff}.${gatewayInt shr 24 and 0xff}"
        val mac = readArpMac(gatewayIp)
        if (mac == null) {
            report("✗ Шлюз $gatewayIp не найден в /proc/net/arp (нет доступа или ARP-кэш пуст)")
            return null
        }

        val networkKey = CalibrationManager.currentNetworkKey(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "gateway_mac_$networkKey"
        val knownMac = prefs.getString(key, null)
        if (knownMac == null) {
            prefs.edit().putString(key, mac).apply()
            report("✓ Запомнил MAC шлюза $gatewayIp: $mac")
            return null
        }
        if (knownMac == mac) {
            report("✓ Шлюз $gatewayIp: MAC совпадает с известным ($mac)")
            return null
        }
        prefs.edit().putString(key, mac).apply()
        report("✗ MAC шлюза $gatewayIp изменился: $knownMac → $mac")
        return SniffFinding(
            "MAC-адрес роутера изменился",
            "Шлюз $gatewayIp раньше отвечал с другого MAC-адреса ($knownMac → $mac) — похоже на ARP-спуфинг: кто-то в сети выдаёт себя за роутер, чтобы перехватывать трафик. Если ты не меняла роутер — это тревожный признак."
        )
    }

    private fun readArpMac(ip: String): String? = try {
        File("/proc/net/arp").readLines().drop(1)
            .firstOrNull { it.trim().startsWith("$ip ") }
            ?.trim()?.split(Regex("\\s+"))?.getOrNull(3)
            ?.takeIf { it != "00:00:00:00:00:00" }
    } catch (_: Exception) { null }

    private fun checkSystemProxy(report: (String) -> Unit): SniffFinding? {
        val host = System.getProperty("http.proxyHost")
        if (host.isNullOrBlank() || host == "127.0.0.1" || host == "localhost") {
            report("✓ Системный HTTP-прокси не настроен")
            return null
        }
        val port = System.getProperty("http.proxyPort") ?: "?"
        report("✗ Найден системный прокси $host:$port")
        return SniffFinding(
            "Настроен системный HTTP-прокси",
            "Трафик устройства идёт через $host:$port. Если ты не настраивал это сам — в публичных Wi-Fi так нередко перехватывают трафик."
        )
    }

    private fun notify(context: Context, findings: List<SniffFinding>) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Защита от перехвата трафика", NotificationManager.IMPORTANCE_HIGH))
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Carnelia: возможен перехват трафика")
            .setContentText(findings.first().title + if (findings.size > 1) " и ещё ${findings.size - 1}" else "")
            .setStyle(Notification.BigTextStyle().bigText(findings.joinToString("\n\n") { "${it.title}\n${it.detail}" }))
            .setAutoCancel(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }
}
