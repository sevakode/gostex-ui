package tv.orbit.shield

/** Only identify the local TorrServer stream when both file and torrent are unambiguous. */
internal fun localTorrentStreamIdentity(value: String?): Pair<String, Int>? {
    if (value.isNullOrBlank()) return null
    return runCatching {
        val uri = java.net.URI(value)
        if (uri.scheme != "http" || uri.host != "127.0.0.1" || uri.port != 8090 || uri.path != "/stream") return null
        val fields = (uri.rawQuery ?: return null).split('&').mapNotNull { field ->
            val at = field.indexOf('=')
            if (at < 0) null else field.substring(0, at) to field.substring(at + 1)
        }
        val hash = fields.filter { it.first == "link" }.singleOrNull()?.second ?: return null
        val index = fields.filter { it.first == "index" }.singleOrNull()?.second?.toIntOrNull() ?: return null
        if (!hash.matches(Regex("[0-9a-fA-F]{40}")) || index <= 0) return null
        hash.lowercase(java.util.Locale.ROOT) to index
    }.getOrNull()
}

/** VLC can expose the selected stream as a relative title rather than a full URI. */
internal fun vlcTorrentTitleIdentity(title: String?): Pair<String, Int>? {
    if (title.isNullOrBlank()) return null
    return if (title.startsWith("stream?")) localTorrentStreamIdentity("http://127.0.0.1:8090/$title")
        else localTorrentStreamIdentity(title)
}

/** VLC may omit the file extension from the title exposed by its media session. */
internal fun torrentVideoTitleMatches(title: String?, names: Set<String>): Boolean {
    if (title.isNullOrBlank()) return false
    fun normalized(value: String): String {
        val name = value.substringAfterLast('/')
        return if (name.substringAfterLast('.', "").lowercase() in setOf("mkv", "mp4", "avi", "mov", "m4v", "webm", "ts", "m2ts")) name.substringBeforeLast('.') else name
    }
    return names.any { title == it || normalized(title).equals(normalized(it), ignoreCase = true) }
}

/** A completed preload remains eligible when the preparation window resumes. */
internal fun shouldAutoOpenPrepared(ready: Boolean, enabled: Boolean, resumed: Boolean): Boolean =
    ready && enabled && resumed

/** The response may be HTTP 200 even when the server aborted preloading. */
internal fun torrentPreloadReady(state: Int?, cached: Long?, target: Long?, fileSize: Long? = null): Boolean {
    if (state != 3 || target == null || target <= 0 || cached == null) return false
    val threshold = fileSize?.takeIf { it > 0 }?.let { minOf(target, it) } ?: target
    return cached >= threshold
}

/** The server reads two edges in 32 KiB steps; its cache count may miss their tails. */
internal fun selectedTorrentPreloadReady(state: Int?, cached: Long?, target: Long?, fileSize: Long?): Boolean {
    if (state != 3 || cached == null || target == null || target <= 0 || fileSize == null || fileSize <= 0) return false
    val threshold = minOf(target, fileSize)
    // A fixed two-block allowance must not make a small buffer look full
    // when almost none of it has been cached.
    val slack = if (threshold >= 65_536L) minOf(65_536L, threshold / 10) else 0L
    return cached >= threshold - slack
}

/** A completed response may precede the server's next cache-metrics refresh. */
internal fun completedSelectedPreload(state: Int?, fileSize: Long?): Boolean =
    state == 3 && fileSize != null && fileSize > 0

/** The status transition can complete before the long-running request returns. */
internal fun observedTorrentPreloadReady(started: Boolean, sawPreload: Boolean,
                                         state: Int?, cached: Long?, target: Long?, fileSize: Long?): Boolean =
    started && sawPreload && selectedTorrentPreloadReady(state, cached, target, fileSize)

/** Do not guess when a file disappeared or the saved path is ambiguous. */
internal fun lastTorrentFileIndex(paths: List<String>, savedPath: String?): Int? {
    if (savedPath.isNullOrBlank()) return null
    return paths.indices.singleOrNull { paths[it] == savedPath }
}
