package tv.orbit.shield

/** A candidate only becomes an offline episode after the download is complete. */
internal data class PrefetchEpisode(val id: Int, val path: String, val bytes: Long)

/** Plans unattended downloads only when the complete episode order is unambiguous. */
internal fun planEpisodePrefetch(
    episodes: List<PrefetchEpisode>,
    currentId: Int,
    count: Int,
    existingIds: Set<Int>,
    freeBytes: Long?,
    reserveBytes: Long,
    quotaBytes: Long,
    occupiedBytes: Long,
): List<PrefetchEpisode> {
    if (count <= 0 || freeBytes == null || freeBytes < 0 || reserveBytes < 0 ||
        quotaBytes < 0 || occupiedBytes < 0 || occupiedBytes > quotaBytes ||
        episodes.any { it.id < 0 || it.bytes <= 0 } ||
        episodes.map { it.id }.toSet().size != episodes.size ||
        !orderedEpisodeFiles(episodes.map { it.path })) return emptyList()
    val current = episodes.indexOfFirst { it.id == currentId }
    if (current < 0 || freeBytes < reserveBytes) return emptyList()
    var space = freeBytes - reserveBytes
    var allowance = quotaBytes - occupiedBytes
    val result = mutableListOf<PrefetchEpisode>()
    for (episode in episodes.drop(current + 1).take(count)) {
        // Existing jobs, including paused ones, are left to the user's control.
        if (episode.id in existingIds) continue
        if (episode.bytes > space || episode.bytes > allowance) break
        result.add(episode)
        space -= episode.bytes
        allowance -= episode.bytes
    }
    return result
}
