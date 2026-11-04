package tv.orbit.shield

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

internal enum class CoverStyle(val id: String, val titleRes: Int, val descriptionRes: Int) {
    GENERATED("generated", R.string.cover_style_generated, R.string.cover_style_generated_hint),
    AMBIENT("ambient", R.string.cover_style_ambient, R.string.cover_style_ambient_hint),
    PLAIN("plain", R.string.cover_style_plain, R.string.cover_style_plain_hint),
    CROP("crop", R.string.cover_style_crop, R.string.cover_style_crop_hint);

    companion object {
        fun fromSavedId(value: String?): CoverStyle = entries.firstOrNull { it.id == value } ?: GENERATED
    }
}

internal enum class TileSize(val id: String, val columns: Int, val titleRes: Int) {
    LARGE("large", 4, R.string.tile_size_large),
    BALANCED("balanced", 5, R.string.tile_size_balanced),
    COMPACT("compact", 6, R.string.tile_size_compact);

    companion object {
        fun fromSavedId(value: String?): TileSize = entries.firstOrNull { it.id == value } ?: LARGE
    }
}

internal data class CoverOptions(
    val style: CoverStyle = CoverStyle.GENERATED,
    val favoriteBadgeVisible: Boolean = true,
    val tileSize: TileSize = TileSize.LARGE
)

internal val LocalCoverStyle = staticCompositionLocalOf { CoverStyle.GENERATED }
internal val LocalTileSize = staticCompositionLocalOf { TileSize.LARGE }

internal object AppearancePreferences {
    const val KEY_PREFIX = "appearance."
    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)

    fun read(context: Context): CoverOptions {
        val preferences = prefs(context)
        return CoverOptions(
            CoverStyle.fromSavedId(runCatching { preferences.getString(KEY_PREFIX + "coverStyle", null) }.getOrNull()),
            runCatching { preferences.getBoolean(KEY_PREFIX + "favoriteBadgeVisible", true) }.getOrDefault(true),
            TileSize.fromSavedId(runCatching { preferences.getString(KEY_PREFIX + "tileSize", null) }.getOrNull())
        )
    }

    fun setCoverStyle(context: Context, value: CoverStyle) {
        prefs(context).edit().putString(KEY_PREFIX + "coverStyle", value.id).apply()
    }

    fun setFavoriteBadgeVisible(context: Context, visible: Boolean) {
        prefs(context).edit().putBoolean(KEY_PREFIX + "favoriteBadgeVisible", visible).apply()
    }

    fun setTileSize(context: Context, value: TileSize) {
        prefs(context).edit().putString(KEY_PREFIX + "tileSize", value.id).apply()
    }
}

/** One listener at the launcher root; cards read the provided style without their own listeners. */
@Composable internal fun rememberCoverOptions(): CoverOptions {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("orbit", Context.MODE_PRIVATE) }
    var options by remember(context) { mutableStateOf(AppearancePreferences.read(context)) }
    DisposableEffect(context, prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith(AppearancePreferences.KEY_PREFIX)) options = AppearancePreferences.read(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        options = AppearancePreferences.read(context)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return options
}

@Composable internal fun AppearanceSettings(initialFocus: FocusRequester) {
    val context = LocalContext.current
    val style = LocalCoverStyle.current
    Text(tr(R.string.appearance_hint), color = Color(0xFFADADB0), fontSize = 13.sp, lineHeight = 19.sp)
    SettingRow(tr(R.string.cover_style_title), tr(style.titleRes), modifier = Modifier.focusRequester(initialFocus), onClick = {
        AppearancePreferences.setCoverStyle(context, CoverStyle.entries[(style.ordinal + 1) % CoverStyle.entries.size])
    })
    Text(tr(style.descriptionRes), color = Color(0xFFADADB0), fontSize = 12.sp, lineHeight = 17.sp)
    val tileSize = LocalTileSize.current
    SettingRow(tr(R.string.tile_size_title), tr(tileSize.titleRes), onClick = {
        AppearancePreferences.setTileSize(context, TileSize.entries[(tileSize.ordinal + 1) % TileSize.entries.size])
    })
    Text(tr(R.string.tile_size_hint), color = Color(0xFFADADB0), fontSize = 12.sp, lineHeight = 17.sp)
    val favoriteBadgeVisible = LocalFavoriteBadgeVisible.current
    SettingRow(tr(R.string.favorite_badge_title), tr(if (favoriteBadgeVisible) R.string.enabled else R.string.disabled), onClick = {
        AppearancePreferences.setFavoriteBadgeVisible(context, !favoriteBadgeVisible)
    })
    Text(tr(R.string.favorite_badge_hint), color = Color(0xFFADADB0), fontSize = 12.sp, lineHeight = 17.sp)
}
