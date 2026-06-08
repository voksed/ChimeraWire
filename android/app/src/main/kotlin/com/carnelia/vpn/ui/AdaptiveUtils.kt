package com.carnelia.vpn.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Tablet threshold: width >= 600dp (SW600 qualifier, industry standard)
private const val TABLET_WIDTH_DP = 600

// Large tablet: width >= 840dp
private const val LARGE_TABLET_WIDTH_DP = 840

data class WindowSize(
    val widthDp: Int,
    val heightDp: Int,
    val isLandscape: Boolean,
    val isTablet: Boolean,        // width >= 600dp
    val isLargeTablet: Boolean,   // width >= 840dp
) {
    // Phone in landscape — compact width but landscape orientation
    val isPhoneLandscape: Boolean get() = isLandscape && !isTablet
    // Any landscape with wide screen
    val isTabletLandscape: Boolean get() = isLandscape && isTablet
    // Best button size for the connect button
    val connectButtonDp: Dp get() = when {
        isLargeTablet -> 220.dp
        isTablet      -> 200.dp
        isLandscape   -> 140.dp
        else          -> 200.dp
    }
    // Whether to show a two-pane (master+detail) layout
    val useTwoPane: Boolean get() = isTablet
}

@Composable
fun rememberWindowSize(): WindowSize {
    val config = LocalConfiguration.current
    return WindowSize(
        widthDp        = config.screenWidthDp,
        heightDp       = config.screenHeightDp,
        isLandscape    = config.orientation == Configuration.ORIENTATION_LANDSCAPE,
        isTablet       = config.screenWidthDp >= TABLET_WIDTH_DP,
        isLargeTablet  = config.screenWidthDp >= LARGE_TABLET_WIDTH_DP,
    )
}
