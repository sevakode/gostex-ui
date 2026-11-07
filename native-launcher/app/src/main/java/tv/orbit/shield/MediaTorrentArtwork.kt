package tv.orbit.shield

/** Reuse one unambiguous torrent's existing art without copying or fetching it. */
internal fun mediaTorrentArtwork(movie: SavedMovie, covers: Map<String, ManagedCover>, choices: Map<String, CoverChoice>): String {
    val hash = linkedTorrentHashes(listOf(movie)).singleOrNull() ?: return ""
    val key = "torrent:$hash"
    val cover = covers[key] ?: return ""
    return when (choices[key] ?: CoverChoice.AUTO) {
        CoverChoice.ORIGINAL -> cover.original
        CoverChoice.AUTO, CoverChoice.MANUAL -> cover.manual.ifEmpty { cover.original }
    }
}
