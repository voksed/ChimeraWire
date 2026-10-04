package com.carnelia.vpn.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
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
import com.carnelia.vpn.utils.PrefsManager
import com.carnelia.vpn.R

/**
 * ChimeraWire "Live Wire" palette: warm ember as the hero accent (connect button, key
 * actions) and teal as the second accent (highlights, active states), on near-black
 * neutral surfaces — mirroring the logo. error stays a distinct red so it never reads as
 * the ember accent. Exactly 4 themes, no ad-hoc variants.
 */
enum class AppTheme(val displayNameResId: Int, val colorScheme: androidx.compose.material3.ColorScheme, val isDark: Boolean) {
    LIGHT(R.string.theme_light, lightColorScheme(
        primary = Color(0xFFA3480F),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDBC7),
        onPrimaryContainer = Color(0xFF351000),
        secondary = Color(0xFF3E6359),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFC1E9DD),
        onSecondaryContainer = Color(0xFF002019),
        tertiary = Color(0xFF0C7C6C),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFA6F2E4),
        onTertiaryContainer = Color(0xFF00201A),
        error = Color(0xFFBA1A1A),
        errorContainer = Color(0xFFFFDAD6),
        onError = Color(0xFFFFFFFF),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFF7F8FA),
        onBackground = Color(0xFF1A1C1E),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1A1C1E),
        surfaceVariant = Color(0xFFECEFF1),
        onSurfaceVariant = Color(0xFF48505A),
        outline = Color(0xFFD9DDE2)
    ), isDark = false),

    LIGHT_HC(R.string.theme_light_hc, lightColorScheme(
        primary = Color(0xFF6E2B00),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF8F3A0A),
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFF1F3B35),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFF34534B),
        onSecondaryContainer = Color(0xFFFFFFFF),
        tertiary = Color(0xFF004036),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFF0C7C6C),
        onTertiaryContainer = Color(0xFFFFFFFF),
        error = Color(0xFF8C0009),
        errorContainer = Color(0xFFDA342E),
        onError = Color(0xFFFFFFFF),
        onErrorContainer = Color(0xFFFFFFFF),
        background = Color(0xFFFFFFFF),
        onBackground = Color(0xFF000000),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF000000),
        surfaceVariant = Color(0xFFE2E6EA),
        onSurfaceVariant = Color(0xFF16191C),
        outline = Color(0xFF000000)
    ), isDark = false),

    DARK(R.string.theme_dark, darkColorScheme(
        primary = Color(0xFFFF9D5C),
        onPrimary = Color(0xFF3A1A00),
        primaryContainer = Color(0xFF5E2E0C),
        onPrimaryContainer = Color(0xFFFFDCC6),
        secondary = Color(0xFFB3CCC6),
        onSecondary = Color(0xFF1E3531),
        secondaryContainer = Color(0xFF2B3E3A),
        onSecondaryContainer = Color(0xFFCFEAE4),
        tertiary = Color(0xFF5FD9C6),
        onTertiary = Color(0xFF003730),
        tertiaryContainer = Color(0xFF0F4C43),
        onTertiaryContainer = Color(0xFFA6F2E5),
        error = Color(0xFFFFB4AB),
        errorContainer = Color(0xFF93000A),
        onError = Color(0xFF690005),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF0E1013),
        onBackground = Color(0xFFECEEF1),
        surface = Color(0xFF16181C),
        onSurface = Color(0xFFECEEF1),
        surfaceVariant = Color(0xFF202327),
        onSurfaceVariant = Color(0xFFAEB4BC),
        outline = Color(0xFF2C3137)
    ), isDark = true),

    DARK_AMOLED(R.string.theme_dark_amoled, darkColorScheme(
        primary = Color(0xFFFFB078),
        onPrimary = Color(0xFF3A1A00),
        primaryContainer = Color(0xFF5E2E0C),
        onPrimaryContainer = Color(0xFFFFDCC6),
        secondary = Color(0xFFB3CCC6),
        onSecondary = Color(0xFF1E3531),
        secondaryContainer = Color(0xFF202A28),
        onSecondaryContainer = Color(0xFFCFEAE4),
        tertiary = Color(0xFF6FE0CE),
        onTertiary = Color(0xFF003730),
        tertiaryContainer = Color(0xFF0F4C43),
        onTertiaryContainer = Color(0xFFA6F2E5),
        error = Color(0xFFFFB4AB),
        errorContainer = Color(0xFF93000A),
        onError = Color(0xFF690005),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF000000),
        onBackground = Color(0xFFECEEF1),
        surface = Color(0xFF000000),
        onSurface = Color(0xFFECEEF1),
        surfaceVariant = Color(0xFF121212),
        onSurfaceVariant = Color(0xFFA8AEB5),
        outline = Color(0xFF242424)
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

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = theme.colorScheme.background.toArgb()
            window.navigationBarColor = theme.colorScheme.background.toArgb()

            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !theme.isDark
            insetsController.isAppearanceLightNavigationBars = !theme.isDark
        }
    }

    MaterialTheme(
        colorScheme = theme.colorScheme,
        shapes = ChimeraShapes,
        content = content
    )
}
