package tv.orbit.shield

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class MovieAddon(val manifestUrl: String, val name: String)
internal data class MovieStreamOption(val name: String, val description: String, val uri: String, val fileIndex: Int?, val sizeBytes: Long?)

/** Opt-in add-ons are queried only for a film explicitly selected by the user. */
internal object MovieAddonClient {
    private const val LIMIT = 1024 * 1024

    private fun manifestUrl(value: String): URL {
        val url = URL(value.trim())
        require(url.protocol == "https" && !url.host.isNullOrBlank() && url.userInfo == null &&
            url.query == null && url.ref == null && url.path.endsWith("/manifest.json"))
        return url
    }

    private fun fetch(url: URL): JSONObject {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            check(connection.responseCode == HttpURLConnection.HTTP_OK)
            require(connection.contentLengthLong < 0 || connection.contentLengthLong <= LIMIT)
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= LIMIT)
                    out.write(buffer, 0, count)
                }
                out.toString("UTF-8")
            }
            return JSONObject(bytes)
        } finally { connection.disconnect() }
    }

    fun inspect(url: String): MovieAddon {
        val manifest = manifestUrl(url)
        val json = fetch(manifest)
        val name = json.getString("name").trim().take(128)
        require(name.isNotEmpty())
        val resources = json.getJSONArray("resources")
        require((0 until resources.length()).any { i ->
            resources.optString(i) == "stream" || resources.optJSONObject(i)?.optString("name") == "stream"
        })
        return MovieAddon(manifest.toString(), name)
    }

    internal fun streamUrl(addon: MovieAddon, mediaType: String, videoId: String): URL {
        require((mediaType == "movie" && videoId.matches(Regex("tt[0-9]{1,16}"))) ||
            (mediaType == "series" && videoId.matches(Regex("tt[0-9]{1,16}:[0-9]{1,4}:[0-9]{1,5}"))))
        val base = manifestUrl(addon.manifestUrl).toString().removeSuffix("/manifest.json")
        return URL("$base/stream/$mediaType/$videoId.json")
    }

    fun streams(addon: MovieAddon, videoId: String, mediaType: String = "movie"): List<MovieStreamOption> =
        parseStreams(fetch(streamUrl(addon, mediaType, videoId)))

    internal fun parseStreams(response: JSONObject): List<MovieStreamOption> {
        val entries = response.getJSONArray("streams")
        return buildList {
            for (index in 0 until minOf(entries.length(), 100)) {
                val entry = entries.optJSONObject(index) ?: continue
                val direct = entry.optString("url").takeIf { MovieLibraryStore.validSource(it) }
                val hash = entry.optString("infoHash").takeIf { it.matches(Regex("(?i)[0-9a-f]{40}")) }
                val uri = direct ?: hash?.let {
                    val trackers = entry.optJSONArray("sources")
                    buildString {
                        append("magnet:?xt=urn:btih:").append(it)
                        if (trackers != null) for (i in 0 until minOf(trackers.length(), 32)) {
                            val tracker = trackers.optString(i).removePrefix("tracker:")
                            if ((tracker.startsWith("https://") || tracker.startsWith("http://") || tracker.startsWith("udp://")) && tracker.length <= 2048) {
                                append("&tr=").append(URLEncoder.encode(tracker, "UTF-8"))
                            }
                        }
                    }
                } ?: continue
                val fileIndex = if (entry.has("fileIdx") && !entry.isNull("fileIdx"))
                    entry.optInt("fileIdx", -1).takeIf { it >= 0 } else null
                val size = entry.optJSONObject("behaviorHints")?.optLong("videoSize", -1)?.takeIf { it > 0 }
                add(MovieStreamOption(entry.optString("name").take(256), entry.optString("description").ifBlank { entry.optString("title") }.take(1024), uri, fileIndex, size))
            }
        }
    }
}
