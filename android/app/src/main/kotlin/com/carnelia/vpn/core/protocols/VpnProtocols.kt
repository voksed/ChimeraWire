package com.carnelia.vpn.core.protocols

import android.content.Context
import com.carnelia.vpn.core.VpnErrorCode
import com.carnelia.vpn.core.VpnServerConfig
import com.carnelia.vpn.core.ConnectionState
import com.carnelia.vpn.core.XrayCoreManager
import com.carnelia.vpn.utils.AppLogger
import kotlinx.coroutines.*
import com.v2raytun.android.service.HevTunnel
import de.blinkt.openvpn.core.VpnStatus
import de.blinkt.openvpn.VpnProfile

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

class OpenVpnProtocol : IVpnProtocol {
    
    private var connectionState = ConnectionState.DISCONNECTED
    private val stateListeners = mutableListOf<(ConnectionState) -> Unit>()
    private val bytesListeners = mutableListOf<(Long, Long) -> Unit>()
    
    private var bytesSent = 0L
    private var bytesReceived = 0L

    // Listener for library events
    private val vpnStatusListener = object : de.blinkt.openvpn.core.VpnStatus.StateListener {
        override fun updateState(state: String?, logmessage: String?, localizedResId: Int, level: de.blinkt.openvpn.core.ConnectionStatus?, intent: android.content.Intent?) {
            val newState = when (level) {
                de.blinkt.openvpn.core.ConnectionStatus.LEVEL_CONNECTED -> ConnectionState.CONNECTED
                de.blinkt.openvpn.core.ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
                de.blinkt.openvpn.core.ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED -> ConnectionState.CONNECTING
                de.blinkt.openvpn.core.ConnectionStatus.LEVEL_AUTH_FAILED,
                de.blinkt.openvpn.core.ConnectionStatus.LEVEL_NONETWORK -> ConnectionState.ERROR
                else -> ConnectionState.DISCONNECTED
            }
            updateConnectionState(newState)
        }

        override fun setConnectedVPN(uuid: String?) {}
    }

    private val vpnByteListener = object : de.blinkt.openvpn.core.VpnStatus.ByteCountListener {
        override fun updateByteCount(inBytes: Long, outBytes: Long, diffIn: Long, diffOut: Long) {
             bytesReceived = inBytes
             bytesSent = outBytes
             bytesListeners.forEach { it(bytesSent, bytesReceived) }
        }
    }

    override suspend fun prepare(): VpnErrorCode = VpnErrorCode.NO_ERROR
    
    override suspend fun start(config: VpnServerConfig): VpnErrorCode {
        updateConnectionState(ConnectionState.CONNECTING)
        try {
            AppLogger.log("OpenVpnProtocol: Starting OpenVPN UI...")
            
            // Register listeners
            de.blinkt.openvpn.core.VpnStatus.addStateListener(vpnStatusListener)
            de.blinkt.openvpn.core.VpnStatus.addByteCountListener(vpnByteListener)

            // Launch the helper which starts the activity
            com.carnelia.vpn.utils.OpenVpnHelper.startVpn(com.carnelia.vpn.CarheliaApplication.instance, config)
            
            return VpnErrorCode.NO_ERROR
            
        } catch (e: Exception) {
            AppLogger.error("OpenVpnProtocol", e)
            updateConnectionState(ConnectionState.ERROR)
            return VpnErrorCode.CONNECTION_FAILED
        }
    }
    
    override suspend fun stop() {
        updateConnectionState(ConnectionState.DISCONNECTING)
        de.blinkt.openvpn.core.VpnStatus.removeStateListener(vpnStatusListener)
        de.blinkt.openvpn.core.VpnStatus.removeByteCountListener(vpnByteListener)
        
        // Try to stop service
        try {
            val intent = android.content.Intent(com.carnelia.vpn.CarheliaApplication.instance, de.blinkt.openvpn.core.OpenVPNService::class.java)
            intent.action = de.blinkt.openvpn.core.OpenVPNService.DISCONNECT_VPN
            com.carnelia.vpn.CarheliaApplication.instance.startService(intent)
        } catch (e: Exception) {
            AppLogger.error("OpenVpnProtocol: Stop failed", e)
        }
        
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
    
    override fun onNetworkInterfaceCreated(fileDescriptor: android.os.ParcelFileDescriptor) {}
    
    private fun updateConnectionState(newState: ConnectionState) {
        if (connectionState != newState) {
            connectionState = newState
            stateListeners.forEach { it(newState) }
        }
    }
}

/**
 * Xray Protocol (Process-based)
 * Uses external libxray_core.so process + Tun2Socks (Local bridge)
 */
class XrayVpnProtocol(private val context: Context) : IVpnProtocol {

    private var connectionState = ConnectionState.DISCONNECTED
    private var bytesSent = 0L
    private var bytesReceived = 0L
    // tun2socks replaced by hev-socks5-tunnel (same approach as V2RayTun)

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var isRunning = false
    private var currentConfig: VpnServerConfig? = null

    private val stateListeners = mutableListOf<(ConnectionState) -> Unit>()
    private val bytesListeners = mutableListOf<(Long, Long) -> Unit>()

    private fun installVlessErrorRecovery(config: VpnServerConfig) {
        XrayCoreManager.onVlessProtocolError = {
            if (isRunning) {
                AppLogger.log("XrayVpnProtocol: VLESS recovery — hot-restarting xray, tun2socks stays up")
                scope.launch {
                    XrayCoreManager.restartCore(context, config)
                }
            }
        }
    }

    override suspend fun prepare(): VpnErrorCode {
        return VpnErrorCode.NO_ERROR
    }

    override suspend fun start(config: VpnServerConfig): VpnErrorCode {
        updateConnectionState(ConnectionState.CONNECTING)
        isRunning = true
        currentConfig = config

        try {
            AppLogger.log("XrayVpnProtocol: Starting Xray Core via Process...")
            installVlessErrorRecovery(config)
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
            currentConfig = config
            installVlessErrorRecovery(config)
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
            HevTunnel.stop()
            XrayCoreManager.stopCore()
        } catch(e: Exception) {
             AppLogger.error("XrayVpnProtocol: Stop error", e)
        }
        XrayCoreManager.onVlessProtocolError = null
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
                AppLogger.log("XrayVpnProtocol: Starting hev-socks5-tunnel bridge (SOCKS5)...")
                HevTunnel.start(
                    context = context,
                    vpnInterface = fileDescriptor,
                    socks5Port = XrayCoreManager.LOCAL_PORT,
                    tunIp = "10.111.222.1",
                    mtu = 1280
                )
                AppLogger.log("XrayVpnProtocol: Tunnel Established!")

                // Stats loop + xray port watchdog
                startStatsLoop()
                startXrayWatchdog()

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

    /**
     * Watchdog: every 20s checks that xray's port is still reachable.
     * If xray has crashed or become unresponsive, hot-restarts it while
     * keeping the tun2socks tunnel alive.
     */
    private fun startXrayWatchdog() {
        val cfg = currentConfig ?: return
        scope.launch {
            var consecutiveFails = 0
            while (isRunning) {
                delay(20_000)
                if (!isRunning) break
                val alive = try {
                    withContext(Dispatchers.IO) {
                        java.net.Socket().use { s ->
                            s.connect(java.net.InetSocketAddress("127.0.0.1", XrayCoreManager.LOCAL_PORT), 1000)
                        }
                    }
                    true
                } catch (_: Exception) { false }

                if (!alive) {
                    consecutiveFails++
                    AppLogger.log("XrayVpnProtocol: Watchdog — xray unreachable (fail #$consecutiveFails)")
                    if (consecutiveFails >= 2) {
                        consecutiveFails = 0
                        AppLogger.log("XrayVpnProtocol: Watchdog — hot-restarting xray")
                        XrayCoreManager.restartCore(context, cfg)
                    }
                } else {
                    consecutiveFails = 0
                }
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
 * Factory for creating protocol instances
 */
object ProtocolFactory {
    fun createProtocol(context: Context, protocol: com.carnelia.vpn.core.VpnProtocol): IVpnProtocol {
        return when (protocol) {
            com.carnelia.vpn.core.VpnProtocol.OUTLINE -> OutlineVpnProtocol()
            
            com.carnelia.vpn.core.VpnProtocol.SHADOWSOCKS,
            com.carnelia.vpn.core.VpnProtocol.WIREGUARD,
            com.carnelia.vpn.core.VpnProtocol.AMNEZIA_WG,
            com.carnelia.vpn.core.VpnProtocol.VLESS,
            com.carnelia.vpn.core.VpnProtocol.VMESS,
            com.carnelia.vpn.core.VpnProtocol.SOCKS,
            com.carnelia.vpn.core.VpnProtocol.HTTP,
            com.carnelia.vpn.core.VpnProtocol.TROJAN -> XrayVpnProtocol(context)
            
            com.carnelia.vpn.core.VpnProtocol.OPENVPN -> OpenVpnProtocol()
            
            else -> XrayVpnProtocol(context) 
        }
    }
}
