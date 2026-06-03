package com.carnelia.vpn.data

import android.content.Context
import android.util.Base64
import com.carnelia.vpn.core.VpnServerConfig
import com.carnelia.vpn.utils.AppLogger
import com.carnelia.vpn.utils.ConfigParser
import com.carnelia.vpn.utils.HappDecryptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit

class SubscriptionManager(private val context: Context) {

    private val repository = ServerRepository(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
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
                resolveAndFetch(sub.url)
            }
            if (content.isBlank()) return false
            
            val (configs, error) = parseAnySubscription(content, sub.id)
            if (error != null) {
                AppLogger.error("SubManager: ${sub.name}: $error")
            }
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
    
    private fun resolveAndFetch(url: String): String {
        // Happ deeplink → decrypt to real HTTPS URL first
        if (HappDecryptor.isHappUrl(url)) {
            val resolved = HappDecryptor.resolveSubscriptionUrl(url)
                ?: throw IOException("Не удалось расшифровать Happ-подписку")
            return fetchUrl(resolved)
        }
        return fetchUrl(url)
    }

    private fun fetchUrl(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "v2rayN/6.0")
            .build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string() ?: ""
        }
    }
    
    private val PROTO_SCHEMES = listOf("vless://", "vmess://", "ss://", "trojan://",
        "wireguard://", "wireguard+amnezia://")

    private fun extractProtocolLines(text: String): List<String> =
        text.lines().mapNotNull { line ->
            val t = line.trim()
            if (PROTO_SCHEMES.any { t.startsWith(it, ignoreCase = true) }) t else null
        }

    private fun tryBase64Decode(data: String): String? {
        val stripped = data.replace(Regex("\\s+"), "")
        if (stripped.length < 20) return null
        // Standard base64
        return try {
            val dec = String(Base64.decode(stripped, Base64.DEFAULT), StandardCharsets.UTF_8)
            if (dec.contains("://")) dec else null
        } catch (_: Exception) {
            // base64url variant
            try {
                val urlSafe = stripped.replace('-', '+').replace('_', '/')
                val dec = String(Base64.decode(urlSafe, Base64.DEFAULT), StandardCharsets.UTF_8)
                if (dec.contains("://")) dec else null
            } catch (_: Exception) { null }
        }
    }

    private fun parseAnySubscription(raw: String, subId: String): Pair<List<VpnServerConfig>, String?> {
        val trimmed = raw.trim()
        val isHtml = trimmed.startsWith("<!") || trimmed.startsWith("<html") ||
                     trimmed.contains("<!DOCTYPE", ignoreCase = true)

        if (isHtml) {
            // Extract embedded protocol links from HTML
            val regex = Regex("""(vless|vmess|ss|trojan|wireguard)://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
            val links = regex.findAll(trimmed).map { it.value.replace("&amp;", "&").trim() }.toList()
            if (links.isNotEmpty()) {
                val configs = links.mapNotNull { ConfigParser.parse(it)?.copy(subscriptionId = subId) }
                return configs to null
            }
            return emptyList<VpnServerConfig>() to "Страница не содержит VPN-серверов."
        }

        // Try base64 decode
        val decoded = tryBase64Decode(trimmed)
        if (decoded != null) {
            val lines = extractProtocolLines(decoded)
            if (lines.isNotEmpty()) {
                val configs = lines.mapNotNull { ConfigParser.parse(it)?.copy(subscriptionId = subId) }
                if (configs.isNotEmpty()) return configs to null
            }
        }

        // Try plain text
        val plainLines = extractProtocolLines(trimmed)
        if (plainLines.isNotEmpty()) {
            val configs = plainLines.mapNotNull { ConfigParser.parse(it)?.copy(subscriptionId = subId) }
            if (configs.isNotEmpty()) return configs to null
        }

        // Try SingBox JSON (detect and warn)
        if (trimmed.startsWith("{") && trimmed.contains("\"outbounds\"")) {
            return emptyList<VpnServerConfig>() to
                "SingBox JSON формат. Используй V2Ray-подписку для полного импорта."
        }

        return emptyList<VpnServerConfig>() to "Неизвестный формат подписки."
    }

    private fun parseConfigs(content: String, subId: String): List<VpnServerConfig> {
        val list = mutableListOf<VpnServerConfig>()
        content.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("//")) {
                val config = ConfigParser.parse(trimmed)
                if (config != null) {
                    list.add(config.copy(subscriptionId = subId))
                }
            }
        }
        return list
    }
}