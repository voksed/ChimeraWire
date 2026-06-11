package com.carnelia.vpn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carnelia.vpn.core.ConnectionState
import com.carnelia.vpn.core.VpnGlobalState
import com.carnelia.vpn.core.XrayCoreManager
import com.carnelia.vpn.data.ServerRepository
import com.carnelia.vpn.ui.theme.CarheliaTheme
import com.carnelia.vpn.utils.AppLogger
import com.carnelia.vpn.utils.LogLevel
import com.carnelia.vpn.utils.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class DebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeIndex = PrefsManager.getThemeIndex(this)
            CarheliaTheme(themeIndex = themeIndex) {
                DebugScreen(onBack = { finish() })
            }
        }
    }
}

// formatBytes — top-level в MainActivity.kt, тот же пакет

private fun formatUptime(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

private fun networkTransport(context: Context): String {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "NONE"
    return buildList {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELL")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETH")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
    }.ifEmpty { listOf("OTHER") }.joinToString("+")
}

private fun saveLogsToDownloads(context: Context): String? {
    return try {
        val name = "carnelia_debug_${System.currentTimeMillis()}.txt"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return null
        context.contentResolver.openOutputStream(uri)?.use {
            it.write(AppLogger.getLogsAsString().toByteArray())
        }
        name
    } catch (e: Exception) {
        AppLogger.error("DebugActivity: saveLogsToDownloads failed", e)
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vpnState by VpnGlobalState.connectionState.collectAsState()
    val stats by VpnGlobalState.stats.collectAsState()
    val lastError by VpnGlobalState.lastError.collectAsState()
    val logs = AppLogger.logs
    val listState = rememberLazyListState()

    // null = все уровни
    var levelFilter by remember { mutableStateOf<LogLevel?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }

    // Внешний IP — по кнопке, через VPN-прокси если подключены
    var externalIp by remember { mutableStateOf<String?>(null) }
    var ipChecking by remember { mutableStateOf(false) }

    val lastServer = remember { ServerRepository(context).getLastUsedServer() }
    val transport = remember(vpnState) { networkTransport(context) }

    val filteredLogs = remember(logs.size, levelFilter, searchQuery) {
        logs.asSequence()
            .filter { levelFilter == null || it.level == levelFilter }
            .filter { searchQuery.isBlank() || it.message.contains(searchQuery, ignoreCase = true) }
            .toList()
    }

    LaunchedEffect(filteredLogs.size, autoScroll) {
        if (autoScroll && filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    fun checkExternalIp() {
        if (ipChecking) return
        ipChecking = true
        externalIp = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val proxy = if (vpnState == ConnectionState.CONNECTED) {
                        java.net.Proxy(
                            java.net.Proxy.Type.HTTP,
                            java.net.InetSocketAddress("127.0.0.1", XrayCoreManager.LOCAL_HTTP_PORT)
                        )
                    } else java.net.Proxy.NO_PROXY
                    val conn = java.net.URL("http://ip-api.com/json/?fields=status,query,countryCode")
                        .openConnection(proxy) as java.net.HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    val body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    val json = JSONObject(body)
                    "${json.optString("query", "?")} ${json.optString("countryCode", "")}"
                } catch (e: Exception) {
                    "FAIL: ${e.message?.take(40)}"
                }
            }
            externalIp = result
            ipChecking = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Debug Panel", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        Text("// удержание кнопки VPN открывает панель", color = Color(0xFF555555), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("CarneliaVPN logs", AppLogger.getLogsAsString()))
                        Toast.makeText(context, "Логи скопированы", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("CP", color = Color(0xFF888888), fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = {
                        val saved = saveLogsToDownloads(context)
                        Toast.makeText(
                            context,
                            if (saved != null) "Сохранено: Downloads/$saved" else "Не удалось сохранить",
                            Toast.LENGTH_LONG
                        ).show()
                    }) {
                        Text("SV", color = Color(0xFF888888), fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "CarneliaVPN debug log")
                            putExtra(Intent.EXTRA_TEXT, AppLogger.getLogsAsString())
                        }
                        context.startActivity(Intent.createChooser(send, "Отправить логи"))
                    }) {
                        Icon(Icons.Default.Share, null, tint = Color(0xFF888888))
                    }
                    IconButton(onClick = { AppLogger.clear() }) {
                        Icon(Icons.Default.Delete, null, tint = Color(0xFFFF4444))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF050505))
            )
        },
        containerColor = Color(0xFF050505)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DebugStateCard(vpnState, if (stats.isConnected) stats.connectionTime else null)

            DebugInfoCard(
                sent = stats.bytesSent,
                received = stats.bytesReceived,
                transport = transport,
                serverLine = lastServer?.let { "${it.protocol.name} · ${it.name} · ${it.host}:${it.port}" },
                externalIp = externalIp,
                ipChecking = ipChecking,
                onCheckIp = ::checkExternalIp,
                lastError = lastError ?: stats.lastError?.name
            )

            Divider(color = Color(0xFF1A1A1A), thickness = 1.dp)

            // Фильтры по уровню + автоскролл
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                FilterChip("ALL (${logs.size})", levelFilter == null) { levelFilter = null }
                FilterChip("I (${logs.count { it.level == LogLevel.INFO }})", levelFilter == LogLevel.INFO) { levelFilter = LogLevel.INFO }
                FilterChip("D (${logs.count { it.level == LogLevel.DEBUG }})", levelFilter == LogLevel.DEBUG) { levelFilter = LogLevel.DEBUG }
                FilterChip("E (${logs.count { it.level == LogLevel.ERROR }})", levelFilter == LogLevel.ERROR, activeColor = Color(0xFFFF4444)) { levelFilter = LogLevel.ERROR }
                Spacer(Modifier.weight(1f))
                FilterChip("AUTO", autoScroll, activeColor = Color(0xFF44DD66)) { autoScroll = !autoScroll }
            }

            // Поиск по логам
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F0F0F), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                if (searchQuery.isEmpty()) {
                    Text("grep...", color = Color(0xFF444444), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color(0xFFCCCCCC),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = SolidColor(Color(0xFF4488FF)),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(filteredLogs) { entry ->
                    val color = when (entry.level) {
                        LogLevel.ERROR -> Color(0xFFFF4444)
                        LogLevel.DEBUG -> Color(0xFF888888)
                        LogLevel.INFO  -> Color(0xFFCCCCCC)
                    }
                    val tag = when (entry.level) {
                        LogLevel.ERROR -> "E"
                        LogLevel.DEBUG -> "D"
                        LogLevel.INFO  -> "I"
                    }
                    Text(
                        "${AppLogger.getFormattedTime(entry.timestamp)} [$tag] ${entry.message}",
                        color = color,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, active: Boolean, activeColor: Color = Color(0xFF4488FF), onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (active) activeColor.copy(alpha = 0.18f) else Color(0xFF111111),
                RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            label,
            color = if (active) activeColor else Color(0xFF666666),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun DebugStateCard(vpnState: ConnectionState, uptimeMs: Long?) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F0F)),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DebugChip(
                label = "VPN",
                value = vpnState.name,
                valueColor = when (vpnState) {
                    ConnectionState.CONNECTED    -> Color(0xFF44DD66)
                    ConnectionState.CONNECTING   -> Color(0xFFFFAA00)
                    ConnectionState.DISCONNECTED -> Color(0xFF666666)
                    else                         -> Color(0xFFFF4444)
                }
            )
            DebugChip("SOCKS", ":${XrayCoreManager.LOCAL_PORT}",      Color(0xFF4488FF))
            DebugChip("HTTP",  ":${XrayCoreManager.LOCAL_HTTP_PORT}", Color(0xFF4488FF))
            if (uptimeMs != null) {
                DebugChip("UPTIME", formatUptime(uptimeMs), Color(0xFF44DD66))
            }
        }
    }
}

@Composable
private fun DebugInfoCard(
    sent: Long,
    received: Long,
    transport: String,
    serverLine: String?,
    externalIp: String?,
    ipChecking: Boolean,
    onCheckIp: () -> Unit,
    lastError: String?
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F0F)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DebugChip("APP", "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", Color(0xFFCCCCCC))
                DebugChip("API", "${android.os.Build.VERSION.SDK_INT}", Color(0xFFCCCCCC))
                DebugChip("NET", transport, Color(0xFFCCCCCC))
                DebugChip("TX", formatBytes(sent), Color(0xFF4488FF))
                DebugChip("RX", formatBytes(received), Color(0xFF44DD66))
            }

            if (serverLine != null) {
                Text(
                    "SRV: $serverLine",
                    color = Color(0xFF888888),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 13.sp
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(if (ipChecking) "CHECKING..." else "CHECK IP", false, onClick = onCheckIp)
                if (externalIp != null) {
                    Text(
                        externalIp,
                        color = if (externalIp.startsWith("FAIL")) Color(0xFFFF4444) else Color(0xFF44DD66),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                    color = Color(0xFF444444),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            if (lastError != null) {
                Text(
                    "ERR: $lastError",
                    color = Color(0xFFFF4444),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 13.sp
                )
            }
        }
    }
}

@Composable
private fun DebugChip(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color(0xFF444444), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Text(value, color = valueColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}
