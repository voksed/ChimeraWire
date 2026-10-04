package com.carnelia.vpn.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import android.app.Activity
import android.os.Build
import com.carnelia.vpn.utils.PrefsManager
import com.carnelia.vpn.R

/**
 * ChimeraWire "Live Wire" palette on a full Material 3 color system. The neutral surfaces are
 * tinted warm (ember-brown) so the whole app reads as one warm monochrome, with ember as the
 * hero accent (connect button, key actions) and a muted gold as the second accent. error stays a
 * distinct red so it never reads as the ember accent. Hex values mirror the design canvas
 * (bg #1A120E, card #271E1A, accent #FFB690, text #F1DFD8). Exactly 4 themes.
 */
enum class AppTheme(val displayNameResId: Int, val colorScheme: androidx.compose.material3.ColorScheme, val isDark: Boolean) {
    LIGHT(R.string.theme_light, lightColorScheme(
        primary = Color(0xFF9B4A16),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDBCA),
        onPrimaryContainer = Color(0xFF351000),
        secondary = Color(0xFF755847),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFDCC9),
        onSecondaryContainer = Color(0xFF2B1709),
        tertiary = Color(0xFF6C5D0F),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFF5E388),
        onTertiaryContainer = Color(0xFF211B00),
        error = Color(0xFFBA1A1A),
        errorContainer = Color(0xFFFFDAD6),
        onError = Color(0xFFFFFFFF),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFFFF8F5),
        onBackground = Color(0xFF221A15),
        surface = Color(0xFFFFF1E9),
        onSurface = Color(0xFF221A15),
        surfaceVariant = Color(0xFFF3E0D5),
        onSurfaceVariant = Color(0xFF52443C),
        outline = Color(0xFFD8C7BC),
        outlineVariant = Color(0xFFEADACF),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFF382E29),
        inverseOnSurface = Color(0xFFFFEDE4),
        inversePrimary = Color(0xFFFFB690),
        surfaceTint = Color(0xFF9B4A16),
        surfaceDim = Color(0xFFE7D7CD),
        surfaceBright = Color(0xFFFFF8F5),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFFF1E9),
        surfaceContainer = Color(0xFFFBEBE1),
        surfaceContainerHigh = Color(0xFFF5E5DB),
        surfaceContainerHighest = Color(0xFFEFDFD5)
    ), isDark = false),

    LIGHT_HC(R.string.theme_light_hc, lightColorScheme(
        primary = Color(0xFF5E2600),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF8A3C0A),
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFF4A2F1F),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFF6A4B38),
        onSecondaryContainer = Color(0xFFFFFFFF),
        tertiary = Color(0xFF3B3300),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFF554C00),
        onTertiaryContainer = Color(0xFFFFFFFF),
        error = Color(0xFF8C0009),
        errorContainer = Color(0xFFDA342E),
        onError = Color(0xFFFFFFFF),
        onErrorContainer = Color(0xFFFFFFFF),
        background = Color(0xFFFFFFFF),
        onBackground = Color(0xFF000000),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF000000),
        surfaceVariant = Color(0xFFF0E0D6),
        onSurfaceVariant = Color(0xFF1C1510),
        outline = Color(0xFF000000),
        outlineVariant = Color(0xFF4A3F38),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFF000000),
        inverseOnSurface = Color(0xFFFFFFFF),
        inversePrimary = Color(0xFFFFB690),
        surfaceTint = Color(0xFF5E2600),
        surfaceDim = Color(0xFFD8C9BF),
        surfaceBright = Color(0xFFFFFFFF),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF4E6DD),
        surfaceContainer = Color(0xFFEEDFD5),
        surfaceContainerHigh = Color(0xFFE6D6CC),
        surfaceContainerHighest = Color(0xFFDECEC3)
    ), isDark = false),

    DARK(R.string.theme_dark, darkColorScheme(
        primary = Color(0xFFFFB690),
        onPrimary = Color(0xFF552100),
        primaryContainer = Color(0xFF75330F),
        onPrimaryContainer = Color(0xFFFFDBCA),
        secondary = Color(0xFFE7BDA7),
        onSecondary = Color(0xFF442B1D),
        secondaryContainer = Color(0xFF5D4032),
        onSecondaryContainer = Color(0xFFFFDBCA),
        tertiary = Color(0xFFD6C98C),
        onTertiary = Color(0xFF383200),
        tertiaryContainer = Color(0xFF4E4A1F),
        onTertiaryContainer = Color(0xFFEEE5A8),
        error = Color(0xFFFFB4AB),
        errorContainer = Color(0xFF93000A),
        onError = Color(0xFF690005),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF1A120E),
        onBackground = Color(0xFFF1DFD8),
        surface = Color(0xFF271E1A),
        onSurface = Color(0xFFF1DFD8),
        surfaceVariant = Color(0xFF2E2420),
        onSurfaceVariant = Color(0xFFD8C2B9),
        outline = Color(0xFF53443D),
        outlineVariant = Color(0xFF3E322C),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFF1DFD8),
        inverseOnSurface = Color(0xFF392E29),
        inversePrimary = Color(0xFF8C4B1C),
        surfaceTint = Color(0xFFFFB690),
        surfaceDim = Color(0xFF1A120E),
        surfaceBright = Color(0xFF423630),
        surfaceContainerLowest = Color(0xFF120C09),
        surfaceContainerLow = Color(0xFF211712),
        surfaceContainer = Color(0xFF271E1A),
        surfaceContainerHigh = Color(0xFF322824),
        surfaceContainerHighest = Color(0xFF3D332E)
    ), isDark = true),

    DARK_AMOLED(R.string.theme_dark_amoled, darkColorScheme(
        primary = Color(0xFFFFB690),
        onPrimary = Color(0xFF552100),
        primaryContainer = Color(0xFF75330F),
        onPrimaryContainer = Color(0xFFFFDBCA),
        secondary = Color(0xFFE7BDA7),
        onSecondary = Color(0xFF442B1D),
        secondaryContainer = Color(0xFF4A3226),
        onSecondaryContainer = Color(0xFFFFDBCA),
        tertiary = Color(0xFFD6C98C),
        onTertiary = Color(0xFF383200),
        tertiaryContainer = Color(0xFF423E18),
        onTertiaryContainer = Color(0xFFEEE5A8),
        error = Color(0xFFFFB4AB),
        errorContainer = Color(0xFF93000A),
        onError = Color(0xFF690005),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF000000),
        onBackground = Color(0xFFF1DFD8),
        surface = Color(0xFF140E0B),
        onSurface = Color(0xFFF1DFD8),
        surfaceVariant = Color(0xFF1C1512),
        onSurfaceVariant = Color(0xFFCBB6AD),
        outline = Color(0xFF3A2E28),
        outlineVariant = Color(0xFF241B17),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFF1DFD8),
        inverseOnSurface = Color(0xFF392E29),
        inversePrimary = Color(0xFF8C4B1C),
        surfaceTint = Color(0xFFFFB690),
        surfaceDim = Color(0xFF000000),
        surfaceBright = Color(0xFF2A211C),
        surfaceContainerLowest = Color(0xFF000000),
        surfaceContainerLow = Color(0xFF0E0A08),
        surfaceContainer = Color(0xFF161010),
        surfaceContainerHigh = Color(0xFF211915),
        surfaceContainerHighest = Color(0xFF2C231E)
    ), isDark = true)
}

/** Softly rounded shapes shared by cards, dialogs, sheets and buttons for a cohesive feel. */
private val ChimeraShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun ChimeraTheme(
    themeIndex: Int? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val currentThemeIndex = themeIndex ?: PrefsManager.getThemeIndex(context)
    val theme = AppTheme.entries.getOrElse(currentThemeIndex) { AppTheme.DARK }

    // Material You: when enabled on Android 12+, derive the scheme from the system wallpaper,
    // keeping the selected theme's light/dark base. Otherwise use the brand ember/teal palette.
    val colorScheme = if (PrefsManager.isDynamicColorEnabled(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (theme.isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        theme.colorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()

            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !theme.isDark
            insetsController.isAppearanceLightNavigationBars = !theme.isDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = ChimeraShapes,
        content = content
    )
}
