package com.carnelia.vpn.core

import android.content.Context
import android.content.SharedPreferences
import com.carnelia.vpn.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.UUID

/**
 * Cloudflare WARP registration and credential management.
 *
 * WARP is WireGuard connecting to Cloudflare's servers.
 * Registration: POST https://api.cloudflareclient.com/v0a4005/reg
 * Returns: private/public key pair, IPv4/IPv6 assignment, account token.
 */
object WarpManager {

    private const val PREFS = "warp_prefs"
    private const val KEY_PRIVATE = "warp_private_key"
    private const val KEY_PUBLIC = "warp_server_public"
    private const val KEY_IPV4 = "warp_ipv4"
    private const val KEY_IPV6 = "warp_ipv6"
    private const val KEY_ENDPOINT_HOST = "warp_endpoint_host"
    private const val KEY_ENDPOINT_PORT = "warp_endpoint_port"
    private const val KEY_RESERVED = "warp_reserved"
    private const val KEY_REGISTERED = "warp_registered"

    private val WARP_ENDPOINTS = listOf(
        "162.159.192.1" to 2408,
        "162.159.193.1" to 2408,
        "162.159.195.1" to 2408,
        "162.159.204.1" to 2408,
        "engage.cloudflareclient.com" to 2408
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isRegistered(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REGISTERED, false)

    /**
     * Registers a new WARP account with Cloudflare and stores credentials.
     * Generates a fresh X25519 key pair, registers via Cloudflare API.
     */
    suspend fun register(context: Context): VpnServerConfig = withContext(Dispatchers.IO) {
        AppLogger.log("WarpManager: Registering with Cloudflare...")

        // Generate X25519 (Curve25519) key pair for WireGuard
        val (privateKeyB64, publicKeyB64) = generateX25519KeyPair()

        val endpoint = WARP_ENDPOINTS.first()
        val body = JSONObject().apply {
            put("key", publicKeyB64)
            put("install_id", UUID.randomUUID().toString().replace("-", "").take(22))
            put("tos", java.time.Instant.now().toString())
            put("model", "Android")
            put("serial_number", UUID.randomUUID().toString())
            put("locale", "en_US")
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.cloudflareclient.com/v0a4005/reg")
            .post(body)
            .header("User-Agent", "okhttp/3.12.1")
            .header("CF-Client-Version", "a-6.36-3672")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response from Cloudflare")

        if (!response.isSuccessful) {
            AppLogger.log("WarpManager: Registration failed: $responseBody")
            throw Exception("Cloudflare WARP registration failed (${response.code}): $responseBody")
        }

        AppLogger.log("WarpManager: Registration successful")
        val json = JSONObject(responseBody)

        val config = json.optJSONObject("config") ?: throw Exception("No config in response")
        val iface = config.optJSONObject("interface") ?: throw Exception("No interface in config")
        val addresses = iface.optJSONObject("addresses") ?: throw Exception("No addresses in config")

        val ipv4 = addresses.optString("v4", "").let { if (it.isNotBlank()) "$it/32" else "" }
        val ipv6 = addresses.optString("v6", "").let { if (it.isNotBlank()) "$it/128" else "" }

        val peers = config.optJSONArray("peers")
        val peer = peers?.optJSONObject(0) ?: throw Exception("No peers in config")
        val serverPublicKey = peer.optString("public_key", "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=")

        // Parse endpoint from peer
        val peerEndpoint = peer.optJSONObject("endpoint")
        val endpointHost = peerEndpoint?.optString("host", endpoint.first) ?: endpoint.first
        val endpointPort = peerEndpoint?.optInt("port", endpoint.second) ?: endpoint.second

        // WARP reserved bytes — derived from account ID
        val accountId = json.optString("id", "")
        val reserved = deriveReservedBytes(accountId)

        // Persist
        prefs(context).edit().apply {
            putString(KEY_PRIVATE, privateKeyB64)
            putString(KEY_PUBLIC, serverPublicKey)
            putString(KEY_IPV4, ipv4)
            putString(KEY_IPV6, ipv6)
            putString(KEY_ENDPOINT_HOST, endpointHost)
            putInt(KEY_ENDPOINT_PORT, endpointPort)
            putString(KEY_RESERVED, reserved.joinToString(","))
            putBoolean(KEY_REGISTERED, true)
        }.apply()

        AppLogger.log("WarpManager: Saved WARP config (IPv4=$ipv4)")
        buildVpnConfig(context)
    }

    fun buildVpnConfig(context: Context): VpnServerConfig {
        val p = prefs(context)
        val reserved = p.getString(KEY_RESERVED, "")?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
        return VpnServerConfig(
            id = "warp_builtin",
            name = "Cloudflare WARP",
            protocol = VpnProtocol.WARP,
            host = p.getString(KEY_ENDPOINT_HOST, "162.159.192.1") ?: "162.159.192.1",
            port = p.getInt(KEY_ENDPOINT_PORT, 2408),
            config = mapOf(
                "private_key" to (p.getString(KEY_PRIVATE, "") ?: ""),
                "public_key" to (p.getString(KEY_PUBLIC, "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=") ?: "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="),
                "ipv4" to (p.getString(KEY_IPV4, "172.16.0.2/32") ?: "172.16.0.2/32"),
                "ipv6" to (p.getString(KEY_IPV6, "") ?: ""),
                "endpoint_host" to (p.getString(KEY_ENDPOINT_HOST, "162.159.192.1") ?: "162.159.192.1"),
                "endpoint_port" to p.getInt(KEY_ENDPOINT_PORT, 2408).toString(),
                "reserved" to reserved.joinToString(",")
            ),
            country = "CF"
        )
    }

    fun clearRegistration(context: Context) {
        prefs(context).edit().clear().apply()
        AppLogger.log("WarpManager: Registration cleared")
    }

    private fun generateX25519KeyPair(): Pair<String, String> {
        // API 31+: X25519 is natively supported. Do NOT call initialize() — X25519 has no parameters.
        // PKCS8 private key DER: 48 bytes, last 32 = raw key.
        // SubjectPublicKeyInfo DER: 44 bytes, last 32 = raw key.
        return try {
            val kpg = KeyPairGenerator.getInstance("X25519")
            val kp = kpg.generateKeyPair()
            val priv = Base64.getEncoder().encodeToString(kp.private.encoded.takeLast(32).toByteArray())
            val pub  = Base64.getEncoder().encodeToString(kp.public.encoded.takeLast(32).toByteArray())
            Pair(priv, pub)
        } catch (_: Exception) {
            generateX25519Fallback()
        }
    }

    private fun generateX25519Fallback(): Pair<String, String> {
        // Try AndroidOpenSSL provider explicitly (available on all Android versions)
        return try {
            val kpg = KeyPairGenerator.getInstance("X25519", "AndroidOpenSSL")
            val kp = kpg.generateKeyPair()
            val priv = Base64.getEncoder().encodeToString(kp.private.encoded.takeLast(32).toByteArray())
            val pub  = Base64.getEncoder().encodeToString(kp.public.encoded.takeLast(32).toByteArray())
            Pair(priv, pub)
        } catch (_: Exception) {
            // Last resort: random clamped private key.
            // Public key cannot be derived without native Curve25519.
            // This will cause WARP registration to fail gracefully rather than crash.
            val priv = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            priv[0]  = (priv[0].toInt()  and 248).toByte()
            priv[31] = (priv[31].toInt() and 127).toByte()
            priv[31] = (priv[31].toInt() or  64).toByte()
            Pair(Base64.getEncoder().encodeToString(priv), Base64.getEncoder().encodeToString(priv))
        }
    }

    private fun deriveReservedBytes(accountId: String): List<Int> {
        if (accountId.isBlank()) return listOf(0, 0, 0)
        val bytes = accountId.filter { it.isLetterOrDigit() }.take(6).toByteArray()
        return listOf(
            (bytes.getOrElse(0) { 0 }.toInt() and 0xFF),
            (bytes.getOrElse(2) { 0 }.toInt() and 0xFF),
            (bytes.getOrElse(4) { 0 }.toInt() and 0xFF)
        )
    }
}