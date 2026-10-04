package com.carnelia.vpn.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Icon-tile accent tones for a grouped row, mapped onto the Material 3 container roles so they
 * follow the active theme.
 */
enum class TileTone { PRIMARY, SECONDARY, TERTIARY, NEUTRAL }

/** One row inside a [GroupSection]: icon tile, title, optional subtitle / trailing value. */
data class GroupRow(
    val icon: ImageVector,
    val title: String,
    val subtitle: String? = null,
    val value: String? = null,
    val tone: TileTone = TileTone.NEUTRAL,
    val onClick: () -> Unit
)

/**
 * A Material 3 "connected list": a titled section whose rows share one surface colour and sit in a
 * single rounded block — the first row rounds at the top, the last at the bottom, inner corners
 * stay tight (4dp), so the group reads as one card split by thin gaps.
 */
@Composable
fun GroupSection(
    title: String,
    rows: List<GroupRow>,
    modifier: Modifier = Modifier,
    tileSize: Dp = 40.dp
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )
        val big = 24.dp
        val small = 4.dp
        rows.forEachIndexed { index, row ->
            val top = if (index == 0) big else small
            val bottom = if (index == rows.lastIndex) big else small
            GroupedRow(
                row = row,
                shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
                tileSize = tileSize
            )
        }
    }
}

@Composable
private fun GroupedRow(row: GroupRow, shape: RoundedCornerShape, tileSize: Dp) {
    val scheme = MaterialTheme.colorScheme
    val tileBg: Color
    val tileFg: Color
    when (row.tone) {
        TileTone.PRIMARY -> { tileBg = scheme.primaryContainer; tileFg = scheme.onPrimaryContainer }
        TileTone.SECONDARY -> { tileBg = scheme.secondaryContainer; tileFg = scheme.onSecondaryContainer }
        TileTone.TERTIARY -> { tileBg = scheme.tertiaryContainer; tileFg = scheme.onTertiaryContainer }
        TileTone.NEUTRAL -> { tileBg = scheme.surfaceContainerHighest; tileFg = scheme.primary }
    }
    Surface(
        shape = shape,
        color = scheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = row.onClick)
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = if (row.subtitle != null) 72.dp else 60.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = tileBg,
                modifier = Modifier.size(tileSize)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(row.icon, contentDescription = null, tint = tileFg, modifier = Modifier.size(tileSize * 0.5f))
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.title,
                    color = scheme.onSurface,
                    fontSize = 16.sp,
                    lineHeight = 24.sp
                )
                if (row.subtitle != null) {
                    Text(
                        row.subtitle,
                        color = scheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
            }
            if (row.value != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    row.value,
                    color = scheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = scheme.onSurfaceVariant
            )
        }
    }
}
