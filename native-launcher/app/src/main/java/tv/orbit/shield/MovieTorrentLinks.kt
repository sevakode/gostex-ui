package tv.orbit.shield

/** Count cards that depend on this server entry; magnet sources can recreate it separately. */
internal fun linkedTorrentMovieCount(movies: List<SavedMovie>, hash: String): Int {
    require(hash.matches(Regex("(?i)[0-9a-f]{40}")))
    val target = "torrserver://$hash"
    return movies.count { movie -> movie.sources.any { it.uri.equals(target, ignoreCase = true) } }
}
