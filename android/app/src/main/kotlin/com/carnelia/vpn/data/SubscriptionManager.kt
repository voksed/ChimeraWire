package com.carnelia.vpn.data

import android.content.Context
import android.util.Base64
import com.carnelia.vpn.core.VpnServerConfig
import com.carnelia.vpn.utils.AppLogger
import com.carnelia.vpn.utils.ConfigParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID

class SubscriptionManager(private val context: Context) {

    private val repository = ServerRepository(context)
    private val client = OkHttpClient()
    
    private val PREFS = "vpn_subs"
    private val KEY_SUBS = "saved_subscriptions"
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = com.google.gson.Gson()

    fun getSubscriptions(): List<Subscription> {
        val json = prefs.getString(KEY_SUBS, "[]")
        return try {
            val list = gson.fromJson(json, Array<Subscription>::class.java)
            list?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addSubscription(name: String, url: String) {
        val subs = getSubscriptions().toMutableList()
        if (subs.any { it.url == url }) return
        
        val newSub = Subscription(
            id = UUID.randomUUID().toString(),
            name = name,
            url = url,
            lastUpdated = 0,
            serverCount = 0
        )
        subs.add(newSub)
        saveSubscriptions(subs)
    }
    
    fun removeSubscription(id: String) {
        val subs = getSubscriptions().toMutableList()
        subs.removeAll { it.id == id }
        saveSubscriptions(subs)
        repository.removeSubscriptionServers(id)
    }

    private fun saveSubscriptions(list: List<Subscription>) {
        prefs.edit().putString(KEY_SUBS, gson.toJson(list)).apply()
    }

    suspend fun updateSubscription(id: String): Boolean {
        val sub = getSubscriptions().find { it.id == id } ?: return false
        
        return try {
            val content = withContext(Dispatchers.IO) {
                fetchUrl(sub.url)
            }
            if (content.isBlank()) return false
            
            val decoded = tryDecode(content)
            
            val configs = parseConfigs(decoded, sub.id)
            if (configs.isNotEmpty()) {
                // We use remove+add because we want to sync the state exactly with the remote list
                repository.removeSubscriptionServers(sub.id)
                repository.addOrUpdateServers(configs)
                
                val updatedSub = sub.copy(lastUpdated = System.currentTimeMillis(), serverCount = configs.size)
                val all = getSubscriptions().toMutableList()
                val idx = all.indexOfFirst { it.id == id }
                if (idx != -1) {
                    all[idx] = updatedSub
                    saveSubscriptions(all)
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            AppLogger.error("SubManager: Failed to update ${sub.name}", e)
            false
        }
    }
    
    private fun fetchUrl(url: String): String {
        val resolvedUrl = if (url.lowercase().startsWith("happ://crypt")) {
            com.carnelia.vpn.utils.HappCrypt5Decryptor.decrypt(context, url)
        } else {
            url
        }
        val request = Request.Builder()
            .url(resolvedUrl)
            .header("User-Agent", "v2rayNG/1.8.0")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            return response.body?.string() ?: ""
        }
    }


    /**
     * Attempts to detect and decode base64-encoded subscription content.
     * Handles:
     *  - Single-line base64 (standard v2ray/happ format)
     *  - Multi-line base64 (line-wrapped every 76 chars)
     *  - URL-safe base64
     *  - Plain text URI lists (returned as-is)
     */
    private fun tryDecode(content: String): String {
        val trimmed = content.trim()

        // Already looks like a plain list of proxy URIs — return as-is
        if (looksLikeProxyList(trimmed)) return trimmed

        // Strip whitespace and try base64 decode
        val noWs = trimmed.replace(Regex("\\s+"), "")
        if (noWs.length > 20 && isBase64Chars(noWs)) {
            for (flags in listOf(
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
                Base64.DEFAULT,
                Base64.URL_SAFE
            )) {
                try {
                    val decoded = String(Base64.decode(noWs, flags), StandardCharsets.UTF_8)
                    if (looksLikeProxyList(decoded) || decoded.contains("://")) {
                        AppLogger.log("SubManager: base64 decoded ${noWs.length} chars → ${decoded.lines().size} lines")
                        return decoded
                    }
                } catch (_: Exception) {}
            }
        }

        return trimmed
    }

    private fun looksLikeProxyList(s: String): Boolean {
        val proxyPrefixes = listOf("vless://", "vmess://", "ss://", "trojan://",
            "hysteria2://", "hy2://", "wireguard://", "socks://", "http://", "https://")
        return s.lines().any { line -> proxyPrefixes.any { line.trimStart().lowercase().startsWith(it) } }
    }

    private fun isBase64Chars(s: String): Boolean {
        return s.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '-' || it == '_' || it == '=' }
    }

    private fun parseConfigs(content: String, subId: String): List<VpnServerConfig> {
        val list = mutableListOf<VpnServerConfig>()

        // Try Clash YAML format
        if (content.contains("proxies:") || content.trimStart().startsWith("proxies:")) {
            parseClashYaml(content, subId, list)
            if (list.isNotEmpty()) return list
        }

        // Try sing-box / generic JSON array with outbounds
        if (content.trimStart().startsWith("{") && content.contains("\"outbounds\"")) {
            parseSingboxJson(content, subId, list)
            if (list.isNotEmpty()) return list
        }

        // Standard URI list (v2ray / happ / standard subscription)
        content.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("//")) {
                val config = ConfigParser.parse(trimmed)
                if (config != null) list.add(config.copy(subscriptionId = subId))
            }
        }
        return list
    }

    /** Parses Clash YAML proxies block into VpnServerConfig list */
    private fun parseClashYaml(yaml: String, subId: String, out: MutableList<VpnServerConfig>) {
        try {
            // Simple line-by-line parser for common proxy types
            var inProxies = false
            var currentProxy = mutableMapOf<String, String>()

            fun flushProxy() {
                if (currentProxy.isEmpty()) return
                val type = currentProxy["type"]?.lowercase() ?: return
                val name = currentProxy["name"] ?: return
                val server = currentProxy["server"] ?: return
                val port = currentProxy["port"]?.toIntOrNull() ?: return
                val config = when (type) {
                    "ss", "shadowsocks" -> {
                        com.carnelia.vpn.core.VpnServerConfig(
                            id = java.util.UUID.randomUUID().toString(),
                            name = name, protocol = com.carnelia.vpn.core.VpnProtocol.SHADOWSOCKS,
                            host = server, port = port,
                            config = mapOf(
                                "method" to (currentProxy["cipher"] ?: "chacha20-ietf-poly1305"),
                                "password" to (currentProxy["password"] ?: "")
                            ), subscriptionId = subId
                        )
                    }
                    "trojan" -> {
                        com.carnelia.vpn.core.VpnServerConfig(
                            id = java.util.UUID.randomUUID().toString(),
                            name = name, protocol = com.carnelia.vpn.core.VpnProtocol.TROJAN,
                            host = server, port = port,
                            config = mapOf(
                                "password" to (currentProxy["password"] ?: ""),
                                "sni" to (currentProxy["sni"] ?: server),
                                "security" to "tls", "type" to "tcp"
                            ), subscriptionId = subId
                        )
                    }
                    "vmess" -> {
                        com.carnelia.vpn.core.VpnServerConfig(
                            id = java.util.UUID.randomUUID().toString(),
                            name = name, protocol = com.carnelia.vpn.core.VpnProtocol.VMESS,
                            host = server, port = port,
                            config = mapOf(
                                "uuid" to (currentProxy["uuid"] ?: ""),
                                "alterId" to (currentProxy["alterId"] ?: "0"),
                                "network" to (currentProxy["network"] ?: "tcp"),
                                "tls" to if (currentProxy["tls"] == "true") "tls" else ""
                            ), subscriptionId = subId
                        )
                    }
                    else -> null
                }
                if (config != null) out.add(config)
                currentProxy = mutableMapOf()
            }

            yaml.lines().forEach { line ->
                val trimmed = line.trim()
                when {
                    trimmed == "proxies:" -> { inProxies = true }
                    inProxies && trimmed.startsWith("- ") -> {
                        flushProxy()
                        val kv = trimmed.removePrefix("- ").split(":", limit = 2)
                        if (kv.size == 2) currentProxy[kv[0].trim()] = kv[1].trim().removeSurrounding("\"")
                    }
                    inProxies && trimmed.contains(":") && !trimmed.startsWith("-") -> {
                        val kv = trimmed.split(":", limit = 2)
                        if (kv.size == 2) currentProxy[kv[0].trim()] = kv[1].trim().removeSurrounding("\"")
                    }
                    inProxies && !trimmed.startsWith(" ") && !trimmed.startsWith("-") && trimmed.endsWith(":") -> {
                        flushProxy()
                        inProxies = false
                    }
                }
            }
            flushProxy()
        } catch (e: Exception) {
            AppLogger.error("SubManager: Clash YAML parse error", e)
        }
    }

    /** Parses sing-box JSON outbounds */
    private fun parseSingboxJson(json: String, subId: String, out: MutableList<VpnServerConfig>) {
        try {
            val root = org.json.JSONObject(json)
            val outbounds = root.optJSONArray("outbounds") ?: return
            for (i in 0 until outbounds.length()) {
                val ob = outbounds.optJSONObject(i) ?: continue
                val type = ob.optString("type").lowercase()
                val tag = ob.optString("tag", "Server $i")
                val server = ob.optString("server")
                if (server.isBlank()) continue
                val port = ob.optInt("server_port", 0)
                if (port <= 0) continue
                val config = when (type) {
                    "vless" -> com.carnelia.vpn.core.VpnServerConfig(
                        id = java.util.UUID.randomUUID().toString(), name = tag,
                        protocol = com.carnelia.vpn.core.VpnProtocol.VLESS, host = server, port = port,
                        config = mapOf("uuid" to ob.optString("uuid")), subscriptionId = subId
                    )
                    "vmess" -> com.carnelia.vpn.core.VpnServerConfig(
                        id = java.util.UUID.randomUUID().toString(), name = tag,
                        protocol = com.carnelia.vpn.core.VpnProtocol.VMESS, host = server, port = port,
                        config = mapOf("uuid" to ob.optString("uuid"), "alterId" to "0", "network" to "tcp", "tls" to ""),
                        subscriptionId = subId
                    )
                    "trojan" -> com.carnelia.vpn.core.VpnServerConfig(
                        id = java.util.UUID.randomUUID().toString(), name = tag,
                        protocol = com.carnelia.vpn.core.VpnProtocol.TROJAN, host = server, port = port,
                        config = mapOf("password" to ob.optString("password"), "sni" to server, "security" to "tls", "type" to "tcp"),
                        subscriptionId = subId
                    )
                    "shadowsocks" -> com.carnelia.vpn.core.VpnServerConfig(
                        id = java.util.UUID.randomUUID().toString(), name = tag,
                        protocol = com.carnelia.vpn.core.VpnProtocol.SHADOWSOCKS, host = server, port = port,
                        config = mapOf("method" to ob.optString("method", "chacha20-ietf-poly1305"), "password" to ob.optString("password")),
                        subscriptionId = subId
                    )
                    "hysteria2" -> com.carnelia.vpn.core.VpnServerConfig(
                        id = java.util.UUID.randomUUID().toString(), name = tag,
                        protocol = com.carnelia.vpn.core.VpnProtocol.HYSTERIA2, host = server, port = port,
                        config = mapOf("password" to ob.optString("password"), "sni" to server, "insecure" to "0"),
                        subscriptionId = subId
                    )
                    else -> null
                }
                if (config != null) out.add(config)
            }
        } catch (e: Exception) {
            AppLogger.error("SubManager: Sing-box JSON parse error", e)
        }
    }
}