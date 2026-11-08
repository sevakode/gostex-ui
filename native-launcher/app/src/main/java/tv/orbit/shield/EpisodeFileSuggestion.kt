package tv.orbit.shield

/** Exact unique filename is only a hint; the user confirms the selected file. */
internal fun suggestFilename(paths: List<String>, filename: String?): Int? {
    if (filename.isNullOrBlank() || filename.length > 512 || filename.any { it.isISOControl() }) return null
    fun basename(value: String) = value.substringAfterLast('/').substringAfterLast('\\')
    val name = basename(filename)
    if (name.isBlank()) return null
    return paths.indices.filter { basename(paths[it]).equals(name, ignoreCase = true) }.singleOrNull()
}

/** Conservative hint only: a user still confirms the file in the system list. */
internal fun suggestEpisodeFile(paths: List<String>, season: Int, episode: Int): Int? {
    if (season !in 0..9999 || episode !in 0..99999) return null
    val allTokens = Regex("(?i)(?<![a-z0-9])(?:s[0-9]{1,4}e[0-9]{1,5}|[0-9]{1,4}x[0-9]{1,5})(?![a-z0-9])")
    val wanted = Regex("(?i)(?<![a-z0-9])(?:s0*${season}e0*${episode}|0*${season}x0*${episode})(?![a-z0-9])(?!(?:[ ._-]+e[0-9]+|[ ._-]+s[0-9]+e[0-9]+|\\s*-\\s*[0-9]+(?![a-z0-9])))")
    val matches = paths.indices.filter { index ->
        val name = paths[index].substringAfterLast('/')
        allTokens.findAll(name).take(2).count() == 1 && wanted.containsMatchIn(name)
    }
    return matches.singleOrNull()
}

/** Keep an existing history entry when a selected file cannot be tied to the requested episode. */
internal fun episodeFileMatchesHistory(paths: List<String>, season: Int, episode: Int, selected: Int): Boolean {
    if (season !in 0..9999 || episode !in 0..99999 || selected !in paths.indices) return false
    val suggested = suggestEpisodeFile(paths, season, episode)
    if (suggested != null) return suggested == selected
    if (paths.size != 1) return false
    // An unlabeled single-video result can use the add-on's episode identity, but
    // a conflicting or multi-episode label is not reliable enough to remember.
    val name = paths.single().substringAfterLast('/')
    val marker = Regex("(?i)(?<![a-z0-9])(?:s[0-9]{1,4}e[0-9]{1,5}|[0-9]{1,4}x[0-9]{1,5})")
    return !marker.containsMatchIn(name)
}

/** Only hand an unattended queue to the player when the torrent's order is unambiguous. */
internal fun orderedEpisodeFiles(paths: List<String>): Boolean {
    if (paths.size < 2) return false
    val marker = Regex("(?i)(?<![a-z0-9])(?:s([0-9]{1,4})e([0-9]{1,5})|([0-9]{1,4})x([0-9]{1,5}))(?![a-z0-9])")
    val multiEpisode = Regex("(?i)(?:[ ._-]+e[0-9]+|[ ._-]+s[0-9]+e[0-9]+|\\s*-\\s*[0-9]+(?![a-z0-9]))")
    val episodes = paths.map { path ->
        val matches = marker.findAll(path.substringAfterLast('/')).take(2).toList()
        if (matches.size != 1) return false
        val m = matches.single()
        val season = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toIntOrNull() ?: return false
        val episode = (m.groupValues[2].ifEmpty { m.groupValues[4] }).toIntOrNull() ?: return false
        val tail = path.substringAfterLast('/').substring(m.range.last + 1)
        if (multiEpisode.find(tail)?.range?.first == 0) return false
        season to episode
    }
    return episodes.zipWithNext().all { (a, b) ->
        (b.first == a.first && b.second == a.second + 1) ||
            (b.first == a.first + 1 && b.second == 1)
    }
}
