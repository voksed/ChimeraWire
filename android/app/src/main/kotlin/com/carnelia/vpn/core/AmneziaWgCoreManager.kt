package com.carnelia.vpn.core

import com.carnelia.vpn.utils.AppLogger
import org.amnezia.awg.GoBackend

object AmneziaWgCoreManager {

    private var tunnelHandle: Int = -1

    fun startTunnel(tunFd: Int, config: VpnServerConfig): Boolean {
        stopTunnel()
        val settings = buildSettings(config)
        AppLogger.log("AmneziaWgCoreManager: awgVersion=${GoBackend.awgVersion()}")
        AppLogger.log("AmneziaWgCoreManager: starting tunnel (fd=$tunFd)")
        AppLogger.log("AmneziaWgCoreManager: settings=\n$settings")
        tunnelHandle = GoBackend.awgTurnOn("awg0", tunFd, settings)
        return if (tunnelHandle >= 0) {
            AppLogger.log("AmneziaWgCoreManager: tunnel up, handle=$tunnelHandle")
            val appliedConfig = GoBackend.awgGetConfig(tunnelHandle)
            AppLogger.log("AmneziaWgCoreManager: applied_config=\n$appliedConfig")
            true
        } else {
            AppLogger.error("AmneziaWgCoreManager: awgTurnOn failed (rc=$tunnelHandle)")
            false
        }
    }

    fun stopTunnel() {
        if (tunnelHandle >= 0) {
            GoBackend.awgTurnOff(tunnelHandle)
            AppLogger.log("AmneziaWgCoreManager: tunnel stopped")
            tunnelHandle = -1
        }
    }

    fun isRunning() = tunnelHandle >= 0

    private fun buildSettings(config: VpnServerConfig): String {
        val c = config.config
        return buildString {
            // Interface
            appendLine("private_key=${b64hex(c["private_key"] ?: "")}")
            appendLine("listen_port=0")
            c["Jc"]?.toIntOrNull()?.let   { appendLine("jc=$it") }
            c["Jmin"]?.toIntOrNull()?.let { appendLine("jmin=$it") }
            c["Jmax"]?.toIntOrNull()?.let { appendLine("jmax=$it") }
            c["S1"]?.toIntOrNull()?.let   { appendLine("s1=$it") }
            c["S2"]?.toIntOrNull()?.let   { appendLine("s2=$it") }
            c["H1"]?.toLongOrNull()?.let  { appendLine("h1=$it") }
            c["H2"]?.toLongOrNull()?.let  { appendLine("h2=$it") }
            c["H3"]?.toLongOrNull()?.let  { appendLine("h3=$it") }
            c["H4"]?.toLongOrNull()?.let  { appendLine("h4=$it") }
            c["I1"]?.toIntOrNull()?.let   { appendLine("i1=$it") }
            c["I2"]?.toIntOrNull()?.let   { appendLine("i2=$it") }
            c["I3"]?.toIntOrNull()?.let   { appendLine("i3=$it") }
            c["I4"]?.toIntOrNull()?.let   { appendLine("i4=$it") }
            c["I5"]?.toIntOrNull()?.let   { appendLine("i5=$it") }

            // Peer
            appendLine("public_key=${b64hex(c["public_key"] ?: "")}")
            val psk = c["preshared_key"] ?: c["pre_shared_key"] ?: ""
            if (psk.isNotBlank()) appendLine("preshared_key=${b64hex(psk)}")
            val port = config.port.takeIf { it > 0 } ?: (c["port"]?.toIntOrNull() ?: 51820)
            appendLine("endpoint=${config.host}:$port")
            (c["allowed_ips"] ?: "0.0.0.0/0")
                .split(",").map { it.trim() }.filter { it.isNotBlank() }
                .forEach { appendLine("allowed_ip=$it") }
            val ka = c["keepalive"]?.toIntOrNull() ?: 25
            appendLine("persistent_keepalive_interval=$ka")
        }
    }

    // amneziawg-go IPC uses hex-encoded keys, not base64
    private fun b64hex(b64: String): String = try {
        android.util.Base64.decode(b64.trim(), android.util.Base64.DEFAULT)
            .joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        b64 // already hex or empty
    }
}
