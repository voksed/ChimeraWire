package com.carnelia.vpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carnelia.vpn.ui.theme.CarheliaTheme
import com.carnelia.vpn.utils.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class TlsInspectorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeIndex = PrefsManager.getThemeIndex(this)
            CarheliaTheme(themeIndex = themeIndex) {
                TlsInspectorScreen(onBack = { finish() })
            }
        }
    }
}

private data class TlsInfo(
    val tlsVersion: String,
    val cipherSuite: String,
    val subject: String,
    val issuer: String,
    val validFrom: String,
    val validTo: String,
    val sha256: String,
    val san: String,
    val chainDepth: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TlsInspectorScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("google.com") }
    var port by remember { mutableStateOf("443") }
    var result by remember { mutableStateOf<TlsInfo?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    fun inspect() {
        val h = host.trim().ifBlank { return }
        val p = port.trim().toIntOrNull() ?: 443
        scope.launch {
            isLoading = true
            error = null
            result = null
            withContext(Dispatchers.IO) {
                try {
                    val ctx = SSLContext.getInstance("TLS")
                    ctx.init(null, null, null)
                    val sock = ctx.socketFactory.createSocket(h, p) as SSLSocket
                    sock.soTimeout = 8000
                    sock.startHandshake()
                    val session = sock.session
                    val certs   = session.peerCertificates
                    val leaf    = certs[0] as X509Certificate

                    val md = MessageDigest.getInstance("SHA-256")
                    val fp = md.digest(leaf.encoded)
                        .joinToString(":") { "%02X".format(it) }

                    val san = try {
                        leaf.subjectAlternativeNames
                            ?.filter { it[0] == 2 }
                            ?.joinToString(", ") { it[1].toString() }
                            .orEmpty()
                    } catch (_: Exception) { "" }

                    result = TlsInfo(
                        tlsVersion  = session.protocol,
                        cipherSuite = session.cipherSuite,
                        subject     = leaf.subjectX500Principal.name,
                        issuer      = leaf.issuerX500Principal.name,
                        validFrom   = sdf.format(leaf.notBefore),
                        validTo     = sdf.format(leaf.notAfter),
                        sha256      = fp,
                        san         = san,
                        chainDepth  = certs.size
                    )
                    sock.close()
                } catch (e: Exception) {
                    error = e.message ?: "Connection failed"
                }
            }
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tls_inspector_title), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.tls_host_label), color = Color.Gray) },
                    singleLine = true,
                    modifier = Modifier.weight(3f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                        focusedBorderColor = Color(0xFF00DDCC),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text(stringResource(R.string.tls_port_label), color = Color.Gray) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                        focusedBorderColor = Color(0xFF00DDCC),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )
            }

            Button(
                onClick = { inspect() },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00DDCC))
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.Black
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.tls_inspect_btn), color = Color.Black, fontWeight = FontWeight.Bold)
            }

            error?.let {
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A0A0A))
                ) {
                    Text(
                        "Error: $it",
                        color = Color(0xFFFF5555),
                        modifier = Modifier.padding(14.dp),
                        fontSize = 12.sp
                    )
                }
            }

            result?.let { r ->
                TlsRow(stringResource(R.string.tls_version),    r.tlsVersion,            Color(0xFF00DDCC))
                TlsRow(stringResource(R.string.tls_cipher),     r.cipherSuite,           MaterialTheme.colorScheme.onBackground)
                TlsRow(stringResource(R.string.tls_chain_depth), stringResource(R.string.tls_certs_suffix, r.chainDepth), Color(0xFF888888))
                TlsRow(stringResource(R.string.tls_subject),    r.subject,               MaterialTheme.colorScheme.onBackground)
                TlsRow(stringResource(R.string.tls_issuer),     r.issuer,                Color(0xFF888888))
                TlsRow(stringResource(R.string.tls_valid_from), r.validFrom,             MaterialTheme.colorScheme.onBackground)
                TlsRow(stringResource(R.string.tls_valid_until), r.validTo,              Color(0xFF44DD66))
                if (r.san.isNotBlank()) TlsRow(stringResource(R.string.tls_sans), r.san, Color(0xFF888888))
                TlsRow(stringResource(R.string.tls_sha256_fp),  r.sha256, Color(0xFF444444), mono = true)
            }
        }
    }
}

@Composable
private fun TlsRow(label: String, value: String, valueColor: Color, mono: Boolean = false) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                label,
                color = Color(0xFF555555),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                color = valueColor,
                fontSize = if (mono) 10.sp else 12.sp,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
            )
        }
    }
}
