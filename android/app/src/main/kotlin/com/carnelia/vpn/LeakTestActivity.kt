package com.carnelia.vpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carnelia.vpn.core.ConnectionState
import com.carnelia.vpn.core.VpnGlobalState
import com.carnelia.vpn.ui.theme.CarheliaTheme
import com.carnelia.vpn.utils.LeakTestManager
import com.carnelia.vpn.utils.PrefsManager
import kotlinx.coroutines.launch

class LeakTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeIndex = PrefsManager.getThemeIndex(this)
            CarheliaTheme(themeIndex = themeIndex) {
                LeakTestScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeakTestScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val vpnState by VpnGlobalState.connectionState.collectAsState()
    val vpnConnected = vpnState == ConnectionState.CONNECTED
    var result by remember { mutableStateOf<LeakTestManager.LeakResult?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    fun runTest() {
        scope.launch {
            isLoading = true
            result = null
            result = LeakTestManager.runFullTest(vpnConnected)
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { runTest() }
    LaunchedEffect(vpnConnected) { if (result != null) runTest() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.leak_test_title), fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { runTest() }, enabled = !isLoading) {
                        Icon(Icons.Default.Refresh, null, tint = if (isLoading) Color.Gray else Color(0xFFFF1744))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0A0A))
            )
        },
        containerColor = Color(0xFF0A0A0A)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator(color = Color(0xFFFF1744))
                        Text(stringResource(R.string.leak_test_checking), color = Color.Gray, fontSize = 14.sp)
                    }
                }
            } else {
                val r = result
                if (r == null) {
                    Text(stringResource(R.string.leak_test_no_data), color = Color.Gray)
                } else {
                    // Overall status card
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (vpnConnected) Color(0xFF0A2E0A) else Color(0xFF2E0A0A)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(48.dp).background(
                                    if (vpnConnected) Color(0xFF00CC44) else Color(0xFFFF1744),
                                    CircleShape
                                ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (vpnConnected) Icons.Default.Check else Icons.Default.Close,
                                    null,
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Column {
                                Text(
                                    if (vpnConnected) stringResource(R.string.leak_test_protected) else stringResource(R.string.leak_test_not_connected),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                                Text(
                                    if (vpnConnected) stringResource(R.string.leak_test_protected_desc) else stringResource(R.string.leak_test_not_connected_desc),
                                    color = Color.Gray,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    // IP card
                    LeakInfoCard(
                        title = stringResource(R.string.leak_test_external_ip),
                        value = r.externalIp ?: stringResource(R.string.leak_test_unknown),
                        subtitle = listOfNotNull(r.ipOrg?.takeIf { it.isNotBlank() }, r.ipCountry?.takeIf { it.isNotBlank() }).joinToString(" · "),
                        isOk = vpnConnected
                    )

                    // DNS card
                    LeakInfoCard(
                        title = stringResource(R.string.leak_test_dns_servers),
                        value = if (r.dnsServers.isEmpty()) stringResource(R.string.leak_test_dns_not_found) else r.dnsServers.joinToString("\n"),
                        subtitle = if (r.dnsServers.isEmpty()) "" else stringResource(R.string.leak_test_servers_found, r.dnsServers.size),
                        isOk = r.dnsServers.isNotEmpty()
                    )

                    // Error
                    r.errorMsg?.let { err ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1A00))
                        ) {
                            Text(
                                stringResource(R.string.leak_test_error, err),
                                color = Color(0xFFFFAA00),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    Text(
                        stringResource(R.string.leak_test_disclaimer),
                        color = Color(0xFF555555),
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun LeakInfoCard(title: String, value: String, subtitle: String, isOk: Boolean) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier.size(8.dp).background(
                        if (isOk) Color(0xFF00CC66) else Color(0xFFFF4444),
                        CircleShape
                    )
                )
                Text(title, color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Color(0xFF888888), fontSize = 11.sp)
            }
        }
    }
}
