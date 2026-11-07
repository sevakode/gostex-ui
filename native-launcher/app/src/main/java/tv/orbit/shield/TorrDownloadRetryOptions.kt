package tv.orbit.shield

import android.content.Context
import java.io.IOException

internal object TorrDownloadRetryOptions {
    private const val KEY = "downloads.retryTransient"
    fun read(context: Context): Boolean = runCatching {
        context.getSharedPreferences("orbit", Context.MODE_PRIVATE).getBoolean(KEY, true)
    }.getOrDefault(true)
    fun write(context: Context, enabled: Boolean) {
        if (!context.getSharedPreferences("orbit", Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).commit())
            throw IOException("Cannot save retry setting")
    }
}
