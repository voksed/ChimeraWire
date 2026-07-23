package com.carnelia.vpn.core

import android.content.Context
import com.carnelia.vpn.utils.AppLogger
import com.carnelia.vpn.utils.PrefsManager
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object SingboxCoreManager {

    private var process: Process? = null
    private var streamJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // sing-box exposes SOCKS5 on this port for Xray to forward into
    const val SOCKS5_PORT = 10812

    /** uTLS ClientHello-профиль по умолчанию (chrome/firefox/...), настраивается в Settings. */
    private fun defaultFingerprint(): String =
        PrefsManager.getTlsFingerprint(com.carnelia.vpn.CarheliaApplication.instance)

    suspend fun startCore(context: Context, config: VpnServerConfig) = withContext(Dispatchers.IO) {
        appContext = context
        stopCore()

        val binary = File(context.applicationInfo.nativeLibraryDir, "libsingbox.so")
        if (!binary.exists()) throw Exception("Sing-box binary not found")

        val configJson = buildConfig(context, config)
        val configFile = File(context.filesDir, "singbox_config.json")
        configFile.writeText(configJson)

        val pb = ProcessBuilder(binary.absolutePath, "run", "-c", configFile.absolutePath)
        pb.directory(context.filesDir)
        pb.redirectErrorStream(true)
        process = pb.start()

        // Capture output lines so we can report them on immediate crash
        val outputLines = mutableListOf<String>()
        streamJob?.cancel()
        streamJob = scope.launch {
            try {
                process!!.inputStream.bufferedReader().use { r ->
                    for (line in r.lineSequence()) {
                        if (!isActive) break
                        AppLogger.log("Sing-box: $line")
                        synchronized(outputLines) { if (outputLines.size < 20) outputLines.add(line) }
                    }
                }
            } catch (_: Exception) {}
        }

        delay(600)
        if (process?.isAlive == false) {
            val exitCode = process?.exitValue() ?: -1
            val lastLines = synchronized(outputLines) { outputLines.takeLast(5).joinToString(" | ") }
            throw Exception("Sing-box died immediately (exit $exitCode): $lastLines")
        }
        AppLogger.log("SingboxCoreManager: Started SOCKS5 on port $SOCKS5_PORT")
    }

    fun stopCore() {
        streamJob?.cancel(); streamJob = null
        process?.destroy(); process = null
    }

    private fun buildConfig(context: Context, vpnConfig: VpnServerConfig): String {
        val root = JSONObject()

        // Log
        root.put("log", JSONObject().apply {
            put("level", "warning")
            put("timestamp", true)
        })

        // DNS
        root.put("dns", buildDns(context))

        // Inbounds — SOCKS5 for Xray bridge
        root.put("inbounds", JSONArray().put(JSONObject().apply {
            put("type", "socks")
            put("tag", "socks-in")
            put("listen", "127.0.0.1")
            put("listen_port", SOCKS5_PORT)
        }))

        // sing-box 1.12+ убрал wireguard из outbounds — теперь это endpoint.
        // WireGuard/AmneziaWG/WARP кладём в "endpoints" (тег "proxy"), остальное — в "outbounds".
        if (isWireguardFamily(vpnConfig.protocol)) {
            root.put("endpoints", JSONArray().put(
                buildWireGuardEndpoint(vpnConfig).apply { put("tag", "proxy") }
            ))
            root.put("outbounds", buildBaseOutbounds())
        } else {
            root.put("outbounds", buildOutbounds(context, vpnConfig))
        }

        // Route
        root.put("route", buildRoute(context))

        return root.toString(2)
    }

    private fun isWireguardFamily(p: VpnProtocol): Boolean =
        p == VpnProtocol.WIREGUARD || p == VpnProtocol.AMNEZIA_WG || p == VpnProtocol.WARP

    // direct + block — общие для обоих случаев
    private fun buildBaseOutbounds(): JSONArray = JSONArray().apply {
        put(JSONObject().apply { put("type", "direct"); put("tag", "direct") })
        put(JSONObject().apply { put("type", "block"); put("tag", "block") })
    }

    // Тег первого DNS-сервера — на него ссылается route.default_domain_resolver
    private fun primaryDnsTag(context: Context): String =
        if (PrefsManager.isNetShieldEnabled(context)) "adguard" else "cf"

    private fun buildDns(context: Context): JSONObject {
        val servers = JSONArray()
        // sing-box 1.12+ DNS server format: tag + type + server (поле идентификатора — "tag", НЕ "id").
        // detour="direct" НЕ ставим: sing-box 1.12+ падает с "detour to an empty direct outbound
        // makes no sense" — DNS и так резолвится напрямую по умолчанию.
        if (PrefsManager.isNetShieldEnabled(context)) {
            servers.put(JSONObject().apply {
                put("tag", "adguard"); put("type", "udp")
                put("server", "94.140.14.14")
            })
        } else {
            val userDns = PrefsManager.getDnsServer(context)
            servers.put(JSONObject().apply {
                put("tag", "cf"); put("type", "udp")
                put("server", "1.1.1.1")
            })
            if (userDns.isNotBlank()) servers.put(JSONObject().apply {
                put("tag", "user"); put("type", "udp")
                put("server", userDns)
            })
            servers.put(JSONObject().apply {
                put("tag", "google"); put("type", "udp")
                put("server", "8.8.8.8")
            })
        }
        return JSONObject().apply {
            put("servers", servers)
            put("strategy", "prefer_ipv4")
        }
    }

    private const val TAG_HOP_ENTRY = "hop_entry"

    /** Мульти-хоп: протоколы, которые sing-box может тоннелировать через detour. */
    private fun isChainableProtocol(p: VpnProtocol): Boolean = p in setOf(
        VpnProtocol.VLESS, VpnProtocol.VMESS, VpnProtocol.TROJAN,
        VpnProtocol.SHADOWSOCKS, VpnProtocol.OUTLINE, VpnProtocol.HYSTERIA2, VpnProtocol.TUIC
    )

    /**
     * Если включён мульти-хоп и выбран корректный "входной" сервер — добавляет для него
     * отдельный outbound (tag=hop_entry) и связывает основной outbound через "detour",
     * так что реальный коннект идёт local -> entry -> exit -> интернет.
     */
    private fun buildMultiHopEntryOutbound(context: Context, exitConfig: VpnServerConfig): JSONObject? {
        if (!PrefsManager.isMultiHopEnabled(context)) return null
        val entryId = PrefsManager.getMultiHopEntryServerId(context) ?: return null
        if (entryId == exitConfig.id) return null
        val entryConfig = try {
            com.carnelia.vpn.data.ServerRepository(context).getServers().find { it.id == entryId }
        } catch (_: Exception) { null } ?: return null
        if (!isChainableProtocol(entryConfig.protocol) || !isChainableProtocol(exitConfig.protocol)) {
            AppLogger.log("MultiHop: протокол entry/exit не поддерживает цепочку в sing-box — пропускаю")
            return null
        }
        AppLogger.log("MultiHop: entry=${entryConfig.name} (${entryConfig.host}) -> exit=${exitConfig.name} (${exitConfig.host})")
        return buildProxyOutboundInternal(entryConfig).apply { put("tag", TAG_HOP_ENTRY) }
    }

    /**
     * XTLS Vision (flow=xtls-rprx-vision) требует прямого владения TCP-соединением для своих
     * трюков с паддингом/сплайсингом — не переживает проксирование через detour. При мульти-хопе
     * снимаем flow с exit-outbound, иначе туннель "поднимается", но данные тихо не идут.
     */
    private fun stripVisionFlowForChaining(outbound: JSONObject) {
        val flow = outbound.optString("flow")
        if (flow.isNotBlank()) {
            AppLogger.log("MultiHop: снимаю flow=$flow с exit-outbound (несовместимо с цепочкой)")
            outbound.remove("flow")
        }
    }

    private fun buildOutbounds(context: Context, vpnConfig: VpnServerConfig): JSONArray {
        val arr = JSONArray()

        // Main proxy outbound
        val proxy = buildProxyOutbound(vpnConfig)

        // Мульти-хоп: если настроен, exit-outbound дозванивается ЧЕРЕЗ entry-outbound
        val entryOutbound = buildMultiHopEntryOutbound(context, vpnConfig)
        if (entryOutbound != null) {
            stripVisionFlowForChaining(proxy)
            proxy.put("detour", TAG_HOP_ENTRY)
            arr.put(entryOutbound)
        }
        arr.put(proxy)

        // Direct
        arr.put(JSONObject().apply { put("type", "direct"); put("tag", "direct") })

        // Block
        arr.put(JSONObject().apply { put("type", "block"); put("tag", "block") })

        return arr
    }

    private lateinit var appContext: Context

    private fun buildProxyOutbound(config: VpnServerConfig): JSONObject {
        val out = buildProxyOutboundInternal(config)
        if (::appContext.isInitialized && BlackWallEngine.isEnabled(appContext)) {
            BlackWallEngine.applyToSingboxOutbound(appContext, out, config)
        }
        return out
    }

    private fun buildProxyOutboundInternal(config: VpnServerConfig): JSONObject {
        return when (config.protocol) {
            VpnProtocol.HYSTERIA2 -> buildHysteria2(config)
            VpnProtocol.TUIC      -> buildTuic(config)
            // WIREGUARD/AMNEZIA_WG/WARP идут через "endpoints" (sing-box 1.12+), не сюда
            VpnProtocol.VLESS     -> buildVless(config)
            VpnProtocol.VMESS     -> buildVmess(config)
            VpnProtocol.TROJAN    -> buildTrojan(config)
            VpnProtocol.SHADOWSOCKS, VpnProtocol.OUTLINE -> buildShadowsocks(config)
            else -> throw Exception("Sing-box: unsupported protocol ${config.protocol}")
        }.apply { put("tag", "proxy") }
    }

    private fun buildHysteria2(config: VpnServerConfig): JSONObject = JSONObject().apply {
        put("type", "hysteria2")
        put("server", config.host)
        put("server_port", config.port)
        put("password", config.config["password"] ?: "")

        val obfsType = config.config["obfs"] ?: ""
        val obfsPass = config.config["obfs_password"] ?: ""
        if (obfsType == "salamander" && obfsPass.isNotBlank()) {
            put("obfs", JSONObject().apply {
                put("type", "salamander")
                put("password", obfsPass)
            })
        }

        val up = config.config["up_mbps"]?.toIntOrNull() ?: 0
        val down = config.config["down_mbps"]?.toIntOrNull() ?: 0
        if (up > 0 || down > 0) {
            put("up_mbps", if (up > 0) up else 100)
            put("down_mbps", if (down > 0) down else 100)
        }

        put("tls", buildTls(
            sni = config.config["sni"] ?: config.host,
            insecure = config.config["insecure"] == "1",
            fingerprint = config.config["fp"]
        ))
    }

    private fun buildTuic(config: VpnServerConfig): JSONObject = JSONObject().apply {
        put("type", "tuic")
        put("server", config.host)
        put("server_port", config.port)
        put("uuid", config.config["uuid"] ?: config.username ?: "")
        put("password", config.config["password"] ?: config.password ?: "")
        put("congestion_control", config.config["cc"] ?: "bbr")

        val udpRelay = config.config["udp_relay_mode"] ?: "native"
        put("udp_relay_mode", udpRelay)

        put("tls", buildTls(
            sni = config.config["sni"] ?: config.host,
            insecure = config.config["insecure"] == "1",
            fingerprint = config.config["fp"],
            alpn = config.config["alpn"]
        ))
    }

    /**
     * WireGuard / AmneziaWG / WARP в формате endpoint (sing-box 1.12+).
     * Внимание: sing-box не поддерживает обфускацию AmneziaWG (Jc/Jmin/Jmax/S1/S2/H1-H4/I1-I5) —
     * AMNEZIA_WG идёт как обычный WireGuard. К серверу с включённой обфускацией хендшейк не пройдёт;
     * для этого нужен amneziawg-go. Обычные WG-серверы работают.
     */
    private fun buildWireGuardEndpoint(config: VpnServerConfig): JSONObject = JSONObject().apply {
        put("type", "wireguard")
        put("system", false)
        put("private_key", config.config["private_key"] ?: "")

        val isWarp = config.protocol == VpnProtocol.WARP

        // Локальные адреса интерфейса
        val addresses = if (isWarp) {
            val ipv4 = config.config["ipv4"] ?: ""
            val ipv6 = config.config["ipv6"] ?: ""
            listOf(ipv4, ipv6).filter { it.isNotBlank() }.ifEmpty { listOf("172.16.0.2/32") }
        } else {
            (config.config["address"] ?: "10.0.0.2/32")
                .split(",").map { it.trim() }.filter { it.isNotBlank() }
        }
        put("address", JSONArray().apply { addresses.forEach { put(it) } })

        val mtu = config.config["mtu"]?.toIntOrNull() ?: if (isWarp) 1280 else null
        if (mtu != null) put("mtu", mtu)

        // Peer
        val peer = JSONObject().apply {
            if (isWarp) {
                put("address", config.config["endpoint_host"] ?: "162.159.192.1")
                put("port", config.config["endpoint_port"]?.toIntOrNull() ?: 2408)
                put("public_key", config.config["public_key"] ?: "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=")
            } else {
                put("address", config.host)
                put("port", config.port)
                put("public_key", config.config["public_key"] ?: "")
            }

            val psk = config.config["preshared_key"] ?: config.config["pre_shared_key"] ?: ""
            if (psk.isNotBlank()) put("pre_shared_key", psk)

            // allowed_ips — что заворачивать в туннель (по умолчанию весь трафик)
            val allowed = (config.config["allowed_ips"] ?: "0.0.0.0/0, ::/0")
                .split(",").map { it.trim() }.filter { it.isNotBlank() }
            put("allowed_ips", JSONArray().apply { allowed.forEach { put(it) } })

            val keepalive = config.config["keepalive"]?.toIntOrNull()
            if (keepalive != null && keepalive > 0) put("persistent_keepalive_interval", keepalive)

            val reserved = config.config["reserved"] ?: ""
            if (reserved.isNotBlank()) {
                val parts = reserved.split(",").mapNotNull { it.trim().toIntOrNull() }
                if (parts.size >= 3) put("reserved", JSONArray().apply { parts.forEach { put(it) } })
            }
        }
        put("peers", JSONArray().put(peer))
    }

    private fun buildVless(config: VpnServerConfig): JSONObject = JSONObject().apply {
        put("type", "vless")
        put("server", config.host)
        put("server_port", config.port)
        put("uuid", config.config["uuid"] ?: "")
        val flow = config.config["flow"] ?: ""
        if (flow.isNotBlank()) put("flow", flow)

        val transport = buildTransport(config)
        if (transport != null) put("transport", transport)

        val security = config.config["security"] ?: "none"
        when (security) {
            "tls" -> put("tls", buildTls(
                sni = config.config["sni"] ?: "",
                insecure = config.config["allowInsecure"] == "1",
                fingerprint = config.config["fp"],
                alpn = config.config["alpn"]
            ))
            "reality" -> put("tls", JSONObject().apply {
                put("enabled", true)
                put("server_name", config.config["sni"] ?: "")
                put("utls", JSONObject().apply {
                    put("enabled", true)
                    put("fingerprint", config.config["fp"]?.ifBlank { defaultFingerprint() } ?: defaultFingerprint())
                })
                put("reality", JSONObject().apply {
                    put("enabled", true)
                    put("public_key", config.config["pbk"] ?: config.config["publicKey"] ?: "")
                    put("short_id", config.config["sid"] ?: "")
                })
            })
        }
    }

    private fun buildVmess(config: VpnServerConfig): JSONObject = JSONObject().apply {
        put("type", "vmess")
        put("server", config.host)
        put("server_port", config.port)
        put("uuid", config.config["uuid"] ?: "")
        put("alter_id", (config.config["alterId"] ?: "0").toIntOrNull() ?: 0)
        put("security", "auto")

        val transport = buildTransport(config)
        if (transport != null) put("transport", transport)

        if (config.config["tls"] == "tls") {
            put("tls", buildTls(sni = config.config["sni"] ?: config.config["host"] ?: "", fingerprint = config.config["fp"]))
        }
    }

    private fun buildTrojan(config: VpnServerConfig): JSONObject = JSONObject().apply {
        put("type", "trojan")
        put("server", config.host)
        put("server_port", config.port)
        put("password", config.config["password"] ?: config.password ?: "")

        val transport = buildTransport(config)
        if (transport != null) put("transport", transport)

        put("tls", buildTls(
            sni = config.config["sni"] ?: config.host,
            insecure = config.config["insecure"] == "1",
            fingerprint = config.config["fp"]
        ))
    }

    private fun buildShadowsocks(config: VpnServerConfig): JSONObject = JSONObject().apply {
        put("type", "shadowsocks")
        put("server", config.host)
        put("server_port", config.port)
        put("method", config.config["method"] ?: "chacha20-ietf-poly1305")
        put("password", config.config["password"] ?: config.password ?: "")
    }

    private fun buildTransport(config: VpnServerConfig): JSONObject? {
        val type = config.config["type"] ?: return null
        return when (type) {
            "ws" -> JSONObject().apply {
                put("type", "ws")
                put("path", config.config["path"] ?: "/")
                val host = config.config["host_header"] ?: config.config["sni"] ?: ""
                if (host.isNotBlank()) put("headers", JSONObject().put("Host", host))
            }
            "grpc" -> JSONObject().apply {
                put("type", "grpc")
                put("service_name", config.config["serviceName"] ?: "")
            }
            "httpupgrade" -> JSONObject().apply {
                put("type", "httpupgrade")
                put("path", config.config["path"] ?: "/")
                val host = config.config["host_header"] ?: ""
                if (host.isNotBlank()) put("host", host)
            }
            "http" -> JSONObject().apply {
                put("type", "http")
                put("path", config.config["path"] ?: "/")
                val host = config.config["host_header"] ?: ""
                if (host.isNotBlank()) put("host", JSONArray().put(host))
            }
            else -> null
        }
    }

    private fun buildTls(
        sni: String,
        insecure: Boolean = false,
        fingerprint: String? = null,
        alpn: String? = null
    ): JSONObject = JSONObject().apply {
        put("enabled", true)
        if (sni.isNotBlank()) put("server_name", sni)
        if (insecure) put("insecure", true)
        val fp = fingerprint?.ifBlank { null } ?: defaultFingerprint()
        put("utls", JSONObject().apply {
            put("enabled", true)
            put("fingerprint", fp)
        })
        if (!alpn.isNullOrBlank()) {
            put("alpn", JSONArray().apply {
                alpn.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { put(it) }
            })
        }
    }

    private fun buildRoute(context: Context): JSONObject = JSONObject().apply {
        // sing-box 1.12+ требует указать, какой DNS резолвит домены серверов (иначе FATAL).
        put("default_domain_resolver", JSONObject().apply { put("server", primaryDnsTag(context)) })

        val rules = JSONArray()

        // Block private IPs (bypass to direct)
        rules.put(JSONObject().apply {
            put("ip_cidr", JSONArray().apply {
                put("10.0.0.0/8"); put("172.16.0.0/12")
                put("192.168.0.0/16"); put("127.0.0.0/8")
            })
            put("outbound", "direct")
        })

        // Blocked domains
        val blocked = PrefsManager.getBlockedDomains(context)
        if (blocked.isNotEmpty()) {
            rules.put(JSONObject().apply {
                put("domain", JSONArray().apply { blocked.forEach { put(it) } })
                put("outbound", "block")
            })
        }

        put("rules", rules)
        put("final", "proxy")
    }
}
