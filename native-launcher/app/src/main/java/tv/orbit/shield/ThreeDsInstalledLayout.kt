package tv.orbit.shield

internal enum class LibrarySource(val value: String) {
    FILES("files"), SINGLE_FILE("single-file"), AZAHAR_INSTALLED("azahar-installed");

    companion object {
        fun read(value: String?, platform: String): LibrarySource =
            when {
                value == SINGLE_FILE.value -> SINGLE_FILE
                platform == "Nintendo 3DS" && value == AZAHAR_INSTALLED.value -> AZAHAR_INSTALLED
                else -> FILES
            }
    }
}

/** Paths relative to the user-selected Azahar data directory. Only application/demo
 * content is walked: saves, shaders, system titles, updates and DLC are not games. */
internal object ThreeDsInstalledLayout {
    private val id32 = Regex("[0-9a-fA-F]{32}")
    private val id8 = Regex("[0-9a-fA-F]{8}")
    private val appName = Regex("[0-9a-fA-F]{8}\\.[aA][pP][pP]")
    private val applicationTypes = setOf("00040000", "00040002")
    fun directory(path: String): Boolean {
        if (path.isEmpty()) return true
        val parts = path.split('/')
        if (parts.size > 8) return false
        return parts.withIndex().all { (index, part) -> when (index) {
            0 -> part == "sdmc"
            1 -> part == "Nintendo 3DS"
            2, 3 -> id32.matches(part)
            4 -> part == "title"
            5 -> part.lowercase(java.util.Locale.ROOT) in applicationTypes
            6 -> id8.matches(part)
            7 -> part == "content"
            else -> false
        } }
    }
    fun application(path: String): Boolean =
        path.split('/').size == 9 && directory(path.substringBeforeLast('/')) &&
            appName.matches(path.substringAfterLast('/'))
}
