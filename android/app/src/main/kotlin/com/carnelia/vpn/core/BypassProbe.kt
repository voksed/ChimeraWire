package com.carnelia.vpn.core

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.random.Random

/**
 * BypassProbe — local, serverless block detection and DPI-bypass strategy search.
 *
 * Mirrors the OONI web_connectivity method (DNS → TCP → TLS → HTTP) and, for every
 * host that fails, walks a ladder of TLS ClientHello fragmentation strategies until
 * one gets through. Fragmentation is emulated by a one-shot loopback relay that
 * re-segments the first TLS record exactly as Xray's `sockopt.fragment` does, so a
 * winning strategy transfers one-to-one into the freedom outbound.
 *
 * Verdicts:
 *  - OK            handshake and HTTP response succeed without any help
 *  - DNS_TAMPERED  system resolver answers are unusable, DoH answers work
 *  - IP_BLOCKED    TCP connect is dropped; fragmentation cannot help
 *  - SNI_BLOCKED   TCP is fine, plain TLS dies, a fragmentation strategy works
 *  - UNREACHABLE   nothing in the ladder works; a remote server is required
 *
 * Pure JVM (no Android APIs) so it can also be driven from a desktop test harness.
 * Must not run while a VPN tunnel is up: the probe would measure the tunnel.
 */
object BypassProbe {

    enum class Verdict { OK, DNS_TAMPERED, IP_BLOCKED, SNI_BLOCKED, UNREACHABLE }

    /** A fragmentation setting in Xray notation; [length] == null means pass-through. */
    data class Strategy(val length: IntRange?, val intervalMs: IntRange) {
        val xrayLength: String get() = length?.let { "${it.first}-${it.last}" } ?: ""
        val xrayInterval: String get() = "${intervalMs.first}-${intervalMs.last}"
        val label: String get() = if (length == null) "plain" else "len $xrayLength / $xrayInterval ms"
    }

    data class SiteResult(
        val host: String,
        val verdict: Verdict,
        val strategy: Strategy?,
        val detail: String
    )

    data class Report(val sites: List<SiteResult>, val best: Strategy?)

    val DEFAULT_HOSTS = listOf(
        "www.youtube.com", "discord.com", "www.instagram.com",
        "www.facebook.com", "x.com", "www.linkedin.com", "telegram.org"
    )

    val PLAIN = Strategy(null, 0..0)

    /** Ordered from the cheapest to the most intrusive; the first success wins. */
    val LADDER = listOf(
        Strategy(100..200, 10..20),
        Strategy(40..80, 10..20),
        Strategy(10..30, 10..20),
        Strategy(5..10, 10..20),
        Strategy(1..5, 1..5),
        Strategy(1..3, 1..5),
        Strategy(1..1, 1..5)
    )

    private const val CONNECT_TIMEOUT_MS = 4000
    private const val IO_TIMEOUT_MS = 5000

    /**
     * Probes [hosts] sequentially and returns per-host verdicts plus the strategy that
     * unblocked the most SNI-blocked hosts (null when nothing needed or found one).
     */
    fun run(
        hosts: List<String> = DEFAULT_HOSTS,
        onProgress: (SiteResult) -> Unit = {}
    ): Report {
        val results = hosts.map { host -> probeHost(host).also(onProgress) }
        val best = results
            .mapNotNull { it.strategy?.takeIf { s -> s.length != null } }
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key
        return Report(results, best)
    }

    internal fun probeHost(host: String): SiteResult {
        val systemIps = resolveSystem(host)
        val dohIps = resolveDoh(host)
        val systemUsable = systemIps.filterNot(::isBogon)

        val candidate = systemUsable.firstOrNull() ?: dohIps.firstOrNull()
            ?: return SiteResult(host, Verdict.UNREACHABLE, null, "DNS: no answer from system or DoH")

        if (systemUsable.isEmpty()) {
            val dnsDetail = if (systemIps.isEmpty()) "system DNS failed" else "system DNS returned ${systemIps.first()}"
            return when (tlsAttempt(host, candidate, PLAIN)) {
                null -> SiteResult(host, Verdict.DNS_TAMPERED, null, "$dnsDetail; DoH $candidate works")
                else -> escalate(host, candidate, "$dnsDetail; ")
            }
        }

        if (!tcpReachable(candidate)) {
            val alt = dohIps.firstOrNull { it != candidate && tcpReachable(it) }
            if (alt == null) return SiteResult(host, Verdict.IP_BLOCKED, null, "TCP $candidate:443 timed out")
            return escalate(host, alt, "")
        }
        if (tlsAttempt(host, candidate, PLAIN) == null) {
            return SiteResult(host, Verdict.OK, null, "TLS and HTTP fine ($candidate)")
        }
        return escalate(host, candidate, "")
    }

    private fun escalate(host: String, ip: String, prefix: String): SiteResult {
        var lastError = "plain TLS failed"
        for (strategy in LADDER) {
            val err = tlsAttempt(host, ip, strategy)
            if (err == null && tlsAttempt(host, ip, strategy) == null) {
                return SiteResult(host, Verdict.SNI_BLOCKED, strategy, "${prefix}works with ${strategy.label}")
            }
            if (err != null) lastError = err
        }
        return SiteResult(host, Verdict.UNREACHABLE, null, "${prefix}no strategy worked ($lastError)")
    }

    /** Returns null on success, otherwise a short failure description. */
    internal fun tlsAttempt(host: String, ip: String, strategy: Strategy): String? {
        val relay = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val pump = Thread { runRelay(relay, ip, strategy) }.apply { isDaemon = true; start() }
        return try {
            val raw = Socket()
            raw.connect(InetSocketAddress("127.0.0.1", relay.localPort), CONNECT_TIMEOUT_MS)
            raw.soTimeout = IO_TIMEOUT_MS
            val ssl = SSLContext.getDefault().socketFactory.createSocket(raw, host, 443, true) as SSLSocket
            ssl.use {
                val params = it.sslParameters
                params.serverNames = listOf(SNIHostName(host))
                params.endpointIdentificationAlgorithm = "HTTPS"
                it.sslParameters = params
                it.startHandshake()
                it.getOutputStream().apply {
                    write("HEAD / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nUser-Agent: Mozilla/5.0\r\n\r\n".toByteArray())
                    flush()
                }
                val line = it.getInputStream().bufferedReader().readLine()
                if (line == null || !line.startsWith("HTTP/")) "no HTTP response" else null
            }
        } catch (e: Exception) {
            e.javaClass.simpleName + (e.message?.let { ": ${it.take(60)}" } ?: "")
        } finally {
            runCatching { relay.close() }
            pump.interrupt()
        }
    }

    private fun runRelay(relay: ServerSocket, ip: String, strategy: Strategy) {
        try {
            relay.accept().use { client ->
                Socket().use { upstream ->
                    upstream.tcpNoDelay = true
                    upstream.connect(InetSocketAddress(ip, 443), CONNECT_TIMEOUT_MS)
                    val up = upstream.getOutputStream()
                    val cin = client.getInputStream()
                    val first = readFirstRecord(cin)
                    if (first != null) writeFragmented(up, first, strategy)
                    val back = Thread { copy(upstream.getInputStream(), client.getOutputStream()) }
                        .apply { isDaemon = true; start() }
                    copy(cin, up)
                    back.join(IO_TIMEOUT_MS.toLong())
                }
            }
        } catch (_: Exception) {
            // The probing side reports the failure; the relay only has to die quietly.
        }
    }

    /** Reads one complete TLS record (5-byte header + body), or null if the stream ends first. */
    private fun readFirstRecord(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val header = ByteArray(5)
        if (!readFully(input, header)) return null
        out.write(header)
        val len = ((header[3].toInt() and 0xFF) shl 8) or (header[4].toInt() and 0xFF)
        val body = ByteArray(len)
        if (!readFully(input, body)) return null
        out.write(body)
        return out.toByteArray()
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun writeFragmented(out: OutputStream, data: ByteArray, strategy: Strategy) {
        val chunks = splitChunks(data.size, strategy.length, Random)
        var pos = 0
        for ((i, size) in chunks.withIndex()) {
            out.write(data, pos, size)
            out.flush()
            pos += size
            if (i < chunks.lastIndex && strategy.intervalMs.last > 0) {
                Thread.sleep(Random.nextLong(strategy.intervalMs.first.toLong(), strategy.intervalMs.last.toLong() + 1))
            }
        }
    }

    /** Chunk sizes summing to [total], each drawn from [range]; a single chunk when [range] is null. */
    internal fun splitChunks(total: Int, range: IntRange?, random: Random): List<Int> {
        if (range == null) return listOf(total)
        val sizes = ArrayList<Int>()
        var left = total
        while (left > 0) {
            val size = minOf(left, random.nextInt(range.first, range.last + 1))
            sizes.add(size)
            left -= size
        }
        return sizes
    }

    private fun copy(input: InputStream, out: OutputStream) {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                out.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun resolveSystem(host: String): List<String> = try {
        InetAddress.getAllByName(host).filterIsInstance<java.net.Inet4Address>().mapNotNull { it.hostAddress }
    } catch (_: Exception) {
        emptyList()
    }

    /** DNS-over-HTTPS A lookup against 1.1.1.1 by IP, so no system resolution is involved. */
    private fun resolveDoh(host: String): List<String> = try {
        val conn = URL("https://1.1.1.1/dns-query?name=$host&type=A").openConnection() as HttpsURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = IO_TIMEOUT_MS
        conn.setRequestProperty("accept", "application/dns-json")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val answers = JSONObject(body).optJSONArray("Answer")
        (0 until (answers?.length() ?: 0))
            .map { answers!!.getJSONObject(it) }
            .filter { it.optInt("type") == 1 }
            .map { it.getString("data") }
    } catch (_: Exception) {
        emptyList()
    }

    private fun tcpReachable(ip: String): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, 443), CONNECT_TIMEOUT_MS) }
        true
    } catch (_: Exception) {
        false
    }

    /** Loopback, unspecified, private and link-local answers are typical DNS sinkholes. */
    internal fun isBogon(ip: String): Boolean {
        val p = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return true
        return p[0] == 0 || p[0] == 127 || p[0] == 10 ||
            (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) ||
            (p[0] == 169 && p[1] == 254)
    }
}
