package tv.orbit.shield

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.json.JSONArray

internal data class VideoOptions(val visible: Set<String>, val order: List<String>)

internal object VideoPreferences {
    private const val VISIBLE = "video.visible"
    private const val ORDER = "video.order"
    private fun prefs(context: Context) = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)
    fun read(context: Context): VideoOptions {
        val prefs = prefs(context)
        val defaults = context.resources.getStringArray(R.array.video_app_packages).toSet()
        val visible = runCatching { prefs.getStringSet(VISIBLE, defaults)?.toSet() ?: defaults }.getOrDefault(defaults)
        val order = runCatching {
            val array = JSONArray(prefs.getString(ORDER, "[]"))
            (0 until array.length()).mapNotNull { array.opt(it) as? String }.filter { it.isNotBlank() }.distinct()
        }.getOrDefault(emptyList())
        return VideoOptions(visible, order)
    }
    fun setVisible(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet(VISIBLE, packages.toSet()).apply()
    }
    fun prioritize(context: Context, packageName: String) {
        val previous = read(context)
        if (packageName !in previous.visible) return
        val order = listOf(packageName) + previous.order.filterNot { it == packageName }
        prefs(context).edit().putString(ORDER, JSONArray(order).toString()).apply()
    }
    fun changes(key: String?) = key == null || key == VISIBLE || key == ORDER
}

@Composable internal fun rememberVideoOptions(context: Context = LocalContext.current): VideoOptions {
    val prefs = remember(context) { context.getSharedPreferences("orbit", Context.MODE_PRIVATE) }
    var options by remember(context) { mutableStateOf(VideoPreferences.read(context)) }
    DisposableEffect(context, prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (VideoPreferences.changes(key)) options = VideoPreferences.read(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        options = VideoPreferences.read(context)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return options
}

internal fun orderedVideoApps(apps: List<TvApp>, options: VideoOptions): List<TvApp> {
    val positions = options.order.withIndex().associate { it.value to it.index }
    return apps.filter { it.packageName in options.visible }.sortedWith(
        compareBy<TvApp> { positions[it.packageName] ?: Int.MAX_VALUE }
            .thenBy { it.name.lowercase(java.util.Locale.ROOT) }.thenBy { it.packageName })
}

@Composable internal fun VideoSettings(initialFocus: FocusRequester, apps: List<TvApp>) {
    val context = LocalContext.current
    val options = rememberVideoOptions()
    val visible = remember(apps, options) { orderedVideoApps(apps, options) }
    Text(tr(R.string.video_settings_hint), color = Color(0xFFADADB0), fontSize = 13.sp, lineHeight = 19.sp)
    SettingRow(tr(R.string.video_choose_apps), tr(R.string.video_selected_count, visible.size),
        Modifier.focusRequester(initialFocus), onClick = {
            val selected = options.visible.toMutableSet()
            val checked = apps.map { it.packageName in selected }.toBooleanArray()
            AlertDialog.Builder(context).setTitle(R.string.video_choose_apps)
                .setMultiChoiceItems(apps.map { it.name }.toTypedArray(), checked) { _, index, enabled ->
                    if (enabled) selected.add(apps[index].packageName) else selected.remove(apps[index].packageName)
                }
                .setPositiveButton(R.string.video_apply) { _, _ -> VideoPreferences.setVisible(context, selected) }
                .setNegativeButton(R.string.cancel, null).show()
        })
    Text(tr(R.string.video_order_hint), color = Color(0xFFADADB0), fontSize = 12.sp, lineHeight = 18.sp)
    visible.forEachIndexed { index, app ->
        key(app.packageName) {
            SettingRow(app.name, tr(R.string.video_position, index + 1), trailing = "↑", onClick = {
                VideoPreferences.prioritize(context, app.packageName)
            })
        }
    }
}
