package tv.orbit.shield

/** A cover is applied only when one source has one exact normalized match. */
internal object TorrentArtworkPolicy {
    fun query(title: String): String = title
        .replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), " ")
        .replace(Regex("[._]+"), " ")
        .replace(Regex("(?i)\\b(?:19|20)\\d{2}\\b.*$"), " ")
        .replace(Regex("(?i)\\b(?:s\\d{1,2}(?:e\\d{1,3})?|season\\s*\\d{1,2}|сезон\\s*\\d{1,2}|(?:480|720|1080|2160)p|web[ -]?dl|bluray|bdrip)\\b.*$"), " ")
        .replace(Regex("\\s+"), " ").trim().take(150)
    fun normalized(value: String) = value.lowercase(java.util.Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
    fun uniqueSource(movieCount: Int, gameCount: Int): String? = when {
        movieCount == 1 && gameCount == 0 -> "movie"
        gameCount == 1 && movieCount == 0 -> "game"
        else -> null
    }
}
