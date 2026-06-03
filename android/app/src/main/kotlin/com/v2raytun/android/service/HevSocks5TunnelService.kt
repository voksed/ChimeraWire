package com.v2raytun.android.service

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import com.carnelia.vpn.utils.AppLogger
import java.io.File

/**
 * JNI wrapper for libhev-socks5-tunnel.so.
 * The class name matches the one registered in the library's JNI_OnLoad:
 *   com/v2raytun/android/service/HevSocks5TunnelService
 */
@Keep
object HevTunnel {

    private var started = false
    private var tunFd = -1
    private var configFile: File? = null

    init {
        try {
            System.loadLibrary("hev-socks5-tunnel")
        } catch (e: UnsatisfiedLinkError) {
            AppLogger.error("HevTunnel: failed to load libhev-socks5-tunnel.so", e)
        }
    }

    @Keep
    private external fun TProxyStartService(configPath: String, fd: Int)

    @Keep
    private external fun TProxyStopService()

    @Keep
    private external fun TProxyGetStats(): LongArray?

    fun start(
        context: Context,
        vpnInterface: ParcelFileDescriptor,
        socks5Port: Int,
        tunIp: String = "10.111.222.1",
        mtu: Int = 1280
    ) {
        if (started) {
            AppLogger.log("HevTunnel: already started")
            return
        }
        try {
            val yaml = buildYaml(socks5Port, tunIp, mtu)
            val file = File(context.filesDir, "hev-socks5-tunnel.yml")
            file.writeText(yaml)
            configFile = file

            // dup + detach so the library owns the fd lifetime
            tunFd = ParcelFileDescriptor.dup(vpnInterface.fileDescriptor).detachFd()
            AppLogger.log("HevTunnel: starting fd=$tunFd mtu=$mtu socks5=127.0.0.1:$socks5Port")

            TProxyStartService(file.absolutePath, tunFd)
            started = true
            AppLogger.log("HevTunnel: started")
        } catch (e: Exception) {
            AppLogger.error("HevTunnel: start failed", e)
            cleanup()
        }
    }

    fun stop() {
        if (!started) return
        try {
            TProxyStopService()
            AppLogger.log("HevTunnel: stopped")
        } catch (e: Exception) {
            AppLogger.error("HevTunnel: stop error", e)
        } finally {
            started = false
            cleanup()
        }
    }

    fun getStats(): LongArray? = try { TProxyGetStats() } catch (_: Throwable) { null }

    private fun cleanup() {
        val fd = tunFd
        tunFd = -1
        if (fd >= 0) try { ParcelFileDescriptor.adoptFd(fd).close() } catch (_: Throwable) {}
        configFile?.delete()
        configFile = null
    }

    private fun buildYaml(socks5Port: Int, tunIp: String, mtu: Int): String = """
tunnel:
  name: tun0
  mtu: $mtu
  multi-queue: false
  ipv4: $tunIp

socks5:
  address: 127.0.0.1
  port: $socks5Port
  udp: 'udp'

misc:
  log-file: stderr
  log-level: warn
""".trimIndent()
}
