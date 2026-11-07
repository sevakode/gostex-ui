package tv.orbit.shield

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
