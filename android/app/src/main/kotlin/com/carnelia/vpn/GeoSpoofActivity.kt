package com.carnelia.vpn

import java.util.Locale

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.carnelia.vpn.service.GeoSpoofService
import kotlinx.coroutines.launch
import com.carnelia.vpn.ui.theme.CarheliaTheme
import com.carnelia.vpn.ui.rememberWindowSize
import com.carnelia.vpn.utils.AppLogger
import com.carnelia.vpn.utils.PrefsManager
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

class GeoSpoofActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val osmConf = Configuration.getInstance()
        osmConf.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        // OSM tile servers отдают 403 без идентифицирующего User-Agent
        osmConf.userAgentValue = "CarneliaVPN/$packageName"
        // На Android 10+ дефолтный /sdcard/osmdroid недоступен (scoped storage) —
        // тайлы скачиваются, но не кэшируются и карта остаётся пустой.
        // Переносим базу и кэш во внутреннюю папку приложения (всегда доступна для записи).
        osmConf.osmdroidBasePath = cacheDir
        osmConf.osmdroidTileCache = java.io.File(cacheDir, "osmdroid-tiles").apply { mkdirs() }

        setContent {
            CarheliaTheme {
                GeoSpoofScreen(
                    onBack = { finish() },
                    onShowToast = { msg ->
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

// ============ Presets: popular cities ============
data class GeoPreset(val name: String, val flag: String, val lat: Double, val lon: Double)

val GEO_PRESETS = listOf(
    GeoPreset("Нью-Йорк",     "🇺🇸", 40.7128, -74.0060),
    GeoPreset("Лондон",       "🇬🇧", 51.5074,  -0.1278),
    GeoPreset("Берлин",       "🇩🇪", 52.5200,  13.4050),
    GeoPreset("Париж",        "🇫🇷", 48.8566,   2.3522),
    GeoPreset("Токио",        "🇯🇵", 35.6762, 139.6503),
    GeoPreset("Дубай",        "🇦🇪", 25.2048,  55.2708),
    GeoPreset("Сингапур",    "🇸🇬",  1.3521, 103.8198),
    GeoPreset("Амстердам",    "🇳🇱", 52.3676,   4.9041),
    GeoPreset("Цюрих",       "🇨🇭", 47.3769,   8.5417),
    GeoPreset("Хельсинки",     "🇫🇮", 60.1699,  24.9384),
    GeoPreset("Тбилиси",      "🇬🇪", 41.6938,  44.8015),
    GeoPreset("Стамбул",     "🇹🇷", 41.0082,  28.9784),
    GeoPreset("Варшава",       "🇵🇱", 52.2297,  21.0122),
)

// ============ Speed presets ============
data class SpeedPreset(val label: String, val ms: Float)
val SPEED_PRESETS = listOf(
    SpeedPreset("На месте", 0f),
    SpeedPreset("Шаг 5 км/ч", 1.4f),
    SpeedPreset("Бег 10 км/ч", 2.8f),
    SpeedPreset("Авто 30 км/ч", 8.3f),
    SpeedPreset("Авто 60 км/ч", 16.7f),
    SpeedPreset("Авто 100 км/ч", 27.8f),
)

private enum class MapLayer(val title: String) {
    STANDARD("Стандарт"),
    SATELLITE("Спутник"),
    TOPO("Рельеф")
}

// ============ Geocoding (поиск места по названию) ============
data class GeoSearchResult(val displayName: String, val lat: Double, val lon: Double)

/**
 * Геокодинг через Nominatim (OpenStreetMap). Тот же приватный стек, что и тайлы —
 * без Google. Требует идентифицирующий User-Agent по политике использования.
 */
private suspend fun geocodePlace(query: String): List<GeoSearchResult> =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val url = java.net.URL(
                "https://nominatim.openstreetmap.org/search?format=json&limit=6&accept-language=ru&q=$q"
            )
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "CarneliaVPN/${BuildConfig.VERSION_NAME} (geo spoof)")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val arr = org.json.JSONArray(body)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val lat = o.optString("lat").toDoubleOrNull() ?: continue
                    val lon = o.optString("lon").toDoubleOrNull() ?: continue
                    add(GeoSearchResult(o.optString("display_name", query), lat, lon))
                }
            }
        } catch (e: Exception) {
            AppLogger.error("GeoSpoof: geocode failed", e)
            emptyList()
        }
    }

private fun mapLayerToTileSource(layer: MapLayer): ITileSource {
    return when (layer) {
        MapLayer.STANDARD -> TileSourceFactory.MAPNIK
        MapLayer.SATELLITE -> object : OnlineTileSourceBase(
            "GoogleSatellite",
            0,
            19,
            256,
            "",
            arrayOf("https://mt1.google.com/vt/lyrs=y&")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                return baseUrl + "x=" + org.osmdroid.util.MapTileIndex.getX(pMapTileIndex) +
                        "&y=" + org.osmdroid.util.MapTileIndex.getY(pMapTileIndex) +
                        "&z=" + org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
            }
        }
        MapLayer.TOPO -> TileSourceFactory.OpenTopo
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeoSpoofScreen(
    onBack: () -> Unit,
    onShowToast: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var isRunning by remember { mutableStateOf(GeoSpoofService.isRunning) }

    // Сервис может остановиться сам (кнопка "Стоп" в уведомлении, убит системой) —
    // при возврате на экран синхронизируем состояние кнопки с реальным.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isRunning = GeoSpoofService.isRunning
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var latText by remember { mutableStateOf(String.format(java.util.Locale.US, "%.6f", PrefsManager.getGeoLat(context))) }
    var lonText by remember { mutableStateOf(String.format(java.util.Locale.US, "%.6f", PrefsManager.getGeoLon(context))) }
    var moveEnabled by remember { mutableStateOf(PrefsManager.isGeoMovementEnabled(context)) }
    var speed by remember { mutableStateOf(PrefsManager.getGeoSpeed(context)) }
    var bearing by remember { mutableStateOf(PrefsManager.getGeoBearing(context)) }
    var mapLayer by remember { mutableStateOf(MapLayer.STANDARD) }
    var showFullMapPicker by remember { mutableStateOf(false) }

    // Поиск места по названию (геокодинг через Nominatim / OpenStreetMap)
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<GeoSearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    val searchScope = rememberCoroutineScope()

    // Validate and send update to running service
    fun applyPoint() {
        val lat = latText.replace(",", ".").toDoubleOrNull()
        val lon = lonText.replace(",", ".").toDoubleOrNull()
        if (lat == null || lon == null || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            onShowToast(context.getString(R.string.geo_invalid_coordinates))
            return
        }
        
        val hasLoc = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLoc) {
            onShowToast("Для работы спуфинга нужно разрешение на геолокацию на устройстве!")
            try {
               ActivityCompat.requestPermissions((context as android.app.Activity), arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
            } catch (e: Exception) {}
            return
        }
        PrefsManager.setGeoCoords(context, lat, lon)
        if (isRunning) {
            val intent = Intent(context, GeoSpoofService::class.java).apply {
                action = GeoSpoofService.ACTION_SET_POINT
                putExtra(GeoSpoofService.EXTRA_LAT, lat)
                putExtra(GeoSpoofService.EXTRA_LON, lon)
            }
            context.startService(intent)
        }
        onShowToast(context.getString(R.string.geo_point_set, lat, lon))
    }

    fun applyMovement() {
        PrefsManager.setGeoMovement(context, moveEnabled, speed, bearing)
        if (isRunning) {
            val intent = Intent(context, GeoSpoofService::class.java).apply {
                action = GeoSpoofService.ACTION_SET_MOVEMENT
                putExtra(GeoSpoofService.EXTRA_MOVE_ENABLED, moveEnabled)
                putExtra(GeoSpoofService.EXTRA_MOVE_SPEED, speed)
                putExtra(GeoSpoofService.EXTRA_MOVE_BEARING, bearing)
            }
            context.startService(intent)
        }
    }

    fun toggleService() {
        if (isRunning) {
            val intent = Intent(context, GeoSpoofService::class.java).apply { action = GeoSpoofService.ACTION_STOP }
            context.startService(intent)
            isRunning = false
        } else {
            if (!isMockLocationEnabled(context)) {
                onShowToast(context.getString(R.string.geo_enable_mock_location))
                return
            }
            applyPoint()
            applyMovement()
            val intent = Intent(context, GeoSpoofService::class.java).apply { action = GeoSpoofService.ACTION_START }
            context.startForegroundService(intent)
            isRunning = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.geo_spoof_title), color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        val windowSize = rememberWindowSize()
        val currentLat = latText.replace(",", ".").toDoubleOrNull() ?: PrefsManager.getGeoLat(context)
        val currentLon = lonText.replace(",", ".").toDoubleOrNull() ?: PrefsManager.getGeoLon(context)

        // ── Map + Joystick block (reused in both portrait and landscape) ──
        @Suppress("LocalVariableName")
        @Composable
        fun MapAndJoystickContent(mapModifier: Modifier = Modifier) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MapLayer.entries.forEach { layer ->
                    FilterChip(
                        selected = mapLayer == layer,
                        onClick = { mapLayer = layer },
                        label = { Text(layer.title, fontSize = 11.sp) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showFullMapPicker = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Установить метку (полный экран)") }
            Spacer(Modifier.height(8.dp))
            OSMTileMap(
                latitude = currentLat,
                longitude = currentLon,
                mapLayer = mapLayer,
                onPositionChange = { newLat, newLon ->
                    latText = String.format(java.util.Locale.US, "%.6f", newLat)
                    lonText = String.format(java.util.Locale.US, "%.6f", newLon)
                    PrefsManager.setGeoCoords(context, newLat, newLon)
                    if (isRunning) {
                        val intent = Intent(context, GeoSpoofService::class.java).apply {
                            action = GeoSpoofService.ACTION_SET_POINT
                            putExtra(GeoSpoofService.EXTRA_LAT, newLat)
                            putExtra(GeoSpoofService.EXTRA_LON, newLon)
                        }
                        context.startService(intent)
                    }
                },
                followPoint = false,
                modifier = if (mapModifier == Modifier) Modifier.height(300.dp) else mapModifier
            )
            Spacer(Modifier.height(12.dp))
            Text("Джойстик: удерживайте, чтобы двигать точку",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            LocationJoystick(
                onDelta = { deltaLat, deltaLon, newBearing ->
                    val updatedLat = (currentLat + deltaLat).coerceIn(-90.0, 90.0)
                    val updatedLon = (currentLon + deltaLon).coerceIn(-180.0, 180.0)
                    latText = String.format(java.util.Locale.US, "%.6f", updatedLat)
                    lonText = String.format(java.util.Locale.US, "%.6f", updatedLon)
                    bearing = newBearing
                    applyPoint()
                    applyMovement()
                }
            )
        }

        // ── Controls block (coords + presets + movement) ──
        @Composable
        fun ControlsContent() {
            StatusCard(isRunning = isRunning, onToggle = { toggleService() })
            if (!isMockLocationEnabled(context)) {
                Spacer(Modifier.height(16.dp))
                MockWarningCard()
            }
            Spacer(Modifier.height(16.dp))
            // Поиск места по названию / адресу
            SpoofCard(title = "🔍 Поиск места") {
                fun runSearch() {
                    if (searchQuery.isBlank() || isSearching) return
                    isSearching = true
                    searchResults = emptyList()
                    searchScope.launch {
                        val res = geocodePlace(searchQuery)
                        searchResults = res
                        isSearching = false
                        if (res.isEmpty()) onShowToast("Ничего не найдено")
                    }
                }
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Город, адрес, место") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { runSearch() }),
                    trailingIcon = {
                        if (isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = { runSearch() }) {
                                Icon(Icons.Default.Search, contentDescription = "Найти",
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    )
                )
                searchResults.forEach { result ->
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        onClick = {
                            latText = String.format(java.util.Locale.US, "%.6f", result.lat)
                            lonText = String.format(java.util.Locale.US, "%.6f", result.lon)
                            searchResults = emptyList()
                            searchQuery = ""
                            applyPoint()
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.LocationOn, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                result.displayName,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            // Coordinates
            SpoofCard(title = "📍 Координаты") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latText,
                        onValueChange = { latText = it },
                        label = { Text("Широта") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    OutlinedTextField(
                        value = lonText,
                        onValueChange = { lonText = it },
                        label = { Text("Долгота") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { applyPoint() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Применить координаты")
                }
            }
        }

        if (windowSize.isLandscape) {
            // ── Landscape: left=controls, right=map+joystick ──
            Row(
                modifier = Modifier.padding(padding).fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // Left: scrollable controls
                Column(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ControlsContent()
                    // City presets (compact in landscape)
                    SpoofCard(title = "🌍 Пресеты") {
                        GEO_PRESETS.chunked(if (windowSize.isLargeTablet) 4 else 3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                                row.forEach { preset ->
                                    FilterChip(
                                        selected = false,
                                        onClick = {
                                            latText = String.format(java.util.Locale.US, "%.6f", preset.lat)
                                            lonText = String.format(java.util.Locale.US, "%.6f", preset.lon)
                                            PrefsManager.setGeoCoords(context, preset.lat, preset.lon)
                                            if (isRunning) {
                                                val intent = Intent(context, GeoSpoofService::class.java).apply {
                                                    action = GeoSpoofService.ACTION_SET_POINT
                                                    putExtra(GeoSpoofService.EXTRA_LAT, preset.lat)
                                                    putExtra(GeoSpoofService.EXTRA_LON, preset.lon)
                                                }
                                                context.startService(intent)
                                            }
                                            onShowToast("${preset.flag} ${preset.name}")
                                        },
                                        label = { Text("${preset.flag} ${preset.name}", fontSize = 10.sp) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                // Right: map + joystick (fills height)
                Column(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    SpoofCard(title = "🗺️ OSM Карта и Джойстик",
                        modifier = Modifier.weight(1f)) {
                        MapAndJoystickContent(
                            mapModifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 160.dp)
                        )
                    }
                }
            }
        } else {
            // ── Portrait: original single-column layout ──
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ControlsContent()

                // Map + joystick
                SpoofCard(title = "🗺️ OSM Карта и Джойстик") {
                    MapAndJoystickContent()
                }

                // ---- City presets ----
                SpoofCard(title = "🌍 Быстрые пресеты") {
                    GEO_PRESETS.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { preset ->
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        latText = String.format(java.util.Locale.US, "%.6f", preset.lat)
                                        lonText = String.format(java.util.Locale.US, "%.6f", preset.lon)
                                        PrefsManager.setGeoCoords(context, preset.lat, preset.lon)
                                        if (isRunning) {
                                            val intent = Intent(context, GeoSpoofService::class.java).apply {
                                                action = GeoSpoofService.ACTION_SET_POINT
                                                putExtra(GeoSpoofService.EXTRA_LAT, preset.lat)
                                                putExtra(GeoSpoofService.EXTRA_LON, preset.lon)
                                            }
                                            context.startService(intent)
                                        }
                                        onShowToast("${preset.flag} ${preset.name}")
                                    },
                                    label = { Text("${preset.flag} ${preset.name}", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }

                // ---- Movement ----
                SpoofCard(title = "🚶 Симуляция движения") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Симуляция движения", modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurface)
                        Switch(checked = moveEnabled, onCheckedChange = { moveEnabled = it; applyMovement() })
                    }
                    if (moveEnabled) {
                        Spacer(Modifier.height(8.dp))
                        Text("Скорость", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        SPEED_PRESETS.forEach { preset ->
                            val selected = speed == preset.ms
                            OutlinedButton(
                                onClick = { speed = preset.ms; applyMovement() },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                )
                            ) {
                                Text(preset.label,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Направление (азимут: ${bearing.toInt()}°)", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        CompassControl(bearing = bearing, onBearingChange = { bearing = it; applyMovement() })
                    }
                }

                Spacer(Modifier.height(32.dp))
            } // end portrait Column
        } // end if/else landscape

        // ── Full-screen map picker dialog (shown from both orientations) ──
        if (showFullMapPicker) {
            var pickerLat by remember { mutableStateOf(currentLat) }
            var pickerLon by remember { mutableStateOf(currentLon) }
            Dialog(
                onDismissRequest = { showFullMapPicker = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Выбор метки", style = MaterialTheme.typography.titleLarge)
                            TextButton(onClick = { showFullMapPicker = false }) { Text("Закрыть") }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MapLayer.entries.forEach { layer ->
                                FilterChip(selected = mapLayer == layer, onClick = { mapLayer = layer },
                                    label = { Text(layer.title, fontSize = 11.sp) })
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OSMTileMap(
                            latitude = pickerLat,
                            longitude = pickerLon,
                            mapLayer = mapLayer,
                            onPositionChange = { newLat, newLon -> pickerLat = newLat; pickerLon = newLon },
                            followPoint = true,
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                latText = String.format(java.util.Locale.US, "%.6f", pickerLat)
                                lonText = String.format(java.util.Locale.US, "%.6f", pickerLon)
                                applyPoint()
                                showFullMapPicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Установить метку") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(isRunning: Boolean, onToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (isRunning) "Подмена GPS АКТИВНА" else "Подмена GPS ВЫКЛЮЧЕНА",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = if (isRunning) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (isRunning) "Ваше местоположение GPS подменяется"
                    else "Используется реальное местоположение GPS",
                    fontSize = 12.sp,
                    color = if (isRunning) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onToggle,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (isRunning) "Стоп" else "Старт")
            }
        }
    }
}

@Composable
private fun MockWarningCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "⚠️ Фиктивные местоположения не включены",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Перейдите в Настройки → Для разработчиков → Выбрать приложение для фиктивных местоположений → выберите Carnelia VPN",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    try {
                        context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    } catch (e: Exception) { }
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)
            ) {
                Text("Открыть настройки разработчика")
            }
        }
    }
}

@Composable
private fun SpoofCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun CompassControl(bearing: Float, onBearingChange: (Float) -> Unit) {
    // 8 direction buttons + center indicator
    val directions = listOf(
        "N" to 0f,
        "NE" to 45f,
        "E" to 90f,
        "SE" to 135f,
        "S" to 180f,
        "SW" to 225f,
        "W" to 270f,
        "NW" to 315f
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        // Top row: NW N NE
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            listOf("NW" to 315f, "N" to 0f, "NE" to 45f).forEach { (label, deg) ->
                DirectionButton(label, deg, bearing, onBearingChange)
            }
        }
        // Middle row: W (center) E
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            DirectionButton("W", 270f, bearing, onBearingChange)
            Spacer(Modifier.size(52.dp))
            DirectionButton("E", 90f, bearing, onBearingChange)
        }
        // Bottom row: SW S SE
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            listOf("SW" to 225f, "S" to 180f, "SE" to 135f).forEach { (label, deg) ->
                DirectionButton(label, deg, bearing, onBearingChange)
            }
        }
    }
}

@Composable
private fun DirectionButton(label: String, degrees: Float, currentBearing: Float, onClick: (Float) -> Unit) {
    val selected = kotlin.math.abs(currentBearing - degrees) < 0.1f
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(52.dp)
            .padding(2.dp)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable { onClick(degrees) }
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

private fun isMockLocationEnabled(context: Context): Boolean {
    return try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // On Android 6+ the app needs to be set as mock location provider in Dev Settings.
        // We can't directly check which app is set, but we try addTestProvider and catch SecurityException.
        lm.addTestProvider(
            LocationManager.GPS_PROVIDER,
            false, false, false, false, true, true, true,
            android.location.Criteria.POWER_LOW,
            android.location.Criteria.ACCURACY_FINE
        )
        lm.removeTestProvider(LocationManager.GPS_PROVIDER)
        true
    } catch (e: SecurityException) {
        false
    } catch (e: Exception) {
        true // Provider already existed, meaning we likely have permission
    }
}

@Composable
private fun OSMTileMap(
    latitude: Double,
    longitude: Double,
    mapLayer: MapLayer,
    onPositionChange: (Double, Double) -> Unit,
    followPoint: Boolean,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val latestOnPositionChange by rememberUpdatedState(onPositionChange)
    val currentPoint = remember(latitude, longitude) { GeoPoint(latitude, longitude) }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(mapLayerToTileSource(mapLayer))
            setMultiTouchControls(true)
            setUseDataConnection(true)
            minZoomLevel = 2.0
            maxZoomLevel = 19.0
            controller.setZoom(12.0)
            // Карта внутри verticalScroll — при касании отбираем перехват жестов
            // у скролла, иначе pinch-zoom и панорамирование не работают.
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN,
                    android.view.MotionEvent.ACTION_POINTER_DOWN ->
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL ->
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false // не поглощаем — osmdroid обрабатывает жест сам
            }
        }
    }

    // Тайлы доезжают асинхронно, а в Compose-AndroidView invalidate() из osmdroid
    // не всегда вызывает onDraw — карта застывает в loading-сетке. Тикаем
    // postInvalidate() со стороны Compose ~4 секунды после открытия / смены точки.
    LaunchedEffect(latitude, longitude, mapLayer) {
        repeat(20) {
            mapView.postInvalidate()
            kotlinx.coroutines.delay(200)
        }
    }

    var isInitialCenterDone by remember(mapView) { mutableStateOf(false) }
    // Запоминаем применённый слой, чтобы setTileSource (он чистит кэш тайлов)
    // вызывался ТОЛЬКО при реальной смене слоя, а не на каждой рекомпозиции.
    var appliedLayer by remember(mapView) { mutableStateOf<MapLayer?>(null) }

    val marker = remember(mapView) {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            isDraggable = true
            title = "Точка спуфинга"
        }
    }

    DisposableEffect(mapView, marker) {
        mapView.onResume()
        
        val dragListener = object : Marker.OnMarkerDragListener {
            override fun onMarkerDragStart(marker: Marker?) = Unit

            override fun onMarkerDrag(marker: Marker?) {
                marker?.position?.let { p ->
                    latestOnPositionChange(p.latitude, p.longitude)
                }
            }

            override fun onMarkerDragEnd(marker: Marker?) {
                marker?.position?.let { p ->
                    latestOnPositionChange(p.latitude, p.longitude)
                }
            }
        }
        marker.setOnMarkerDragListener(dragListener)

        val tapOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (p != null) {
                    latestOnPositionChange(p.latitude, p.longitude)
                    return true
                }
                return false
            }

            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p != null) {
                    latestOnPositionChange(p.latitude, p.longitude)
                    return true
                }
                return false
            }
        })

        mapView.overlays.add(tapOverlay)
        mapView.overlays.add(marker)

        onDispose {
            mapView.onPause()
            mapView.overlays.remove(marker)
            mapView.overlays.remove(tapOverlay)
            mapView.onDetach()
        }
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        // Высоту задаёт вызывающий (embedded — фикс .height, полный экран — weight).
        // КЛЮЧЕВОЕ: высота должна быть ОГРАНИЧЕННОЙ. Внутри verticalScroll без явной
        // высоты osmdroid MapView меряется огромным вьюпортом и рисует loading-сетку.
        modifier = modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    mapView
                },
                update = { map ->
                    // setTileSource чистит кэш тайлов — вызываем только при смене слоя
                    if (appliedLayer != mapLayer) {
                        map.setTileSource(mapLayerToTileSource(mapLayer))
                        appliedLayer = mapLayer
                    }
                    marker.position = currentPoint
                    if (!isInitialCenterDone || followPoint) {
                        map.controller.setCenter(currentPoint)
                        isInitialCenterDone = true
                    }
                    map.invalidate()
                }
            )

            // Кнопки зума — надёжная альтернатива pinch-зуму
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ZoomButton("+") { mapView.controller.zoomIn() }
                ZoomButton("−") { mapView.controller.zoomOut() }
            }
        }
    }

    Text(
        text = "Тап/долгий тап или перетаскивание маркера меняют координаты",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun ZoomButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 4.dp,
        modifier = Modifier.size(44.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun LocationJoystick(
    onDelta: (deltaLat: Double, deltaLon: Double, bearingDeg: Float) -> Unit
) {
    val radiusPx = 120f
    var knob by remember { mutableStateOf(Offset.Zero) }
    val outlineSoft = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val outlineCross = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val knobBg = MaterialTheme.colorScheme.primary
    val knobBorder = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.4f)

    Box(
        modifier = Modifier
            .size(220.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { knob = Offset.Zero },
                    onDragCancel = { knob = Offset.Zero },
                    onDrag = { change, dragAmount ->
                        val updated = knob + dragAmount
                        val length = sqrt(updated.x * updated.x + updated.y * updated.y)
                        val clamped = if (length > radiusPx) {
                            val k = radiusPx / length
                            Offset(updated.x * k, updated.y * k)
                        } else updated
                        knob = clamped

                        val normalizedX = (knob.x / radiusPx).coerceIn(-1f, 1f)
                        val normalizedY = (knob.y / radiusPx).coerceIn(-1f, 1f)

                        // About ~2-30 meters per update depending on deflection.
                        val stepScale = 0.00010 * sqrt((normalizedX * normalizedX + normalizedY * normalizedY).toDouble())
                        val deltaLat = (-normalizedY * stepScale).coerceIn(-0.00025, 0.00025)
                        val deltaLon = (normalizedX * stepScale).coerceIn(-0.00025, 0.00025)

                        val angle = Math.toDegrees(atan2(normalizedX.toDouble(), -normalizedY.toDouble()))
                        val bearing = ((angle + 360.0) % 360.0).toFloat()

                        onDelta(deltaLat, deltaLon, bearing)
                        change.consume()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = min(size.width, size.height) / 2.4f
            drawCircle(outlineSoft, radius = baseRadius, center = center, style = Stroke(width = 3f))
            drawLine(outlineCross, Offset(center.x, 0f), Offset(center.x, size.height), 1f)
            drawLine(outlineCross, Offset(0f, center.y), Offset(size.width, center.y), 1f)
        }

        Box(
            modifier = Modifier
                .offset(x = (knob.x / 2.5f).dp, y = (knob.y / 2.5f).dp)
                .size(56.dp)
                .clip(CircleShape)
                .background(knobBg)
                .border(2.dp, knobBorder, CircleShape)
        ) {
        }
    }
}
