package com.carnelia.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.carnelia.vpn.AppFirewallActivity
import com.carnelia.vpn.utils.AppLogger
import com.carnelia.vpn.utils.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream

/**
 * LocalFirewallService — a per-app internet blocker that needs no VPN server.
 *
 * Standard local-firewall technique: establish a VpnService whose allow-list contains
 * ONLY the blocked apps, so just their traffic is pulled into the TUN. Nothing is
 * forwarded — packets are read and discarded — so the captured apps lose all
 * connectivity (IPv4 and IPv6) while every other app keeps its normal, direct network.
 *
 * It is independent of [CarheliaVpnService]: Android permits a single active VpnService,
 * so this runs when the main tunnel is off (the "Black Wall without Connect" mode).
 */
class LocalFirewallService : VpnService() {

    companion object {
        const val ACTION_START = "com.carnelia.vpn.FIREWALL_START"
        const val ACTION_STOP = "com.carnelia.vpn.FIREWALL_STOP"

        private const val NOTIFICATION_ID = 47
        private const val CHANNEL_ID = "firewall_channel"
        // Link-local addresses for the sink interface; never routed off-device.
        private const val TUN4 = "10.47.0.1"
        private const val TUN6 = "fd47:0047:0047::1"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var blockedCount: Int = 0
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stop(); return START_NOT_STICKY }
            else -> start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stop()
    }

    override fun onRevoke() = stop()

    private fun start() {
        val blocked = PrefsManager.getFirewallBlockedApps(this).filter { it != packageName }
        if (blocked.isEmpty()) {
            AppLogger.log("LocalFirewall: no apps to block — not starting")
            stop()
            return
        }

        // Re-establishing replaces any previous interface, so a running instance can be
        // refreshed with a new block-list by simply starting it again.
        closeInterface()

        blockedCount = blocked.size
        startForeground(NOTIFICATION_ID, buildNotification(blocked.size))
        AppLogger.log("LocalFirewall: blocking ${blocked.size} apps")

        try {
            val builder = Builder()
            builder.setSession("ChimeraWire Firewall")
            builder.addAddress(TUN4, 32)
            builder.addAddress(TUN6, 128)
            // Capture every destination for the listed apps, both families, then drop it.
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)

            var applied = 0
            for (pkg in blocked) {
                try {
                    builder.addAllowedApplication(pkg)
                    applied++
                } catch (e: Exception) {
                    // App uninstalled since it was added to the list — skip it.
                    AppLogger.log("LocalFirewall: cannot block $pkg (${e.message})")
                }
            }
            if (applied == 0) {
                AppLogger.log("LocalFirewall: none of the blocked apps are installed — stopping")
                stop()
                return
            }

            if (android.os.Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

            vpnInterface = builder.establish()
            isRunning = true
            AppLogger.log("LocalFirewall: interface up, sinking $applied apps")

            scope.launch { drainLoop() }
        } catch (e: Exception) {
            AppLogger.error("LocalFirewall: failed to establish", e)
            stop()
        }
    }

    /**
     * Drains the TUN so captured packets are discarded rather than buffered. The reads
     * block until an app in the list sends traffic; that traffic then goes nowhere.
     */
    private suspend fun drainLoop() {
        val fd = vpnInterface ?: return
        val input = FileInputStream(fd.fileDescriptor)
        val buffer = ByteArray(32767)
        try {
            while (scope.isActive) {
                val n = input.read(buffer)
                if (n < 0) break
                // Intentionally discarded: no forwarding == no connectivity for these apps.
            }
        } catch (e: Exception) {
            AppLogger.log("LocalFirewall: drain ended (${e.message})")
        }
    }

    private fun closeInterface() {
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
    }

    private fun stop() {
        isRunning = false
        blockedCount = 0
        scope.cancel()
        closeInterface()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        AppLogger.log("LocalFirewall: stopped")
    }

    private fun buildNotification(count: Int): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Firewall", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, LocalFirewallService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 1,
            Intent(this, AppFirewallActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Блокатор трафика активен")
            .setContentText("Заблокировано приложений: $count")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Стоп", stopPi
                ).build()
            )
            .build()
    }
}
