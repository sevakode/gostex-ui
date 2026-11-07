package tv.orbit.shield

/** Only canonical Cinemeta episode IDs are accepted from saved state or a player receipt. */
internal fun decodeEpisodeHistory(seriesId: String, value: String?): MovieEpisode? {
    if (!seriesId.matches(Regex("tt[0-9]{1,16}")) || value == null) return null
    val match = Regex("^(tt[0-9]{1,16}):([0-9]{1,4}):([0-9]{1,5})$").matchEntire(value) ?: return null
    if (match.groupValues[1] != seriesId) return null
    val season = match.groupValues[2].toIntOrNull() ?: return null
    val episode = match.groupValues[3].toIntOrNull() ?: return null
    if (value != "$seriesId:$season:$episode") return null
    return MovieEpisode(value, season, episode, "")
}

internal fun episodeHistoryKey(seriesId: String) = "played:episode:$seriesId"
