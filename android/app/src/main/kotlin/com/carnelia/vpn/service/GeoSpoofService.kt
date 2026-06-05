package com.carnelia.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.carnelia.vpn.GeoSpoofActivity
import com.carnelia.vpn.R
import com.carnelia.vpn.utils.AppLogger
import com.carnelia.vpn.utils.PrefsManager
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class GeoSpoofService : Service() {

    companion object {
        const val CHANNEL_ID = "geo_spoof_channel"
        const val NOTIFICATION_ID = 42

        const val ACTION_START        = "com.carnelia.vpn.GEO_START"
        const val ACTION_STOP         = "com.carnelia.vpn.GEO_STOP"
        const val ACTION_SET_POINT    = "com.carnelia.vpn.GEO_SET_POINT"
        const val ACTION_SET_MOVEMENT = "com.carnelia.vpn.GEO_SET_MOVEMENT"

        const val EXTRA_LAT          = "lat"
        const val EXTRA_LON          = "lon"
        const val EXTRA_MOVE_ENABLED = "move_enabled"
        const val EXTRA_MOVE_SPEED   = "move_speed"    // m/s
        const val EXTRA_MOVE_BEARING = "move_bearing"  // degrees 0-360

        var isRunning = false
            private set

        // Providers we mock. On API 26+ also mock fused (Google FusedLocationProvider).
        private val MOCK_PROVIDERS: List<String>
            get() = buildList {
                add(LocationManager.GPS_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // "fused" is the internal name used by Google Play Services —
                    // mocking it helps apps that query FusedLocationProviderClient directly.
                    add("fused")
                }
            }
    }

    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())

    private var currentLat  = 0.0
    private var currentLon  = 0.0
    private var movementEnabled = false
    private var moveSpeedMs = 1.4f
    private var moveBearing = 0f

    private val publishRunnable = object : Runnable {
        override fun run() {
            if (movementEnabled) advancePosition()
            publishLocation(currentLat, currentLon)
            handler.postDelayed(this, 1000)
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                currentLat      = PrefsManager.getGeoLat(this)
                currentLon      = PrefsManager.getGeoLon(this)
                movementEnabled = PrefsManager.isGeoMovementEnabled(this)
                moveSpeedMs     = PrefsManager.getGeoSpeed(this)
                moveBearing     = PrefsManager.getGeoBearing(this)

                startForegroundCompat()
                registerProviders()
                handler.removeCallbacks(publishRunnable)
                handler.post(publishRunnable)
                isRunning = true
                AppLogger.log("GeoSpoof: Started at $currentLat, $currentLon (API ${Build.VERSION.SDK_INT})")
            }
            ACTION_STOP -> stopSelf()
            ACTION_SET_POINT -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, currentLat)
                val lon = intent.getDoubleExtra(EXTRA_LON, currentLon)
                currentLat = lat; currentLon = lon
                PrefsManager.setGeoCoords(this, lat, lon)
                AppLogger.log("GeoSpoof: Point → $lat, $lon")
            }
            ACTION_SET_MOVEMENT -> {
                movementEnabled = intent.getBooleanExtra(EXTRA_MOVE_ENABLED, false)
                moveSpeedMs     = intent.getFloatExtra(EXTRA_MOVE_SPEED, 1.4f)
                moveBearing     = intent.getFloatExtra(EXTRA_MOVE_BEARING, 0f)
                PrefsManager.setGeoMovement(this, movementEnabled, moveSpeedMs, moveBearing)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(publishRunnable)
        unregisterProviders()
        isRunning = false
        AppLogger.log("GeoSpoof: Stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Foreground compat (API 19 → 34) ───────────────────────────────────────

    private fun startForegroundCompat() {
        val notif = buildNotification()
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                try {
                    startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                } catch (_: Exception) {
                    startForeground(NOTIFICATION_ID, notif)
                }
            }
            else -> startForeground(NOTIFICATION_ID, notif)
        }
    }

    // ── Provider registration ─────────────────────────────────────────────────
    //
    // Strategy across API levels:
    //   1. Always removeTestProvider first — avoids "provider already exists" crash on re-start.
    //   2. addTestProvider with requirements that mimic a real GPS chip.
    //   3. setTestProviderEnabled(true) so clients see the provider as available.
    //   4. On SecurityException: the user hasn't selected this app as Mock Location App
    //      in Developer Options → clear log message guides them.

    private fun registerProviders() {
        MOCK_PROVIDERS.forEach { provider ->
            try {
                // Remove silently in case leftover from a previous session
                try { locationManager.removeTestProvider(provider) } catch (_: Exception) {}

                locationManager.addTestProvider(
                    provider,
                    /* requiresNetwork     */ false,
                    /* requiresSatellite   */ false,
                    /* requiresCell        */ false,
                    /* hasMonetaryCost     */ false,
                    /* supportsAltitude    */ true,
                    /* supportsSpeed       */ true,
                    /* supportsBearing     */ true,
                    /* powerRequirement    */ Criteria.POWER_LOW,
                    /* accuracy           */ Criteria.ACCURACY_FINE
                )
                locationManager.setTestProviderEnabled(provider, true)
                AppLogger.log("GeoSpoof: registered provider '$provider'")
            } catch (e: SecurityException) {
                AppLogger.log("GeoSpoof: SecurityException on '$provider' — " +
                    "enable Developer Options → Mock location app → CarneliaVPN")
            } catch (e: Exception) {
                AppLogger.log("GeoSpoof: register '$provider' failed: ${e.message}")
            }
        }
    }

    private fun unregisterProviders() {
        MOCK_PROVIDERS.forEach { provider ->
            try { locationManager.removeTestProvider(provider) } catch (_: Exception) {}
        }
    }

    // ── Location publishing ───────────────────────────────────────────────────

    private fun publishLocation(lat: Double, lon: Double) {
        MOCK_PROVIDERS.forEach { provider ->
            try {
                val loc = buildLocation(provider, lat, lon)
                locationManager.setTestProviderLocation(provider, loc)
            } catch (e: Exception) {
                AppLogger.log("GeoSpoof: publish '$provider' failed: ${e.message}")
            }
        }
    }

    private fun buildLocation(provider: String, lat: Double, lon: Double): Location {
        return Location(provider).apply {
            latitude          = lat
            longitude         = lon
            altitude          = 50.0
            accuracy          = 3.0f
            bearing           = moveBearing
            speed             = if (movementEnabled) moveSpeedMs else 0f
            time              = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

            // API 26+: vertical accuracy and bearing/speed accuracy
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                verticalAccuracyMeters = 5.0f
                bearingAccuracyDegrees = 1.0f
                speedAccuracyMetersPerSecond = 0.5f
            }
            // API 29+: elapsed realtime uncertainty
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                elapsedRealtimeUncertaintyNanos = 0.0
            }
        }
    }

    // ── Movement ──────────────────────────────────────────────────────────────

    private fun advancePosition() {
        val dist = moveSpeedMs.toDouble()   // metres per second × 1s tick
        val R    = 6_371_000.0
        val ang  = dist / R
        val brRad  = moveBearing * PI / 180.0
        val latRad = currentLat * PI / 180.0
        val lonRad = currentLon * PI / 180.0

        val newLat = asin(sin(latRad) * cos(ang) + cos(latRad) * sin(ang) * cos(brRad))
        val newLon = lonRad + atan2(
            sin(brRad) * sin(ang) * cos(latRad),
            cos(ang) - sin(latRad) * sin(newLat)
        )
        currentLat = newLat * 180.0 / PI
        currentLon = newLon * 180.0 / PI
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Geo Spoof", NotificationManager.IMPORTANCE_LOW).apply {
            description = "GPS location spoofing active"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, GeoSpoofActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, GeoSpoofService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Spoof — Active")
            .setContentText("%.5f, %.5f".format(currentLat, currentLon))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openPi)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
