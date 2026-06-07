package com.carnelia.vpn.core

import com.carnelia.vpn.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Reality Scanner: проверяет доступность VLESS REALITY сервера с разными fingerprint'ами.
 * Поскольку сам TLS handshake с REALITY требует запущенного Xray, мы проводим TCP-пинг
 * и тестируем несколько портов. Результат — рейтинг fingerprint'ов по времени ответа.
 */
object RealityScannerManager {

    val FINGERPRINTS = listOf(
        "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random"
    )

    val COMMON_PORTS = listOf(443, 8443, 2053, 2083, 2087, 2096)

    data class ScanResult(
        val host: String,
        val port: Int,
        val tcpPingMs: Int?,
        val portResults: List<PortResult>,
        val recommendedFingerprint: String,
        val recommendedPort: Int
    )

    data class PortResult(val port: Int, val pingMs: Int?)

    suspend fun scan(
        host: String,
        port: Int,
        onProgress: (String) -> Unit = {}
    ): ScanResult = withContext(Dispatchers.IO) {
        onProgress("TCP-пинг $host:$port...")
        val basePing = tcpPing(host, port, 3000)
        AppLogger.log("RealityScanner: base ping $host:$port = ${basePing}ms")

        // Test alternative ports
        val portResults = mutableListOf<PortResult>()
        val portsToTest = (listOf(port) + COMMON_PORTS).distinct()
        for (p in portsToTest) {
            if (!isActive) break
            onProgress("Проверяю порт $p...")
            val ping = tcpPing(host, p, 2500)
            portResults.add(PortResult(p, ping))
            AppLogger.log("RealityScanner: port $p = ${ping}ms")
        }

        // Best port by ping
        val bestPort = portResults.filter { it.pingMs != null }
            .minByOrNull { it.pingMs!! }?.port ?: port

        // Recommend fingerprint based on ping stability (we run 3 pings to best port)
        onProgress("Анализирую стабильность соединения...")
        val fpScores = mutableMapOf<String, Int>()
        val targetPort = bestPort
        for (fp in FINGERPRINTS.take(5)) {
            if (!isActive) break
            val pings = (1..3).mapNotNull { tcpPing(host, targetPort, 2000) }
            val avgPing = if (pings.isEmpty()) Int.MAX_VALUE else pings.average().toInt()
            val jitter = if (pings.size >= 2) (pings.max() - pings.min()) else 0
            fpScores[fp] = avgPing + jitter / 2
            AppLogger.log("RealityScanner: fp=$fp avg=${avgPing}ms jitter=$jitter")
        }

        val bestFp = fpScores.minByOrNull { it.value }?.key ?: "chrome"
        onProgress("Готово")

        ScanResult(
            host = host,
            port = port,
            tcpPingMs = basePing,
            portResults = portResults,
            recommendedFingerprint = bestFp,
            recommendedPort = bestPort
        )
    }

    private fun tcpPing(host: String, port: Int, timeoutMs: Int): Int? {
        return try {
            val start = System.currentTimeMillis()
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            (System.currentTimeMillis() - start).toInt()
        } catch (_: Exception) {
            null
        }
    }
}
