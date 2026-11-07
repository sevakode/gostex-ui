package tv.orbit.shield

import android.content.Context
import java.io.IOException

internal object TorrDownloadSpace {
    const val KEY = "downloads.reserveMiB"
    const val DEFAULT_MIB = 512
    val choices = listOf(0, 256, 512, 1024, 2048)
    fun read(context: Context): Int {
        val value = runCatching { context.getSharedPreferences("orbit", Context.MODE_PRIVATE).getInt(KEY, DEFAULT_MIB) }.getOrDefault(DEFAULT_MIB)
        return value.takeIf { it in choices } ?: DEFAULT_MIB
    }
    fun write(context: Context, value: Int) {
        require(value in choices)
        if (!context.getSharedPreferences("orbit", Context.MODE_PRIVATE).edit().putInt(KEY, value).commit()) throw IOException("Cannot save storage reserve")
    }
}
