package tv.orbit.shield

import java.util.Locale

/** Local media URL for an explicitly selected server file, never a guessed index. */
internal object TorrPlayback {
    private val videoExtensions = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "mpg", "mpeg", "ts", "m2ts")
    fun isVideo(path: String): Boolean = path.substringAfterLast('.', "").lowercase(Locale.ROOT) in videoExtensions
    fun url(hash: String, index: Int): String {
        require(hash.matches(Regex("[0-9a-fA-F]{40}")) && index > 0)
        return "http://127.0.0.1:8090/stream?link=$hash&index=$index&play=1"
    }
}
