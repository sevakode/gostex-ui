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
internal data class AppsOptions(val tabVisible: Boolean = false)

internal object AppsPreferences {
    private const val TAB_KEY = "apps.tabVisible"
    fun read(context: Context) = AppsOptions(runCatching {
        context.getSharedPreferences("orbit", Context.MODE_PRIVATE).getBoolean(TAB_KEY, false)
    }.getOrDefault(false))

    fun setTabVisible(context: Context, visible: Boolean) {
        context.getSharedPreferences("orbit", Context.MODE_PRIVATE).edit().putBoolean(TAB_KEY, visible).apply()
    }

    fun changesOptions(key: String?): Boolean = key == null || key == TAB_KEY
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
