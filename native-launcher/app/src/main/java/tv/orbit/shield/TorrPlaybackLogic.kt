package tv.orbit.shield

/** The response may be HTTP 200 even when the server aborted preloading. */
internal fun torrentPreloadReady(state: Int?, cached: Long?, target: Long?): Boolean =
    state == 3 && target != null && target > 0 && cached != null && cached >= target

/** Do not guess when a file disappeared or the saved path is ambiguous. */
internal fun lastTorrentFileIndex(paths: List<String>, savedPath: String?): Int? {
    if (savedPath.isNullOrBlank()) return null
    return paths.indices.singleOrNull { paths[it] == savedPath }
}
