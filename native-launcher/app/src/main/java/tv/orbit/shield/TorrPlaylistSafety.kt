package tv.orbit.shield

import java.net.URI

/** Accept only a local server playlist containing exactly the expected videos.
 * Sidecar URLs must also refer to known, non-video files in the same torrent. */
internal fun isSafeTorrentPlaylist(text: String, hash: String, videoIds: List<Int>, allIds: Set<Int>, sidecarIds: Set<Int>, port: Int = 8090): Boolean {
    if (!hash.matches(Regex("(?i)[0-9a-f]{40}")) || port !in 1..65535 ||
        videoIds.size < 2 || videoIds.distinct().size != videoIds.size || !allIds.containsAll(videoIds)) return false
    if (text.length > 2 * 1024 * 1024 || text.any { it.code < 32 && it != '\n' && it != '\r' && it != '\t' }) return false
    val lines = text.lines().map { it.trimEnd('\r') }
    if (lines.firstOrNull() != "#EXTM3U") return false
    val videoSet = videoIds.toSet()
    if (!allIds.containsAll(sidecarIds) || sidecarIds.any { it in videoSet }) return false
    val permittedKeys = setOf("link", "index", "play")
    fun localStream(raw: String, allowedIds: Set<Int>): Int? = runCatching {
        val uri = URI(raw)
        if (uri.scheme != "http" || uri.host != "127.0.0.1" || uri.port != port ||
            uri.rawUserInfo != null || uri.rawFragment != null || uri.rawPath?.startsWith("/stream/") != true) return@runCatching null
        val parts = uri.rawQuery?.split('&') ?: return@runCatching null
        val params = parts.map { it.split('=', limit = 2) }
        if (params.any { it[0] !in permittedKeys } ||
            params.count { it[0] == "link" } != 1 || params.count { it[0] == "index" } != 1 ||
            params.count { it[0] == "play" } != 1) return@runCatching null
        if (!params.single { it[0] == "link" }.getOrNull(1).equals(hash, true)) return@runCatching null
        val id = params.single { it[0] == "index" }.getOrNull(1)?.toIntOrNull() ?: return@runCatching null
        id.takeIf { it in allowedIds }
    }.getOrNull()
    val entries = mutableListOf<Int>()
    for (line in lines.drop(1)) when {
        line.isEmpty() -> Unit
        line.startsWith("#EXTINF:") -> Unit
        line.startsWith("#EXTVLCOPT:input-slave=") -> {
            val value = line.substringAfter('=')
            val sidecars = value.removeSuffix("#").split('#')
            if (sidecars.isEmpty() || sidecars.any { it.isEmpty() || localStream(it, sidecarIds) == null }) return false
        }
        line.startsWith('#') -> return false
        else -> {
            if (entries.size >= videoIds.size) return false
            entries.add(localStream(line, videoSet) ?: return false)
        }
    }
    return entries == videoIds
}
