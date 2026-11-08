package tv.orbit.shield

/** The response may be HTTP 200 even when the server aborted preloading. */
internal fun torrentPreloadReady(state: Int?, cached: Long?, target: Long?, fileSize: Long? = null): Boolean {
    if (state != 3 || target == null || target <= 0 || cached == null) return false
    val threshold = fileSize?.takeIf { it > 0 }?.let { minOf(target, it) } ?: target
    return cached >= threshold
}

/** Do not guess when a file disappeared or the saved path is ambiguous. */
internal fun lastTorrentFileIndex(paths: List<String>, savedPath: String?): Int? {
    if (savedPath.isNullOrBlank()) return null
    return paths.indices.singleOrNull { paths[it] == savedPath }
}
