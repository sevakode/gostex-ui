package tv.orbit.shield

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

internal object TorrMediaVisibility {
    const val KEY = "downloads.showMediaTorrents"
    fun read(context: Context): Boolean = runCatching {
        context.getSharedPreferences("orbit", Context.MODE_PRIVATE).getBoolean(KEY, false)
    }.getOrDefault(false)
    fun write(context: Context, visible: Boolean) {
        context.getSharedPreferences("orbit", Context.MODE_PRIVATE).edit().putBoolean(KEY, visible).apply()
    }
}

@Composable internal fun rememberTorrMediaVisibility(): Boolean {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("orbit", Context.MODE_PRIVATE) }
    var visible by remember(context) { mutableStateOf(TorrMediaVisibility.read(context)) }
    DisposableEffect(context, prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == TorrMediaVisibility.KEY) visible = TorrMediaVisibility.read(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        visible = TorrMediaVisibility.read(context)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return visible
}
