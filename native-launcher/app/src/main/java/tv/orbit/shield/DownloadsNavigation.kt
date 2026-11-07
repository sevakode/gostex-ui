package tv.orbit.shield

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

internal data class DownloadIndicator(val running: Boolean, val queued: Boolean, val fraction: Float?)
internal fun downloadIndicator(jobs: List<TorrDownloadJob>): DownloadIndicator {
    val running = jobs.firstOrNull { it.state == "running" }
    val fraction = running?.takeIf { it.total > 0 }?.let { (it.offset.toDouble() / it.total).coerceIn(0.0, 1.0).toFloat() }
    return DownloadIndicator(running != null, jobs.any { it.state == "queued" }, fraction)
}

internal object DownloadsNavigation {
    const val KEY = "downloads.tabVisible"
    fun visible(context: Context) = context.getSharedPreferences("orbit", Context.MODE_PRIVATE).readBooleanOrDefault(KEY, true)
    fun visible(context: Context, value: Boolean) { context.getSharedPreferences("orbit", Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply() }
}

@Composable internal fun rememberDownloadsVisible(): Boolean {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("orbit", Context.MODE_PRIVATE) }
    var value by remember(context) { mutableStateOf(DownloadsNavigation.visible(context)) }
    DisposableEffect(context, prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == DownloadsNavigation.KEY) value = DownloadsNavigation.visible(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        value = DownloadsNavigation.visible(context)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}

@Composable internal fun DownloadsVisibilitySetting(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val visible = rememberDownloadsVisible()
    SettingRow(tr(R.string.downloads_tab_visibility), tr(if (visible) R.string.enabled else R.string.disabled), modifier,
        onClick = { DownloadsNavigation.visible(context, !visible) })
}

/** Uses the existing transfer state; no additional polling or background service. */
@Composable internal fun DownloadsShortcut(active: Boolean, onClick: () -> Unit) {
    val jobs by TorrDownloadStore.jobs.collectAsState()
    val state = remember(jobs) { downloadIndicator(jobs) }
    val progress = state.fraction
    val description = when {
        progress != null -> tr(R.string.downloads_indicator_progress, (progress * 100).toInt())
        state.running -> tr(R.string.downloads_indicator_active)
        state.queued -> tr(R.string.downloads_indicator_queued)
        else -> tr(R.string.torr_download_channel)
    }
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        CompactIconButton(CompactIcon.Download, description, active = active, onClick = onClick)
        if (state.running || state.queued) Canvas(Modifier.size(36.dp).align(Alignment.Center)) {
            if (progress == null) drawCircle(Color(0xFFCCFF00), radius = 3.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(size.width - 4.dp.toPx(), 4.dp.toPx()))
            else {
                val width = 2.dp.toPx()
                val inset = width / 2
                drawArc(Color(0xFFCCFF00), startAngle = -90f,
                    sweepAngle = (360f * progress).coerceAtLeast(4f),
                    useCenter = false, topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = Size(size.width - width, size.height - width), style = Stroke(width, cap = StrokeCap.Round))
            }
        }
    }
}
