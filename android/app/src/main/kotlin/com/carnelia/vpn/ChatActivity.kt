package com.carnelia.vpn

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carnelia.vpn.core.VpnServerConfig
import com.carnelia.vpn.core.chat.ChatMessage
import com.carnelia.vpn.core.chat.ChatRoomRegistry
import com.carnelia.vpn.data.ServerRepository
import com.carnelia.vpn.service.ChatService
import com.carnelia.vpn.ui.theme.CarheliaTheme
import com.carnelia.vpn.utils.PrefsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeIndex = PrefsManager.getThemeIndex(this)
            CarheliaTheme(themeIndex = themeIndex) {
                ChatRootScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun ChatRootScreen(onBack: () -> Unit) {
    var selected by remember { mutableStateOf<VpnServerConfig?>(null) }
    BackHandler(enabled = selected != null) { selected = null }

    val current = selected
    if (current == null) {
        RoomListScreen(onBack = onBack, onOpenRoom = { selected = it })
    } else {
        RoomScreen(config = current, onBack = { selected = null })
    }
}

@Composable
private fun RoomListScreen(onBack: () -> Unit, onOpenRoom: (VpnServerConfig) -> Unit) {
    val context = LocalContext.current
    val servers = remember { ServerRepository(context).getServers() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Чат", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground) },
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
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                "Комната — это все, у кого есть тот же серверный конфиг. Общий VLESS/пароль/ключ служит секретом, " +
                    "по которому телефоны находят друг друга через публичную DHT-сеть — сторонних серверов нет.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            if (servers.isEmpty()) {
                Text("Нет сохранённых серверов — сначала добавь хотя бы один", color = Color(0xFF444444), fontSize = 13.sp)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(servers, key = { it.id }) { server ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(Modifier.weight(1f).clickable { onOpenRoom(server) }) {
                                    Text(server.name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                                    Text("${server.protocol} · ${server.host}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                }
                                Icon(Icons.Default.Forum, null, tint = Color(0xFF00CC88))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomScreen(config: VpnServerConfig, onBack: () -> Unit) {
    val context = LocalContext.current
    val room = remember(config.id) { ChatRoomRegistry.get(context, config) }
    val messages by room.messages.collectAsState()
    val peers by room.peers.collectAsState()
    val isRunning by room.isRunning.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val sdf = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun toggleRoom() {
        val intent = Intent(context, ChatService::class.java).apply {
            action = if (isRunning) ChatService.ACTION_STOP_ROOM else ChatService.ACTION_START_ROOM
            putExtra(ChatService.EXTRA_CONFIG, config)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(config.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.People, null, tint = Color(0xFF888888), modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("${peers.size} в сети", fontSize = 11.sp, color = Color(0xFF888888))
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    IconButton(onClick = { toggleRoom() }) {
                        Icon(
                            if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                            null,
                            tint = if (isRunning) Color(0xFFFF6666) else Color(0xFF44DD66)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!isRunning) {
                Text(
                    "Комната выключена — нажми ▶ сверху, чтобы начать поиск собеседников",
                    fontSize = 12.sp,
                    color = Color(0xFFFFAA00),
                    modifier = Modifier.padding(12.dp)
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(messages, key = { it.id }) { msg -> MessageBubble(msg, sdf) }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Сообщение…", color = Color.Gray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground
                    )
                )
                IconButton(onClick = {
                    if (input.isNotBlank()) {
                        room.sendMessage(input.trim())
                        input = ""
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, tint = Color(0xFF00CC88))
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage, sdf: SimpleDateFormat) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.outgoing) Arrangement.End else Arrangement.Start
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (msg.outgoing) Color(0xFF1A3D2E) else MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(Modifier.padding(10.dp)) {
                if (!msg.outgoing) {
                    Text(msg.from, fontSize = 11.sp, color = Color(0xFF44DD66), fontWeight = FontWeight.Bold)
                }
                Text(msg.text, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                Text(sdf.format(Date(msg.timestamp)), fontSize = 9.sp, color = Color(0xFF666666))
            }
        }
    }
}
