package tv.orbit.shield

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared geometry for game artwork and installed-app banners on the TV canvas. */
internal object TileLayout {
    const val aspectRatio = 16f / 9f
    val screenInset = 36.dp
    val focusInset = 6.dp
    val gap = 16.dp
    val titleGap = 9.dp
    val titleSize = 14.sp

    fun width(available: Dp, size: TileSize): Dp =
        ((available - (screenInset + focusInset) * 2 - gap * (size.columns - 1)) / size.columns).coerceAtLeast(1.dp)
}
