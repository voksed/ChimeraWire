package com.carnelia.vpn

import android.app.AppOpsManager
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.carnelia.vpn.service.LocalFirewallService
import com.carnelia.vpn.ui.theme.CarheliaTheme
import com.carnelia.vpn.utils.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App traffic analyzer and per-app internet blocker. Lists apps with their cumulative
 * data usage (NetworkStatsManager) and a block toggle each; blocking is enforced by
 * [LocalFirewallService], a serverless local VPN that sinks the selected apps' traffic.
 *
 * UI copy is Russian inline (the owner's language) — a candidate for later resource
 * extraction alongside the rest of the app's i18n.
 */
class AppFirewallActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CarheliaTheme(themeIndex = PrefsManager.getThemeIndex(this)) {
                AppFirewallScreen(this)
            }
        }
    }
}

private data class FwApp(
    val pkg: String,
    val label: String,
    val uid: Int,
    val icon: androidx.compose.ui.graphics.ImageBitmap?,
    val rxBytes: Long,
    val txBytes: Long
) {
    val totalBytes get() = rxBytes + txBytes
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppFirewallScreen(activity: AppFirewallActivity) {
    val context = activity as Context
    val scope = rememberCoroutineScope()

    var hasUsageAccess by remember { mutableStateOf(hasUsageAccess(context)) }
    var loading by remember { mutableStateOf(true) }
    var apps by remember { mutableStateOf<List<FwApp>>(emptyList()) }
    var blocked by remember { mutableStateOf(PrefsManager.getFirewallBlockedApps(context)) }
    var firewallOn by remember { mutableStateOf(LocalFirewallService.isRunning) }
    var query by remember { mutableStateOf("") }

    // Reflect the service's real state (it may stop itself if the block-list empties).
    LaunchedEffect(Unit) {
        while (isActive) { firewallOn = LocalFirewallService.isRunning; delay(1000) }
    }

    fun reload() {
        loading = true
        scope.launch {
            hasUsageAccess = hasUsageAccess(context)
            val list = withContext(Dispatchers.IO) { loadApps(context, hasUsageAccess) }
            apps = list
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    val vpnPermLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == AppCompatActivity.RESULT_OK) startFirewall(context).also { firewallOn = true }
        else Toast.makeText(context, "Нужно разрешение VPN для локального блокатора", Toast.LENGTH_LONG).show()
    }

    fun applyRunningState() {
        // Any block-list change while active re-establishes the interface with the new set.
        if (LocalFirewallService.isRunning) startFirewall(context)
    }

    fun toggleBlock(pkg: String, block: Boolean) {
        if (block) PrefsManager.addFirewallBlockedApp(context, pkg)
        else PrefsManager.removeFirewallBlockedApp(context, pkg)
        blocked = PrefsManager.getFirewallBlockedApps(context)
        applyRunningState()
    }

    fun enableFirewall() {
        if (blocked.none { it != context.packageName }) {
            Toast.makeText(context, "Отметь приложения для блокировки", Toast.LENGTH_SHORT).show()
            return
        }
        val prep = VpnService.prepare(context)
        if (prep != null) vpnPermLauncher.launch(prep)
        else { startFirewall(context); firewallOn = true }
    }

    fun disableFirewall() {
        context.startService(Intent(context, LocalFirewallService::class.java).apply {
            action = LocalFirewallService.ACTION_STOP
        })
        firewallOn = false
    }

    val visibleApps = remember(apps, query, blocked) {
        val q = query.trim().lowercase()
        apps.filter { q.isBlank() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
            .sortedWith(compareByDescending<FwApp> { it.pkg in blocked }.thenByDescending { it.totalBytes })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Блокатор и трафик", color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = { activity.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            // Master switch — starts/stops the serverless firewall.
            val accent = if (firewallOn) Color(0xFF44DD66) else MaterialTheme.colorScheme.primary
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).background(accent.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Block, null, tint = accent, modifier = Modifier.size(24.dp)) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Локальный блокатор", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            if (firewallOn) "Активен · заблокировано ${LocalFirewallService.blockedCount}"
                            else "Блокирует интернет выбранным приложениям без VPN-сервера",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = firewallOn,
                        onCheckedChange = { if (it) enableFirewall() else disableFirewall() },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accent)
                    )
                }
            }

            if (!hasUsageAccess) {
                UsageAccessBanner(context)
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Поиск приложения") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            )

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                    items(visibleApps, key = { it.pkg }) { app ->
                        AppRow(
                            app = app,
                            blocked = app.pkg in blocked,
                            showUsage = hasUsageAccess,
                            onToggle = { toggleBlock(app.pkg, it) }
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun UsageAccessBanner(context: Context) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Чтобы видеть расход трафика по приложениям, дай доступ к статистике использования.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                try { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                catch (_: Exception) { Toast.makeText(context, "Не удалось открыть настройки", Toast.LENGTH_SHORT).show() }
            }) { Text("Открыть настройки доступа") }
        }
    }
}

@Composable
private fun AppRow(app: FwApp, blocked: Boolean, showUsage: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (app.icon != null) {
            Image(app.icon, null, modifier = Modifier.size(38.dp))
        } else {
            Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                if (showUsage) "↓ ${formatTraffic(app.rxBytes)}   ↑ ${formatTraffic(app.txBytes)}" else app.pkg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = blocked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFFF4444))
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
}

// ── data loading ────────────────────────────────────────────────────────────

private fun startFirewall(context: Context) {
    val intent = Intent(context, LocalFirewallService::class.java).apply {
        action = LocalFirewallService.ACTION_START
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
    else context.startService(intent)
}

private fun hasUsageAccess(context: Context): Boolean {
    return try {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        else
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }
}

/** Lists apps that hold the INTERNET permission, resolving label, icon and data usage. */
private fun loadApps(context: Context, withUsage: Boolean): List<FwApp> {
    val pm = context.packageManager
    val self = context.packageName
    val usage = if (withUsage) queryUsageByUid(context) else emptyMap()

    return pm.getInstalledApplications(0)
        .asSequence()
        .filter { it.packageName != self }
        .filter { pm.checkPermission(android.Manifest.permission.INTERNET, it.packageName) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        .map { info: ApplicationInfo ->
            val u = usage[info.uid] ?: (0L to 0L)
            val icon = try { pm.getApplicationIcon(info).toBitmap(38, 38).asImageBitmap() } catch (_: Exception) { null }
            FwApp(
                pkg = info.packageName,
                label = pm.getApplicationLabel(info).toString(),
                uid = info.uid,
                icon = icon,
                rxBytes = u.first,
                txBytes = u.second
            )
        }
        .sortedByDescending { it.totalBytes }
        .toList()
}

/** Sums cumulative Wi-Fi + mobile bytes per uid over all recorded history. */
private fun queryUsageByUid(context: Context): Map<Int, Pair<Long, Long>> {
    val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
    val result = HashMap<Int, Pair<Long, Long>>()
    @Suppress("DEPRECATION")
    val types = intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)
    for (type in types) {
        try {
            val stats = nsm.querySummary(type, null, 0L, System.currentTimeMillis())
            val bucket = android.app.usage.NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                val prev = result[bucket.uid] ?: (0L to 0L)
                result[bucket.uid] = (prev.first + bucket.rxBytes) to (prev.second + bucket.txBytes)
            }
            stats.close()
        } catch (_: Exception) {
            // Mobile stats may need a subscriber id / READ_PHONE_STATE on some devices; skip.
        }
    }
    return result
}

private fun formatTraffic(b: Long): String = when {
    b <= 0 -> "0 Б"
    b < 1024 -> "$b Б"
    b < 1024 * 1024 -> "%.0f КБ".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f МБ".format(b / 1024.0 / 1024.0)
    else -> "%.2f ГБ".format(b / 1024.0 / 1024.0 / 1024.0)
}
