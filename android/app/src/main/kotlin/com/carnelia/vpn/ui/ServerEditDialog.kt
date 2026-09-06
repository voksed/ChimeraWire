package com.carnelia.vpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carnelia.vpn.core.VpnServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Editor for proxy-server fields (VLESS / VMess / Trojan / Shadowsocks): host, port,
 * SNI and — for REALITY — public key, short id and uTLS fingerprint. Also runs an
 * availability check that TLS-probes the endpoint, reports latency and reads the
 * certificate the server actually presents so a stale SNI can be corrected in one tap.
 */
@Composable
fun ServerEditDialog(
    server: VpnServerConfig,
    accentColor: Color,
    onSave: (VpnServerConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val isReality = server.config["security"] == "reality"
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf(server.host) }
    var port by remember { mutableStateOf(server.port.toString()) }
    var sni by remember { mutableStateOf(server.config["sni"] ?: server.config["host"] ?: "") }
    var pbk by remember { mutableStateOf(server.config["pbk"] ?: server.config["publicKey"] ?: "") }
    var sid by remember { mutableStateOf(server.config["sid"] ?: "") }
    var fp by remember { mutableStateOf(server.config["fp"] ?: "") }

    var checking by remember { mutableStateOf(false) }
    var checkText by remember { mutableStateOf<String?>(null) }
    var checkColor by remember { mutableStateOf(Color.Gray) }
    var suggestedSni by remember { mutableStateOf<String?>(null) }

    val scheme = MaterialTheme.colorScheme
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = scheme.onSurface,
        unfocusedTextColor = scheme.onSurface,
        focusedBorderColor = accentColor,
        unfocusedBorderColor = scheme.outline,
        focusedLabelColor = accentColor,
        unfocusedLabelColor = scheme.onSurfaceVariant
    )

    @Composable
    fun Field(value: String, onChange: (String) -> Unit, label: String, numeric: Boolean = false) {
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(if (numeric) it.filter(Char::isDigit) else it) },
            label = { Text(label) },
            singleLine = true,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )
    }

    fun runCheck() {
        if (checking) return
        checking = true
        checkText = "Проверяю…"
        checkColor = Color.Gray
        suggestedSni = null
        val h = host.trim()
        val p = port.toIntOrNull() ?: 443
        val currentSni = sni.trim()
        scope.launch {
            val result = withContext(Dispatchers.IO) { probeServer(h, p, currentSni) }
            checking = false
            checkText = result.message
            checkColor = when (result.severity) {
                CheckSeverity.OK -> Color(0xFF44DD66)
                CheckSeverity.WARN -> Color(0xFFFFAA00)
                CheckSeverity.ERROR -> Color(0xFFFF4444)
            }
            // If the presented cert CN differs from our SNI, offer to apply it.
            val cn = result.certCn
            if (cn != null && cn.isNotBlank() && !cn.equals(currentSni, ignoreCase = true)) {
                suggestedSni = cn
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Параметры сервера", color = scheme.onSurface, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Field(host, { host = it }, "Адрес (host)")
                Field(port, { port = it }, "Порт", numeric = true)
                Field(sni, { sni = it }, "SNI / serverName")

                if (isReality) {
                    Text("REALITY", color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    Field(pbk, { pbk = it }, "Public key (pbk)")
                    Field(sid, { sid = it }, "Short ID (sid)")
                    Field(fp, { fp = it }, "Fingerprint (fp) — chrome / ios / …")
                }

                Button(
                    onClick = { runCheck() },
                    enabled = !checking,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    if (checking) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Проверить доступность")
                }

                checkText?.let { Text(it, color = checkColor, fontSize = 12.sp) }

                suggestedSni?.let { cn ->
                    OutlinedButton(
                        onClick = { sni = cn; suggestedSni = null; checkText = "SNI изменён на $cn — сохрани и переподключись"; checkColor = Color(0xFF44DD66) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Применить SNI: $cn", color = accentColor, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val newPort = port.toIntOrNull()?.coerceIn(1, 65535) ?: server.port
                val cfg = server.config.toMutableMap()
                fun put(key: String, value: String) {
                    val v = value.trim()
                    if (v.isBlank()) cfg.remove(key) else cfg[key] = v
                }
                put("sni", sni)
                if (isReality) {
                    put("pbk", pbk); put("sid", sid); put("fp", fp)
                }
                onSave(server.copy(host = host.trim().ifBlank { server.host }, port = newPort, config = cfg))
            }) { Text("Сохранить", color = accentColor) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = scheme.onSurfaceVariant) }
        },
        containerColor = scheme.surface
    )
}

private enum class CheckSeverity { OK, WARN, ERROR }
private class ProbeResult(val severity: CheckSeverity, val message: String, val certCn: String?)

/**
 * TCP-connects and completes a TLS handshake (trust-all, verification disabled) to read
 * the certificate the server actually presents. For a REALITY endpoint this reveals the
 * masqueraded destination, so a wrong SNI in the saved key can be detected and fixed.
 */
private fun probeServer(host: String, port: Int, sni: String): ProbeResult {
    if (host.isBlank()) return ProbeResult(CheckSeverity.ERROR, "Пустой адрес сервера", null)
    // 1) TCP reachability + latency
    val start = System.currentTimeMillis()
    try {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 6000) }
    } catch (e: Exception) {
        return ProbeResult(CheckSeverity.ERROR, "✗ Сервер недоступен ($host:$port): ${e.message}", null)
    }
    val tcpMs = System.currentTimeMillis() - start

    // 2) TLS probe (trust-all) to read the presented certificate
    return try {
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, java.security.SecureRandom())
        val socket = ctx.socketFactory.createSocket() as javax.net.ssl.SSLSocket
        socket.connect(java.net.InetSocketAddress(host, port), 6000)
        socket.soTimeout = 6000
        if (sni.isNotBlank()) {
            val params = socket.sslParameters
            params.serverNames = listOf(javax.net.ssl.SNIHostName(sni))
            socket.sslParameters = params
        }
        socket.startHandshake()
        val cert = socket.session.peerCertificates.firstOrNull() as? java.security.cert.X509Certificate
        socket.close()
        val cn = cert?.subjectX500Principal?.name
            ?.split(",")
            ?.firstOrNull { it.trim().startsWith("CN=", ignoreCase = true) }
            ?.substringAfter("=")?.trim()
        if (cn == null) {
            ProbeResult(CheckSeverity.WARN, "✓ TCP $tcpMs мс, но сертификат не прочитан", null)
        } else if (sni.isNotBlank() && cn.equals(sni, ignoreCase = true)) {
            ProbeResult(CheckSeverity.OK, "✓ Доступен ($tcpMs мс). SNI совпадает с сертификатом ($cn)", cn)
        } else {
            ProbeResult(CheckSeverity.WARN, "⚠ TCP $tcpMs мс. Сервер отдаёт сертификат «$cn», а SNI = «$sni» — не совпадает. Это ломает REALITY.", cn)
        }
    } catch (e: Exception) {
        ProbeResult(CheckSeverity.WARN, "✓ TCP $tcpMs мс, но TLS-проба не удалась: ${e.message}", null)
    }
}
