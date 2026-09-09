package com.carnelia.vpn

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carnelia.vpn.core.BlackWallEngine
import com.carnelia.vpn.core.BlackWallEngine.StealthLevel
import com.carnelia.vpn.core.ConnectionState
import com.carnelia.vpn.core.VpnGlobalState
import com.carnelia.vpn.core.VpnProtocol
import com.carnelia.vpn.core.VpnServerConfig
import com.carnelia.vpn.service.CarheliaVpnService
import com.carnelia.vpn.ui.theme.CarheliaTheme
import com.carnelia.vpn.utils.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BlackWallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val themeIndex = PrefsManager.getThemeIndex(this)
        setContent {
            CarheliaTheme(themeIndex = themeIndex) {
                BlackWallScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlackWallScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(BlackWallEngine.isEnabled(context)) }
    var level   by remember { mutableStateOf(BlackWallEngine.getLevel(context)) }

    // Serverless bypass: a direct connection with TLS fragmentation, no upstream server.
    val connState by VpnGlobalState.connectionState.collectAsState()
    val bypassRunning = connState == ConnectionState.CONNECTED ||
        connState == ConnectionState.CONNECTING ||
        connState == ConnectionState.RECONNECTING
    val freedomConfig = remember {
        VpnServerConfig(
            id = "blackwall-direct",
            name = "Black Wall (без сервера)",
            protocol = VpnProtocol.FREEDOM,
            host = "direct",
            port = 443,
            config = emptyMap()
        )
    }
    fun startBypass() {
        context.startService(Intent(context, CarheliaVpnService::class.java).apply {
            action = CarheliaVpnService.ACTION_CONNECT
            putExtra(CarheliaVpnService.EXTRA_CONFIG, freedomConfig)
        })
    }
    val vpnConsentLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) startBypass()
        else Toast.makeText(context, "Нужно разрешение VPN", Toast.LENGTH_SHORT).show()
    }
    fun toggleBypass() {
        if (bypassRunning) {
            context.startService(Intent(context, CarheliaVpnService::class.java).apply {
                action = CarheliaVpnService.ACTION_DISCONNECT
            })
        } else {
            val prep = VpnService.prepare(context)
            if (prep != null) vpnConsentLauncher.launch(prep) else startBypass()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Black Wall", color = MaterialTheme.colorScheme.onSurface)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null,
                            tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── Main toggle card ──────────────────────────────────────────────
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (enabled)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Security, null,
                            tint = if (enabled) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "BLACK WALL",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (enabled) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "Anti-DPI Stealth Engine",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = {
                                enabled = it
                                BlackWallEngine.setEnabled(context, it)
                            }
                        )
                    }

                    if (enabled) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))

                        Text(
                            "Уровень защиты",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        StealthLevel.entries
                            .filter { it != StealthLevel.OFF }
                            .forEach { lvl ->
                                val selected = level == lvl
                                Card(
                                    onClick = {
                                        level = lvl
                                        BlackWallEngine.setLevel(context, lvl)
                                    },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (selected)
                                            MaterialTheme.colorScheme.primaryContainer
                                        else
                                            MaterialTheme.colorScheme.surface
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                lvl.label,
                                                fontWeight = if (selected) FontWeight.Bold
                                                             else FontWeight.Normal,
                                                color = if (selected)
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                else
                                                    MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                lvl.description,
                                                fontSize = 11.sp,
                                                color = if (selected)
                                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                                else
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (selected) {
                                            Icon(
                                                Icons.Default.Check, null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }

                        Spacer(Modifier.height(8.dp))
                        if (enabled && level != StealthLevel.OFF) {
                            Text(
                                text = when (level) {
                                    StealthLevel.GHOST ->
                                        "• Фрагментация TLS ClientHello (1-5 байт)\n• Маскировка SNI под CDN-домен"
                                    StealthLevel.PHANTOM ->
                                        "• Фрагментация TLS (1-3 байт)\n• SNI-маскировка\n• Рандомный uTLS fingerprint"
                                    StealthLevel.WRAITH ->
                                        "• Максимальная фрагментация (1-2 байт)\n• SNI-маскировка + uTLS\n• Шумовой трафик (имитация браузера)"
                                    else -> ""
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp
                            )
                        }

                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = { toggleBypass() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (bypassRunning) MaterialTheme.colorScheme.errorContainer
                                                 else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(
                                if (bypassRunning) "Остановить" else "Запустить без сервера",
                                color = if (bypassRunning) MaterialTheme.colorScheme.onErrorContainer
                                        else MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        Text(
                            "Прямое подключение с фрагментацией TLS — обход DPI без VPN-сервера.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }

            // ── Info card ─────────────────────────────────────────────────────
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Как работает",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Ghost — обходит базовую DPI-фильтрацию через разбивку TLS-рукопожатия " +
                        "и подмену SNI на доверенный CDN-домен.\n\n" +
                        "Phantom — добавляет рандомный отпечаток браузера (uTLS), " +
                        "защищает от сигнатурного анализа.\n\n" +
                        "Wraith — максимальная маскировка: шумовые HTTPS-запросы делают " +
                        "трафик неотличимым от обычного браузера.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                    Text(
                        "Настройки применяются при следующем подключении VPN.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
