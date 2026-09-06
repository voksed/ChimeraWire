package com.carnelia.vpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carnelia.vpn.core.VpnProtocol
import com.carnelia.vpn.core.VpnServerConfig

/**
 * Редактор параметров WireGuard / AmneziaWG сервера:
 * MTU, обфускация Jc/Jmin/Jmax/S1-S5/H1-H4/I1-I5, порт.
 * Параметры хранятся в config map сервера и уходят в экспорт/бэкап.
 */
@Composable
fun AwgSettingsDialog(
    server: VpnServerConfig,
    accentColor: Color,
    onSave: (VpnServerConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val isAmnezia = server.protocol == VpnProtocol.AMNEZIA_WG

    var mtu by remember { mutableStateOf(server.config["mtu"] ?: "") }
    var jc by remember { mutableStateOf(server.config["Jc"] ?: "") }
    var jmin by remember { mutableStateOf(server.config["Jmin"] ?: "") }
    var jmax by remember { mutableStateOf(server.config["Jmax"] ?: "") }
    var s1 by remember { mutableStateOf(server.config["S1"] ?: "") }
    var s2 by remember { mutableStateOf(server.config["S2"] ?: "") }
    var s3 by remember { mutableStateOf(server.config["S3"] ?: "") }
    var s4 by remember { mutableStateOf(server.config["S4"] ?: "") }
    var s5 by remember { mutableStateOf(server.config["S5"] ?: "") }
    var h1 by remember { mutableStateOf(server.config["H1"] ?: "") }
    var h2 by remember { mutableStateOf(server.config["H2"] ?: "") }
    var h3 by remember { mutableStateOf(server.config["H3"] ?: "") }
    var h4 by remember { mutableStateOf(server.config["H4"] ?: "") }
    // I1-I5: show only if parseable int (AmneziaVPN may export malformed Qt QByteArray string)
    var i1 by remember { mutableStateOf(server.config["I1"]?.toIntOrNull()?.toString() ?: "") }
    var i2 by remember { mutableStateOf(server.config["I2"]?.toIntOrNull()?.toString() ?: "") }
    var i3 by remember { mutableStateOf(server.config["I3"]?.toIntOrNull()?.toString() ?: "") }
    var i4 by remember { mutableStateOf(server.config["I4"]?.toIntOrNull()?.toString() ?: "") }
    var i5 by remember { mutableStateOf(server.config["I5"]?.toIntOrNull()?.toString() ?: "") }
    var port by remember { mutableStateOf(server.port.toString()) }

    val scheme = MaterialTheme.colorScheme
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = scheme.onSurface,
        unfocusedTextColor = scheme.onSurface,
        focusedBorderColor = accentColor,
        unfocusedBorderColor = scheme.outline,
        focusedLabelColor = accentColor,
        unfocusedLabelColor = scheme.onSurfaceVariant
    )

    @Composable
    fun Field(value: String, onChange: (String) -> Unit, label: String, numeric: Boolean = false) {
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(if (numeric) it.filter(Char::isDigit) else it) },
            label = { Text(label) },
            singleLine = true,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isAmnezia) "Настройки AmneziaWG" else "Настройки WireGuard",
                color = scheme.onSurface,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Field(mtu, { mtu = it }, "MTU", numeric = true)

                if (isAmnezia) {
                    Field(jc, { jc = it }, "Jc – Junk packet count", numeric = true)
                    Field(jmin, { jmin = it }, "Jmin – Junk packet minimum size", numeric = true)
                    Field(jmax, { jmax = it }, "Jmax – Junk packet maximum size", numeric = true)
                    Field(s1, { s1 = it }, "S1 – Init packet junk size", numeric = true)
                    Field(s2, { s2 = it }, "S2 – Response packet junk size", numeric = true)
                    Field(s3, { s3 = it }, "S3 – Cookie reply junk size", numeric = true)
                    Field(s4, { s4 = it }, "S4 – Transport junk size", numeric = true)
                    Field(s5, { s5 = it }, "S5 – Additional junk size", numeric = true)
                    Field(h1, { h1 = it }, "H1 – Init packet magic header")
                    Field(h2, { h2 = it }, "H2 – Response packet magic header")
                    Field(h3, { h3 = it }, "H3 – Underload packet magic header")
                    Field(h4, { h4 = it }, "H4 – Transport packet magic header")
                    Field(i1, { i1 = it }, "I1 – First special junk packet")
                    Field(i2, { i2 = it }, "I2 – Second special junk packet")
                    Field(i3, { i3 = it }, "I3 – Third special junk packet")
                    Field(i4, { i4 = it }, "I4 – Fourth special junk packet")
                    Field(i5, { i5 = it }, "I5 – Fifth special junk packet")
                }

                Text(
                    "Настройки сервера",
                    color = scheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Field(port, { port = it }, "Порт", numeric = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val newPort = port.toIntOrNull()?.coerceIn(1, 65535) ?: server.port
                val cfg = server.config.toMutableMap()

                fun put(key: String, value: String) {
                    val v = value.trim()
                    if (v.isBlank()) cfg.remove(key) else cfg[key] = v
                }
                put("mtu", mtu)
                if (isAmnezia) {
                    put("Jc", jc); put("Jmin", jmin); put("Jmax", jmax)
                    put("S1", s1); put("S2", s2); put("S3", s3); put("S4", s4); put("S5", s5)
                    put("H1", h1); put("H2", h2); put("H3", h3); put("H4", h4)
                    put("I1", i1); put("I2", i2); put("I3", i3); put("I4", i4); put("I5", i5)
                }
                // endpoint хранит host:port — синхронизируем с новым портом
                if (newPort != server.port) {
                    val host = server.host
                    cfg["endpoint"] = if (host.contains(":")) "[$host]:$newPort" else "$host:$newPort"
                }
                onSave(server.copy(port = newPort, config = cfg))
            }) { Text("Сохранить", color = accentColor) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = scheme.onSurfaceVariant) }
        },
        containerColor = scheme.surface
    )
}
