package tv.orbit.shield

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

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
