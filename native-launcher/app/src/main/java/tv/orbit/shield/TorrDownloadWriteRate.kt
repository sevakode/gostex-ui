package tv.orbit.shield

import android.content.Context
import java.io.IOException

internal object TorrDownloadWriteRate {
    const val KEY = "downloads.writeRateKiB"
    const val PLAYBACK_KEY = "downloads.playbackWriteRateKiB"
    val choices = listOf(0, 512, 1024, 2048, 4096, 8192)

    fun read(context: Context): Int = read(context, KEY)
    fun readPlayback(context: Context): Int = read(context, PLAYBACK_KEY)
    fun write(context: Context, value: Int) = write(context, KEY, value)
    fun writePlayback(context: Context, value: Int) = write(context, PLAYBACK_KEY, value)

    private fun read(context: Context, key: String): Int {
        val value = runCatching { context.getSharedPreferences("orbit", Context.MODE_PRIVATE).getInt(key, 0) }.getOrDefault(0)
        return value.takeIf { it in choices } ?: 0
    }

    private fun write(context: Context, key: String, value: Int) {
        require(value in choices)
        if (!context.getSharedPreferences("orbit", Context.MODE_PRIVATE).edit().putInt(key, value).commit()) throw IOException("Cannot save write rate")
    }
}
