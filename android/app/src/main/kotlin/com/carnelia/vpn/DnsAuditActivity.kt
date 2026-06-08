package com.carnelia.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carnelia.vpn.ui.theme.CarheliaTheme
import com.carnelia.vpn.utils.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

class DnsAuditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeIndex = PrefsManager.getThemeIndex(this)
            CarheliaTheme(themeIndex = themeIndex) {
                DnsAuditScreen(context = this, onBack = { finish() })
            }
        }
    }
}

private data class DnsEntry(
    val domain: String,
    val ips: List<String> = emptyList(),
    val ms: Long = 0,
    val done: Boolean = false,
    val failed: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsAuditScreen(context: Context, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val domains = listOf(
        "google.com", "youtube.com", "twitter.com", "facebook.com",
        "cloudflare.com", "github.com", "reddit.com", "amazon.com"
    )

    var dnsServers by remember { mutableStateOf<List<String>>(emptyList()) }
    var entries by remember { mutableStateOf(domains.map { DnsEntry(it) }) }
    var isRunning by remember { mutableStateOf(false) }

    fun fetchDnsServers(): List<String> {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return emptyList()
            val props = cm.getLinkProperties(net) ?: return emptyList()
            props.dnsServers.mapNotNull { it.hostAddress }
        } catch (_: Exception) { emptyList() }
    }

    fun runAudit() {
        if (isRunning) return
        isRunning = true
        entries = domains.map { DnsEntry(it) }
        scope.launch {
            dnsServers = withContext(Dispatchers.IO) { fetchDnsServers() }
            withContext(Dispatchers.IO) {
                domains.forEachIndexed { idx, domain ->
                    if (!isActive) return@forEachIndexed
                    val start = System.currentTimeMillis()
                    val ips = try {
                        InetAddress.getAllByName(domain).mapNotNull { it.hostAddress }
                    } catch (_: Exception) { null }
                    val ms = System.currentTimeMillis() - start
                    withContext(Dispatchers.Main) {
                        entries = entries.toMutableList().also {
                            it[idx] = DnsEntry(
                                domain  = domain,
                                ips     = ips ?: emptyList(),
                                ms      = ms,
                                done    = true,
                                failed  = ips == null
                            )
                        }
                    }
                }
            }
            isRunning = false
        }
    }

    LaunchedEffect(Unit) { runAudit() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dns_audit_title), fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                },
                actions = {
                    if (isRunning) {
                        Box(Modifier.padding(end = 12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF44DD66)
                            )
                        }
                    } else {
                        IconButton(onClick = { runAudit() }) {
                            Icon(Icons.Default.Refresh, null, tint = Color(0xFF44DD66))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0A0A))
            )
        },
        containerColor = Color(0xFF0A0A0A)
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (dnsServers.isNotEmpty()) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            stringResource(R.string.dns_audit_system_servers),
                            color = Color(0xFF555555),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(4.dp))
                        dnsServers.forEach { dns ->
                            Text(dns, color = Color(0xFF44DD66), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries, key = { it.domain }) { entry ->
                    DnsEntryCard(entry = entry)
                }
            }
        }
    }
}

@Composable
private fun DnsEntryCard(entry: DnsEntry) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.domain, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                when {
                    !entry.done  -> Text(stringResource(R.string.dns_audit_resolving), color = Color(0xFF555555), fontSize = 11.sp)
                    entry.failed -> Text(stringResource(R.string.dns_audit_failed), color = Color(0xFFFF4444), fontSize = 11.sp)
                    else -> {
                        Text(
                            entry.ips.firstOrNull() ?: "—",
                            color = Color(0xFF44DD66),
                            fontSize = 11.sp
                        )
                        if (entry.ips.size > 1) {
                            Text(
                                "+${entry.ips.size - 1} more",
                                color = Color(0xFF555555),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
            if (entry.done && !entry.failed) {
                Text(
                    "${entry.ms}ms",
                    color = when {
                        entry.ms < 50  -> Color(0xFF44DD66)
                        entry.ms < 200 -> Color(0xFFFFAA00)
                        else           -> Color(0xFFFF4444)
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
