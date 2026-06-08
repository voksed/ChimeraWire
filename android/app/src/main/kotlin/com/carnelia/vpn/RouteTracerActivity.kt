package com.carnelia.vpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.stringResource
import com.carnelia.vpn.ui.theme.CarheliaTheme
import com.carnelia.vpn.utils.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RouteTracerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeIndex = PrefsManager.getThemeIndex(this)
            CarheliaTheme(themeIndex = themeIndex) {
                RouteTracerScreen(onBack = { finish() })
            }
        }
    }
}

private data class TraceHop(val ttl: Int, val ip: String?, val ms: Long?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteTracerScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var targetHost by remember { mutableStateOf("8.8.8.8") }
    var hops by remember { mutableStateOf<List<TraceHop>>(emptyList()) }
    var isRunning by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun trace() {
        if (isRunning) return
        isRunning = true
        hops = emptyList()
        val host = targetHost.trim().ifBlank { "8.8.8.8" }
        scope.launch {
            withContext(Dispatchers.IO) {
                for (ttl in 1..20) {
                    if (!isActive) break
                    val start = System.currentTimeMillis()
                    try {
                        val proc = Runtime.getRuntime().exec(
                            arrayOf("ping", "-c", "1", "-t", "$ttl", "-W", "2", host)
                        )
                        val output = proc.inputStream.bufferedReader().readText()
                        proc.waitFor()
                        val elapsed = System.currentTimeMillis() - start

                        val fromMatch = Regex("From ([\\d.]+)").find(output)
                        val timeMatch = Regex("time[=<]([0-9.]+)").find(output)
                        val reached   = output.contains("64 bytes from") ||
                                        output.contains("bytes from $host")

                        val hopIp = fromMatch?.groupValues?.get(1)
                            ?: if (reached) host else null
                        val hopMs = timeMatch?.groupValues?.get(1)?.toLongOrNull()
                            ?: if (hopIp != null) elapsed else null

                        withContext(Dispatchers.Main) {
                            hops = hops + TraceHop(ttl, hopIp, hopMs)
                            if (hops.isNotEmpty()) {
                                scope.launch { listState.animateScrollToItem(hops.size - 1) }
                            }
                        }
                        if (reached) break
                    } catch (_: Exception) {
                        withContext(Dispatchers.Main) {
                            hops = hops + TraceHop(ttl, null, null)
                        }
                    }
                }
            }
            isRunning = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.route_tracer_title), fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0A0A))
            )
        },
        containerColor = Color(0xFF0A0A0A)
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = targetHost,
                    onValueChange = { targetHost = it },
                    label = { Text(stringResource(R.string.route_tracer_hint), color = Color.Gray) },
                    singleLine = true,
                    enabled = !isRunning,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFFFAA00),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )
                Button(
                    onClick = { trace() },
                    enabled = !isRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFAA00))
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.Black
                        )
                    } else {
                        Text(stringResource(R.string.route_tracer_btn), color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (hops.isEmpty() && !isRunning) {
                Text(stringResource(R.string.route_tracer_hint_empty), color = Color(0xFF444444), fontSize = 13.sp)
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(hops, key = { it.ttl }) { hop ->
                        HopRow(hop)
                    }
                }
            }
        }
    }
}

@Composable
private fun HopRow(hop: TraceHop) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "${hop.ttl}",
            color = Color(0xFF555555),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(22.dp)
        )
        Text(
            text = hop.ip ?: "* * *",
            color = if (hop.ip != null) Color.White else Color(0xFF3A3A3A),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = hop.ms?.let { "${it}ms" } ?: "",
            color = when {
                hop.ms == null -> Color.Transparent
                hop.ms < 50   -> Color(0xFF44DD66)
                hop.ms < 150  -> Color(0xFFFFAA00)
                else          -> Color(0xFFFF4444)
            },
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
