package tv.orbit.shield

import android.content.SharedPreferences
import kotlin.random.Random

/** One small write per displayed scene; shared across preview, dream and shortcut. */
internal class ArtProgramCursor(private val preferences: SharedPreferences) {
    companion object {
        private const val KEY = "screensaver.nextScene"
        private const val MAX_POSITION = 1_000_000L
        private const val INITIAL_VARIANTS = 20L
    }

    fun nextScene(): Long {
        val saved = runCatching { preferences.getLong(KEY, -1L) }.getOrDefault(-1L)
        return saved.takeIf { it in 0..MAX_POSITION } ?: Random.nextLong(INITIAL_VARIANTS)
    }

    fun shown(scene: Long) {
        if (scene !in 0..MAX_POSITION) return
        val previous = runCatching { preferences.getLong(KEY, -1L) }.getOrDefault(-1L)
        // An older, slower view must not move a newer view's cursor backwards.
        if (previous in 0..MAX_POSITION && previous > scene && scene != MAX_POSITION) return
        preferences.edit().putLong(KEY, if (scene == MAX_POSITION) 0 else scene + 1).apply()
    }
}
