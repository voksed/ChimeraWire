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

    const val LOCAL_PORT = 10811
    const val LOCAL_PASSWORD = "local-singbox-bridge"
    const val LOCAL_METHOD = "chacha20-ietf-poly1305"

    suspend fun startCore(context: Context, config: VpnServerConfig) = withContext(Dispatchers.IO) {
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

        streamJob?.cancel()
        streamJob = scope.launch {
            try {
                process!!.inputStream.bufferedReader().use { r ->
                    for (line in r.lineSequence()) {
                        if (!isActive) break
                        AppLogger.log("Sing-box: $line")
                    }
                }
            } catch (_: Exception) {}
        }

        delay(400)
        if (process?.isAlive == false) {
            throw Exception("Sing-box died immediately (exit ${process?.exitValue()})")
        }
        AppLogger.log("SingboxCoreManager: Started on port $LOCAL_PORT")
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

        // Inbounds — Shadowsocks local bridge for tun2socks
        root.put("inbounds", JSONArray().put(JSONObject().apply {
            put("type", "shadowsocks")
            put("tag", "ss-in")
            put("listen", "127.0.0.1")
            put("listen_port", LOCAL_PORT)
            put("method", LOCAL_METHOD)
            put("password", LOCAL_PASSWORD)
        }))

        // Outbounds
        root.put("outbounds", buildOutbounds(context, vpnConfig))

        // Route
        root.put("route", buildRoute(context))

        return root.toString(2)
    }

    private fun buildDns(context: Context): JSONObject {
        val servers = JSONArray()
        if (PrefsManager.isNetShieldEnabled(context)) {
            servers.put(JSONObject().apply {
                put("tag", "adguard"); put("address", "94.140.14.14")
            })
        } else {
            val userDns = PrefsManager.getDnsServer(context)
            if (userDns.isNotBlank()) servers.put(JSONObject().apply {
                put("tag", "user"); put("address", userDns)
            })
            servers.put(JSONObject().apply { put("tag", "cf"); put("address", "1.1.1.1") })
            servers.put(JSONObject().apply { put("tag", "google"); put("address", "8.8.8.8") })
        }
        return JSONObject().apply {
            put("servers", servers)
            put("strategy", "prefer_ipv4")
        }
    }

    private fun buildOutbounds(context: Context, vpnConfig: VpnServerConfig): JSONArray {
        val arr = JSONArray()

        // Main proxy outbound
        val proxy = buildProxyOutbound(vpnConfig)
        arr.put(proxy)

        // Direct
        arr.put(JSONObject().apply { put("type", "direct"); put("tag", "direct") })

        // Block
        arr.put(JSONObject().apply { put("type", "block"); put("tag", "block") })

        return arr
    }

    private fun buildProxyOutbound(config: VpnServerConfig): JSONObject {
        return when (config.protocol) {
            VpnProtocol.HYSTERIA2 -> buildHysteria2(config)
            VpnProtocol.TUIC      -> buildTuic(config)
            VpnProtocol.WIREGUARD -> buildWireGuard(config)
            VpnProtocol.AMNEZIA_WG -> buildWireGuard(config) // AmneziaWG fields ignored in sing-box WG
            VpnProtocol.WARP      -> buildWarp(config)
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
            insecure = config.config["insecure"] == "1"
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
            alpn = config.config["alpn"]
        ))
    }

    private fun buildWireGuard(config: VpnServerConfig): JSONObject = JSONObject().apply {
        put("type", "wireguard")
        put("server", config.host)
        put("server_port", config.port)
        put("private_key", config.config["private_key"] ?: "")
        put("peer_public_key", config.config["public_key"] ?: "")

        val psk = config.config["preshared_key"] ?: ""
        if (psk.isNotBlank()) put("pre_shared_key", psk)

        val address = config.config["address"] ?: "10.0.0.2/32"
        put("local_address", JSONArray().apply {
            address.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { put(it) }
        })

        val mtu = config.config["mtu"]?.toIntOrNull()
        if (mtu != null) put("mtu", mtu)
    }

    private fun buildWarp(config: VpnServerConfig): JSONObject = JSONObject().apply {
        put("type", "wireguard")
        put("server", config.config["endpoint_host"] ?: "162.159.192.1")
        put("server_port", config.config["endpoint_port"]?.toIntOrNull() ?: 2408)
        put("private_key", config.config["private_key"] ?: "")
        put("peer_public_key", config.config["public_key"] ?: "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=")

        val ipv4 = config.config["ipv4"] ?: ""
        val ipv6 = config.config["ipv6"] ?: ""
        put("local_address", JSONArray().apply {
            if (ipv4.isNotBlank()) put(ipv4)
            if (ipv6.isNotBlank()) put(ipv6)
            if (ipv4.isBlank() && ipv6.isBlank()) put("172.16.0.2/32")
        })

        // WARP reserved bytes
        val reserved = config.config["reserved"] ?: ""
        if (reserved.isNotBlank()) {
            val parts = reserved.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (parts.size >= 3) {
                put("reserved", JSONArray().apply { parts.forEach { put(it) } })
            }
        }
        put("mtu", 1280)
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
                    put("fingerprint", config.config["fp"]?.ifBlank { "chrome" } ?: "chrome")
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
            put("tls", buildTls(sni = config.config["sni"] ?: config.config["host"] ?: ""))
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
        val fp = fingerprint?.ifBlank { null }
        if (fp != null) {
            put("utls", JSONObject().apply {
                put("enabled", true)
                put("fingerprint", fp)
            })
        }
        if (!alpn.isNullOrBlank()) {
            put("alpn", JSONArray().apply {
                alpn.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { put(it) }
            })
        }
    }

    private fun buildRoute(context: Context): JSONObject = JSONObject().apply {
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
