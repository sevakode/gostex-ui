package tv.orbit.shield

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import java.util.Locale

/** Local playback choices. File paths are scoped to a title and torrent. */
internal object TorrPlaybackPreferences {
    const val AUTO_KEY = "movies.torrent.autoPlay"
    const val NEXT_KEY = "movies.torrent.autoNext"
    const val RESTART_NEAR_END_KEY = "movies.video.restartNearEnd"
    private fun prefs(context: Context) = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)
    fun autoPlay(context: Context) = prefs(context).readBooleanOrDefault(AUTO_KEY, true)
    fun autoNext(context: Context) = prefs(context).readBooleanOrDefault(NEXT_KEY, true)
    fun restartNearEnd(context: Context) = prefs(context).readBooleanOrDefault(RESTART_NEAR_END_KEY, true)
    private fun fileKey(series: String, hash: String): String? {
        if (series.isBlank() || series.length > 256 || !hash.matches(Regex("(?i)[0-9a-f]{40}"))) return null
        return "movies.torrent.lastFile:$series:${hash.lowercase(Locale.ROOT)}"
    }
    fun rememberFile(context: Context, series: String, hash: String, path: String) {
        val key = fileKey(series, hash) ?: return
        if (path.isNotBlank() && path.length <= 4096) prefs(context).edit().putString(key, path).apply()
    }
    fun lastFileIndex(context: Context, series: String, hash: String, paths: List<String>): Int? {
        val key = fileKey(series, hash) ?: return null
        val path = runCatching { prefs(context).getString(key, null) }.getOrNull() ?: return null
        return lastTorrentFileIndex(paths, path)
    }
}

@Composable internal fun VideoRestartNearEndSetting() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("orbit", Context.MODE_PRIVATE) }
    var enabled by remember(context) { mutableStateOf(TorrPlaybackPreferences.restartNearEnd(context)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == TorrPlaybackPreferences.RESTART_NEAR_END_KEY) enabled = TorrPlaybackPreferences.restartNearEnd(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        enabled = TorrPlaybackPreferences.restartNearEnd(context)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SettingRow(tr(R.string.movies_restart_near_end), tr(if (enabled) R.string.enabled else R.string.disabled), isSelected = enabled, trailing = if (enabled) "✓" else "—", onClick = {
        prefs.edit().putBoolean(TorrPlaybackPreferences.RESTART_NEAR_END_KEY, !enabled).apply()
    })
}

@Composable internal fun TorrAutoPlaySetting(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("orbit", Context.MODE_PRIVATE) }
    var enabled by remember(context) { mutableStateOf(TorrPlaybackPreferences.autoPlay(context)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == TorrPlaybackPreferences.AUTO_KEY) enabled = TorrPlaybackPreferences.autoPlay(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        enabled = TorrPlaybackPreferences.autoPlay(context)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SettingRow(tr(R.string.movies_auto_play), tr(if (enabled) R.string.enabled else R.string.disabled), modifier = modifier, isSelected = enabled, trailing = if (enabled) "✓" else "—", onClick = {
        prefs.edit().putBoolean(TorrPlaybackPreferences.AUTO_KEY, !enabled).apply()
    })
}

@Composable internal fun TorrAutoNextSetting() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("orbit", Context.MODE_PRIVATE) }
    var enabled by remember(context) { mutableStateOf(TorrPlaybackPreferences.autoNext(context)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == TorrPlaybackPreferences.NEXT_KEY) enabled = TorrPlaybackPreferences.autoNext(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        enabled = TorrPlaybackPreferences.autoNext(context)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SettingRow(tr(R.string.movies_auto_next), tr(if (enabled) R.string.enabled else R.string.disabled), isSelected = enabled, trailing = if (enabled) "✓" else "—", onClick = {
        prefs.edit().putBoolean(TorrPlaybackPreferences.NEXT_KEY, !enabled).apply()
    })
}
