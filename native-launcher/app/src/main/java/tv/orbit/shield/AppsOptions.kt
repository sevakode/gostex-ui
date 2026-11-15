package tv.orbit.shield

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/** Existing installations keep their chosen Home layout until the owner enables this tab. */
internal data class AppsOptions(
    val tabVisible: Boolean = false,
    val catalogOrder: List<String> = InstallableAppCatalog.packageNames,
    val catalogHidden: Set<String> = emptySet(),
)

internal object AppsPreferences {
    private const val TAB_KEY = "apps.tabVisible"
    private const val ORDER_KEY = "apps.catalogOrder"
    private const val HIDDEN_KEY = "apps.catalogHidden"
    private fun order(preferences: SharedPreferences): List<String> = runCatching {
        AppCatalogPreferencesCodec.decodeOrder(preferences.getString(ORDER_KEY, "[]") ?: "[]")
    }.getOrDefault(emptyList())
    private fun hidden(preferences: SharedPreferences): Set<String> = runCatching {
        AppCatalogPreferencesCodec.checkedHidden(preferences.getStringSet(HIDDEN_KEY, emptySet())?.toSet() ?: emptySet())
    }.getOrDefault(emptySet())

    fun read(context: Context): AppsOptions {
        val preferences = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)
        val known = InstallableAppCatalog.packageNames
        val ordered = (order(preferences).filter { it in known } + known).distinct()
        return AppsOptions(runCatching { preferences.getBoolean(TAB_KEY, false) }.getOrDefault(false),
            ordered, hidden(preferences).filter { it in known }.toSet())
    }

    fun setTabVisible(context: Context, visible: Boolean) {
        context.getSharedPreferences("orbit", Context.MODE_PRIVATE).edit().putBoolean(TAB_KEY, visible).apply()
    }

    fun setCatalogVisible(context: Context, packageName: String, visible: Boolean) {
        if (packageName !in InstallableAppCatalog.packageNames) return
        val preferences = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)
        val before = hidden(preferences)
        val after = if (visible) before - packageName else before + packageName
        if (runCatching { AppCatalogPreferencesCodec.checkedHidden(after) }.isFailure) return
        if (before != after) preferences.edit().putStringSet(HIDDEN_KEY, after).apply()
    }

    fun moveCatalog(context: Context, packageName: String, delta: Int) {
        if (delta != -1 && delta != 1) return
        val known = InstallableAppCatalog.packageNames
        if (packageName !in known) return
        val preferences = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)
        val stored = order(preferences)
        val expanded = (stored + known).distinct()
        val displayed = expanded.filter { it in known }.toMutableList()
        val from = displayed.indexOf(packageName)
        val to = (from + delta).coerceIn(0, displayed.lastIndex)
        if (from == to) return
        displayed.add(to, displayed.removeAt(from))
        // Keep slots for unknown IDs so opening this version never discards future choices.
        val reordered = displayed.iterator()
        val after = expanded.map { if (it in known) reordered.next() else it }
        val serialized = runCatching { AppCatalogPreferencesCodec.encodeOrder(after) }.getOrNull() ?: return
        preferences.edit().putString(ORDER_KEY, serialized).apply()
    }

    fun changesOptions(key: String?): Boolean = key == null || key in setOf(TAB_KEY, ORDER_KEY, HIDDEN_KEY)
}

internal val LocalAppsOptions = staticCompositionLocalOf { AppsOptions() }

/** One root listener; the Home Apps row remains an independent section preference. */
@Composable internal fun rememberAppsOptions(context: Context = LocalContext.current): AppsOptions {
    val preferences = remember(context) { context.getSharedPreferences("orbit", Context.MODE_PRIVATE) }
    var options by remember(context) { mutableStateOf(AppsPreferences.read(context)) }
    DisposableEffect(context, preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (AppsPreferences.changesOptions(key)) options = AppsPreferences.read(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        options = AppsPreferences.read(context)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return options
}

@Composable internal fun AppsVisibilitySetting(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val options = LocalAppsOptions.current
    SettingRow(tr(R.string.apps_tab_visibility), tr(if (options.tabVisible) R.string.enabled else R.string.disabled),
        modifier = modifier, onClick = { AppsPreferences.setTabVisible(context, !options.tabVisible) })
    Text(tr(R.string.apps_tab_hint), color = Color(0xFFADADB0), fontSize = 12.sp, lineHeight = 17.sp)
}
