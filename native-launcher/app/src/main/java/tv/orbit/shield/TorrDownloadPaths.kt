package tv.orbit.shield

import java.io.IOException

/** Keep archive references intact; reject unsafe paths instead of silently renaming them. */
internal object TorrDownloadPaths {
    fun components(path: String): List<String> {
        if (path.isEmpty() || path.length > 4096 || path.startsWith('/') || path.endsWith('/')) throw IOException("Invalid torrent path")
        val parts = path.split('/')
        if (parts.size > 32) throw IOException("Torrent path is too deep")
        parts.forEach { part ->
            if (part.isEmpty() || part == "." || part == ".." || part.endsWith('.') || part.endsWith(' ') ||
                part.any { it.isISOControl() || it in "\\:*?\"<>|" } || part.toByteArray(Charsets.UTF_8).size > 255) {
                throw IOException("Unsupported torrent path")
            }
            part.forEachIndexed { i, c ->
                if ((Character.isHighSurrogate(c) && (i + 1 == part.length || !Character.isLowSurrogate(part[i + 1]))) ||
                    (Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(part[i - 1])))) {
                    throw IOException("Invalid Unicode path")
                }
            }
        }
        return parts
    }
}
