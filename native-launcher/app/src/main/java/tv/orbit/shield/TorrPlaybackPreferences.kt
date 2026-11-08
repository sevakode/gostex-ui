package tv.orbit.shield

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/** Local playback choices. File paths are scoped to a title and torrent. */
internal object TorrPlaybackPreferences {
    const val AUTO_KEY = "movies.torrent.autoPlay"
    private fun prefs(context: Context) = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)
    fun autoPlay(context: Context) = prefs(context).readBooleanOrDefault(AUTO_KEY, true)
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

@Composable internal fun TorrAutoPlaySetting() {
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
    SettingRow(tr(R.string.movies_auto_play), tr(if (enabled) R.string.enabled else R.string.disabled), onClick = {
        prefs.edit().putBoolean(TorrPlaybackPreferences.AUTO_KEY, !enabled).apply()
    })
}
