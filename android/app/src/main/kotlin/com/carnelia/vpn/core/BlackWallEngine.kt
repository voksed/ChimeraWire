package com.carnelia.vpn.core

import android.content.Context
import com.carnelia.vpn.utils.AppLogger
import com.carnelia.vpn.utils.PrefsManager
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Black Wall — Anti-DPI Stealth Engine
 *
 * Three-layer protection against deep packet inspection (DPI/TSPU):
 *
 *  Layer 1 — TLS Fragmentation
 *    Splits TLS ClientHello into multiple TCP segments.
 *    DPI reassembly algorithms struggle with non-standard fragment sizes.
 *
 *  Layer 2 — SNI Camouflage
 *    Replaces the real SNI with a whitelisted CDN domain.
 *    The real destination is hidden inside the encrypted payload.
 *
 *  Layer 3 — Noise Injection
 *    Generates periodic fake HTTPS requests to legitimate domains.
 *    Makes the traffic pattern indistinguishable from normal browsing.
 */
object BlackWallEngine {

    private const val PREF_ENABLED = "black_wall_enabled"
    private const val PREF_LEVEL = "black_wall_level"

    enum class StealthLevel(val label: String, val description: String) {
        OFF("Выкл", "Обычный режим"),
        GHOST("Ghost", "Фрагментация TLS + маскировка SNI"),
        PHANTOM("Phantom", "Ghost + рандомизация пакетов"),
        WRAITH("Wraith", "Phantom + шумовой трафик")
    }

    // Whitelisted SNI domains that are not blocked anywhere
    private val CAMOUFLAGE_DOMAINS = listOf(
        "www.bing.com",
        "www.microsoft.com",
        "login.microsoftonline.com",
        "discord.com",
        "www.cloudflare.com",
        "cdn.jsdelivr.net",
        "ajax.googleapis.com",
        "fonts.googleapis.com"
    )

    fun isEnabled(context: Context): Boolean =
        PrefsManager.getBlackWallEnabled(context)

    fun getLevel(context: Context): StealthLevel =
        try {
            StealthLevel.valueOf(PrefsManager.getBlackWallLevel(context))
        } catch (_: Exception) {
            StealthLevel.GHOST
        }

    fun setEnabled(context: Context, enabled: Boolean) {
        PrefsManager.setBlackWallEnabled(context, enabled)
        AppLogger.log("BlackWall: ${if (enabled) "ACTIVATED (${getLevel(context).label})" else "DEACTIVATED"}")
    }

    fun setLevel(context: Context, level: StealthLevel) {
        PrefsManager.setBlackWallLevel(context, level.name)
        AppLogger.log("BlackWall: Level set to ${level.label}")
    }

    fun getCamouflageDomain(context: Context): String {
        // Pick a random whitelist domain each session
        return CAMOUFLAGE_DOMAINS.random()
    }

    /**
     * Applies Black Wall settings to an Xray outbound JSON object.
     * Injects: TLS fragmentation, SNI override, timing randomization.
     */
    fun applyToXrayOutbound(context: Context, outbound: JSONObject, config: VpnServerConfig) {
        val level = getLevel(context)
        if (level == StealthLevel.OFF) return

        AppLogger.log("BlackWall: Applying level ${level.label} to ${config.protocol}")

        // Layer 1: TLS Fragmentation (all levels)
        val streamSettings = outbound.optJSONObject("streamSettings") ?: JSONObject()
        val sockopt = streamSettings.optJSONObject("sockopt") ?: JSONObject()

        val fragment = JSONObject().apply {
            // Fragment TLS ClientHello into 1-3 byte pieces
            put("packets", "tlshello")
            when (level) {
                StealthLevel.GHOST   -> put("length", "1-5")
                StealthLevel.PHANTOM -> put("length", "1-3")
                StealthLevel.WRAITH  -> put("length", "1-2")
                else -> {}
            }
            // Random interval between fragments
            put("interval", when (level) {
                StealthLevel.GHOST   -> "10-20"
                StealthLevel.PHANTOM -> "5-15"
                StealthLevel.WRAITH  -> "2-8"
                else -> "10-20"
            })
        }
        sockopt.put("fragment", fragment)
        sockopt.put("tcpKeepAliveInterval", 300)

        // Layer 2: SNI Camouflage for VLESS/Trojan/VMess
        if (level != StealthLevel.OFF) {
            val tls = streamSettings.optJSONObject("tlsSettings")
                ?: streamSettings.optJSONObject("realitySettings")
            if (tls != null && !tls.has("serverName")) {
                // Only set if not already specified
                val camoDomain = getCamouflageDomain(context)
                tls.put("serverName", camoDomain)
                AppLogger.log("BlackWall: SNI camouflage → $camoDomain")
            }
        }

        // Layer 2b: uTLS fingerprint (Phantom+)
        if (level == StealthLevel.PHANTOM || level == StealthLevel.WRAITH) {
            val tls = streamSettings.optJSONObject("tlsSettings")
            if (tls != null && !tls.has("fingerprint")) {
                // Rotate between browser fingerprints
                val fingerprints = listOf("chrome", "firefox", "safari", "randomized")
                tls.put("fingerprint", fingerprints.random())
            }
        }

        streamSettings.put("sockopt", sockopt)
        outbound.put("streamSettings", streamSettings)
    }

    /**
     * Applies Black Wall to Sing-box outbound JSON.
     * Sing-box supports utls fingerprinting and custom SNI.
     */
    fun applyToSingboxOutbound(context: Context, outbound: JSONObject, config: VpnServerConfig) {
        val level = getLevel(context)
        if (level == StealthLevel.OFF) return

        AppLogger.log("BlackWall: Applying sing-box stealth level ${level.label}")

        val tls = outbound.optJSONObject("tls") ?: return

        // Layer 2: SNI camouflage (only if not REALITY)
        if (!tls.has("reality") && !tls.optBoolean("reality", false)) {
            if (!tls.has("server_name") || tls.optString("server_name").isBlank()) {
                tls.put("server_name", getCamouflageDomain(context))
            }
        }

        // Layer 2b: uTLS fingerprint (Phantom+)
        if (level == StealthLevel.PHANTOM || level == StealthLevel.WRAITH) {
            if (!tls.has("utls")) {
                val fingerprints = listOf("chrome", "firefox", "safari", "randomized")
                tls.put("utls", JSONObject().apply {
                    put("enabled", true)
                    put("fingerprint", fingerprints.random())
                })
            }
        }
    }

    private var noiseJob: Job? = null

    /**
     * Layer 3: Noise Injection (Wraith level only)
     * Makes periodic fake HTTPS requests to legitimate domains.
     * Traffic looks like normal web browsing alongside VPN.
     */
    fun startNoiseInjection(context: Context, scope: CoroutineScope) {
        if (getLevel(context) != StealthLevel.WRAITH) return
        noiseJob?.cancel()
        noiseJob = scope.launch(Dispatchers.IO) {
            val noiseTargets = listOf(
                "https://www.bing.com/favicon.ico",
                "https://www.cloudflare.com/favicon.ico",
                "https://fonts.googleapis.com/css?family=Roboto",
                "https://ajax.googleapis.com/ajax/libs/jquery/3.6.0/jquery.min.js"
            )
            AppLogger.log("BlackWall: Noise injection started (Wraith mode)")
            while (isActive) {
                try {
                    val url = noiseTargets.random()
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 3000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    conn.connect()
                    conn.disconnect()
                } catch (_: Exception) {}
                // Random interval 15-45 seconds
                delay((15_000L..45_000L).random())
            }
        }
    }

    fun stopNoiseInjection() {
        noiseJob?.cancel()
        noiseJob = null
        AppLogger.log("BlackWall: Noise injection stopped")
    }
}
