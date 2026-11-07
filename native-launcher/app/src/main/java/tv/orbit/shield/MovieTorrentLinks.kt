package tv.orbit.shield

/** Count cards that depend on this server entry; magnet sources can recreate it separately. */
internal fun linkedTorrentMovieCount(movies: List<SavedMovie>, hash: String): Int {
    require(hash.matches(Regex("(?i)[0-9a-f]{40}")))
    val target = "torrserver://$hash"
    return movies.count { movie -> movie.sources.any { it.uri.equals(target, ignoreCase = true) } }
}

private val directTorrentSource = Regex("(?i)torrserver://[0-9a-f]{40}")

/** Known torrent identities already present in media, including self-contained magnets. */
internal fun linkedTorrentHashes(movies: List<SavedMovie>): Set<String> = movies.asSequence()
    .flatMap { it.sources.asSequence() }
    .mapNotNull { source -> torrentSourceHash(source.uri) }
    .toSet()

private fun torrentSourceHash(uri: String): String? {
    if (uri.matches(directTorrentSource)) return uri.substringAfter("://").lowercase(java.util.Locale.ROOT)
    if (!uri.startsWith("magnet:?", ignoreCase = true)) return null
    return uri.substringAfter('?').split('&').firstNotNullOfOrNull { parameter ->
        val pair = parameter.split('=', limit = 2)
        if (pair.size != 2 || !pair[0].equals("xt", ignoreCase = true)) null
        else runCatching { java.net.URLDecoder.decode(pair[1], "UTF-8") }.getOrNull()
            ?.takeIf { it.startsWith("urn:btih:", ignoreCase = true) }
            ?.substringAfterLast(':')?.let(::decodeTorrentHash)
    }
}

private fun decodeTorrentHash(value: String): String? {
    if (value.matches(Regex("(?i)[0-9a-f]{40}"))) return value.lowercase(java.util.Locale.ROOT)
    if (!value.matches(Regex("(?i)[a-z2-7]{32}"))) return null
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    var bits = 0
    var accumulator = 0
    val bytes = ArrayList<Byte>(20)
    for (character in value.uppercase(java.util.Locale.ROOT)) {
        accumulator = (accumulator shl 5) or alphabet.indexOf(character)
        bits += 5
        if (bits >= 8) {
            bits -= 8
            bytes.add(((accumulator shr bits) and 255).toByte())
            accumulator = accumulator and ((1 shl bits) - 1)
        }
    }
    return bytes.takeIf { it.size == 20 && bits == 0 }?.joinToString("") { "%02x".format(it.toInt() and 255) }
}
