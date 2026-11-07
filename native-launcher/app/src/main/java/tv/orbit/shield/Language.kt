package tv.orbit.shield

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.util.Locale

internal object Language {
    fun context(base: Context): Context {
        val saved = base.getSharedPreferences("orbit", Context.MODE_PRIVATE).readStringOrDefault("language")
        val system = base.resources.configuration.locales
        val supported = setOf("ru", "en", "es")
        val code = saved?.takeIf { it in supported }
            ?: (0 until system.size()).map { system[it].language }.firstOrNull { it in supported }
            ?: "en"
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(code)))
        return base.createConfigurationContext(config)
    }
}

@Composable internal fun tr(id: Int, vararg args: Any): String = stringResource(id, *args)

@Composable internal fun tabTitle(tab: String): String = tr(when (tab) {
    "home" -> R.string.home
    "library" -> R.string.library
    "downloads" -> R.string.torr_download_channel
    "favorites" -> R.string.favorites
    "movies" -> R.string.media_tab_title
    "apps" -> R.string.apps
    "settings" -> R.string.settings
    else -> R.string.home
})

@Composable internal fun genre(value: String): String {
    val id = when (value) {
        "Платформер" -> R.string.genre_platform
        "Экшен" -> R.string.genre_action
        "Головоломка" -> R.string.genre_puzzle
        "Приключение" -> R.string.genre_adventure
        "Стратегия" -> R.string.genre_strategy
        "Шутер" -> R.string.genre_shooter
        "Role-playing (RPG)" -> R.string.genre_rpg
        "Simulation" -> R.string.genre_sim
        "Beat'em Up" -> R.string.genre_beat
        "Shoot'em Up" -> R.string.genre_shoot
        else -> return value
    }
    return tr(id)
}

internal fun description(game: Game): String = game.description
