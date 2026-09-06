package com.carnelia.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.carnelia.vpn.R
import com.carnelia.vpn.core.RealityScannerManager
import com.carnelia.vpn.core.VpnServerConfig
import com.carnelia.vpn.data.ServerRepository
import kotlinx.coroutines.launch

@Composable
fun RealityScannerDialog(
    server: VpnServerConfig,
    repository: ServerRepository,
    onDismiss: () -> Unit,
    onApplied: (VpnServerConfig) -> Unit
) {
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    var progress by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<RealityScannerManager.ScanResult?>(null) }
    var isScanning by remember { mutableStateOf(true) }
    var appliedFp by remember { mutableStateOf(server.config["fp"] ?: "chrome") }

    val strStarting = stringResource(R.string.reality_scanner_starting)
    val strAnalyzing = stringResource(R.string.reality_scanner_analyzing)
    val strCheckingPort = stringResource(R.string.reality_scanner_checking_port)
    val strDone = stringResource(R.string.ok_button)

    LaunchedEffect(Unit) {
        progress = strStarting
        scope.launch {
            result = RealityScannerManager.scan(server.host, server.port) { msg ->
                progress = msg
            }
            isScanning = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = scheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.reality_scanner_title), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = scheme.onSurface)
                Text(server.name, color = scheme.onSurfaceVariant, fontSize = 12.sp)
                HorizontalDivider(color = scheme.outline)

                if (isScanning) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                    ) {
                        CircularProgressIndicator(color = Color(0xFFFF1744))
                        Text(progress, color = scheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                } else {
                    val r = result
                    if (r == null) {
                        Text(stringResource(R.string.reality_scanner_error), color = Color.Red)
                    } else {
                        // TCP Ping
                        ScanInfoRow(
                            stringResource(R.string.reality_scanner_tcp_ping),
                            if (r.tcpPingMs != null) "${r.tcpPingMs} ms" else stringResource(R.string.reality_scanner_unavailable)
                        )

                        // Ports
                        Text(stringResource(R.string.reality_scanner_ports), color = scheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        r.portResults.forEach { pr ->
                            val isRecommended = pr.port == r.recommendedPort
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(modifier = Modifier.size(6.dp).background(
                                        if (pr.pingMs != null) Color(0xFF00CC66) else Color(0xFFFF4444), CircleShape
                                    ))
                                    Text(":${pr.port}", color = if (isRecommended) Color(0xFFFF1744) else scheme.onSurface, fontSize = 13.sp)
                                    if (isRecommended) Text(stringResource(R.string.reality_scanner_recommended), color = Color(0xFFFF1744), fontSize = 10.sp)
                                }
                                Text(
                                    if (pr.pingMs != null) "${pr.pingMs} ms" else stringResource(R.string.reality_scanner_timeout),
                                    color = pingColor(pr.pingMs), fontSize = 12.sp
                                )
                            }
                        }

                        HorizontalDivider(color = scheme.outline)

                        // Recommended fingerprint
                        Text(stringResource(R.string.reality_scanner_fingerprint), color = scheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(R.string.reality_scanner_recommended_fp, r.recommendedFingerprint),
                            color = Color(0xFFFF1744), fontSize = 14.sp, fontWeight = FontWeight.Bold
                        )

                        // Fingerprint picker
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            RealityScannerManager.FINGERPRINTS.forEach { fp ->
                                val isRecommended = fp == r.recommendedFingerprint
                                val isSelected = fp == appliedFp
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .border(1.dp, if (isSelected) Color(0xFFFF1744) else scheme.outline, RoundedCornerShape(8.dp))
                                        .background(if (isSelected) Color(0xFFFF1744).copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(8.dp)),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { appliedFp = fp },
                                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFF1744))
                                    )
                                    Text(fp, color = scheme.onSurface, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    if (isRecommended) {
                                        Icon(Icons.Default.Check, null, tint = Color(0xFF00CC66), modifier = Modifier.size(14.dp).padding(end = 8.dp))
                                    }
                                }
                            }
                        }

                        // Apply button
                        Button(
                            onClick = {
                                val newConfig = server.copy(
                                    config = server.config.toMutableMap().apply {
                                        put("fp", appliedFp)
                                        put("fingerprint", appliedFp)
                                    }
                                )
                                repository.updateServer(newConfig)
                                onApplied(newConfig)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744))
                        ) {
                            Text(stringResource(R.string.reality_scanner_apply_fp, appliedFp))
                        }
                    }
                }

                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.close_button), color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ScanInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private fun pingColor(ms: Int?): Color = when {
    ms == null -> Color(0xFFFF4444)
    ms < 100 -> Color(0xFF00CC66)
    ms < 300 -> Color(0xFFFFAA00)
    else -> Color(0xFFFF4444)
}
