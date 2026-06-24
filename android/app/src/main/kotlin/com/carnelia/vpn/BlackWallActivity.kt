package com.carnelia.vpn

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.carnelia.vpn.core.AppRisk
import com.carnelia.vpn.core.BlackWallEngine
import com.carnelia.vpn.core.BlackWallEngine.StealthLevel
import com.carnelia.vpn.core.SecurityAlertNotifier
import com.carnelia.vpn.core.SecurityScanner
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

            HorizontalDivider()
            SniffingGuardSection()

            HorizontalDivider()
            AntiSpySection()

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SniffingGuardSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    var findings by remember { mutableStateOf<List<com.carnelia.vpn.core.SniffFinding>>(emptyList()) }
    val log = remember { mutableStateListOf<String>() }

    fun check() {
        if (checking) return
        checking = true
        checked = false
        log.clear()
        scope.launch {
            val newFindings = withContext(Dispatchers.IO) {
                com.carnelia.vpn.core.SniffingGuard.checkAll(context) { line ->
                    scope.launch(Dispatchers.Main) { log.add(line) }
                }
            }
            findings = newFindings
            checking = false
            checked = true
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("ЗАЩИТА ОТ ПЕРЕХВАТА", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                Text("Снифинг и MITM: подозрительные сертификаты, ARP-спуфинг, чужой прокси", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Button(onClick = { check() }, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
            if (checking) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            else Text("Проверить сеть")
        }

        if (log.isNotEmpty()) {
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    log.forEach { line ->
                        Text(
                            line,
                            color = when {
                                line.startsWith("✓") -> Color(0xFF44DD66)
                                line.startsWith("✗") -> Color(0xFFFF8800)
                                else -> Color(0xFF888888)
                            },
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        if (checked && findings.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Security, null, tint = Color(0xFF44DD66))
                Text("Признаков перехвата трафика не найдено", color = Color(0xFF44DD66), fontSize = 13.sp)
            }
        }

        findings.forEach { finding ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFF4444).copy(alpha = 0.10f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(finding.title, color = Color(0xFFFF4444), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(finding.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun AntiSpySection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<AppRisk>>(emptyList()) }
    val vpnState by com.carnelia.vpn.core.VpnGlobalState.connectionState.collectAsState()
    val isLockedDown = vpnState == com.carnelia.vpn.core.ConnectionState.LOCKDOWN

    fun scan() {
        if (scanning) return
        scanning = true
        scope.launch {
            val found = withContext(Dispatchers.IO) { SecurityScanner.scanAll(context) }
            results = found
            scanning = false
            scanned = true
        }
    }

    fun runSelfTest() {
        val fake = AppRisk(
            packageName = "test.synthetic.entry",
            appName = "ТЕСТ: пример опасного приложения",
            score = 85,
            reasons = listOf(
                "Использует Accessibility — может читать экран и эмулировать нажатия",
                "Права администратора устройства — может заблокировать/сбросить телефон",
                "Скрыто из списка приложений — нет значка в меню"
            ),
            isSynthetic = true
        )
        results = listOf(fake) + results.filter { !it.isSynthetic }
        scanned = true
        SecurityAlertNotifier.notify(context, fake)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("АНТИ-ШПИОН", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                Text("Поиск шпионского/сталкерского ПО на устройстве", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Text(
            "Эвристика без root: Accessibility-абьюз, права администратора устройства, скрытые иконки, опасные комбинации прав, установка не из магазина. Каждое новое приложение проверяется автоматически сразу после установки. При высоком риске (≥60 баллов) Carnelia сама блокирует сеть устройства, чтобы шпион/RAT не успел ничего отправить.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp
        )

        if (isLockedDown) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFF4444).copy(alpha = 0.15f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Warning, null, tint = Color(0xFFFF4444))
                    Column(Modifier.weight(1f)) {
                        Text("РЕЖИМ ЗАЩИТЫ АКТИВЕН", fontWeight = FontWeight.Bold, color = Color(0xFFFF4444), fontSize = 13.sp)
                        Text("Сеть устройства заблокирована. Удали угрозу и сними блокировку.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { scan() }, enabled = !scanning, modifier = Modifier.weight(1f)) {
                if (scanning) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("Сканировать")
            }
            OutlinedButton(onClick = { runSelfTest() }, modifier = Modifier.weight(1f)) {
                Text("Тест уведомления")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (isLockedDown) {
                Button(
                    onClick = { com.carnelia.vpn.core.NetworkLockdownManager.disengage(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF44DD66)),
                    modifier = Modifier.weight(1f)
                ) { Text("Снять блокировку", color = Color.Black, fontWeight = FontWeight.Bold) }
            } else {
                OutlinedButton(
                    onClick = { com.carnelia.vpn.core.NetworkLockdownManager.engage(context, "ручной тест") },
                    modifier = Modifier.weight(1f)
                ) { Text("Тест блокировки сети", color = Color(0xFFFF4444)) }
            }
        }

        if (scanned && results.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Shield, null, tint = Color(0xFF44DD66))
                Text("Ничего подозрительного не найдено", color = Color(0xFF44DD66), fontSize = 13.sp)
            }
        }

        results.forEach { risk -> AntiSpyRiskCard(risk) }
    }
}

@Composable
private fun AntiSpyRiskCard(risk: AppRisk) {
    val context = LocalContext.current
    val color = when {
        risk.score >= 60 -> Color(0xFFFF4444)
        risk.score >= 30 -> Color(0xFFFFAA00)
        else -> Color(0xFF4488FF)
    }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Warning, null, tint = color, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(risk.appName, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(risk.packageName, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                }
                Text(SecurityScanner.severityLabel(risk.score), color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            risk.reasons.forEach { reason ->
                Text("• $reason", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            if (!risk.isSynthetic) {
                OutlinedButton(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${risk.packageName}")
                                }
                            )
                        } catch (_: Exception) {}
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Открыть в настройках", color = color, fontSize = 12.sp)
                }
            } else {
                Text("(синтетическая запись, не настоящее приложение)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
        }
    }
}
