package com.carnelia.vpn

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carnelia.vpn.ui.GroupRow
import com.carnelia.vpn.ui.GroupSection
import com.carnelia.vpn.ui.TileTone
import com.carnelia.vpn.ui.theme.ChimeraTheme
import com.carnelia.vpn.utils.PrefsManager

class ToolsHubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeIndex = PrefsManager.getThemeIndex(this)
            ChimeraTheme(themeIndex = themeIndex) {
                ToolsHubScreen(
                    onBack = { finish() },
                    onTool = { cls -> startActivity(Intent(this, cls)) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolsHubScreen(onBack: () -> Unit, onTool: (Class<*>) -> Unit) {
    val privacy = listOf(
        GroupRow(Icons.Default.Https,       stringResource(R.string.xhub_doh_name),              stringResource(R.string.xhub_doh_desc),             tone = TileTone.PRIMARY) { onTool(StandaloneToolsActivity::class.java) },
        GroupRow(Icons.Default.Policy,       stringResource(R.string.tool_leak_test_name),        stringResource(R.string.tool_leak_test_desc),       tone = TileTone.PRIMARY) { onTool(LeakTestActivity::class.java) },
        GroupRow(Icons.Default.Fingerprint,  stringResource(R.string.tool_fingerprint_name),      stringResource(R.string.tool_fingerprint_desc),     tone = TileTone.PRIMARY) { onTool(FingerprintCheckActivity::class.java) },
        GroupRow(Icons.Default.Dns,          stringResource(R.string.tool_dns_audit_name),        stringResource(R.string.tool_dns_audit_desc),       tone = TileTone.PRIMARY) { onTool(DnsAuditActivity::class.java) },
    )
    val analysis = listOf(
        GroupRow(Icons.Default.Route,        stringResource(R.string.tool_route_tracer_name),     stringResource(R.string.tool_route_tracer_desc),    tone = TileTone.TERTIARY) { onTool(RouteTracerActivity::class.java) },
        GroupRow(Icons.Default.Insights,     stringResource(R.string.tool_traffic_graph_name),    stringResource(R.string.tool_traffic_graph_desc),   tone = TileTone.TERTIARY) { onTool(TrafficGraphActivity::class.java) },
        GroupRow(Icons.Default.VerifiedUser, stringResource(R.string.tool_tls_inspector_name),    stringResource(R.string.tool_tls_inspector_desc),   tone = TileTone.TERTIARY) { onTool(TlsInspectorActivity::class.java) },
        GroupRow(Icons.AutoMirrored.Filled.ManageSearch, stringResource(R.string.tool_packet_inspector_name), stringResource(R.string.tool_packet_inspector_desc), tone = TileTone.TERTIARY) { onTool(PacketInspectorActivity::class.java) },
        GroupRow(Icons.Default.SettingsEthernet, stringResource(R.string.tool_port_scan_name),    stringResource(R.string.tool_port_scan_desc),       tone = TileTone.TERTIARY) { onTool(PortScannerActivity::class.java) },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                stringResource(R.string.xhub_tools_title),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 32.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.padding(start = 12.dp, top = 12.dp)
            )
            Text(
                stringResource(R.string.xhub_tools_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 24.dp)
            )
            GroupSection(stringResource(R.string.xhub_group_privacy), privacy, tileSize = 48.dp)
            Spacer(Modifier.height(24.dp))
            GroupSection(stringResource(R.string.xhub_group_analysis), analysis, tileSize = 48.dp)
        }
    }
}
