package com.carnelia.vpn.core.protocols

import android.content.Context
import com.carnelia.vpn.core.VpnErrorCode
import com.carnelia.vpn.core.VpnServerConfig
import com.carnelia.vpn.core.ConnectionState
import com.carnelia.vpn.core.XrayCoreManager
import com.carnelia.vpn.utils.AppLogger
import kotlinx.coroutines.*
import org.json.JSONObject
import tun2socks.Tun2socks
import shadowsocks.Shadowsocks

/**
 * Base VPN Protocol Interface
 */
interface IVpnProtocol {
    
    suspend fun prepare(): VpnErrorCode
    
    suspend fun start(config: VpnServerConfig): VpnErrorCode
    
    suspend fun stop()
    
    fun getConnectionState(): ConnectionState
    
    fun getBytesTransferred(): Pair<Long, Long>
    
    fun onConnectionStateChanged(listener: (ConnectionState) -> Unit)
    
    fun onBytesChanged(listener: (Long, Long) -> Unit)
    
    fun onNetworkInterfaceCreated(fileDescriptor: android.os.ParcelFileDescriptor)
}


/**
 * Xray Protocol (Process-based)
 * Uses external libxray_core.so process + Tun2Socks (Local bridge)
 */
class XrayVpnProtocol(private val context: Context) : IVpnProtocol {
    
    private var connectionState = ConnectionState.DISCONNECTED
    private var bytesSent = 0L
    private var bytesReceived = 0L
    private var activeTunnel: tun2socks.Tunnel? = null
    
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var isRunning = false
    
    private val stateListeners = mutableListOf<(ConnectionState) -> Unit>()
    private val bytesListeners = mutableListOf<(Long, Long) -> Unit>()
    
    override suspend fun prepare(): VpnErrorCode {
        return VpnErrorCode.NO_ERROR
    }
    
    override suspend fun start(config: VpnServerConfig): VpnErrorCode {
        updateConnectionState(ConnectionState.CONNECTING)
        isRunning = true
        
        try {
            AppLogger.log("XrayVpnProtocol: Starting Xray Core via Process...")
            XrayCoreManager.startCore(context, config)

            // Poll until Xray actually binds port 10808 (max 5 sec)
            var waited = 0
            while (waited < 5000) {
                try {
                    withContext(Dispatchers.IO) {
                        java.net.Socket("127.0.0.1", XrayCoreManager.LOCAL_PORT).use {}
                    }
                    break  // port open
                } catch (e: Exception) {
                    delay(200)
                    waited += 200
                }
            }
            if (waited >= 5000) {
                AppLogger.error("XrayVpnProtocol: Xray did not bind port ${XrayCoreManager.LOCAL_PORT} in 5s")
                throw Exception("Xray failed to start (port ${XrayCoreManager.LOCAL_PORT} not open)")
            }
            AppLogger.log("XrayVpnProtocol: Xray ready on port ${XrayCoreManager.LOCAL_PORT} after ${waited}ms")
            
            // Xray is running. We report connected so VpnService creates interface.
            // Then onNetworkInterfaceCreated starts Tun2Socks (to localhost).
            updateConnectionState(ConnectionState.CONNECTED)
            return VpnErrorCode.NO_ERROR
            
        } catch (e: Exception) {
            AppLogger.error("XrayVpnProtocol: Start failed", e)
            XrayCoreManager.stopCore()
            return VpnErrorCode.PROTOCOL_ERROR
        }
    }
    
    /**
     * Hot server switch: restarts Xray with a new config without tearing down the TUN interface.
     * The tun2socks tunnel keeps running; only the upstream Xray process is replaced.
     */
    suspend fun switchServer(config: VpnServerConfig) {
        updateConnectionState(ConnectionState.RECONNECTING)
        try {
            AppLogger.log("XrayVpnProtocol: Hot-switching server to ${config.host}:${config.port}")
            XrayCoreManager.stopCore()
            delay(300)
            XrayCoreManager.startCore(context, config)

            // Wait until Xray binds port 10808 (max 5 s)
            var waited = 0
            while (waited < 5000) {
                try {
                    withContext(Dispatchers.IO) {
                        java.net.Socket("127.0.0.1", XrayCoreManager.LOCAL_PORT).use {}
                    }
                    break
                } catch (_: Exception) {
                    delay(200)
                    waited += 200
                }
            }
            if (waited >= 5000) {
                AppLogger.error("XrayVpnProtocol: Xray did not bind port ${XrayCoreManager.LOCAL_PORT} after switch")
                updateConnectionState(ConnectionState.ERROR)
                return
            }
            AppLogger.log("XrayVpnProtocol: Server switched, Xray ready after ${waited}ms")
            updateConnectionState(ConnectionState.CONNECTED)
        } catch (e: Exception) {
            AppLogger.error("XrayVpnProtocol: switchServer failed", e)
            updateConnectionState(ConnectionState.ERROR)
        }
    }

    override suspend fun stop() {
        isRunning = false
        updateConnectionState(ConnectionState.DISCONNECTING)
        try {
            activeTunnel?.disconnect()
            activeTunnel = null
            XrayCoreManager.stopCore()
        } catch(e: Exception) {
             AppLogger.error("XrayVpnProtocol: Stop error", e)
        }
        scope.cancel()
        updateConnectionState(ConnectionState.DISCONNECTED)
    }
    
    override fun getConnectionState(): ConnectionState = connectionState
    
    override fun getBytesTransferred(): Pair<Long, Long> = Pair(bytesSent, bytesReceived)
    
    override fun onConnectionStateChanged(listener: (ConnectionState) -> Unit) {
        stateListeners.add(listener)
    }
    
    override fun onBytesChanged(listener: (Long, Long) -> Unit) {
        bytesListeners.add(listener)
    }

    override fun onNetworkInterfaceCreated(fileDescriptor: android.os.ParcelFileDescriptor) {
        if (!isRunning) return
        
        scope.launch {
            try {
                AppLogger.log("XrayVpnProtocol: Connecting Tun2Socks to Local Xray Bridge...")
                
                // Config for Tun2Socks -> Localhost Xray Port
                // Go/mobile shadowsocks client expects "method" as cipher field.
                val jsonConfig = JSONObject()
                jsonConfig.put("host", "127.0.0.1")
                jsonConfig.put("port", XrayCoreManager.LOCAL_PORT)
                jsonConfig.put("password", XrayCoreManager.LOCAL_PASSWORD)
                jsonConfig.put("method", XrayCoreManager.LOCAL_METHOD)
                
                // Tun2Socks client
                val client = Shadowsocks.newClientFromJSON(jsonConfig.toString())
                val tunnel = Tun2socks.connectShadowsocksTunnel(fileDescriptor.fd.toLong(), client, true)
                
                activeTunnel = tunnel
                AppLogger.log("XrayVpnProtocol: Tunnel Established!")
                
                // Stats loop
                startStatsLoop()

            } catch (e: Exception) {
                AppLogger.error("XrayVpnProtocol: TUN Bridge Failed", e)
                stop()
            }
        }
    }
    
    private fun startStatsLoop() {
        scope.launch {
             val uid = android.os.Process.myUid()
             while (isRunning) {
                 try {
                     val rx = android.net.TrafficStats.getUidRxBytes(uid)
                     val tx = android.net.TrafficStats.getUidTxBytes(uid)
                     if (rx != bytesReceived || tx != bytesSent) {
                        bytesReceived = rx
                        bytesSent = tx
                        bytesListeners.forEach { it(bytesSent, bytesReceived) }
                     }
                 } catch (e: Exception) {}
                 delay(2000)
             }
        }
    }
    
    private fun updateConnectionState(newState: ConnectionState) {
        if (connectionState != newState) {
            connectionState = newState
            stateListeners.forEach { it(newState) }
        }
    }
}

/**
 * Sing-box protocol — uses libsingbox.so for Hysteria2, TUIC, WireGuard, WARP, VLESS, VMess, Trojan, SS
 * Exposes SS:10811 for tun2socks (same bridge pattern as XrayVpnProtocol on :10808)
 */
class SingboxVpnProtocol(private val context: Context) : IVpnProtocol {

    private var connectionState = ConnectionState.DISCONNECTED
    private var bytesSent = 0L
    private var bytesReceived = 0L
    private var activeTunnel: tun2socks.Tunnel? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var isRunning = false
    private val stateListeners = mutableListOf<(ConnectionState) -> Unit>()
    private val bytesListeners = mutableListOf<(Long, Long) -> Unit>()

    override suspend fun prepare() = VpnErrorCode.NO_ERROR

    override suspend fun start(config: com.carnelia.vpn.core.VpnServerConfig): VpnErrorCode {
        updateConnectionState(ConnectionState.CONNECTING)
        isRunning = true

        // sing-box (и AmneziaWG/WG поверх него) часто не поднимается с первой попытки:
        // процесс может умереть на гонке DNS/биндинга порта, а UDP-протоколам нужно
        // несколько попыток рукопожатия. Раньше пользователь жал Connect по 3 раза вручную —
        // теперь повторяем автоматически.
        val maxAttempts = 3
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            if (!isRunning) return VpnErrorCode.CONNECTION_FAILED
            try {
                AppLogger.log("SingboxVpnProtocol: Starting sing-box (${config.protocol}), attempt $attempt/$maxAttempts...")
                com.carnelia.vpn.core.SingboxCoreManager.startCore(context, config)

                // Wait for sing-box SOCKS5
                var waited = 0
                var portReady = false
                while (waited < 6000) {
                    try {
                        withContext(Dispatchers.IO) {
                            java.net.Socket("127.0.0.1", com.carnelia.vpn.core.SingboxCoreManager.SOCKS5_PORT).use {}
                        }
                        portReady = true
                        break
                    } catch (_: Exception) { delay(200); waited += 200 }
                }
                if (!portReady) throw Exception("Sing-box SOCKS5 port ${com.carnelia.vpn.core.SingboxCoreManager.SOCKS5_PORT} not ready")

                // Start Xray as SS:10808 → SOCKS5:10812 bridge (tun2socks → Xray → sing-box)
                AppLogger.log("SingboxVpnProtocol: Starting Xray SS bridge → sing-box :${com.carnelia.vpn.core.SingboxCoreManager.SOCKS5_PORT}...")
                com.carnelia.vpn.core.XrayCoreManager.startCoreAsSocks5Bridge(context)

                AppLogger.log("SingboxVpnProtocol: Ready after ${waited}ms (attempt $attempt)")
                updateConnectionState(ConnectionState.CONNECTED)
                return VpnErrorCode.NO_ERROR
            } catch (e: Exception) {
                lastError = e
                AppLogger.error("SingboxVpnProtocol: Start failed (attempt $attempt/$maxAttempts)", e)
                com.carnelia.vpn.core.SingboxCoreManager.stopCore()
                com.carnelia.vpn.core.XrayCoreManager.stopCore()
                if (attempt < maxAttempts && isRunning) delay(700)
            }
        }
        AppLogger.error("SingboxVpnProtocol: Start failed after $maxAttempts attempts", lastError)
        updateConnectionState(ConnectionState.ERROR)
        return VpnErrorCode.CONNECTION_FAILED
    }

    override suspend fun stop() {
        isRunning = false
        updateConnectionState(ConnectionState.DISCONNECTING)
        try {
            activeTunnel?.disconnect(); activeTunnel = null
            com.carnelia.vpn.core.SingboxCoreManager.stopCore()
            com.carnelia.vpn.core.XrayCoreManager.stopCore()
        } catch (_: Exception) {}
        scope.cancel()
        updateConnectionState(ConnectionState.DISCONNECTED)
    }

    override fun getConnectionState() = connectionState
    override fun getBytesTransferred() = Pair(bytesSent, bytesReceived)
    override fun onConnectionStateChanged(listener: (ConnectionState) -> Unit) { stateListeners.add(listener) }
    override fun onBytesChanged(listener: (Long, Long) -> Unit) { bytesListeners.add(listener) }

    override fun onNetworkInterfaceCreated(fileDescriptor: android.os.ParcelFileDescriptor) {
        if (!isRunning) return
        scope.launch {
            try {
                AppLogger.log("SingboxVpnProtocol: Tun2Socks → Xray SS:${com.carnelia.vpn.core.XrayCoreManager.LOCAL_PORT} → sing-box SOCKS5:${com.carnelia.vpn.core.SingboxCoreManager.SOCKS5_PORT}")
                val json = org.json.JSONObject().apply {
                    put("host", "127.0.0.1")
                    put("port", com.carnelia.vpn.core.XrayCoreManager.LOCAL_PORT)
                    put("password", com.carnelia.vpn.core.XrayCoreManager.LOCAL_PASSWORD)
                    put("method", com.carnelia.vpn.core.XrayCoreManager.LOCAL_METHOD)
                }
                val client = shadowsocks.Shadowsocks.newClientFromJSON(json.toString())
                activeTunnel = Tun2socks.connectShadowsocksTunnel(fileDescriptor.fd.toLong(), client, true)
                AppLogger.log("SingboxVpnProtocol: Tunnel established!")

                // Hysteria2 (and TUIC/QUIC protocols) drop idle sessions after ~5-6 min.
                // Send a TCP connect via sing-box SOCKS5 every 2 min to keep QUIC stream alive.
                scope.launch {
                    while (isActive && isRunning) {
                        delay(120_000L)
                        try {
                            withContext(Dispatchers.IO) {
                                val proxy = java.net.Proxy(
                                    java.net.Proxy.Type.SOCKS,
                                    java.net.InetSocketAddress("127.0.0.1", com.carnelia.vpn.core.SingboxCoreManager.SOCKS5_PORT)
                                )
                                java.net.Socket(proxy).use { s ->
                                    s.soTimeout = 3000
                                    s.connect(java.net.InetSocketAddress("1.1.1.1", 53), 3000)
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }

                val uid = android.os.Process.myUid()
                while (isRunning) {
                    val rx = android.net.TrafficStats.getUidRxBytes(uid)
                    val tx = android.net.TrafficStats.getUidTxBytes(uid)
                    if (rx != bytesReceived || tx != bytesSent) {
                        bytesReceived = rx; bytesSent = tx
                        bytesListeners.forEach { it(bytesSent, bytesReceived) }
                    }
                    kotlinx.coroutines.delay(2000)
                }
            } catch (e: Exception) {
                AppLogger.error("SingboxVpnProtocol: Tunnel failed", e)
                stop()
            }
        }
    }

    private fun updateConnectionState(s: ConnectionState) {
        if (connectionState != s) { connectionState = s; stateListeners.forEach { it(s) } }
    }
}

/**
 * Hysteria2 protocol:
 *   Hysteria2 binary → SOCKS5 on :10810
 *   Xray (SS inbound :10808 → SOCKS5 outbound :10810) → Tun2Socks (same as other protocols)
 */
class Hysteria2VpnProtocol(private val context: Context) : IVpnProtocol {

    private var connectionState = ConnectionState.DISCONNECTED
    private var bytesSent = 0L
    private var bytesReceived = 0L
    private var activeTunnel: tun2socks.Tunnel? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var isRunning = false
    private val stateListeners = mutableListOf<(ConnectionState) -> Unit>()
    private val bytesListeners = mutableListOf<(Long, Long) -> Unit>()

    override suspend fun prepare() = VpnErrorCode.NO_ERROR

    override suspend fun start(config: com.carnelia.vpn.core.VpnServerConfig): VpnErrorCode {
        updateConnectionState(ConnectionState.CONNECTING)
        isRunning = true
        try {
            // Step 1: Start Hysteria2 — SOCKS5 on port 10810
            AppLogger.log("Hysteria2VpnProtocol: Starting hysteria2 process...")
            com.carnelia.vpn.core.Hysteria2ProcessManager.start(context, config)

            // Step 2: Start Xray as SS:10808 → SOCKS5:10810 bridge
            AppLogger.log("Hysteria2VpnProtocol: Starting Xray SS bridge → Hy2 SOCKS5...")
            com.carnelia.vpn.core.XrayCoreManager.startCoreAsSocks5Bridge(context)

            // Step 3: Wait for Xray port
            var waited = 0
            while (waited < 5000) {
                try {
                    withContext(Dispatchers.IO) {
                        java.net.Socket("127.0.0.1", com.carnelia.vpn.core.XrayCoreManager.LOCAL_PORT).use {}
                    }
                    break
                } catch (_: Exception) { delay(200); waited += 200 }
            }
            updateConnectionState(ConnectionState.CONNECTED)
            return VpnErrorCode.NO_ERROR
        } catch (e: Exception) {
            AppLogger.error("Hysteria2VpnProtocol: Start failed", e)
            com.carnelia.vpn.core.Hysteria2ProcessManager.stop()
            com.carnelia.vpn.core.XrayCoreManager.stopCore()
            updateConnectionState(ConnectionState.ERROR)
            return VpnErrorCode.CONNECTION_FAILED
        }
    }

    override suspend fun stop() {
        isRunning = false
        updateConnectionState(ConnectionState.DISCONNECTING)
        try {
            activeTunnel?.disconnect(); activeTunnel = null
            com.carnelia.vpn.core.Hysteria2ProcessManager.stop()
            com.carnelia.vpn.core.XrayCoreManager.stopCore()
        } catch (_: Exception) {}
        scope.cancel()
        updateConnectionState(ConnectionState.DISCONNECTED)
    }

    override fun getConnectionState() = connectionState
    override fun getBytesTransferred() = Pair(bytesSent, bytesReceived)
    override fun onConnectionStateChanged(l: (ConnectionState) -> Unit) { stateListeners.add(l) }
    override fun onBytesChanged(l: (Long, Long) -> Unit) { bytesListeners.add(l) }

    override fun onNetworkInterfaceCreated(fileDescriptor: android.os.ParcelFileDescriptor) {
        if (!isRunning) return
        scope.launch {
            try {
                // Same as XrayVpnProtocol — tun2socks → local Xray SS :10808
                val jsonConfig = org.json.JSONObject()
                jsonConfig.put("host", "127.0.0.1")
                jsonConfig.put("port", com.carnelia.vpn.core.XrayCoreManager.LOCAL_PORT)
                jsonConfig.put("password", com.carnelia.vpn.core.XrayCoreManager.LOCAL_PASSWORD)
                jsonConfig.put("method", com.carnelia.vpn.core.XrayCoreManager.LOCAL_METHOD)
                val client = shadowsocks.Shadowsocks.newClientFromJSON(jsonConfig.toString())
                val tunnel = Tun2socks.connectShadowsocksTunnel(fileDescriptor.fd.toLong(), client, true)
                activeTunnel = tunnel
                AppLogger.log("Hysteria2VpnProtocol: Tun2Socks tunnel established!")
                // Stats
                val uid = android.os.Process.myUid()
                while (isRunning) {
                    val rx = android.net.TrafficStats.getUidRxBytes(uid)
                    val tx = android.net.TrafficStats.getUidTxBytes(uid)
                    if (rx != bytesReceived || tx != bytesSent) {
                        bytesReceived = rx; bytesSent = tx
                        bytesListeners.forEach { it(bytesSent, bytesReceived) }
                    }
                    kotlinx.coroutines.delay(2000)
                }
            } catch (e: Exception) {
                AppLogger.error("Hysteria2VpnProtocol: Tunnel failed", e)
                stop()
            }
        }
    }

    private fun updateConnectionState(s: ConnectionState) {
        if (connectionState != s) { connectionState = s; stateListeners.forEach { it(s) } }
    }
}

/**
 * AmneziaWG protocol — native userspace tunnel via libamneziawg.so.
 * Supports AWG obfuscation extensions (Jc/Jmin/Jmax/S1/S2/H1-H4/I1-I5).
 * No sing-box involved — amneziawg-go reads/writes the TUN fd directly.
 *
 * Socket loop protection: CarheliaVpnService always adds addDisallowedApplication(packageName),
 * so our process traffic bypasses the VPN tunnel — AWG UDP reaches the server directly.
 */
class AmneziaWgVpnProtocol(private val context: Context) : IVpnProtocol {

    private var connectionState = ConnectionState.DISCONNECTED
    private var bytesSent = 0L
    private var bytesReceived = 0L
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var isRunning = false
    private val stateListeners = mutableListOf<(ConnectionState) -> Unit>()
    private val bytesListeners = mutableListOf<(Long, Long) -> Unit>()
    private var activeConfig: com.carnelia.vpn.core.VpnServerConfig? = null

    override suspend fun prepare() = VpnErrorCode.NO_ERROR

    override suspend fun start(config: com.carnelia.vpn.core.VpnServerConfig): VpnErrorCode {
        activeConfig = config
        isRunning = true
        AppLogger.log("AmneziaWgVpnProtocol: start() — reporting CONNECTED (tunnel starts on TUN fd)")
        updateConnectionState(ConnectionState.CONNECTED)
        return VpnErrorCode.NO_ERROR
    }

    override suspend fun stop() {
        isRunning = false
        updateConnectionState(ConnectionState.DISCONNECTING)
        try {
            com.carnelia.vpn.core.AmneziaWgCoreManager.stopTunnel()
        } catch (_: Exception) {}
        scope.cancel()
        updateConnectionState(ConnectionState.DISCONNECTED)
    }

    override fun getConnectionState() = connectionState
    override fun getBytesTransferred() = Pair(bytesSent, bytesReceived)
    override fun onConnectionStateChanged(l: (ConnectionState) -> Unit) { stateListeners.add(l) }
    override fun onBytesChanged(l: (Long, Long) -> Unit) { bytesListeners.add(l) }

    override fun onNetworkInterfaceCreated(fileDescriptor: android.os.ParcelFileDescriptor) {
        if (!isRunning) return
        val config = activeConfig ?: run {
            AppLogger.error("AmneziaWgVpnProtocol: no active config in onNetworkInterfaceCreated")
            return
        }
        scope.launch {
            val ok = com.carnelia.vpn.core.AmneziaWgCoreManager.startTunnel(fileDescriptor.fd, config)
            if (!ok) {
                AppLogger.error("AmneziaWgVpnProtocol: tunnel failed to start")
                updateConnectionState(ConnectionState.ERROR)
                return@launch
            }
            val uid = android.os.Process.myUid()
            while (isRunning) {
                val rx = android.net.TrafficStats.getUidRxBytes(uid)
                val tx = android.net.TrafficStats.getUidTxBytes(uid)
                if (rx != bytesReceived || tx != bytesSent) {
                    bytesReceived = rx; bytesSent = tx
                    bytesListeners.forEach { it(bytesSent, bytesReceived) }
                }
                delay(2000)
            }
        }
    }

    private fun updateConnectionState(s: ConnectionState) {
        if (connectionState != s) { connectionState = s; stateListeners.forEach { it(s) } }
    }
}

/**
 * Auto-selects the best core engine per protocol:
 *   AmneziaWG → AmneziaWgVpnProtocol (native libamneziawg.so, AWG obfuscation)
 *   Sing-box  → Hysteria2, TUIC, WARP, WireGuard
 *   Xray      → VLESS+REALITY/XTLS, VMess, Trojan, Shadowsocks, SOCKS, HTTP
 *   OpenVPN   → OpenVPN
 *   Outline   → Sing-box (same as Shadowsocks)
 */
object ProtocolFactory {

    private val SINGBOX_PROTOCOLS = setOf(
        com.carnelia.vpn.core.VpnProtocol.HYSTERIA2,
        com.carnelia.vpn.core.VpnProtocol.TUIC,
        com.carnelia.vpn.core.VpnProtocol.WARP,
        com.carnelia.vpn.core.VpnProtocol.WIREGUARD
    )

    fun createProtocol(context: Context, protocol: com.carnelia.vpn.core.VpnProtocol): IVpnProtocol {
        return when {
            protocol == com.carnelia.vpn.core.VpnProtocol.AMNEZIA_WG -> AmneziaWgVpnProtocol(context)
            protocol == com.carnelia.vpn.core.VpnProtocol.OUTLINE -> SingboxVpnProtocol(context)
            protocol in SINGBOX_PROTOCOLS -> SingboxVpnProtocol(context)
            else -> XrayVpnProtocol(context) // VLESS, VMess, Trojan, SS, SOCKS, HTTP
        }
    }
}
