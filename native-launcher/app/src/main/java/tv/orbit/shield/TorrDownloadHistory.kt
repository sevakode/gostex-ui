package tv.orbit.shield

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import java.util.UUID

/** Visibility only: hidden completed jobs remain available to the media library. */
internal object TorrDownloadHistory {
    const val KEY = "downloads.hiddenFinished"

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences("orbit", Context.MODE_PRIVATE)

    private fun validId(id: String): Boolean = id.length == 36 && runCatching {
        UUID.fromString(id).toString().equals(id, ignoreCase = true)
    }.getOrDefault(false)

    private fun read(preferences: SharedPreferences): Set<String> = runCatching {
        preferences.getStringSet(KEY, emptySet()).orEmpty().filterTo(linkedSetOf(), ::validId)
    }.getOrDefault(emptySet())

    fun read(context: Context): Set<String> = read(preferences(context))

    fun completed(jobs: List<TorrDownloadJob>, hidden: Set<String>): List<TorrDownloadJob> =
        jobs.filter { it.state == "done" && it.id !in hidden }

    /** Call off the UI thread. Re-read here so consecutive hides do not lose each other. */
    @Synchronized fun hide(context: Context, id: String) {
        require(validId(id))
        val preferences = preferences(context)
        val hidden = read(preferences)
        if (id !in hidden && !preferences.edit().putStringSet(KEY, hidden + id).commit()) {
            // Android publishes its in-memory edit even when the disk write fails.
            // Restore visibility as well; a repeated disk failure is still reported below.
            runCatching { preferences.edit().putStringSet(KEY, hidden).commit() }
            throw IOException("Cannot save download history visibility")
        }
    }

    /** Returns a disposer; the owning screen must release the listener when it leaves. */
    fun observe(context: Context, changed: (Set<String>) -> Unit): () -> Unit {
        val preferences = preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY) changed(read(preferences))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        changed(read(preferences))
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}

internal fun torrDownloadHistoryPage(page: Int, visibleCount: Int): Int =
    page.coerceIn(0, ((visibleCount - 1).coerceAtLeast(0)) / 20)
