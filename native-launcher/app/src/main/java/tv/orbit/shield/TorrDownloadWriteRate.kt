package tv.orbit.shield

import android.content.Context
import java.io.IOException

internal object TorrDownloadWriteRate {
    const val KEY = "downloads.writeRateKiB"
    val choices = listOf(0, 512, 1024, 2048, 4096, 8192)
    fun read(context: Context): Int {
        val value = runCatching { context.getSharedPreferences("orbit", Context.MODE_PRIVATE).getInt(KEY, 0) }.getOrDefault(0)
        return value.takeIf { it in choices } ?: 0
    }
    fun write(context: Context, value: Int) {
        require(value in choices)
        if (!context.getSharedPreferences("orbit", Context.MODE_PRIVATE).edit().putInt(KEY, value).commit()) throw IOException("Cannot save write rate")
    }
}
