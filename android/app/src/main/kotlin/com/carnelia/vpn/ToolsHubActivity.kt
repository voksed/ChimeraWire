package com.carnelia.vpn

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carnelia.vpn.ui.theme.CarheliaTheme
import com.carnelia.vpn.utils.PrefsManager

class ToolsHubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeIndex = PrefsManager.getThemeIndex(this)
            CarheliaTheme(themeIndex = themeIndex) {
                ToolsHubScreen(
                    onBack = { finish() },
                    onTool = { cls -> startActivity(Intent(this, cls)) }
                )
            }
        }
    }
}

private data class ToolEntry(
    val icon: ImageVector,
    val tint: Color,
    val title: String,
    val desc: String,
    val cls: Class<*>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolsHubScreen(onBack: () -> Unit, onTool: (Class<*>) -> Unit) {
    val tools = listOf(
        ToolEntry(Icons.Default.Security,     Color(0xFFFF1744), stringResource(R.string.tool_leak_test_name),       stringResource(R.string.tool_leak_test_desc),       LeakTestActivity::class.java),
        ToolEntry(Icons.Default.Fingerprint,  Color(0xFF00AAFF), stringResource(R.string.tool_fingerprint_name),    stringResource(R.string.tool_fingerprint_desc),    FingerprintCheckActivity::class.java),
        ToolEntry(Icons.Default.Dns,          Color(0xFF44DD66), stringResource(R.string.tool_dns_audit_name),      stringResource(R.string.tool_dns_audit_desc),      DnsAuditActivity::class.java),
        ToolEntry(Icons.Default.Router,       Color(0xFFFFAA00), stringResource(R.string.tool_route_tracer_name),   stringResource(R.string.tool_route_tracer_desc),   RouteTracerActivity::class.java),
        ToolEntry(Icons.Default.ShowChart,    Color(0xFFCC44FF), stringResource(R.string.tool_traffic_graph_name),  stringResource(R.string.tool_traffic_graph_desc),  TrafficGraphActivity::class.java),
        ToolEntry(Icons.Default.Lock,         Color(0xFF00DDCC), stringResource(R.string.tool_tls_inspector_name),  stringResource(R.string.tool_tls_inspector_desc),  TlsInspectorActivity::class.java),
        ToolEntry(Icons.Default.ManageSearch, Color(0xFFFF8800), stringResource(R.string.tool_packet_inspector_name), stringResource(R.string.tool_packet_inspector_desc), PacketInspectorActivity::class.java),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Инструменты",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = 1.sp
                    )
                },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(tools) { tool ->
                HubCard(tool = tool, onClick = { onTool(tool.cls) })
            }
        }
    }
}

@Composable
private fun HubCard(tool: ToolEntry, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = tool.tint.copy(alpha = 0.15f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(tool.icon, null, tint = tool.tint, modifier = Modifier.size(26.dp))
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(tool.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(tool.desc, color = Color(0xFF777777), fontSize = 12.sp)
            }
            Icon(Icons.Default.KeyboardArrowRight, null, tint = Color(0xFF444444))
        }
    }
}
