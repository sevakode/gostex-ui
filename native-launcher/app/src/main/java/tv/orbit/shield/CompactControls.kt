package tv.orbit.shield

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface

/** One TV-sized focus target for toolbar and playback actions; glyphs never depend on system fonts. */
internal enum class CompactIcon { Search, Clear, Open, Previous, Next, Play, Pause, Close, Add, Folder, Settings, Music }

@Composable internal fun CompactIconButton(
    icon: CompactIcon,
    description: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(40.dp).touchSurface(enabled = enabled, onClick = onClick).semantics { contentDescription = description },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Color(0xFFCCFF00) else if (active) Color(0xFF303034) else Color(0xFF202024),
            contentColor = if (primary) Color(0xFF0A0A0A) else if (active) Color(0xFFCCFF00) else Color(0xFFF5F5F5),
            focusedContainerColor = Color(0xFFCCFF00),
            focusedContentColor = Color(0xFF0A0A0A),
            disabledContainerColor = Color(0xFF18181B),
            disabledContentColor = Color(0xFF56565B),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color(0xFFCCFF00)))),
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            CompactGlyph(icon, Modifier.size(20.dp), LocalContentColor.current)
        }
    }
}

@Composable internal fun CompactGlyph(icon: CompactIcon, modifier: Modifier = Modifier, tint: Color = Color(0xFFF5F5F5)) {
    Canvas(modifier) {
        val unit = size.minDimension / 24f
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, point(x1, y1), point(x2, y2), 2f * unit, StrokeCap.Round)
        fun triangle(left: Float, right: Float, top: Float = 5f, bottom: Float = 19f) {
            val path = Path().apply {
                moveTo(left * unit, top * unit)
                lineTo(right * unit, 12f * unit)
                lineTo(left * unit, bottom * unit)
                close()
            }
            drawPath(path, tint)
        }
        when (icon) {
            CompactIcon.Search -> {
                drawCircle(tint, 6.5f * unit, point(10f, 10f), style = Stroke(2f * unit))
                line(15f, 15f, 21f, 21f)
            }
            CompactIcon.Clear, CompactIcon.Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            CompactIcon.Add -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
            CompactIcon.Play -> triangle(7f, 19f)
            CompactIcon.Pause -> { line(8f, 5f, 8f, 19f); line(16f, 5f, 16f, 19f) }
            CompactIcon.Previous -> { line(5f, 5f, 5f, 19f); triangle(19f, 8f) }
            CompactIcon.Next -> { triangle(5f, 16f); line(19f, 5f, 19f, 19f) }
            CompactIcon.Open -> {
                val path = Path().apply {
                    moveTo(10f * unit, 4f * unit); lineTo(4f * unit, 4f * unit)
                    lineTo(4f * unit, 20f * unit); lineTo(20f * unit, 20f * unit); lineTo(20f * unit, 14f * unit)
                }
                drawPath(path, tint, style = Stroke(2f * unit, cap = StrokeCap.Round))
                line(12f, 12f, 21f, 3f); line(14f, 3f, 21f, 3f); line(21f, 3f, 21f, 10f)
            }
            CompactIcon.Folder -> {
                val path = Path().apply {
                    moveTo(3f * unit, 5f * unit); lineTo(10f * unit, 5f * unit)
                    lineTo(13f * unit, 8f * unit); lineTo(21f * unit, 8f * unit)
                    lineTo(21f * unit, 19f * unit); lineTo(3f * unit, 19f * unit); close()
                }
                drawPath(path, tint, style = Stroke(2f * unit))
            }
            CompactIcon.Settings -> {
                drawCircle(tint, 6f * unit, point(12f, 12f), style = Stroke(2f * unit))
                drawCircle(tint, 2f * unit, point(12f, 12f), style = Stroke(2f * unit))
                line(12f, 2f, 12f, 5f); line(12f, 19f, 12f, 22f)
                line(2f, 12f, 5f, 12f); line(19f, 12f, 22f, 12f)
                line(5f, 5f, 7f, 7f); line(17f, 17f, 19f, 19f)
                line(5f, 19f, 7f, 17f); line(17f, 7f, 19f, 5f)
            }
            CompactIcon.Music -> {
                line(9f, 17f, 9f, 5f); line(9f, 5f, 20f, 3f); line(20f, 3f, 20f, 15f)
                drawCircle(tint, 3f * unit, point(6f, 18f)); drawCircle(tint, 3f * unit, point(17f, 16f))
            }
        }
    }
}
