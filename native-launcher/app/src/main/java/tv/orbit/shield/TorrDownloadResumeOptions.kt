package tv.orbit.shield

import android.content.Context
import java.io.IOException

internal object TorrDownloadResumeOptions {
    const val KEY = "downloads.resumeInterrupted"

    fun read(context: Context): Boolean = runCatching {
        context.getSharedPreferences("orbit", Context.MODE_PRIVATE).getBoolean(KEY, false)
    }.getOrDefault(false)

    fun write(context: Context, enabled: Boolean) {
        if (!context.getSharedPreferences("orbit", Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).commit())
            throw IOException("Cannot save download resume setting")
    }
}
