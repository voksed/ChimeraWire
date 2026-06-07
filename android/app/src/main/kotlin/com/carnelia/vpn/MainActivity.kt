package com.carnelia.vpn

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.carnelia.vpn.utils.PrefsManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carnelia.vpn.ui.theme.CarheliaTheme
import com.carnelia.vpn.ui.ManualEntryDialog
import com.carnelia.vpn.core.*
import com.carnelia.vpn.service.CarheliaVpnService
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
        val config = com.carnelia.vpn.utils.ConfigParser.parse(configStr)
        if (config != null) {
            val repository = ServerRepository(this)
            repository.addServer(config)
            Toast.makeText(this, "Server imported: ${config.name}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Invalid config format", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vpnManager = VpnManager(this)

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

            CarheliaTheme(themeIndex = themeIndex) {
                val currentTheme = AppTheme.values().getOrElse(themeIndex) { AppTheme.CARNELIA }
                
                CarheliaApp(
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
    }

    private fun startVpn(config: VpnServerConfig) {
        try {
            val intent = android.net.VpnService.prepare(this)
            if (intent != null) {
                pendingVpnConfig = config
                vpnPrepareLauncher.launch(intent)
                return
            }
            if (config.protocol == VpnProtocol.OPENVPN) {
                com.carnelia.vpn.utils.OpenVpnHelper.startVpn(this, config)
                return
            }
            val serviceIntent = Intent(this, CarheliaVpnService::class.java).apply {
                action = CarheliaVpnService.ACTION_CONNECT
                putExtra(CarheliaVpnService.EXTRA_CONFIG, config)
            }
            startService(serviceIntent)
        } catch (e: Exception) {
             Toast.makeText(this, "Error: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun stopVpn() {
        val intent = Intent(this, CarheliaVpnService::class.java).apply {
            action = CarheliaVpnService.ACTION_DISCONNECT
        }
        startService(intent)
    }

    private fun switchVpn(config: VpnServerConfig) {
        try {
            val serviceIntent = Intent(this, CarheliaVpnService::class.java).apply {
                action = CarheliaVpnService.ACTION_RECONNECT
                putExtra(CarheliaVpnService.EXTRA_CONFIG, config)
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
fun CarheliaApp(
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
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.menu_title),
                    modifier = Modifier.padding(start = 24.dp, bottom = 16.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    color = primary
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = outline)
                Spacer(Modifier.height(16.dp))
                
                // Settings
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.settings_title_menu)) },
                    selected = false,
                    onClick = {
                        context.startActivity(Intent(context, SettingsActivity::class.java))
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Default.Tune, contentDescription = null, tint = onSurface) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent,
                        unselectedTextColor = onSurface
                    )
                )

                // Black Wall
                val blackWallActive = remember { com.carnelia.vpn.core.BlackWallEngine.isEnabled(context) }
                NavigationDrawerItem(
                    label = { Text("Black Wall") },
                    selected = false,
                    onClick = {
                        context.startActivity(Intent(context, BlackWallActivity::class.java))
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Default.Lock, contentDescription = null, tint = if (blackWallActive) Color(0xFF00AAFF) else onSurface) },
                    badge = if (blackWallActive) {{ Text(com.carnelia.vpn.core.BlackWallEngine.getLevel(context).label, fontSize = 10.sp, color = Color(0xFF00AAFF)) }} else null,
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent,
                        unselectedTextColor = onSurface
                    )
                )

                // Subscriptions
                NavigationDrawerItem(
                    label = { Text("Подписки") },
                    selected = false,
                    onClick = {
                        showSubscriptionsDialog = true
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = onSurface) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent,
                        unselectedTextColor = onSurface
                    )
                )

                // Leak Test
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.leak_test_title)) },
                    selected = false,
                    onClick = {
                        context.startActivity(Intent(context, LeakTestActivity::class.java))
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Default.Security, contentDescription = null, tint = onSurface) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent,
                        unselectedTextColor = onSurface
                    )
                )

                // Geolocation Spoofing
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.geo_spoof_title)) },
                    selected = false,
                    onClick = {
                        context.startActivity(Intent(context, GeoSpoofActivity::class.java))
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Default.LocationOn, contentDescription = null, tint = onSurface) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent,
                        unselectedTextColor = onSurface
                    )
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
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 2.sp,
                            color = primary
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = primary)
                        }
                    },
                    actions = {
                        val canShare = activeConfig != null
                        if (canShare) {
                            IconButton(onClick = {
                                shareContent = "${activeConfig?.protocol?.name?.lowercase()}://${activeConfig?.host}:${activeConfig?.port}"
                                showShareDialog = true
                            }) {
                                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share_tooltip), tint = onBackground)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = background),
                    modifier = Modifier.shadow(elevation = 8.dp)
                )
            },
            containerColor = background
        ) { paddingValues ->
            val windowSize = rememberWindowSize()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = if (isDark) Brush.verticalGradient(
                            colors = listOf(surfaceVariant, background)
                        ) else Brush.verticalGradient(
                            colors = listOf(background, background)
                        )
                    )
                    .padding(paddingValues)
            ) {
                if (windowSize.isLandscape) {
                    // ── Landscape / Tablet landscape: two-column layout ──
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        // Left pane: connect button + stats
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            ConnectButton(
                                connectionState = connectionState,
                                currentTheme = currentTheme,
                                buttonSize = windowSize.connectButtonDp,
                                onConnect = {
                                    val server = activeConfig ?: repository.getServers().firstOrNull()
                                    if (server != null) onConnect(server) else showServerList = true
                                },
                                onDisconnect = onDisconnect
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            StatsRow(stats = stats)
                        }

                        // Right pane: status + duration + server picker
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            ConnectionStatusText(connectionState = connectionState)
                            if (connectionState == ConnectionState.CONNECTED) {
                                Text(
                                    text = connectionDuration,
                                    fontSize = if (windowSize.isTablet) 32.sp else 22.sp,
                                    fontWeight = FontWeight.Light,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
                                )
                            } else {
                                Spacer(modifier = Modifier.height(32.dp))
                            }
                            ServerPickerPill(
                                activeConfig = activeConfig,
                                onClick = { showServerList = true }
                            )
                        }
                    }
                } else {
                    // ── Portrait: original single-column layout ──
                    val hPadding = if (windowSize.isTablet) 48.dp else 24.dp
                    Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = hPadding, vertical = 24.dp)
                            .then(
                                if (windowSize.isTablet)
                                    Modifier.widthIn(max = 480.dp).align(Alignment.Center)
                                else Modifier.fillMaxSize()
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.weight(1f))

                        ConnectionStatusText(connectionState = connectionState)

                        if (connectionState == ConnectionState.CONNECTED) {
                            Text(
                                text = connectionDuration,
                                fontSize = if (windowSize.isTablet) 32.sp else 24.sp,
                                fontWeight = FontWeight.Light,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        ConnectButton(
                            connectionState = connectionState,
                            currentTheme = currentTheme,
                            buttonSize = windowSize.connectButtonDp,
                            onConnect = {
                                val server = activeConfig ?: repository.getServers().firstOrNull()
                                if (server != null) onConnect(server) else showServerList = true
                            },
                            onDisconnect = onDisconnect
                        )

                        Spacer(modifier = Modifier.height(32.dp))
                        StatsRow(stats = stats)
                        Spacer(modifier = Modifier.weight(1f))

                        ServerPickerPill(
                            activeConfig = activeConfig,
                            onClick = { showServerList = true }
                        )
                    }
                    } // end Box (tablet portrait centering)
                }
            }
        }
    }
}

@Composable
fun UpdateDialog(info: UpdateManager.UpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_available_title, info.version), color = Color.White) },
        text = {
            Column {
                Text(stringResource(R.string.update_current_version, "2.4.2"), color = Color.Gray, fontSize = 12.sp)
                if (info.changelog.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(info.changelog, color = Color(0xFFCCCCCC), fontSize = 12.sp, maxLines = 8)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                UpdateManager.openDownload(context, info.downloadUrl)
                onDismiss()
            }) { Text(stringResource(R.string.update_action), color = Color(0xFFFF1744)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later), color = Color.Gray) }
        },
        containerColor = Color(0xFF1A1A1A)
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
fun ConnectionStatusText(connectionState: ConnectionState) {
    Text(
        text = when (connectionState) {
            ConnectionState.CONNECTED    -> stringResource(R.string.status_secured)
            ConnectionState.CONNECTING   -> stringResource(R.string.status_connecting)
            ConnectionState.RECONNECTING -> stringResource(R.string.status_switching)
            else -> stringResource(R.string.status_not_protected)
        },
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = when (connectionState) {
            ConnectionState.CONNECTED    -> Color(0xFF00CC66)
            ConnectionState.RECONNECTING -> Color(0xFFFFAA00)
            ConnectionState.ERROR        -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
        },
        letterSpacing = 1.sp
    )
}

@Composable
fun ConnectButton(
    connectionState: ConnectionState,
    currentTheme: AppTheme,
    buttonSize: androidx.compose.ui.unit.Dp,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val iconSize = (buttonSize.value * 0.4f).dp
    val isConnected = connectionState == ConnectionState.CONNECTED
    val isDarkTheme = currentTheme.isDark

    Box(
        modifier = Modifier.size(buttonSize),
        contentAlignment = Alignment.Center
    ) {
        // Outer ring for glow effect on dark themes
        if (isDarkTheme) {
            Box(
                modifier = Modifier
                    .size(buttonSize)
                    .clip(CircleShape)
                    .background(primary.copy(alpha = if (isConnected) 0.15f else 0.07f))
            )
        }
        // Main button
        Box(
            modifier = Modifier
                .size(buttonSize - 8.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isConnected -> primary
                        isDarkTheme -> surfaceVariant
                        else -> primary.copy(alpha = 0.1f)
                    }
                )
                .border(
                    width = if (isConnected) 0.dp else 2.dp,
                    color = if (isConnected) Color.Transparent else primary.copy(alpha = 0.6f),
                    shape = CircleShape
                )
                .clickable { if (isConnected) onDisconnect() else onConnect() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = "Connect",
                modifier = Modifier.size(iconSize),
                tint = if (isConnected) onPrimary else primary.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
fun StatsRow(stats: com.carnelia.vpn.core.VpnStats) {
    val onBg = MaterialTheme.colorScheme.onBackground
    Row(
        modifier = Modifier.padding(horizontal = 32.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.ArrowDownward, null, tint = onBg.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
            Text(formatBytes(stats.bytesReceived), color = onBg, fontSize = 12.sp)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.ArrowUpward, null, tint = onBg.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
            Text(formatBytes(stats.bytesSent), color = onBg, fontSize = 12.sp)
        }
    }
}

@Composable
fun ServerPickerPill(activeConfig: com.carnelia.vpn.core.VpnServerConfig?, onClick: () -> Unit) {
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = surfaceVariant,
        border = BorderStroke(1.dp, outline),
        modifier = Modifier.height(56.dp).fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Box(modifier = Modifier.size(8.dp).background(
                color = if (activeConfig != null) Color(0xFF00CC66) else MaterialTheme.colorScheme.error,
                shape = CircleShape
            ))
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                activeConfig?.name ?: stringResource(R.string.select_server_btn),
                color = onSurface,
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Default.KeyboardArrowUp, null, tint = onSurface.copy(alpha = 0.5f))
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
    val accentColor = if (currentTheme == AppTheme.TON) Color(0xFF0088CC) else Color(0xFFFF1744)
    val sdf = remember { SimpleDateFormat("dd.MM.yy HH:mm", java.util.Locale.getDefault()) }

    var subscriptions by remember { mutableStateOf(subManager.getSubscriptions()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newSubName by remember { mutableStateOf("") }
    var newSubUrl by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var updatingId by remember { mutableStateOf<String?>(null) }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false; addError = null },
            title = { Text("Добавить подписку", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newSubName,
                        onValueChange = { newSubName = it; addError = null },
                        label = { Text("Название", color = Color.Gray) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = accentColor, unfocusedBorderColor = Color(0xFF555555)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newSubUrl,
                        onValueChange = { newSubUrl = it; addError = null },
                        label = { Text("URL подписки", color = Color.Gray) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = accentColor, unfocusedBorderColor = Color(0xFF555555)
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
                        name.isBlank() -> addError = "Введите название"
                        url.isBlank() -> addError = "Введите URL"
                        !url.startsWith("http") && !url.lowercase().startsWith("happ://") -> addError = "URL должен начинаться с http:// или happ://"
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
                }) { Text("Добавить", color = accentColor) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false; addError = null }) {
                    Text("Отмена", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1A1A1A)
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF121212))
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Подписки", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showAddDialog = true }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Add, null, tint = accentColor)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, null, tint = Color.Gray)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color(0xFF333333))
                Spacer(Modifier.height(12.dp))

                if (subscriptions.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Нет подписок", color = Color.Gray)
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { showAddDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                            ) { Text("Добавить подписку") }
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(subscriptions, key = { it.id }) { sub ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1F1F)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(sub.name, color = Color.White, fontWeight = FontWeight.Bold)
                                        Text(sub.url, color = Color.Gray, fontSize = 11.sp, maxLines = 1)
                                        if (sub.lastUpdated > 0) {
                                            Text(
                                                "${sdf.format(Date(sub.lastUpdated))} · ${sub.serverCount} серв.",
                                                color = Color(0xFF888888), fontSize = 11.sp
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
                                        Icon(Icons.Default.Delete, null, tint = Color.Gray.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
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
                        Text("Обновить все")
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
    val accentColor = if (currentTheme == AppTheme.TON) Color(0xFF0088CC) else Color(0xFFFF1744)

    var servers by remember { mutableStateOf(repository.getServers()) }
    var selectedFilter by remember { mutableStateOf<String?>(null) } // null = все, "" = ручные, subId = по подписке
    var showManualAdd by remember { mutableStateOf(false) }
    var serverToRename by remember { mutableStateOf<VpnServerConfig?>(null) }
    var renameText by remember { mutableStateOf("") }
    var serverToScan by remember { mutableStateOf<VpnServerConfig?>(null) }

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
            title = { Text(stringResource(R.string.rename_server_title), color = Color.White) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.rename_server_hint), color = Color.Gray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = if (currentTheme == AppTheme.TON) Color(0xFF0088CC) else Color(0xFFFF1744),
                        unfocusedBorderColor = Color(0xFF555555)
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
                }) { Text(stringResource(R.string.save_action), color = if (currentTheme == AppTheme.TON) Color(0xFF0088CC) else Color(0xFFFF1744)) }
            },
            dismissButton = {
                TextButton(onClick = { serverToRename = null }) {
                    Text(stringResource(R.string.cancel_action), color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1A1A1A)
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
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Gray)
                                Spacer(Modifier.width(4.dp))
                            } else {
                                IconButton(onClick = { pingServers(servers) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Refresh, null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                                }
                            }
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, null, tint = Color.Gray)
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Add Tools
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onImportClipboard,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222222)),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                             Icon(Icons.Default.ContentPaste, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                        Button(
                            onClick = onScanQr,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222222)),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                             Icon(Icons.Default.QrCodeScanner, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                        Button(
                            onClick = { showManualAdd = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222222)),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                             Icon(Icons.Default.Edit, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = Color(0xFF333333))
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
                                    label = { Text("Все (${servers.size})", fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = accentColor,
                                        selectedLabelColor = Color.White
                                    )
                                )
                            }
                            if (hasManual) {
                                item {
                                    FilterChip(
                                        selected = selectedFilter == "",
                                        onClick = { selectedFilter = "" },
                                        label = { Text("Ручные (${servers.count { it.subscriptionId.isNullOrBlank() }})", fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = accentColor,
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                }
                            }
                            items(subIds) { subId ->
                                val subName = subNameById[subId] ?: "Подписка"
                                val cnt = servers.count { it.subscriptionId == subId }
                                FilterChip(
                                    selected = selectedFilter == subId,
                                    onClick = { selectedFilter = subId },
                                    label = { Text("$subName ($cnt)", fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = accentColor,
                                        selectedLabelColor = Color.White
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
                                            !pingResults.containsKey(server.id) -> Color.Gray
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
                                            tint = Color.Gray.copy(alpha = 0.7f),
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
                                            tint = Color.Gray.copy(alpha = 0.7f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    if (isSelected) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(Icons.Default.Check, null, tint = Color.White)
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

