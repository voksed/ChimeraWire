package com.carnelia.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carnelia.vpn.core.ConnectionState
import com.carnelia.vpn.core.VpnGlobalState
import com.carnelia.vpn.ui.theme.CarheliaTheme
import com.carnelia.vpn.utils.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class FingerprintCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeIndex = PrefsManager.getThemeIndex(this)
            CarheliaTheme(themeIndex = themeIndex) {
                FingerprintCheckScreen(onBack = { finish() })
            }
        }
    }
}

private data class FingerprintResult(
    val ip: String,
    val country: String,
    val regionName: String,
    val city: String,
    val isp: String,
    val org: String,
    val asn: String,
    val timezone: String,
    val proxy: Boolean,
    val hosting: Boolean,
    val mobile: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FingerprintCheckScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vpnState by VpnGlobalState.connectionState.collectAsState()
    val vpnConnected = vpnState == ConnectionState.CONNECTED
    // VpnGlobalState видит только саму Carnelia — если активен ДРУГОЙ VPN-клиент,
    // система всё равно маршрутизирует трафик через него, но баннер этого не показывал.
    val systemVpnActive = remember(vpnState) { isAnyVpnActive(context) }
    val protectedByAny = vpnConnected || systemVpnActive

    var result by remember { mutableStateOf<FingerprintResult?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun runCheck() {
        scope.launch {
            isLoading = true
            error = null
            result = null
            withContext(Dispatchers.IO) {
                try {
                    // When VPN is connected, route through the local Xray HTTP proxy
                    // so the check reflects the VPN IP, not the real IP.
                    val proxy = if (vpnConnected) {
                        java.net.Proxy(
                            java.net.Proxy.Type.HTTP,
                            java.net.InetSocketAddress("127.0.0.1", com.carnelia.vpn.core.XrayCoreManager.LOCAL_HTTP_PORT)
                        )
                    } else {
                        java.net.Proxy.NO_PROXY
                    }
                    val client = OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .proxy(proxy)
                        .build()
                    // ip-api.com free tier only supports HTTP (not HTTPS)
                    val req = Request.Builder()
                        .url("http://ip-api.com/json/?fields=status,query,country,regionName,city,isp,org,as,timezone,proxy,hosting,mobile")
                        .build()
                    val resp = client.newCall(req).execute()
                    val body = resp.body?.string() ?: ""
                    val json = JSONObject(body)
                    result = FingerprintResult(
                        ip         = json.optString("query", "unknown"),
                        country    = json.optString("country", "unknown"),
                        regionName = json.optString("regionName", ""),
                        city       = json.optString("city", ""),
                        isp        = json.optString("isp", ""),
                        org        = json.optString("org", ""),
                        asn        = json.optString("as", ""),
                        timezone   = json.optString("timezone", ""),
                        proxy      = json.optBoolean("proxy", false),
                        hosting    = json.optBoolean("hosting", false),
                        mobile     = json.optBoolean("mobile", false)
                    )
                } catch (e: Exception) {
                    error = e.message ?: "Unknown error"
                }
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { runCheck() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.fingerprint_title), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    IconButton(onClick = { runCheck() }, enabled = !isLoading) {
                        Icon(Icons.Default.Refresh, null, tint = if (isLoading) Color.Gray else Color(0xFF00AAFF))
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (protectedByAny) Color(0xFF0A2E0A) else Color(0xFF1A0A0A)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        vpnConnected -> stringResource(R.string.fingerprint_via_vpn)
                        systemVpnActive -> stringResource(R.string.fingerprint_other_vpn)
                        else -> stringResource(R.string.fingerprint_no_vpn)
                    },
                    color = if (protectedByAny) Color(0xFF44DD66) else Color(0xFFFF5555),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }

            if (isLoading) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFF00AAFF))
                }
            } else if (error != null) {
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A0A0A))
                ) {
                    Text(
                        "Error: $error",
                        color = Color(0xFFFF5555),
                        modifier = Modifier.padding(16.dp),
                        fontSize = 13.sp
                    )
                }
            } else result?.let { r ->
                FpInfoCard(stringResource(R.string.fingerprint_ip), r.ip, Color(0xFF00AAFF))
                FpInfoCard(
                    stringResource(R.string.fingerprint_location),
                    listOf(r.city, r.regionName, r.country).filter { it.isNotBlank() }.joinToString(", "),
                    Color(0xFF44DD66)
                )
                FpInfoCard(stringResource(R.string.fingerprint_isp), r.isp, MaterialTheme.colorScheme.onBackground)
                FpInfoCard(stringResource(R.string.fingerprint_org), r.org, MaterialTheme.colorScheme.onBackground)
                FpInfoCard(stringResource(R.string.fingerprint_asn), r.asn, MaterialTheme.colorScheme.onBackground)
                FpInfoCard(stringResource(R.string.fingerprint_timezone), r.timezone, MaterialTheme.colorScheme.onBackground)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FpFlagChip(stringResource(R.string.fingerprint_flag_proxy),   r.proxy,   if (r.proxy)   Color(0xFFFF4444) else Color(0xFF44DD66))
                    FpFlagChip(stringResource(R.string.fingerprint_flag_hosting), r.hosting, if (r.hosting) Color(0xFFFFAA00) else Color(0xFF44DD66))
                    FpFlagChip(stringResource(R.string.fingerprint_flag_mobile),  r.mobile,  Color(0xFF888888))
                }
            }
        }
    }
}

private fun isAnyVpnActive(context: Context): Boolean = try {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
} catch (_: Exception) { false }

@Composable
private fun FpInfoCard(label: String, value: String, valueColor: Color) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(label, color = Color(0xFF555555), fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            Text(value.ifBlank { "—" }, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun FpFlagChip(label: String, active: Boolean, color: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(color, RoundedCornerShape(3.dp))
            )
            Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}
