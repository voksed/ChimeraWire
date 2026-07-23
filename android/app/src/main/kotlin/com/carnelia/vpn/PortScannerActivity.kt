package com.carnelia.vpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carnelia.vpn.ui.theme.CarheliaTheme
import com.carnelia.vpn.utils.NetworkUtils
import com.carnelia.vpn.utils.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class PortScannerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeIndex = PrefsManager.getThemeIndex(this)
            CarheliaTheme(themeIndex = themeIndex) {
                PortScannerScreen(onBack = { finish() })
            }
        }
    }
}

private data class PortResult(val port: Int, val service: String, val ms: Long)

private val COMMON_PORTS = linkedMapOf(
    21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP", 53 to "DNS",
    80 to "HTTP", 110 to "POP3", 143 to "IMAP", 443 to "HTTPS", 445 to "SMB",
    993 to "IMAPS", 995 to "POP3S", 1723 to "PPTP", 3306 to "MySQL",
    3389 to "RDP", 5432 to "PostgreSQL", 5900 to "VNC", 8080 to "HTTP-alt", 8443 to "HTTPS-alt"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortScannerScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var targetHost by remember { mutableStateOf("") }
    var customRange by remember { mutableStateOf("") }
    var useCustomRange by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<PortResult>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0 to 0) } // done to total

    fun scan() {
        val host = targetHost.trim()
        if (host.isBlank() || isScanning) return
        isScanning = true
        results = emptyList()

        val ports: List<Pair<Int, String>> = if (useCustomRange) {
            val parts = customRange.trim().split("-").mapNotNull { it.trim().toIntOrNull() }
            val range = when {
                parts.size == 2 -> (parts[0].coerceIn(1, 65535))..(parts[1].coerceIn(1, 65535))
                parts.size == 1 -> parts[0]..parts[0]
                else -> 1..1024
            }
            range.toList().take(4096).map { it to (COMMON_PORTS[it] ?: "?") }
        } else {
            COMMON_PORTS.entries.map { it.key to it.value }
        }
        progress = 0 to ports.size

        scope.launch {
            withContext(Dispatchers.IO) {
                val semaphore = Semaphore(48)
                ports.map { (port, service) ->
                    async {
                        semaphore.withPermit {
                            val ms = NetworkUtils.pingServer(host, port)
                            withContext(Dispatchers.Main) { progress = (progress.first + 1) to progress.second }
                            if (ms >= 0) {
                                withContext(Dispatchers.Main) {
                                    results = (results + PortResult(port, service, ms)).sortedBy { it.port }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
            isScanning = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Сканер портов", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground) },
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
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = targetHost,
                onValueChange = { targetHost = it },
                label = { Text("Хост или IP", color = Color.Gray) },
                singleLine = true,
                enabled = !isScanning,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                    focusedBorderColor = Color(0xFF00AAFF),
                    unfocusedBorderColor = Color(0xFF444444)
                )
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !useCustomRange,
                    onClick = { useCustomRange = false },
                    label = { Text("Частые порты") },
                    enabled = !isScanning
                )
                FilterChip(
                    selected = useCustomRange,
                    onClick = { useCustomRange = true },
                    label = { Text("Диапазон") },
                    enabled = !isScanning
                )
            }

            if (useCustomRange) {
                OutlinedTextField(
                    value = customRange,
                    onValueChange = { customRange = it },
                    label = { Text("Например: 1-1024", color = Color.Gray) },
                    singleLine = true,
                    enabled = !isScanning,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                        focusedBorderColor = Color(0xFF00AAFF),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )
            }

            Button(
                onClick = { scan() },
                enabled = !isScanning && targetHost.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00AAFF))
            ) {
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text("Сканировать", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            if (isScanning) {
                LinearProgressIndicator(
                    progress = { if (progress.second > 0) progress.first / progress.second.toFloat() else 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF00AAFF)
                )
                Text("${progress.first}/${progress.second}", fontSize = 12.sp, color = Color(0xFF888888), fontFamily = FontFamily.Monospace)
            }

            if (results.isEmpty() && !isScanning) {
                Text("Открытых портов пока не найдено", color = Color(0xFF444444), fontSize = 13.sp)
            } else {
                Text("Открыто: ${results.size}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(results, key = { it.port }) { r ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("${r.port}", color = Color(0xFF44DD66), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Text(r.service, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Text("${r.ms}ms", color = Color(0xFF888888), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
