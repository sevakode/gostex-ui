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

    /** Keep the saved size ordering while avoiding unreadably narrow cards in compact windows. */
    fun columns(available: Dp, size: TileSize): Int {
        val minimum = when (size) {
            TileSize.LARGE -> 160.dp
            TileSize.BALANCED -> 128.dp
            TileSize.COMPACT -> 96.dp
        }
        val content = (available - (screenInset + focusInset) * 2).coerceAtLeast(0.dp)
        val count = ((content + gap) / (minimum + gap)).toInt()
        return count.coerceIn(1, size.columns)
    }

    fun width(available: Dp, size: TileSize): Dp =
        columns(available, size).let { count ->
            ((available - (screenInset + focusInset) * 2 - gap * (count - 1)) / count).coerceAtLeast(1.dp)
        }
}
