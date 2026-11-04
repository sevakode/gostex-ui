package tv.orbit.shield

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

internal enum class ArtTheme(val id: String, val titleRes: Int) {
    GAMES("games", R.string.art_theme_games),
    MEDIA("media", R.string.art_theme_media),
    COMICS("comics", R.string.art_theme_comics),
    BOOKS("books", R.string.art_theme_books),
    HISTORY("history", R.string.art_theme_history)
}

internal enum class CaptionMode(val id: String, val titleRes: Int) {
    DETAILS("details", R.string.art_caption_details),
    TITLE("title", R.string.art_caption_title),
    HIDDEN("hidden", R.string.art_caption_hidden)
}

internal enum class ArtSpeed(val id: String, val titleRes: Int, val durationScale: Double) {
    SLOW("slow", R.string.art_speed_slow, 1.5),
    NORMAL("normal", R.string.art_speed_normal, 1.0),
    FAST("fast", R.string.art_speed_fast, .65)
}

internal enum class RevealStyle(val id: String, val titleRes: Int, val descriptionRes: Int) {
    MIXED("mixed", R.string.art_effect_mixed, R.string.art_effect_mixed_hint),
    SNAKES("snakes", R.string.art_effect_snakes, R.string.art_effect_snakes_hint),
    BLOCKS("blocks", R.string.art_effect_blocks, R.string.art_effect_blocks_hint),
    WEAVE("weave", R.string.art_effect_weave, R.string.art_effect_weave_hint),
    CALM("calm", R.string.art_effect_calm, R.string.art_effect_calm_hint),
    SCENE("scene", R.string.art_effect_scene, R.string.art_effect_scene_hint),
    INK("ink", R.string.art_effect_ink, R.string.art_effect_ink_hint),
    TILES("tiles", R.string.art_effect_tiles, R.string.art_effect_tiles_hint),
    FOCUS("focus", R.string.art_effect_focus, R.string.art_effect_focus_hint),
    STILL("still", R.string.art_effect_still, R.string.art_effect_still_hint);

    companion object {
        fun fromSavedId(id: String?): RevealStyle = when (id) {
            // Restore the playful intent of retired ids without changing current saved choices.
            "flow" -> SNAKES
            "rain" -> BLOCKS
            "vortex" -> WEAVE
            else -> entries.firstOrNull { it.id == id } ?: MIXED
        }
    }
}

internal data class ArtOptions(
    val priorities: List<ArtTheme> = ArtTheme.entries.toList(),
    val captionMode: CaptionMode = CaptionMode.DETAILS,
    val speed: ArtSpeed = ArtSpeed.NORMAL,
    val holdSeconds: Int = 10,
    val revealStyle: RevealStyle = RevealStyle.MIXED,
    val interludes: Boolean = true
)

internal object ArtPreferences {
    const val KEY_PREFIX = "art."
    val holdTimes = listOf(10, 20, 30, 60)
    val programs = listOf(RevealStyle.MIXED, RevealStyle.CALM, RevealStyle.STILL)

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)
    private fun SharedPreferences.string(key: String): String? = runCatching { getString(KEY_PREFIX + key, null) }.getOrNull()

    fun read(context: Context): ArtOptions {
        val prefs = prefs(context)
        val savedOrder = prefs.string("priorities").orEmpty().split(',').mapNotNull { id -> ArtTheme.entries.firstOrNull { it.id == id } }.distinct()
        // Keep known choices in their saved order and append new themes after upgrades.
        val priorities = savedOrder + ArtTheme.entries.filterNot { it in savedOrder }
        val hold = runCatching { prefs.getInt(KEY_PREFIX + "holdSeconds", 10) }.getOrDefault(10)
        return ArtOptions(
            priorities = priorities,
            captionMode = CaptionMode.entries.firstOrNull { it.id == prefs.string("captionMode") } ?: CaptionMode.DETAILS,
            speed = ArtSpeed.entries.firstOrNull { it.id == prefs.string("speed") } ?: ArtSpeed.NORMAL,
            holdSeconds = hold.takeIf { it in holdTimes } ?: 10,
            revealStyle = RevealStyle.fromSavedId(prefs.string("revealStyle")),
            interludes = runCatching { prefs.getBoolean(KEY_PREFIX + "interludes", true) }.getOrDefault(true)
        )
    }

    fun setCaptionMode(context: Context, value: CaptionMode) { prefs(context).edit().putString(KEY_PREFIX + "captionMode", value.id).apply() }
    fun setSpeed(context: Context, value: ArtSpeed) { prefs(context).edit().putString(KEY_PREFIX + "speed", value.id).apply() }
    fun setRevealStyle(context: Context, value: RevealStyle) { prefs(context).edit().putString(KEY_PREFIX + "revealStyle", value.id).apply() }
    fun setInterludes(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean(KEY_PREFIX + "interludes", enabled).apply() }
    fun setHoldSeconds(context: Context, seconds: Int) { prefs(context).edit().putInt(KEY_PREFIX + "holdSeconds", seconds.takeIf { it in holdTimes } ?: 10).apply() }
    fun prioritize(context: Context, theme: ArtTheme) {
        val order = listOf(theme) + read(context).priorities.filterNot { it == theme }
        prefs(context).edit().putString(KEY_PREFIX + "priorities", order.joinToString(",") { it.id }).apply()
    }
}

@Composable internal fun ArtSettings(initialFocus: FocusRequester) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("orbit", Context.MODE_PRIVATE) }
    var options by remember(context) { mutableStateOf(ArtPreferences.read(context)) }
    DisposableEffect(context, prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, name ->
            if (name == null || name.startsWith(ArtPreferences.KEY_PREFIX)) options = ArtPreferences.read(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        options = ArtPreferences.read(context)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    Text(tr(R.string.art_hint), color = Color(0xFFADADB0), fontSize = 13.sp, lineHeight = 19.sp)
    SettingRow(tr(R.string.art_preview), modifier = Modifier.focusRequester(initialFocus), onClick = {
        context.startActivity(Intent(context, PixelArtPreviewActivity::class.java))
    })
    SettingRow(tr(R.string.art_captions), tr(options.captionMode.titleRes), onClick = {
        ArtPreferences.setCaptionMode(context, CaptionMode.entries[(options.captionMode.ordinal + 1) % CaptionMode.entries.size])
    })
    SettingRow(tr(R.string.art_effect), tr(options.revealStyle.titleRes), onClick = {
        val programs = ArtPreferences.programs
        // A previously selected concrete style remains valid until the user changes it.
        ArtPreferences.setRevealStyle(context, programs[(programs.indexOf(options.revealStyle) + 1) % programs.size])
    })
    Text(tr(options.revealStyle.descriptionRes), color = Color(0xFFADADB0), fontSize = 12.sp, lineHeight = 17.sp)
    SettingRow(tr(R.string.art_speed), tr(options.speed.titleRes), onClick = {
        ArtPreferences.setSpeed(context, ArtSpeed.entries[(options.speed.ordinal + 1) % ArtSpeed.entries.size])
    })
    SettingRow(tr(R.string.art_hold), tr(R.string.art_seconds, options.holdSeconds), onClick = {
        val times = ArtPreferences.holdTimes
        ArtPreferences.setHoldSeconds(context, times[(times.indexOf(options.holdSeconds) + 1) % times.size])
    })
    SettingRow(tr(R.string.art_interludes), tr(if (options.interludes) R.string.art_interludes_on else R.string.art_interludes_off), onClick = {
        ArtPreferences.setInterludes(context, !options.interludes)
    })
    Text(tr(R.string.art_interludes_hint), color = Color(0xFFADADB0), fontSize = 12.sp, lineHeight = 17.sp)
    Text(tr(R.string.art_priorities), color = Color(0xFFCCFF00), fontSize = 12.sp)
    Text(tr(R.string.art_priority_hint), color = Color(0xFFADADB0), fontSize = 12.sp, lineHeight = 17.sp)
    options.priorities.forEachIndexed { index, theme ->
        key(theme.id) {
            SettingRow(tr(theme.titleRes), tr(R.string.art_priority_number, index + 1), trailing = "↑", onClick = {
                ArtPreferences.prioritize(context, theme)
            })
        }
    }
    SettingRow(tr(R.string.art_system), onClick = {
        runCatching { context.startActivity(Intent(Settings.ACTION_DREAM_SETTINGS)) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    })
}
