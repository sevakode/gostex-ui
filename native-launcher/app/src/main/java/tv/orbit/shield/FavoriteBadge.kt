package tv.orbit.shield

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal val LocalFavoriteBadgeVisible = staticCompositionLocalOf { true }

/** A shared quiet mark; its preference is observed once at the launcher root. */
@Composable internal fun FavoriteBadge(modifier: Modifier = Modifier) {
    if (!LocalFavoriteBadgeVisible.current) return
    val description = tr(R.string.favorite_badge_description)
    Box(modifier.size(22.dp).semantics { contentDescription = description }.drawWithCache {
        val unit = size.minDimension / 22f
        // Outlined heart is 11 × 10 dp, centered on a small scrim for pale artwork.
        val heart = Path().apply {
            moveTo(11f * unit, 16f * unit)
            cubicTo(10f * unit, 14.8f * unit, 5.5f * unit, 11.9f * unit, 5.5f * unit, 9.1f * unit)
            cubicTo(5.5f * unit, 6.2f * unit, 9.1f * unit, 5.2f * unit, 11f * unit, 8f * unit)
            cubicTo(12.9f * unit, 5.2f * unit, 16.5f * unit, 6.2f * unit, 16.5f * unit, 9.1f * unit)
            cubicTo(16.5f * unit, 11.9f * unit, 12f * unit, 14.8f * unit, 11f * unit, 16f * unit)
            close()
        }
        val stroke = Stroke(width = 1.15.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        onDrawBehind {
            drawRoundRect(Color.Black.copy(alpha = 0.38f), cornerRadius = CornerRadius(6.dp.toPx()))
            drawPath(heart, Color.White.copy(alpha = 0.78f), style = stroke)
        }
    })
}
