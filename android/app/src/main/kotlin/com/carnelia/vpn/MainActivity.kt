package com.carnelia.vpn

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.carnelia.vpn.utils.PrefsManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carnelia.vpn.ui.theme.ChimeraTheme
import com.carnelia.vpn.ui.ManualEntryDialog
import com.carnelia.vpn.core.*
import com.carnelia.vpn.service.ChimeraVpnService
import com.carnelia.vpn.data.ServerRepository
import com.carnelia.vpn.data.SubscriptionManager
import java.text.SimpleDateFormat
import java.util.Date
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.BorderStroke
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import androidx.compose.material.icons.filled.Refresh
import com.carnelia.vpn.ui.theme.AppTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Dns

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.window.Dialog
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.google.zxing.BarcodeFormat
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.foundation.Image
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import com.carnelia.vpn.ui.rememberWindowSize
import com.carnelia.vpn.utils.UpdateManager
import com.carnelia.vpn.ui.RealityScannerDialog
import com.carnelia.vpn.core.VpnProtocol

class MainActivity : ComponentActivity() {

    private lateinit var vpnManager: VpnManager
    private var pendingVpnConfig: VpnServerConfig? = null

    // QR Code Scanner Launcher
    private val qrCodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            importConfig(result.contents)
        }
    }

    // POST_NOTIFICATIONS — на Android 13+ объявления в манифесте недостаточно,
    // без runtime-запроса ВСЕ уведомления (включая алерты Анти-шпиона) тихо блокируются системой.
    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* пользователь решил — больше не спрашиваем повторно в этой сессии */ }

    private val vpnPrepareLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val config = pendingVpnConfig
            pendingVpnConfig = null
            if (config != null) {
                startVpn(config)
            }
        } else {
            pendingVpnConfig = null
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_LONG).show()
        }
    }

    private fun importConfig(configStr: String) {
        val trimmed = configStr.trim()
        val repository = ServerRepository(this)

        if (trimmed.startsWith("[")) {
            val configs = com.carnelia.vpn.utils.ConfigImportExport.importFromJson(trimmed)
            if (configs.isNotEmpty()) {
                configs.forEach { repository.addServer(it) }
                Toast.makeText(this, getString(R.string.xmain_imported, configs.size), Toast.LENGTH_SHORT).show()
                return
            }
        }

        // Несколько ключей построчно (список из Телеграм / подписка plain-text).
        // Обязательно ДО одиночного парсинга: URI-парсеры режут строку по '#'
        // и съедают весь остаток текста как имя сервера.
        val lines = trimmed.lines().map { it.trim() }.filter { it.contains("://") }
        if (lines.size > 1) {
            val imported = lines.mapNotNull { com.carnelia.vpn.utils.ConfigParser.parse(it) }
            if (imported.isNotEmpty()) {
                imported.forEach { repository.addServer(it) }
                Toast.makeText(this, getString(R.string.xmain_imported, imported.size), Toast.LENGTH_SHORT).show()
                return
            }
        }

        val config = com.carnelia.vpn.utils.ConfigParser.parse(trimmed)
        if (config != null) {
            repository.addServer(config)
            Toast.makeText(this, "Server imported: ${config.name}", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Invalid config format", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vpnManager = VpnManager(this)

        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val context = LocalContext.current
            var themeIndex by remember { mutableStateOf(PrefsManager.getThemeIndex(context)) }
            
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            DisposableEffect(lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        themeIndex = PrefsManager.getThemeIndex(context)
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }

            ChimeraTheme(themeIndex = themeIndex) {
                val currentTheme = AppTheme.entries.getOrElse(themeIndex) { AppTheme.DARK }
                
                ChimeraApp(
                    vpnManager, 
                    ::startVpn, 
                    ::stopVpn,
                    ::switchVpn,
                    currentTheme, 
                    onScanQr = { 
                        val options = ScanOptions()
                        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        options.setPrompt("Scan VPN QR Code")
                        options.setBeepEnabled(false)
                        qrCodeLauncher.launch(options)
                    },
                    onImportClipboard = {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clipData = clipboard.primaryClip
                        if (clipData != null && clipData.itemCount > 0) {
                            val text = clipData.getItemAt(0).text.toString()
                            importConfig(text)
                        } else {
                            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }

        // Обработка входящего интента (JSON из Телеграм, файловых менеджеров и т.д.)
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                // tc:// и ton-connect:// — deep links TON Connect, не файлы конфигов
                if (uri.scheme != "content" && uri.scheme != "file") return
                val text = readTextFromUri(uri) ?: return
                importConfig(text)
            }
            Intent.ACTION_SEND -> {
                // Текст передан напрямую (поделиться текстом)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) {
                    importConfig(text)
                    return
                }
                // Файл передан как поток (JSON файл из Телеграм)
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM) ?: return
                val fileText = readTextFromUri(uri) ?: return
                importConfig(fileText)
            }
        }
    }

    private fun readTextFromUri(uri: android.net.Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
        } catch (e: Exception) {
            com.carnelia.vpn.utils.AppLogger.error("MainActivity: readTextFromUri failed", e)
            Toast.makeText(this, getString(R.string.xmain_read_file_failed), Toast.LENGTH_SHORT).show()
            null
        }
    }

    private fun startVpn(config: VpnServerConfig) {
        try {
            val intent = android.net.VpnService.prepare(this)
            if (intent != null) {
                pendingVpnConfig = config
                vpnPrepareLauncher.launch(intent)
                return
            }
            val serviceIntent = Intent(this, ChimeraVpnService::class.java).apply {
                action = ChimeraVpnService.ACTION_CONNECT
                putExtra(ChimeraVpnService.EXTRA_CONFIG, config)
            }
            startService(serviceIntent)
        } catch (e: Exception) {
             Toast.makeText(this, "Error: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun stopVpn() {
        val intent = Intent(this, ChimeraVpnService::class.java).apply {
            action = ChimeraVpnService.ACTION_DISCONNECT
        }
        startService(intent)
    }

    private fun switchVpn(config: VpnServerConfig) {
        try {
            val serviceIntent = Intent(this, ChimeraVpnService::class.java).apply {
                action = ChimeraVpnService.ACTION_RECONNECT
                putExtra(ChimeraVpnService.EXTRA_CONFIG, config)
            }
            startService(serviceIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Error: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        vpnManager.destroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChimeraApp(
    vpnManager: VpnManager,
    onConnect: (VpnServerConfig) -> Unit,
    onDisconnect: () -> Unit,
    onSwitch: (VpnServerConfig) -> Unit,
    currentTheme: AppTheme,
    onScanQr: () -> Unit,
    onImportClipboard: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val background = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onBackground = MaterialTheme.colorScheme.onBackground
    val outline = MaterialTheme.colorScheme.outline
    val isDark = currentTheme.isDark
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // Update check
    var updateInfo by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        updateInfo = UpdateManager.checkForUpdate()
    }
    updateInfo?.let { info ->
        UpdateDialog(info = info, onDismiss = { updateInfo = null })
    }

    // Data
    val connectionState by VpnGlobalState.connectionState.collectAsState()
    val stats by VpnGlobalState.stats.collectAsState()
    val repository = remember { ServerRepository(context) }
    val subCount = remember { SubscriptionManager(context).getSubscriptions().size }
    var activeConfig by remember {
        mutableStateOf(repository.getLastUsedServer() ?: repository.getServers().firstOrNull())
    }
    
    // Server List Dialog State
    var showServerList by remember { mutableStateOf(false) }
    var showSubscriptionsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(showServerList) {
        if (!showServerList) {
             val current = repository.getLastUsedServer()
             if (current != null) {
                 activeConfig = current
             } else {
                 // Active server might have been deleted, fallback to first available
                 activeConfig = repository.getServers().firstOrNull()
             }
        }
    }
    
    // QR Share State
    var showShareDialog by remember { mutableStateOf(false) }
    var shareContent by remember { mutableStateOf("") }

    if (showShareDialog && shareContent.isNotEmpty()) {
        QrCodeDialog(content = shareContent, onDismiss = { showShareDialog = false })
    }
    
    // Server Selection Dialog
    if (showServerList) {
        ServerSelectionDialog(
            repository = repository,
            onServerSelected = { server ->
                activeConfig = server
                repository.setLastUsedServer(server)
                showServerList = false
                // If VPN is active, hot-switch without dropping the tunnel
                if (connectionState == ConnectionState.CONNECTED ||
                    connectionState == ConnectionState.RECONNECTING) {
                    onSwitch(server)
                }
            },
            onDismiss = { showServerList = false },
            onImportClipboard = {
                onImportClipboard()
                // Ideally refresh here
                showServerList = false 
            },
            onScanQr = {
                onScanQr()
                showServerList = false
            },
            currentTheme = currentTheme,
            activeInfo = activeConfig
        )
    }

    if (showSubscriptionsDialog) {
        SubscriptionsDialog(
            onDismiss = {
                showSubscriptionsDialog = false
                activeConfig = repository.getLastUsedServer() ?: repository.getServers().firstOrNull()
            },
            currentTheme = currentTheme
        )
    }

    // Connection Duration Timer
    var connectionDuration by remember { mutableStateOf("00:00:00") }
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.CONNECTED) {
            while (true) {
                val duration = if (stats.connectionTime > 0) (System.currentTimeMillis() - stats.connectionTime) / 1000 else 0L
                val h = duration / 3600
                val m = (duration % 3600) / 60
                val s = duration % 60
                connectionDuration = String.format("%02d:%02d:%02d", h, m, s)
                delay(1000)
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = surface,
                drawerContentColor = onSurface
            ) {
                // ── Header: brand tile + live status ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.carnelia_vpn_title), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = onSurface)
                        Text(
                            if (connectionState == ConnectionState.CONNECTED) stringResource(R.string.status_secured) else stringResource(R.string.status_not_protected),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                DrawerSectionLabel(stringResource(R.string.xdrawer_section_connection))
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.xdrawer_home)) },
                    selected = true,
                    onClick = { scope.launch { drawerState.close() } },
                    icon = { Icon(Icons.Default.PowerSettingsNew, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = chimeraDrawerColors()
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.xmain_subscriptions)) },
                    selected = false,
                    badge = { if (subCount > 0) Text("$subCount", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = {
                        showSubscriptionsDialog = true
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Default.Sync, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = chimeraDrawerColors()
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.outline)
                DrawerSectionLabel(stringResource(R.string.xdrawer_section_protection))
                NavigationDrawerItem(
                    label = { Text("Black Wall") },
                    selected = false,
                    onClick = {
                        context.startActivity(Intent(context, BlackWallActivity::class.java))
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Default.Security, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = chimeraDrawerColors()
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.xmain_traffic_blocker)) },
                    selected = false,
                    onClick = {
                        context.startActivity(Intent(context, AppFirewallActivity::class.java))
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Default.Block, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = chimeraDrawerColors()
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.geo_spoof_title)) },
                    selected = false,
                    onClick = {
                        context.startActivity(Intent(context, GeoSpoofActivity::class.java))
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = chimeraDrawerColors()
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.outline)
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.xmain_tools)) },
                    selected = false,
                    onClick = {
                        context.startActivity(Intent(context, ToolsHubActivity::class.java))
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Default.Build, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = chimeraDrawerColors()
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.settings_title_menu)) },
                    selected = false,
                    onClick = {
                        context.startActivity(Intent(context, SettingsActivity::class.java))
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = chimeraDrawerColors()
                )

                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.xdrawer_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(16.dp)
                )

            }
        }
    ) {

        // CONTENT (Clean UI Style)
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.carnelia_vpn_title),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Normal,
                            color = onBackground
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = onBackground)
                        }
                    },
                    actions = {
                        val canShare = activeConfig != null
                        if (canShare) {
                            IconButton(onClick = {
                                // Полный JSON (uuid, REALITY pbk/sid/fp/sni — всё из config-карты), а не голый
                                // host:port, который всё равно не распарсить обратно. subscriptionId обнуляем —
                                // он ссылается на подписку этого устройства, на чужом телефоне бессмыслен.
                                activeConfig?.let { cfg ->
                                    shareContent = com.carnelia.vpn.utils.ConfigImportExport.exportToJson(listOf(cfg.copy(subscriptionId = null)))
                                    showShareDialog = true
                                }
                            }) {
                                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share_tooltip), tint = onBackground)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = background)
                )
            },
            containerColor = background
        ) { paddingValues ->
            val windowSize = rememberWindowSize()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(background)
                    .padding(paddingValues)
            ) {
                if (windowSize.isLandscape) {
                    // ── Landscape / Tablet landscape: two-column layout ──
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        // Left pane: connect ring
                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            ConnectRing(
                                connectionState = connectionState,
                                onConnect = {
                                    val server = activeConfig ?: repository.getServers().firstOrNull()
                                    if (server != null) onConnect(server) else showServerList = true
                                },
                                onDisconnect = onDisconnect
                            )
                        }

                        // Right pane: status + stats + server
                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            StatusChip(connectionState, connectionDuration)
                            Spacer(Modifier.height(16.dp))
                            HeadlineStatus(connectionState)
                            Spacer(Modifier.height(24.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatCard(Icons.Default.ArrowDownward, formatBytes(stats.bytesReceived), stringResource(R.string.received_label), Modifier.weight(1f))
                                StatCard(Icons.Default.ArrowUpward, formatBytes(stats.bytesSent), stringResource(R.string.sent_label), Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(16.dp))
                            ServerCard(activeConfig) { showServerList = true }
                        }
                    }
                } else {
                    // ── Portrait: single-column layout per the design canvas ──
                    val hPadding = if (windowSize.isTablet) 48.dp else 16.dp
                    Column(
                        modifier = Modifier
                            .padding(horizontal = hPadding)
                            .then(
                                if (windowSize.isTablet)
                                    Modifier.widthIn(max = 480.dp).align(Alignment.Center).fillMaxHeight()
                                else Modifier.fillMaxSize()
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(24.dp))
                        StatusChip(connectionState, connectionDuration)
                        Spacer(Modifier.height(20.dp))
                        HeadlineStatus(connectionState)
                        Spacer(Modifier.height(40.dp))
                        ConnectRing(
                            connectionState = connectionState,
                            onConnect = {
                                val server = activeConfig ?: repository.getServers().firstOrNull()
                                if (server != null) onConnect(server) else showServerList = true
                            },
                            onDisconnect = onDisconnect
                        )
                        Spacer(Modifier.height(40.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatCard(Icons.Default.ArrowDownward, formatBytes(stats.bytesReceived), stringResource(R.string.received_label), Modifier.weight(1f))
                            StatCard(Icons.Default.ArrowUpward, formatBytes(stats.bytesSent), stringResource(R.string.sent_label), Modifier.weight(1f))
                        }
                        Spacer(Modifier.weight(1f))
                        ServerCard(activeConfig) { showServerList = true }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun UpdateDialog(info: UpdateManager.UpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text(stringResource(R.string.update_available_title, info.version), color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column {
                Text(stringResource(R.string.update_current_version, BuildConfig.VERSION_NAME), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                if (info.changelog.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    // Full release notes in a scrollable region so long changelogs fit
                    // without being truncated or overflowing the dialog.
                    Text(
                        info.changelog,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier
                            .heightIn(max = 340.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
                if (downloading) {
                    Spacer(Modifier.height(12.dp))
                    if (progress >= 0f) {
                        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                        Text("${(progress * 100).toInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !downloading, onClick = {
                downloading = true
                progress = 0f
                scope.launch {
                    val ok = UpdateManager.downloadAndInstall(context, info.downloadUrl) { p -> progress = p }
                    downloading = false
                    if (ok) onDismiss() else UpdateManager.openDownload(context, info.downloadUrl)
                }
            }) { Text(stringResource(R.string.update_action), color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            TextButton(enabled = !downloading, onClick = onDismiss) { Text(stringResource(R.string.update_later), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
fun StatBox(label: String, value: String, currentTheme: AppTheme) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}


fun formatBytes(bytes: Long): String {
    return when {
         bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024 * 1024.0))
    }
}

// ── Reusable connect-screen sub-composables ────────────────────────────────

@Composable
private fun chimeraDrawerColors() = NavigationDrawerItemDefaults.colors(
    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
    selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
    unselectedContainerColor = Color.Transparent,
    unselectedTextColor = MaterialTheme.colorScheme.onSurface,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
)

@Composable
private fun DrawerSectionLabel(text: String) {
    Text(
        text,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 28.dp, top = 8.dp, bottom = 8.dp)
    )
}

/** Status pill above the headline: ember-filled when connected (with the live timer), outlined otherwise. */
@Composable
fun StatusChip(connectionState: ConnectionState, duration: String) {
    val connected = connectionState == ConnectionState.CONNECTED
    val scheme = MaterialTheme.colorScheme
    val label = when (connectionState) {
        ConnectionState.CONNECTED -> stringResource(R.string.xmain_chip_connected) + " · " + duration
        ConnectionState.CONNECTING -> stringResource(R.string.status_connecting)
        ConnectionState.RECONNECTING -> stringResource(R.string.status_switching)
        else -> stringResource(R.string.xmain_chip_disconnected)
    }
    val bg = if (connected) scheme.primaryContainer else Color.Transparent
    val fg = if (connected) scheme.onPrimaryContainer else scheme.onSurfaceVariant
    val borderColor = if (connected) scheme.primaryContainer else scheme.outline
    Surface(shape = RoundedCornerShape(8.dp), color = bg, border = BorderStroke(1.dp, borderColor)) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Shield, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Large headline word ("Защищено" / "Не защищено") with a supporting line, animated on state change. */
@Composable
fun HeadlineStatus(connectionState: ConnectionState) {
    val supporting = if (connectionState == ConnectionState.CONNECTED)
        stringResource(R.string.status_hint_connected) else stringResource(R.string.status_hint_disconnected)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            targetState = connectionState,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "headline"
        ) { state ->
            Text(
                text = when (state) {
                    ConnectionState.CONNECTED    -> stringResource(R.string.status_secured)
                    ConnectionState.CONNECTING   -> stringResource(R.string.status_connecting)
                    ConnectionState.RECONNECTING -> stringResource(R.string.status_switching)
                    else -> stringResource(R.string.status_not_protected)
                },
                fontSize = 32.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(supporting, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The hero power control: a 176dp button inside a 224dp ring. The button morphs from a full
 * circle (off) to a rounded square (on) and swaps to the ember fill, matching the design canvas.
 * A spinner shows during connect/reconnect; a long-press opens the debug panel.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ConnectRing(
    connectionState: ConnectionState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val isConnected = connectionState == ConnectionState.CONNECTED
    val isBusy = connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.RECONNECTING
    val radius by animateDpAsState(if (isConnected) 56.dp else 88.dp, label = "btn-radius")
    val btnBg = if (isConnected) scheme.primary else scheme.surfaceContainerHighest
    val btnFg = if (isConnected) scheme.onPrimary else scheme.primary
    val ringBg = if (isConnected) scheme.surfaceContainerHigh else scheme.surfaceContainer

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (isPressed) 0.95f else 1f, label = "press-scale")

    Box(
        modifier = Modifier.size(224.dp).clip(CircleShape).background(ringBg),
        contentAlignment = Alignment.Center
    ) {
        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(200.dp),
                color = scheme.primary,
                strokeWidth = 3.dp,
                trackColor = Color.Transparent
            )
        }
        Box(
            modifier = Modifier
                .size(176.dp)
                .scale(pressScale)
                .clip(RoundedCornerShape(radius))
                .background(btnBg)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { if (isConnected) onDisconnect() else onConnect() },
                    // Долгое нажатие — Debug Panel с логами
                    onLongClick = {
                        context.startActivity(Intent(context, DebugActivity::class.java))
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = "Connect",
                modifier = Modifier.size(64.dp),
                tint = btnFg
            )
        }
    }
}

/** One of the two traffic tiles: a circular tonal icon badge + value + label. */
@Composable
fun StatCard(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(16.dp), color = scheme.surfaceContainer, modifier = modifier) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(scheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                AnimatedContent(
                    targetState = value,
                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
                    label = "stat-value"
                ) { v ->
                    Text(v, color = scheme.onSurface, fontSize = 22.sp, lineHeight = 28.sp)
                }
                Text(label, color = scheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
            }
        }
    }
}

/** Bottom server selector card: icon tile, "Сервер" + name, online dot, expand chevron. */
@Composable
fun ServerCard(activeConfig: com.carnelia.vpn.core.VpnServerConfig?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val online = Color(0xFFA8D5A2)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.heightIn(min = 72.dp).padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(scheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Dns, null, tint = scheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                val label = if (activeConfig != null)
                    stringResource(R.string.xmain_server_prefix) + " · " + activeConfig.protocol.name
                else stringResource(R.string.xmain_server_prefix)
                Text(label, color = scheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
                Text(
                    activeConfig?.name ?: stringResource(R.string.select_server_btn),
                    color = scheme.onSurface,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    maxLines = 1
                )
            }
            if (activeConfig != null) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(online))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.xmain_online), color = online, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.UnfoldMore, null, tint = scheme.onSurfaceVariant)
        }
    }
}

@Composable
fun QrCodeDialog(content: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(R.string.scan_to_import), style = MaterialTheme.typography.titleMedium, color = Color.Black)
                Spacer(modifier = Modifier.height(16.dp))
                
                val bitmap = remember(content) {
                    try {
                        val encoder = BarcodeEncoder()
                        encoder.encodeBitmap(content, BarcodeFormat.QR_CODE, 600, 600)
                    } catch (e: Exception) {
                        null
                    }
                }
                
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier.size(200.dp)
                    )
                } else {
                    Text(stringResource(R.string.error_generating_qr), color = Color.Red)
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onDismiss) { Text(stringResource(R.string.close_button)) }
            }
        }
    }
}

@Composable
fun SubscriptionsDialog(
    onDismiss: () -> Unit,
    currentTheme: AppTheme
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val subManager = remember { SubscriptionManager(context) }
    val accentColor = MaterialTheme.colorScheme.primary
    val sdf = remember { SimpleDateFormat("dd.MM.yy HH:mm", java.util.Locale.getDefault()) }

    var subscriptions by remember { mutableStateOf(subManager.getSubscriptions()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newSubName by remember { mutableStateOf("") }
    var newSubUrl by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var updatingId by remember { mutableStateOf<String?>(null) }

    val errName = stringResource(R.string.xmain_enter_name)
    val errUrl = stringResource(R.string.xmain_enter_url)
    val errUrlFormat = stringResource(R.string.xmain_url_format)

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false; addError = null },
            title = { Text(stringResource(R.string.xmain_add_sub), color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newSubName,
                        onValueChange = { newSubName = it; addError = null },
                        label = { Text(stringResource(R.string.xmain_name), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = accentColor, unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newSubUrl,
                        onValueChange = { newSubUrl = it; addError = null },
                        label = { Text(stringResource(R.string.xmain_sub_url), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = accentColor, unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (addError != null) Text(addError!!, color = Color.Red, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newSubName.trim()
                    val url = newSubUrl.trim()
                    when {
                        name.isBlank() -> addError = errName
                        url.isBlank() -> addError = errUrl
                        !url.startsWith("http") && !url.lowercase().startsWith("happ://") -> addError = errUrlFormat
                        else -> {
                            subManager.addSubscription(name, url)
                            subscriptions = subManager.getSubscriptions()
                            val newSub = subscriptions.find { it.url == url }
                            if (newSub != null) {
                                scope.launch {
                                    updatingId = newSub.id
                                    subManager.updateSubscription(newSub.id)
                                    subscriptions = subManager.getSubscriptions()
                                    updatingId = null
                                }
                            }
                            showAddDialog = false; newSubName = ""; newSubUrl = ""
                        }
                    }
                }) { Text(stringResource(R.string.xmain_add), color = accentColor) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false; addError = null }) {
                    Text(stringResource(R.string.xmain_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.xmain_subscriptions), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showAddDialog = true }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Add, null, tint = accentColor)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))

                if (subscriptions.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.xmain_no_subs), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { showAddDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                            ) { Text(stringResource(R.string.xmain_add_sub)) }
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(subscriptions, key = { it.id }) { sub ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(sub.name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                                        Text(sub.url, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
                                        if (sub.lastUpdated > 0) {
                                            Text(
                                                stringResource(R.string.xmain_sub_meta, sdf.format(Date(sub.lastUpdated)), sub.serverCount),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp
                                            )
                                        }
                                    }
                                    if (updatingId == sub.id) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = accentColor)
                                    } else {
                                        IconButton(onClick = {
                                            scope.launch {
                                                updatingId = sub.id
                                                subManager.updateSubscription(sub.id)
                                                subscriptions = subManager.getSubscriptions()
                                                updatingId = null
                                            }
                                        }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.Refresh, null, tint = accentColor, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    IconButton(onClick = {
                                        subManager.removeSubscription(sub.id)
                                        subscriptions = subManager.getSubscriptions()
                                    }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                subscriptions.forEach { sub ->
                                    updatingId = sub.id
                                    subManager.updateSubscription(sub.id)
                                }
                                subscriptions = subManager.getSubscriptions()
                                updatingId = null
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                        enabled = updatingId == null
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.xmain_update_all))
                    }
                }
            }
        }
    }
}

@Composable
fun ServerSelectionDialog(
    repository: ServerRepository,
    onServerSelected: (VpnServerConfig) -> Unit,
    onDismiss: () -> Unit,
    onImportClipboard: () -> Unit,
    onScanQr: () -> Unit,
    currentTheme: AppTheme,
    activeInfo: VpnServerConfig?
) {
    val context = LocalContext.current
    val subManager = remember { SubscriptionManager(context) }
    val subscriptions = remember { subManager.getSubscriptions() }
    val subNameById = remember { subscriptions.associate { it.id to it.name } }
    val accentColor = MaterialTheme.colorScheme.primary

    var servers by remember { mutableStateOf(repository.getServers()) }
    var selectedFilter by remember { mutableStateOf<String?>(null) } // null = все, "" = ручные, subId = по подписке
    var showManualAdd by remember { mutableStateOf(false) }
    var serverToRename by remember { mutableStateOf<VpnServerConfig?>(null) }
    var renameText by remember { mutableStateOf("") }
    var serverToScan by remember { mutableStateOf<VpnServerConfig?>(null) }
    var serverToConfig by remember { mutableStateOf<VpnServerConfig?>(null) }
    var serverToEdit by remember { mutableStateOf<VpnServerConfig?>(null) }

    serverToScan?.let { scanServer ->
        RealityScannerDialog(
            server = scanServer,
            repository = repository,
            onDismiss = { serverToScan = null },
            onApplied = { updated ->
                servers = repository.getServers()
                if (activeInfo?.id == updated.id) onServerSelected(updated)
                serverToScan = null
            }
        )
    }

    serverToConfig?.let { cfgServer ->
        com.carnelia.vpn.ui.AwgSettingsDialog(
            server = cfgServer,
            accentColor = accentColor,
            onDismiss = { serverToConfig = null },
            onSave = { updated ->
                repository.updateServer(updated)
                servers = repository.getServers()
                serverToConfig = null
            }
        )
    }

    serverToEdit?.let { editServer ->
        com.carnelia.vpn.ui.ServerEditDialog(
            server = editServer,
            accentColor = accentColor,
            onDismiss = { serverToEdit = null },
            onSave = { updated ->
                repository.updateServer(updated)
                servers = repository.getServers()
                if (activeInfo?.id == updated.id) onServerSelected(updated)
                serverToEdit = null
            }
        )
    }

    val filteredServers = remember(servers, selectedFilter) {
        when (selectedFilter) {
            null -> servers
            "" -> servers.filter { it.subscriptionId.isNullOrBlank() }
            else -> servers.filter { it.subscriptionId == selectedFilter }
        }
    }
    var pingResults by remember { mutableStateOf<Map<String, Int?>>(emptyMap()) }
    var isPinging by remember { mutableStateOf(false) }
    val pingScope = rememberCoroutineScope()

    // Protocols that use UDP — TCP socket ping will always fail for them
    val UDP_PROTOCOLS = setOf(
        com.carnelia.vpn.core.VpnProtocol.HYSTERIA2,
        com.carnelia.vpn.core.VpnProtocol.TUIC,
        com.carnelia.vpn.core.VpnProtocol.WARP,
        com.carnelia.vpn.core.VpnProtocol.WIREGUARD,
        com.carnelia.vpn.core.VpnProtocol.AMNEZIA_WG
    )

    fun icmpPing(host: String): Int? {
        return try {
            val start = System.currentTimeMillis()
            val proc = Runtime.getRuntime().exec(arrayOf("ping", "-c", "1", "-W", "2", host))
            val exit = proc.waitFor()
            val elapsed = (System.currentTimeMillis() - start).toInt()
            if (exit == 0) {
                val output = proc.inputStream.bufferedReader().readText()
                val match = Regex("time[=<]([0-9.]+)").find(output)
                match?.groupValues?.get(1)?.toFloatOrNull()?.toInt() ?: elapsed
            } else null
        } catch (_: Exception) { null }
    }

    fun pingServers(list: List<VpnServerConfig>) {
        if (isPinging) return
        isPinging = true
        pingResults = emptyMap()
        pingScope.launch(Dispatchers.IO) {
            val results = mutableMapOf<String, Int?>()
            for (server in list) {
                if (!isActive) break
                val ping = if (server.protocol in UDP_PROTOCOLS) {
                    icmpPing(server.host)
                } else {
                    try {
                        val start = System.currentTimeMillis()
                        java.net.Socket().use { it.connect(java.net.InetSocketAddress(server.host, server.port), 3000) }
                        (System.currentTimeMillis() - start).toInt()
                    } catch (e: Exception) { null }
                }
                results[server.id] = ping
                withContext(Dispatchers.Main) { pingResults = results.toMap() }
            }
            withContext(Dispatchers.Main) { isPinging = false }
        }
    }

    LaunchedEffect(servers) { delay(300); pingServers(servers) }

    serverToRename?.let { server ->
        AlertDialog(
            onDismissRequest = { serverToRename = null },
            title = { Text(stringResource(R.string.rename_server_title), color = MaterialTheme.colorScheme.onSurface) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.rename_server_hint), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = renameText.trim()
                    if (trimmed.isNotBlank()) {
                        repository.updateServer(server.copy(name = trimmed))
                        servers = repository.getServers()
                    }
                    serverToRename = null
                }) { Text(stringResource(R.string.save_action), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { serverToRename = null }) {
                    Text(stringResource(R.string.cancel_action), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showManualAdd) {
        ManualEntryDialog(
            onDismiss = { showManualAdd = false },
            onSave = { config ->
                repository.addServer(config)
                onServerSelected(config)
                servers = repository.getServers()
                showManualAdd = false
            }
        )
    } else {
        Dialog(onDismissRequest = onDismiss) {
            Card(
                 modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f),
                 shape = RoundedCornerShape(24.dp),
                 colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(), 
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.select_server_btn), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isPinging) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(4.dp))
                            } else {
                                IconButton(onClick = { pingServers(servers) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                }
                            }
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Add Tools
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onImportClipboard,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                             Icon(Icons.Default.ContentPaste, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        }
                        Button(
                            onClick = onScanQr,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                             Icon(Icons.Default.QrCodeScanner, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        }
                        Button(
                            onClick = { showManualAdd = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                             Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Spacer(modifier = Modifier.height(8.dp))

                    // Фильтр-табы
                    val hasManual = servers.any { it.subscriptionId.isNullOrBlank() }
                    val subIds = servers.mapNotNull { it.subscriptionId }.distinct()
                    if (subIds.isNotEmpty() || hasManual) {
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            item {
                                FilterChip(
                                    selected = selectedFilter == null,
                                    onClick = { selectedFilter = null },
                                    label = { Text(stringResource(R.string.xmain_all_count, servers.size), fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = accentColor,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                            if (hasManual) {
                                item {
                                    FilterChip(
                                        selected = selectedFilter == "",
                                        onClick = { selectedFilter = "" },
                                        label = { Text(stringResource(R.string.xmain_manual_count, servers.count { it.subscriptionId.isNullOrBlank() }), fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = accentColor,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    )
                                }
                            }
                            items(subIds) { subId ->
                                val subName = subNameById[subId] ?: stringResource(R.string.xmain_subscription)
                                val cnt = servers.count { it.subscriptionId == subId }
                                FilterChip(
                                    selected = selectedFilter == subId,
                                    onClick = { selectedFilter = subId },
                                    label = { Text("$subName ($cnt)", fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = accentColor,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // List
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(filteredServers, key = { it.id }) { server ->
                            val isSelected = activeInfo?.id == server.id
                            Card(
                                onClick = { onServerSelected(server) },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected)
                                        MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(server.name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                                        val subName = server.subscriptionId?.let { subNameById[it] }
                                        if (subName != null) {
                                            Text(
                                                "📋 $subName",
                                                color = accentColor.copy(alpha = 0.8f),
                                                fontSize = 10.sp
                                            )
                                        }
                                        val pingMs = pingResults[server.id]
                                        val isUdp = server.protocol in UDP_PROTOCOLS
                                        val pingText = when {
                                            !pingResults.containsKey(server.id) -> server.host
                                            pingMs == null && isUdp -> "◈ UDP · ${server.host}"
                                            pingMs == null -> "✕ timeout"
                                            pingMs < 100 -> "● ${pingMs}ms"
                                            pingMs < 300 -> "● ${pingMs}ms"
                                            else -> "● ${pingMs}ms"
                                        }
                                        val pingColor = when {
                                            !pingResults.containsKey(server.id) -> MaterialTheme.colorScheme.onSurfaceVariant
                                            pingMs == null && isUdp -> Color(0xFF8888FF)
                                            pingMs == null -> Color(0xFFFF4444)
                                            pingMs < 100 -> Color(0xFF00CC66)
                                            pingMs < 300 -> Color(0xFFFFAA00)
                                            else -> Color(0xFFFF4444)
                                        }
                                        Text(pingText, color = pingColor, fontSize = 12.sp, maxLines = 1)
                                    }

                                    // Reality Scanner button (only for VLESS REALITY)
                                    if (server.protocol == VpnProtocol.VLESS && server.config["security"] == "reality") {
                                        IconButton(
                                            onClick = { serverToScan = server },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Search,
                                                contentDescription = "Reality Scanner",
                                                tint = Color(0xFF00AAFF).copy(alpha = 0.8f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    // WireGuard / AmneziaWG settings (MTU, Jc, I1-I5, порт)
                                    if (server.protocol == VpnProtocol.WIREGUARD || server.protocol == VpnProtocol.AMNEZIA_WG) {
                                        IconButton(
                                            onClick = { serverToConfig = server },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Tune,
                                                contentDescription = "Protocol Settings",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    // Proxy server parameters (host / port / SNI, plus REALITY pbk/sid/fp)
                                    // with an availability + certificate probe. Not shown for WireGuard,
                                    // which is served by the Tune dialog above.
                                    if (server.protocol != VpnProtocol.WIREGUARD && server.protocol != VpnProtocol.AMNEZIA_WG) {
                                        IconButton(
                                            onClick = { serverToEdit = server },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Settings,
                                                contentDescription = stringResource(R.string.xmain_server_params),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    // Rename Button
                                    IconButton(
                                        onClick = {
                                            renameText = server.name
                                            serverToRename = server
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = stringResource(R.string.rename_tooltip),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    // Delete Button
                                    IconButton(
                                        onClick = { 
                                            repository.removeServer(server.id)
                                            servers = repository.getServers()
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete, 
                                            contentDescription = "Delete", 
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    if (isSelected) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                            }
                        }
                        if (servers.isEmpty()) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    Text(stringResource(R.string.no_servers_found_add_one), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

