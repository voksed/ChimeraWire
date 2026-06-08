package com.carnelia.vpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.WifiOff
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
import com.carnelia.vpn.utils.AppLogger
import com.carnelia.vpn.utils.LogLevel
import com.carnelia.vpn.utils.PrefsManager

class PacketInspectorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeIndex = PrefsManager.getThemeIndex(this)
            CarheliaTheme(themeIndex = themeIndex) {
                PacketInspectorScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PacketInspectorScreen(onBack: () -> Unit) {
    val listState  = rememberLazyListState()
    var autoScroll by remember { mutableStateOf(true) }

    // AppLogger.logs is mutableStateListOf — Compose tracks it reactively
    val logs     = AppLogger.logs
    val filtered = logs.filter { entry ->
        entry.message.startsWith("Xray:") ||
        entry.message.contains("accepted", ignoreCase = true) ||
        entry.message.contains("->", ignoreCase = false) ||
        entry.message.contains("tcp", ignoreCase = true) ||
        entry.message.contains("udp", ignoreCase = true)
    }.takeLast(300)

    LaunchedEffect(filtered.size) {
        if (autoScroll && filtered.size > 1) {
            try { listState.scrollToItem(filtered.size - 1) } catch (_: Exception) {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.packet_inspector_title),
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { autoScroll = !autoScroll }) {
                        Icon(
                            if (autoScroll) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                            contentDescription = null,
                            tint = if (autoScroll) Color(0xFFFF8800) else Color(0xFF44DD66)
                        )
                    }
                    IconButton(onClick = { AppLogger.clear() }) {
                        Icon(Icons.Default.Delete, null, tint = Color(0xFF444444))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF080808))
            )
        },
        containerColor = Color(0xFF080808)
    ) { padding ->
        if (filtered.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.WifiOff,
                        null,
                        tint = Color(0xFF2A2A2A),
                        modifier = Modifier.size(52.dp)
                    )
                    Text(stringResource(R.string.packet_inspector_empty), color = Color(0xFF444444), fontSize = 13.sp)
                    Text(
                        stringResource(R.string.packet_inspector_empty_hint),
                        color = Color(0xFF333333),
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                itemsIndexed(filtered) { _, entry ->
                    val lineColor = when {
                        entry.level == LogLevel.ERROR                      -> Color(0xFFFF4444)
                        entry.message.contains("udp", ignoreCase = true)   -> Color(0xFFFFAA00)
                        entry.message.contains("tcp", ignoreCase = true)   -> Color(0xFF44AAFF)
                        entry.message.contains("->")                       -> Color(0xFF44DD66)
                        else                                               -> Color(0xFF666666)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            AppLogger.getFormattedTime(entry.timestamp),
                            color = Color(0xFF3A3A3A),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            entry.message.removePrefix("Xray: "),
                            color = lineColor,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
