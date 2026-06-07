package com.carnelia.vpn.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

object LeakTestManager {

    private const val SOCKS5_PORT = 10808

    data class LeakResult(
        val externalIp: String?,
        val ipCountry: String?,
        val ipOrg: String?,
        val dnsServers: List<String>,
        val isVpnConnected: Boolean,
        val errorMsg: String? = null
    )

    private fun buildClient(useVpnProxy: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
        if (useVpnProxy) {
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", SOCKS5_PORT)))
        }
        return builder.build()
    }

    suspend fun runFullTest(vpnConnected: Boolean): LeakResult = withContext(Dispatchers.IO) {
        val client = buildClient(vpnConnected)
        try {
            val ipInfo = fetchIpInfo(client)
            val dnsServers = probeDnsServers()

            LeakResult(
                externalIp = ipInfo?.first,
                ipCountry = ipInfo?.second,
                ipOrg = ipInfo?.third,
                dnsServers = dnsServers,
                isVpnConnected = vpnConnected
            )
        } catch (e: Exception) {
            LeakResult(
                externalIp = null,
                ipCountry = null,
                ipOrg = null,
                dnsServers = emptyList(),
                isVpnConnected = vpnConnected,
                errorMsg = e.message
            )
        }
    }

    private fun fetchIpInfo(client: OkHttpClient): Triple<String, String, String>? {
        // cloudflare trace — fast, no quota
        try {
            val req = Request.Builder().url("https://1.1.1.1/cdn-cgi/trace").build()
            val body = client.newCall(req).execute().body?.string() ?: ""
            val map = body.lines().associate { line ->
                val eq = line.indexOf('=')
                if (eq > 0) line.substring(0, eq) to line.substring(eq + 1) else "" to ""
            }
            val ip = map["ip"] ?: ""
            val loc = map["loc"] ?: ""
            if (ip.isNotBlank()) {
                val org = fetchOrg(client, ip)
                return Triple(ip, loc, org)
            }
        } catch (_: Exception) {}

        // fallback: ipify
        try {
            val req = Request.Builder().url("https://api.ipify.org?format=json").build()
            val json = JSONObject(client.newCall(req).execute().body?.string() ?: "{}")
            val ip = json.optString("ip", "")
            if (ip.isNotBlank()) {
                val org = fetchOrg(client, ip)
                return Triple(ip, "", org)
            }
        } catch (_: Exception) {}

        return null
    }

    private fun fetchOrg(client: OkHttpClient, ip: String): String {
        return try {
            val req = Request.Builder().url("https://ipapi.co/$ip/json/").build()
            val json = JSONObject(client.newCall(req).execute().body?.string() ?: "{}")
            val org = json.optString("org", "")
            val country = json.optString("country_name", "")
            if (org.isNotBlank()) "$org, $country" else country
        } catch (_: Exception) { "" }
    }

    private fun probeDnsServers(): List<String> {
        val result = mutableListOf<String>()
        val targets = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "1.0.0.1", "8.8.4.4")
        for (target in targets) {
            try {
                Socket().use { it.connect(InetSocketAddress(target, 53), 1500) }
                result.add(target)
            } catch (_: Exception) {}
        }
        return result
    }
}
