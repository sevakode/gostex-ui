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

internal enum class NdsEmulatorChoice(val key: String) {
    MELON_DS("melonds"), RETROARCH_MELON_DS_DS("retroarch_melonds_ds");

    companion object {
        fun fromKey(key: String?): NdsEmulatorChoice = entries.firstOrNull { it.key == key } ?: MELON_DS
    }
}

internal data class LibraryOptions(
    val tabVisible: Boolean = true,
    val ndsEmulator: NdsEmulatorChoice = NdsEmulatorChoice.MELON_DS,
    val androidGamesVisible: Boolean = true
)
internal val LocalLibraryOptions = staticCompositionLocalOf { LibraryOptions() }

internal object LibraryPreferences {
    private const val TAB_KEY = "library.tabVisible"
    private const val NDS_KEY = "library.ndsEmulator"
    private const val ANDROID_GAMES_KEY = "library.androidGamesVisible"
    fun read(context: Context): LibraryOptions {
        val preferences = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)
        return LibraryOptions(
            tabVisible = runCatching { preferences.getBoolean(TAB_KEY, true) }.getOrDefault(true),
            ndsEmulator = NdsEmulatorChoice.fromKey(runCatching { preferences.getString(NDS_KEY, null) }.getOrNull()),
            androidGamesVisible = runCatching { preferences.getBoolean(ANDROID_GAMES_KEY, true) }.getOrDefault(true)
        )
    }
    fun setTabVisible(context: Context, visible: Boolean) {
        context.getSharedPreferences("orbit", Context.MODE_PRIVATE).edit().putBoolean(TAB_KEY, visible).apply()
    }
    fun setNdsEmulator(context: Context, choice: NdsEmulatorChoice) {
        context.getSharedPreferences("orbit", Context.MODE_PRIVATE).edit().putString(NDS_KEY, choice.key).apply()
    }
    fun setAndroidGamesVisible(context: Context, visible: Boolean) {
        context.getSharedPreferences("orbit", Context.MODE_PRIVATE).edit().putBoolean(ANDROID_GAMES_KEY, visible).apply()
    }
    fun changesOptions(key: String?): Boolean = key == null || key == TAB_KEY || key == NDS_KEY || key == ANDROID_GAMES_KEY
}

/** One root listener; selection is independent of the number of available ROMs. */
@Composable internal fun rememberLibraryOptions(context: Context = LocalContext.current): LibraryOptions {
    val preferences = remember(context) { context.getSharedPreferences("orbit", Context.MODE_PRIVATE) }
    var options by remember(context) { mutableStateOf(LibraryPreferences.read(context)) }
    DisposableEffect(context, preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (LibraryPreferences.changesOptions(key)) options = LibraryPreferences.read(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        options = LibraryPreferences.read(context)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return options
}

@Composable internal fun AndroidGamesVisibilitySetting(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val visible = LocalLibraryOptions.current.androidGamesVisible
    SettingRow(tr(R.string.android_games_visibility), tr(if (visible) R.string.enabled else R.string.disabled),
        modifier = modifier, onClick = { LibraryPreferences.setAndroidGamesVisible(context, !visible) })
    Text(tr(R.string.android_games_hint), color = Color(0xFFADADB0), fontSize = 12.sp, lineHeight = 17.sp)
}

@Composable internal fun LibraryVisibilitySetting(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val options = LocalLibraryOptions.current
    SettingRow(tr(R.string.library_tab_visibility), tr(if (options.tabVisible) R.string.enabled else R.string.disabled),
        modifier = modifier, onClick = { LibraryPreferences.setTabVisible(context, !options.tabVisible) })
    Text(tr(R.string.library_tab_hint), color = Color(0xFFADADB0), fontSize = 12.sp, lineHeight = 17.sp)
}

@Composable internal fun NintendoDsEmulatorSetting(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val selected = LocalLibraryOptions.current.ndsEmulator
    val label = when (selected) {
        NdsEmulatorChoice.MELON_DS -> R.string.nds_emulator_standalone
        NdsEmulatorChoice.RETROARCH_MELON_DS_DS -> R.string.nds_emulator_retroarch
    }
    SettingRow(tr(R.string.nds_emulator_title), tr(label), modifier = modifier, onClick = {
        val choices = NdsEmulatorChoice.entries
        LibraryPreferences.setNdsEmulator(context, choices[(selected.ordinal + 1) % choices.size])
    })
    Text(tr(R.string.nds_emulator_hint), color = Color(0xFFADADB0), fontSize = 12.sp, lineHeight = 17.sp)
}
