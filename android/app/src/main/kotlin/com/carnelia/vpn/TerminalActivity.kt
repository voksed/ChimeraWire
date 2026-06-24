package com.carnelia.vpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carnelia.vpn.ui.theme.CarheliaTheme
import com.carnelia.vpn.utils.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

class TerminalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeIndex = PrefsManager.getThemeIndex(this)
            CarheliaTheme(themeIndex = themeIndex) {
                TerminalScreen(onBack = { finish() })
            }
        }
    }
}

private data class TermLine(val text: String, val color: Color)

private val COL_OUT = Color(0xFFCCCCCC)
private val COL_ERR = Color(0xFFFF6666)
private val COL_SYS = Color(0xFF44DD66)
private val COL_WARN = Color(0xFFFF8800)
private val COL_DIM = Color(0xFF666666)

/**
 * Реальный интерактивный sh-процесс (без PTY — управление job'ами вроде Ctrl+C недоступно,
 * "стоп" возможен только через destroy()+restart всей сессии).
 */
private class ShellSession(private val onLine: (String, Color) -> Unit) {
    private var process: Process? = null
    private var stdin: OutputStream? = null
    private var stdoutJob: Job? = null
    private var stderrJob: Job? = null

    fun start(scope: CoroutineScope, nativeLibDir: String, filesDir: String) {
        stop()
        try {
            val proc = ProcessBuilder("/system/bin/sh", "-i").start()
            process = proc
            stdin = proc.outputStream

            stdoutJob = scope.launch(Dispatchers.IO) { pump(proc.inputStream, COL_OUT) }
            stderrJob = scope.launch(Dispatchers.IO) { pump(proc.errorStream, COL_ERR) }

            send("export PATH=\"\$PATH:$nativeLibDir\"")
            send("alias xray='$nativeLibDir/libxray_core.so'")
            send("alias singbox='$nativeLibDir/libsingbox.so'")
            send("cd '$filesDir'")
            onLine("shell: /system/bin/sh -i (pid известен через ps)", COL_SYS)
            onLine("alias: xray, singbox · рабочая папка: $filesDir · 'core' — встроенные команды по ядрам", COL_DIM)
        } catch (e: Exception) {
            onLine("не удалось запустить shell: ${e.message}", COL_ERR)
        }
    }

    private suspend fun pump(stream: InputStream, color: Color) {
        try {
            stream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    withContext(Dispatchers.Main) { onLine(line, color) }
                }
            }
        } catch (_: Exception) {
        } finally {
            withContext(Dispatchers.Main) { onLine("[shell process ended]", COL_WARN) }
        }
    }

    fun send(cmd: String) {
        try {
            stdin?.write((cmd + "\n").toByteArray())
            stdin?.flush()
        } catch (e: Exception) {
            onLine("ошибка ввода: ${e.message}", COL_ERR)
        }
    }

    fun stop() {
        try { stdoutJob?.cancel() } catch (_: Exception) {}
        try { stderrJob?.cancel() } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        process = null
        stdin = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val output = remember { mutableStateListOf<TermLine>() }
    var input by remember { mutableStateOf("") }
    val history = remember { mutableStateListOf<String>() }

    fun addLine(text: String, color: Color = COL_OUT) {
        text.split("\n").forEach { output.add(TermLine(it, color)) }
        if (output.size > 4000) repeat(output.size - 4000) { output.removeAt(0) }
    }

    val session = remember { ShellSession(onLine = { t, c -> addLine(t, c) }) }

    fun restartShell() {
        addLine("--- перезапуск сессии ---", COL_WARN)
        session.start(scope, context.applicationInfo.nativeLibraryDir, context.filesDir.absolutePath)
    }

    DisposableEffect(Unit) {
        session.start(scope, context.applicationInfo.nativeLibraryDir, context.filesDir.absolutePath)
        onDispose { session.stop() }
    }

    fun handleCore(args: List<String>) {
        when (args.firstOrNull()) {
            null, "help" -> addLine(
                """
                core paths     — пути к конфигам и нативным библиотекам ядер
                core awg       — статус и версия AmneziaWG (JNI, не отдельный процесс — не виден в ps)
                core kill      — экстренно остановить все ядра (Xray/sing-box/AmneziaWG)
                core restart   — перезапустить shell-сессию (если процесс завис без -c таймаута)
                """.trimIndent(), COL_SYS
            )
            "paths" -> {
                addLine("native libs: ${context.applicationInfo.nativeLibraryDir}")
                addLine("files dir:   ${context.filesDir.absolutePath}")
                addLine("xray cfg:    ${context.filesDir.absolutePath}/xray_config.json")
                addLine("singbox cfg: ${context.filesDir.absolutePath}/singbox_config.json")
            }
            "awg" -> {
                val running = com.carnelia.vpn.core.AmneziaWgCoreManager.isRunning()
                addLine("awg running: $running", if (running) COL_SYS else COL_DIM)
                try {
                    addLine("awg version: ${org.amnezia.awg.GoBackend.awgVersion()}")
                } catch (e: Exception) {
                    addLine("awg version: n/a (${e.message})", COL_ERR)
                }
            }
            "kill" -> {
                try { com.carnelia.vpn.core.XrayCoreManager.stopCore() } catch (_: Exception) {}
                try { com.carnelia.vpn.core.SingboxCoreManager.stopCore() } catch (_: Exception) {}
                try { com.carnelia.vpn.core.AmneziaWgCoreManager.stopTunnel() } catch (_: Exception) {}
                addLine("все ядра остановлены (xray/sing-box/awg)", COL_WARN)
            }
            "restart" -> restartShell()
            else -> addLine("неизвестная core-команда: ${args.firstOrNull()}", COL_ERR)
        }
    }

    fun submit(raw: String) {
        val line = raw.trim()
        if (line.isEmpty()) return
        addLine("$ $line", COL_DIM)
        history.add(line)

        val parts = line.split(Regex("\\s+"))
        when {
            parts[0] == "core" -> handleCore(parts.drop(1))
            line == "clear" -> output.clear()
            else -> session.send(line)
        }
    }

    LaunchedEffect(output.size) {
        if (output.isNotEmpty()) {
            try { listState.scrollToItem(output.size - 1) } catch (_: Exception) {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("sh", fontWeight = FontWeight.Bold, color = Color.White, fontFamily = FontFamily.Monospace) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { restartShell() }) {
                        Icon(Icons.Default.RestartAlt, null, tint = Color(0xFFFFAA00))
                    }
                    IconButton(onClick = { output.clear() }) {
                        Icon(Icons.Default.Delete, null, tint = Color(0xFF444444))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF050505))
            )
        },
        containerColor = Color(0xFF050505)
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                itemsIndexed(output) { _, line ->
                    Text(line.text, color = line.color, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D0D0D))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("$", color = COL_SYS, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = { if (input.isNotBlank()) { submit(input); input = "" } }
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = COL_SYS
                    )
                )
                IconButton(onClick = { if (input.isNotBlank()) { submit(input); input = "" } }) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, tint = COL_SYS)
                }
            }
        }
    }
}
