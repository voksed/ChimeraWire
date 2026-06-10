package com.carnelia.vpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.carnelia.vpn.core.VpnGlobalState
import com.carnelia.vpn.ui.theme.CarheliaTheme
import com.carnelia.vpn.utils.PrefsManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class TrafficGraphActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeIndex = PrefsManager.getThemeIndex(this)
            CarheliaTheme(themeIndex = themeIndex) {
                TrafficGraphScreen(onBack = { finish() })
            }
        }
    }
}

private const val GRAPH_HISTORY = 60

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrafficGraphScreen(onBack: () -> Unit) {
    val downHistory = remember { mutableStateListOf<Long>() }
    val upHistory   = remember { mutableStateListOf<Long>() }
    var currentDown by remember { mutableStateOf(0L) }
    var currentUp   by remember { mutableStateOf(0L) }
    var totalDown   by remember { mutableStateOf(0L) }
    var totalUp     by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        var prev = VpnGlobalState.stats.value
        while (isActive) {
            delay(2000)
            val cur = VpnGlobalState.stats.value
            val downBps = maxOf(0L, cur.bytesReceived - prev.bytesReceived)
            val upBps   = maxOf(0L, cur.bytesSent    - prev.bytesSent)
            // Convert 2-second delta to per-second rate for display
            currentDown = downBps / 2
            currentUp   = upBps / 2
            totalDown   = cur.bytesReceived
            totalUp     = cur.bytesSent
            downHistory.add(downBps / 2)
            upHistory.add(upBps / 2)
            if (downHistory.size > GRAPH_HISTORY) downHistory.removeAt(0)
            if (upHistory.size   > GRAPH_HISTORY) upHistory.removeAt(0)
            prev = cur
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.traffic_graph_title), fontWeight = FontWeight.Bold, color = Color.White) },
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
            // Live speed row
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SpeedStat(stringResource(R.string.traffic_graph_download), currentDown, Color(0xFF44DD66))
                SpeedStat(stringResource(R.string.traffic_graph_upload),   currentUp,   Color(0xFFFFAA00))
            }

            // Graph
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0D0D)),
                modifier = Modifier.fillMaxWidth().height(220.dp)
            ) {
                SpeedGraph(
                    downHistory = downHistory.toList(),
                    upHistory   = upHistory.toList(),
                    modifier    = Modifier.fillMaxSize().padding(16.dp)
                )
            }

            Text(
                stringResource(R.string.traffic_graph_legend),
                color = Color(0xFF3A3A3A),
                fontSize = 11.sp
            )

            // Total session stats
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TotalStat(stringResource(R.string.traffic_graph_total_down), totalDown, Color(0xFF44DD66))
                TotalStat(stringResource(R.string.traffic_graph_total_up),   totalUp,   Color(0xFFFFAA00))
            }
        }
    }
}

@Composable
private fun SpeedStat(label: String, bps: Long, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            formatBps(bps),
            color = color,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Text(label, color = Color(0xFF666666), fontSize = 11.sp)
    }
}

@Composable
private fun TotalStat(label: String, bytes: Long, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            formatTrafficBytes(bytes),
            color = color.copy(alpha = 0.7f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Text(label, color = Color(0xFF555555), fontSize = 10.sp)
    }
}

@Composable
private fun SpeedGraph(
    downHistory: List<Long>,
    upHistory: List<Long>,
    modifier: Modifier
) {
    val downColor = Color(0xFF44DD66)
    val upColor   = Color(0xFFFFAA00)
    val gridColor = Color(0xFF1A1A1A)

    Canvas(modifier = modifier) {
        if (downHistory.isEmpty() && upHistory.isEmpty()) return@Canvas

        val w = size.width
        val h = size.height
        val all = downHistory + upHistory
        val maxVal = maxOf(all.maxOrNull() ?: 0L, 1L).toFloat()

        // Horizontal grid lines
        repeat(4) { i ->
            val y = h * (i + 1) / 4f
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }

        fun List<Long>.toLinePath(): Path {
            val path = Path()
            if (isEmpty()) return path
            // Fixed step based on full history width so at 60 points graph spans 0..w
            val step = w / (GRAPH_HISTORY - 1).toFloat()
            val startX = w - step * (size - 1)
            forEachIndexed { idx, v ->
                val x = startX + idx * step
                val y = h - (v.toFloat() / maxVal) * h * 0.95f
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            return path
        }

        drawPath(downHistory.toLinePath(), downColor, style = Stroke(width = 2.5f))
        drawPath(upHistory.toLinePath(),   upColor,   style = Stroke(width = 2.5f))
    }
}

private fun formatBps(bps: Long): String = when {
    bps < 1024        -> "$bps B/s"
    bps < 1024 * 1024 -> "${bps / 1024} KB/s"
    else              -> "%.1f MB/s".format(bps / (1024.0 * 1024.0))
}

private fun formatTrafficBytes(bytes: Long): String = when {
    bytes < 1024        -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else                -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
