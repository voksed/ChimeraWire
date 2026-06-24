package com.carnelia.vpn.core

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import com.carnelia.vpn.data.ServerRepository
import com.carnelia.vpn.service.CarheliaVpnService
import com.carnelia.vpn.utils.AppLogger
import com.carnelia.vpn.utils.NetworkUtils
import com.carnelia.vpn.utils.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

data class CalibrationProfile(
    val label: String,
    val blackWall: BlackWallEngine.StealthLevel,
    val fragmentationMode: String?, // null = выключена
    val muxEnabled: Boolean
)

data class CalibrationResult(
    val success: Boolean,
    val serverName: String? = null,
    val profileLabel: String? = null,
    val tcpPingMs: Long? = null,
    val latencyMs: Long? = null,
    val mbps: Double? = null,
    val log: List<String> = emptyList()
)

/**
 * "Калибровка" — пользователь жмёт одну кнопку, клиент сам:
 *  1. Если для текущей сети (Wi-Fi SSID / "мобильная") уже есть запомненная рабочая
 *     комбинация — сначала быстро проверяет ТОЛЬКО её. Если она и сейчас работает,
 *     калибровка занимает секунды, а не минуты.
 *  2. Иначе пингует все сохранённые серверы, ранжирует по задержке.
 *  3. Для протоколов с TLS (Xray/sing-box outbound) перебирает профили Black Wall +
 *     фрагментации, для каждой РЕАЛЬНО подключается и проверяет живой трафик через
 *     локальный SOCKS-прокси (HTTP-запрос), а не просто факт ConnectionState.CONNECTED —
 *     DPI часто пропускает handshake, но режет данные дальше, поэтому "интерфейс поднялся"
 *     само по себе ничего не доказывает.
 *  4. Для WireGuard/AmneziaWG/WARP/OpenVPN профили не варьируются — там обфускация либо
 *     завязана на серверный конфиг (Jc/Jmin и т.п.), либо это другой движок без proxy-порта,
 *     проверяем по факту прироста байт трафика.
 *  5. Победителя проверяет одним финальным замером реальной скорости и запоминает его
 *     для текущей сети — следующая калибровка здесь будет быстрой.
 */
object CalibrationManager {

    private val TLS_PROFILES = listOf(
        CalibrationProfile("Прямое соединение", BlackWallEngine.StealthLevel.OFF, null, true),
        CalibrationProfile("Ghost + лёгкая фрагментация", BlackWallEngine.StealthLevel.GHOST, "light", true),
        CalibrationProfile("Phantom + средняя фрагментация", BlackWallEngine.StealthLevel.PHANTOM, "balanced", false),
        CalibrationProfile("Wraith + агрессивная фрагментация", BlackWallEngine.StealthLevel.WRAITH, "aggressive", false)
    )

    private val NO_PROFILE_VARIANTS = setOf(
        VpnProtocol.WIREGUARD, VpnProtocol.AMNEZIA_WG, VpnProtocol.WARP, VpnProtocol.OPENVPN
    )
    private val SINGBOX_ROUTED = setOf(
        VpnProtocol.HYSTERIA2, VpnProtocol.TUIC, VpnProtocol.WARP, VpnProtocol.WIREGUARD, VpnProtocol.OUTLINE
    )
    private val NO_PROXY_PROBE = setOf(VpnProtocol.AMNEZIA_WG, VpnProtocol.OPENVPN)

    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val PROBE_TIMEOUT_MS = 5_000L
    private const val MAX_SERVERS_TO_TRY = 3
    private const val MEMORY_PREFS = "calibration_memory"
    private const val PROBE_URL = "https://www.gstatic.com/generate_204"
    private const val THROUGHPUT_URL = "https://speed.cloudflare.com/__down?bytes=3000000"

    val DNS_CANDIDATES = listOf(
        "1.1.1.1" to "Cloudflare",
        "8.8.8.8" to "Google",
        "9.9.9.9" to "Quad9",
        "94.140.14.14" to "AdGuard"
    )

    suspend fun calibrate(context: Context, onProgress: (String) -> Unit): CalibrationResult {
        val log = mutableListOf<String>()
        fun report(line: String) {
            log.add(line)
            onProgress(line)
            AppLogger.log("Calibration: $line")
        }

        return try {
            calibrateInternal(context, log, ::report)
        } catch (e: Exception) {
            // Защитная сетка: что угодно непредвиденное не должно вешать диалог навечно.
            AppLogger.error("Calibration: непредвиденная ошибка", e)
            CalibrationResult(false, log = log + "Ошибка калибровки: ${e.message}")
        }
    }

    private suspend fun calibrateInternal(
        context: Context,
        log: MutableList<String>,
        report: (String) -> Unit
    ): CalibrationResult {
        val servers = ServerRepository(context).getServers()
        if (servers.isEmpty()) {
            return CalibrationResult(false, log = log + "Нет сохранённых серверов")
        }

        val networkKey = currentNetworkKey(context)
        report("Сеть: $networkKey")

        val bestDns = raceDns(report)
        if (bestDns != null) {
            PrefsManager.setDnsServer(context, bestDns)
        }

        // Быстрый путь: для этой сети уже есть проверенная рабочая комбинация.
        recallWinner(context, networkKey)?.let { (serverId, profileLabel) ->
            val server = servers.find { it.id == serverId }
            val profile = TLS_PROFILES.find { it.label == profileLabel }
                ?: CalibrationProfile(profileLabel, BlackWallEngine.getLevel(context), null, PrefsManager.isMuxEnabled(context))
            if (server != null) {
                report("Уже знаю рабочую комбинацию для этой сети: ${server.name} · ${profile.label} — проверяю")
                val outcome = tryCombo(context, server, profile, report = { report(it) })
                if (outcome != null) {
                    report("✓ Подтверждено за секунды — память не подвела")
                    val tcpPing = finalize(context, server, profile, outcome, networkKey, report = { report(it) })
                    return CalibrationResult(true, server.name, profile.label, tcpPing, outcome.first, outcome.second, log)
                }
                report("✗ Запомненная комбинация больше не работает — делаю полный перебор")
            }
        }

        report("Проверяю пинг ${servers.size} серверов...")
        val ranked = servers.map { server -> server to NetworkUtils.pingServer(server.host, server.port) }
            .filter { it.second > 0 }
            .sortedBy { it.second }

        if (ranked.isEmpty()) {
            saveFailure(context, networkKey)
            return CalibrationResult(false, log = log + "Ни один сервер не отвечает на TCP-пинг — сеть недоступна или все хосты заблокированы")
        }
        report("Отвечают: " + ranked.take(5).joinToString(", ") { "${it.first.name} (${it.second}мс)" })

        var best: Triple<VpnServerConfig, CalibrationProfile, Pair<Long, Double?>>? = null

        for ((server, _) in ranked.take(MAX_SERVERS_TO_TRY)) {
            val profiles = if (server.protocol in NO_PROFILE_VARIANTS) {
                listOf(CalibrationProfile("По умолчанию", BlackWallEngine.getLevel(context), null, PrefsManager.isMuxEnabled(context)))
            } else {
                TLS_PROFILES
            }

            for (profile in profiles) {
                report("Пробую: ${server.name} · ${profile.label} [BlackWall=${profile.blackWall} frag=${profile.fragmentationMode ?: "off"} mux=${profile.muxEnabled} dns=${bestDns ?: PrefsManager.getDnsServer(context)}]")
                val outcome = tryCombo(context, server, profile, report = { report(it) })
                if (outcome != null) {
                    report("✓ Реальный трафик подтверждён · ${outcome.first}мс" + (outcome.second?.let { " · %.1f Мбит/с".format(it) } ?: ""))
                    if (best == null || outcome.first < best!!.third.first) {
                        best = Triple(server, profile, outcome)
                    }
                } else {
                    report("✗ Подключилось, но реальный трафик не прошёл (или не подключилось вовсе)")
                }
                if (server.protocol in NO_PROFILE_VARIANTS) break
            }
            if (best != null) break
        }

        if (best == null) {
            saveFailure(context, networkKey)
            return CalibrationResult(false, log = log + "Ни одна комбинация не дала реального трафика — похоже на жёсткую блокировку сети")
        }

        val (server, profile, outcome) = best!!
        val tcpPing = finalize(context, server, profile, outcome, networkKey, report = { report(it) })
        return CalibrationResult(true, server.name, profile.label, tcpPing, outcome.first, outcome.second, log)
    }

    /** Применяет профиль, подключается, ждёт реального трафика. null = не сработало. */
    private suspend fun tryCombo(
        context: Context,
        server: VpnServerConfig,
        profile: CalibrationProfile,
        report: (String) -> Unit
    ): Pair<Long, Double?>? {
        applyProfile(context, profile)
        ensureDisconnected(context)

        sendAction(context, CarheliaVpnService.ACTION_CONNECT, server)
        val connected = waitFor(ConnectionState.CONNECTED, ConnectionState.ERROR, CONNECT_TIMEOUT_MS) == ConnectionState.CONNECTED

        val result = if (!connected) {
            null
        } else {
            val probeLatency = verifyRealTraffic(context, server)
            probeLatency?.let { it to null }
        }

        ensureDisconnected(context)
        delay(400)
        return result
    }

    private suspend fun finalize(
        context: Context,
        server: VpnServerConfig,
        profile: CalibrationProfile,
        outcome: Pair<Long, Double?>,
        networkKey: String,
        report: (String) -> Unit
    ): Long? {
        report("Применяю: ${server.name} · ${profile.label}")
        applyProfile(context, profile)
        ServerRepository(context).setLastUsedServer(server)
        sendAction(context, CarheliaVpnService.ACTION_CONNECT, server)
        report("Подключаюсь с финальными настройками...")
        waitFor(ConnectionState.CONNECTED, ConnectionState.ERROR, CONNECT_TIMEOUT_MS)

        val tcpPing = NetworkUtils.pingServer(server.host, server.port).takeIf { it > 0 }
        if (tcpPing != null) report("Honest TCP-пинг: ${tcpPing}мс")

        var mbps: Double? = null
        if (server.protocol !in NO_PROXY_PROBE) {
            report("Замеряю реальную скорость...")
            mbps = measureThroughput(server.protocol)
            report(if (mbps != null) "Реальная скорость: %.1f Мбит/с".format(mbps) else "Скорость замерить не удалось (сеть слишком медленная/нестабильная)")
        }

        rememberWinner(context, networkKey, server.id, profile.label)
        CalibrationHistoryStore.add(
            context,
            CalibrationHistoryEntry(
                timestamp = System.currentTimeMillis(),
                networkKey = networkKey,
                success = true,
                serverName = server.name,
                profileLabel = profile.label,
                tcpPingMs = tcpPing,
                latencyMs = outcome.first,
                mbps = mbps
            )
        )
        report("Готово")
        return tcpPing
    }

    /**
     * Реальная проверка живого трафика, а не просто факт CONNECTED.
     * Возвращает честную задержку пробного запроса в мс, либо null если трафик не прошёл.
     */
    private suspend fun verifyRealTraffic(context: Context, server: VpnServerConfig): Long? {
        if (server.protocol in NO_PROXY_PROBE) {
            // Нет локального proxy-порта (AmneziaWG — нативный туннель, OpenVPN — отдельный движок).
            // Сигнал успеха — реальный прирост байт трафика, а не просто "хендшейк прошёл".
            // Задержку тут отдельно не измерить — приближаем её обычным TCP-пингом до хоста.
            val before = VpnGlobalState.stats.value
            delay(2500)
            val after = VpnGlobalState.stats.value
            val delta = (after.bytesReceived - before.bytesReceived) + (after.bytesSent - before.bytesSent)
            if (delta <= 1500) return null
            return NetworkUtils.pingServer(server.host, server.port).takeIf { it > 0 } ?: 9999L
        }

        // sing-box поднимает настоящий SOCKS5-инбаунд (SOCKS5_PORT) — обычный клиент работает.
        // У Xray на LOCAL_PORT внутренний Shadowsocks-мост для tun2socks (требует SS-шифрование),
        // обычным SOCKS5/HTTP-клиентом туда нельзя — для диагностики у него отдельный HTTP-порт.
        val proxy = if (server.protocol in SINGBOX_ROUTED) {
            Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", SingboxCoreManager.SOCKS5_PORT))
        } else {
            Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", XrayCoreManager.LOCAL_HTTP_PORT))
        }
        return withContext(Dispatchers.IO) {
            try {
                val start = System.currentTimeMillis()
                val conn = URL(PROBE_URL).openConnection(proxy) as HttpURLConnection
                conn.connectTimeout = PROBE_TIMEOUT_MS.toInt()
                conn.readTimeout = PROBE_TIMEOUT_MS.toInt()
                val code = conn.responseCode
                val latency = System.currentTimeMillis() - start
                conn.disconnect()
                if (code in 200..299 || code == 204) latency else null
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun measureThroughput(protocol: VpnProtocol): Double? {
        val proxy = if (protocol in SINGBOX_ROUTED) {
            Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", SingboxCoreManager.SOCKS5_PORT))
        } else {
            Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", XrayCoreManager.LOCAL_HTTP_PORT))
        }
        return withContext(Dispatchers.IO) {
            try {
                val conn = URL(THROUGHPUT_URL).openConnection(proxy) as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 10000
                val start = System.currentTimeMillis()
                var total = 0L
                conn.inputStream.use { input ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                    }
                }
                conn.disconnect()
                val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1)
                (total * 8.0) / elapsed / 1000.0
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Гоняет несколько публичных DNS напрямую (без VPN) и возвращает самый быстрый отвечающий. */
    private suspend fun raceDns(report: (String) -> Unit): String? {
        report("Проверяю DNS-серверы напрямую...")
        val results = DNS_CANDIDATES.map { (ip, name) ->
            val time = withContext(Dispatchers.IO) { dnsQueryTimeMs(ip) }
            report(if (time != null) "  $name ($ip): ${time}мс" else "  $name ($ip): не отвечает")
            ip to time
        }
        val best = results.filter { it.second != null }.minByOrNull { it.second!! }
        if (best != null) report("✓ Быстрейший DNS: ${best.first} (${best.second}мс)")
        return best?.first
    }

    /** Сырой DNS-запрос (A-запись example.com) по UDP — без VPN, без какого-либо туннеля. */
    private fun dnsQueryTimeMs(server: String): Long? {
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 2000
                val query = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00) +
                    encodeDnsName("example.com") + byteArrayOf(0x00, 0x01, 0x00, 0x01)
                val start = System.currentTimeMillis()
                socket.send(DatagramPacket(query, query.size, InetAddress.getByName(server), 53))
                val responseBuf = ByteArray(512)
                socket.receive(DatagramPacket(responseBuf, responseBuf.size))
                System.currentTimeMillis() - start
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeDnsName(name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        name.split(".").forEach { label ->
            out.write(label.length)
            out.write(label.toByteArray())
        }
        out.write(0)
        return out.toByteArray()
    }

    private fun applyProfile(context: Context, profile: CalibrationProfile) {
        BlackWallEngine.setEnabled(context, profile.blackWall != BlackWallEngine.StealthLevel.OFF)
        if (profile.blackWall != BlackWallEngine.StealthLevel.OFF) {
            BlackWallEngine.setLevel(context, profile.blackWall)
        }
        PrefsManager.setFragmentationEnabled(context, profile.fragmentationMode != null)
        if (profile.fragmentationMode != null) {
            PrefsManager.setFragmentationMode(context, profile.fragmentationMode)
        }
        PrefsManager.setMuxEnabled(context, profile.muxEnabled)
    }

    private fun sendAction(context: Context, action: String, server: VpnServerConfig?) {
        val intent = Intent(context, CarheliaVpnService::class.java).apply {
            this.action = action
            if (server != null) putExtra(CarheliaVpnService.EXTRA_CONFIG, server)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
    }

    private suspend fun ensureDisconnected(context: Context) {
        if (VpnGlobalState.connectionState.value == ConnectionState.DISCONNECTED) return
        sendAction(context, CarheliaVpnService.ACTION_DISCONNECT, null)
        waitFor(ConnectionState.DISCONNECTED, ConnectionState.DISCONNECTED, 6000)
    }

    private suspend fun waitFor(success: ConnectionState, failure: ConnectionState, timeoutMs: Long): ConnectionState? {
        return withTimeoutOrNull(timeoutMs) {
            VpnGlobalState.connectionState.first { it == success || it == failure }
        }
    }

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

    private fun saveFailure(context: Context, networkKey: String) {
        CalibrationHistoryStore.add(
            context,
            CalibrationHistoryEntry(System.currentTimeMillis(), networkKey, false, null, null, null, null, null)
        )
    }

    /**
     * Лёгкая проверка живого трафика на УЖЕ подключённом сервере, без переподключения —
     * используется для самоисцеления во время сессии (CarheliaVpnService).
     */
    suspend fun quickHealthCheck(context: Context, server: VpnServerConfig): Boolean {
        return verifyRealTraffic(context, server) != null
    }

    private fun rememberWinner(context: Context, networkKey: String, serverId: String, profileLabel: String) {
        context.getSharedPreferences(MEMORY_PREFS, Context.MODE_PRIVATE).edit()
            .putString("winner_$networkKey", "$serverId|$profileLabel")
            .apply()
    }

    private fun recallWinner(context: Context, networkKey: String): Pair<String, String>? {
        val raw = context.getSharedPreferences(MEMORY_PREFS, Context.MODE_PRIVATE)
            .getString("winner_$networkKey", null) ?: return null
        val parts = raw.split("|", limit = 2)
        return if (parts.size == 2) parts[0] to parts[1] else null
    }
}
