package com.carnelia.vpn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.Activity
import com.carnelia.vpn.utils.PrefsManager
import com.carnelia.vpn.R

/**
 * Единая палитра Carnelia: холодные нейтральные серые (не тёплая охра/шоколад) + один акцент —
 * карнелиан (сердолик), от которого взято имя приложения. Ровно 4 темы, без вариаций-костылей.
 */
enum class AppTheme(val displayNameResId: Int, val colorScheme: androidx.compose.material3.ColorScheme, val isDark: Boolean) {
    LIGHT(R.string.theme_light, lightColorScheme(
        primary = Color(0xFFE4432C),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFDE7E3),
        onPrimaryContainer = Color(0xFF7A1F12),
        secondary = Color(0xFF6B7280),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFEEF0F3),
        onSecondaryContainer = Color(0xFF12141A),
        tertiary = Color(0xFF6B7280),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFEEF0F3),
        onTertiaryContainer = Color(0xFF12141A),
        error = Color(0xFFDC2626),
        errorContainer = Color(0xFFFEE2E2),
        onError = Color(0xFFFFFFFF),
        onErrorContainer = Color(0xFF7F1D1D),
        background = Color(0xFFF5F6F8),
        onBackground = Color(0xFF12141A),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF12141A),
        surfaceVariant = Color(0xFFEEF0F3),
        onSurfaceVariant = Color(0xFF6B7280),
        outline = Color(0xFFE4E7EC)
    ), isDark = false),

    LIGHT_HC(R.string.theme_light_hc, lightColorScheme(
        primary = Color(0xFFB3271A),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFD9D2),
        onPrimaryContainer = Color(0xFF3D0805),
        secondary = Color(0xFF374151),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE4E7EC),
        onSecondaryContainer = Color(0xFF000000),
        tertiary = Color(0xFF000000),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE4E7EC),
        onTertiaryContainer = Color(0xFF000000),
        error = Color(0xFFB3271A),
        errorContainer = Color(0xFFFFD9D2),
        onError = Color(0xFFFFFFFF),
        onErrorContainer = Color(0xFF3D0805),
        background = Color(0xFFFFFFFF),
        onBackground = Color(0xFF000000),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF000000),
        surfaceVariant = Color(0xFFE4E7EC),
        onSurfaceVariant = Color(0xFF000000),
        outline = Color(0xFF000000)
    ), isDark = false),

    DARK(R.string.theme_dark, darkColorScheme(
        primary = Color(0xFFFF6B4E),
        onPrimary = Color(0xFF1A0704),
        primaryContainer = Color(0xFF3A1B15),
        onPrimaryContainer = Color(0xFFFFD9CC),
        secondary = Color(0xFF9AA1AC),
        onSecondary = Color(0xFF12141A),
        secondaryContainer = Color(0xFF1E2126),
        onSecondaryContainer = Color(0xFFF4F5F7),
        tertiary = Color(0xFF9AA1AC),
        onTertiary = Color(0xFF12141A),
        tertiaryContainer = Color(0xFF1E2126),
        onTertiaryContainer = Color(0xFFF4F5F7),
        error = Color(0xFFFF7A63),
        errorContainer = Color(0xFF3A1B15),
        onError = Color(0xFF1A0704),
        onErrorContainer = Color(0xFFFFD9D2),
        background = Color(0xFF0D0F12),
        onBackground = Color(0xFFF4F5F7),
        surface = Color(0xFF16181C),
        onSurface = Color(0xFFF4F5F7),
        surfaceVariant = Color(0xFF1E2126),
        onSurfaceVariant = Color(0xFF9AA1AC),
        outline = Color(0xFF262A31)
    ), isDark = true),

    DARK_AMOLED(R.string.theme_dark_amoled, darkColorScheme(
        primary = Color(0xFFFF7A5C),
        onPrimary = Color(0xFF1A0704),
        primaryContainer = Color(0xFF3A1B15),
        onPrimaryContainer = Color(0xFFFFD9CC),
        secondary = Color(0xFF9AA1AC),
        onSecondary = Color(0xFF0A0A0A),
        secondaryContainer = Color(0xFF141414),
        onSecondaryContainer = Color(0xFFF4F5F7),
        tertiary = Color(0xFF9AA1AC),
        onTertiary = Color(0xFF0A0A0A),
        tertiaryContainer = Color(0xFF141414),
        onTertiaryContainer = Color(0xFFF4F5F7),
        error = Color(0xFFFF8A73),
        errorContainer = Color(0xFF3A1B15),
        onError = Color(0xFF1A0704),
        onErrorContainer = Color(0xFFFFD9D2),
        background = Color(0xFF000000),
        onBackground = Color(0xFFF4F5F7),
        surface = Color(0xFF000000),
        onSurface = Color(0xFFF4F5F7),
        surfaceVariant = Color(0xFF121212),
        onSurfaceVariant = Color(0xFF9AA1AC),
        outline = Color(0xFF232323)
    ), isDark = true)
}

@Composable
fun CarheliaTheme(
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
        content = content
    )
}
