package com.carnelia.vpn.core

import android.content.Context
import com.carnelia.vpn.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.io.File

object Hysteria2ProcessManager {

    private var process: Process? = null
    private var streamJob: kotlinx.coroutines.Job? = null
    private val scope = kotlinx.coroutines.CoroutineScope(
        Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    const val SOCKS5_PORT = 10810

    suspend fun start(context: Context, config: VpnServerConfig) = withContext(Dispatchers.IO) {
        stop()

        val binary = File(context.applicationInfo.nativeLibraryDir, "libhysteria2.so")
        if (!binary.exists()) throw Exception("Hysteria2 binary not found")

        val configJson = buildConfig(config)
        val configFile = File(context.filesDir, "hy2_config.json")
        configFile.writeText(configJson)

        val pb = ProcessBuilder(binary.absolutePath, "-c", configFile.absolutePath)
        pb.directory(context.filesDir)
        pb.redirectErrorStream(true)
        process = pb.start()

        // Stream gobbler
        streamJob = scope.launch {
            try {
                process!!.inputStream.bufferedReader().use { r ->
                    for (line in r.lineSequence()) {
                        if (!this.isActive) break
                        AppLogger.log("Hy2: $line")
                    }
                }
            } catch (_: Exception) {}
        }

        // Wait for SOCKS5 port to open (max 6s)
        var waited = 0
        while (waited < 6000) {
            try {
                withContext(Dispatchers.IO) {
                    java.net.Socket("127.0.0.1", SOCKS5_PORT).use {}
                }
                AppLogger.log("Hysteria2: SOCKS5 ready on port $SOCKS5_PORT after ${waited}ms")
                return@withContext
            } catch (_: Exception) {
                delay(200); waited += 200
            }
        }

        if (process?.isAlive == false) {
            throw Exception("Hysteria2 process died immediately (exit ${process?.exitValue()})")
        }
        throw Exception("Hysteria2: SOCKS5 port $SOCKS5_PORT not ready in 6s")
    }

    fun stop() {
        streamJob?.cancel(); streamJob = null
        process?.destroy(); process = null
    }

    fun isRunning(): Boolean = process?.isAlive == true

    private fun buildConfig(config: VpnServerConfig): String {
        val root = JSONObject()
        root.put("server", "${config.host}:${config.port}")
        root.put("auth", config.config["password"] ?: "")

        // Obfs salamander
        val obfsType = config.config["obfs"] ?: ""
        val obfsPass = config.config["obfs_password"] ?: ""
        if (obfsType == "salamander" && obfsPass.isNotBlank()) {
            root.put("obfs", JSONObject().apply {
                put("type", "salamander")
                put("salamander", JSONObject().put("password", obfsPass))
            })
        }

        // TLS
        val sni = config.config["sni"] ?: config.host
        root.put("tls", JSONObject().apply {
            put("sni", sni)
            put("insecure", config.config["insecure"] == "1")
        })

        // Bandwidth (optional)
        val up = config.config["up_mbps"]?.toIntOrNull() ?: 0
        val down = config.config["down_mbps"]?.toIntOrNull() ?: 0
        if (up > 0 || down > 0) {
            root.put("bandwidth", JSONObject().apply {
                if (up > 0) put("up", "${up} mbps")
                if (down > 0) put("down", "${down} mbps")
            })
        }

        // SOCKS5 outbound for Tun2Socks
        root.put("socks5", JSONObject().apply {
            put("listen", "127.0.0.1:$SOCKS5_PORT")
        })

        return root.toString()
    }
}
