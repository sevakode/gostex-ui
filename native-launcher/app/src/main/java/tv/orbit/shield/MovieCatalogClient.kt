package tv.orbit.shield

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class MovieCatalogItem(
    val id: String,
    val title: String,
    val year: String,
    val poster: String?,
    val description: String = "",
    val durationSeconds: Long? = null,
    val mediaType: String = "movie",
)

/** Metadata only; no streaming engine or background polling. Caller runs on Dispatchers.IO. */
internal object MovieCatalogClient {
    const val DEFAULT_BASE = "https://v3-cinemeta.strem.io"
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_ITEMS = 100

    fun search(query: String, base: String = DEFAULT_BASE, type: String = "movie"): List<MovieCatalogItem> {
        val term = query.trim().take(150)
        require(term.isNotEmpty())
        require(type in setOf("movie", "series"))
        val origin = origin(base)
        val encoded = URLEncoder.encode(term, "UTF-8").replace("+", "%20")
        val metas = fetch(URL("${origin}/catalog/$type/top/search=$encoded.json")).getJSONArray("metas")
        return buildList {
            for (i in 0 until minOf(metas.length(), MAX_ITEMS)) {
                val item = metas.optJSONObject(i) ?: continue
                val id = item.optString("id").take(256)
                val title = item.optString("name").trim().take(256)
                if (id.isBlank() || title.isBlank()) continue
                val poster = item.optString("poster").takeIf { it.startsWith("https://") }?.take(2048)
                add(MovieCatalogItem(id, title, item.optString("releaseInfo").take(64), poster, mediaType = type))
            }
        }.distinctBy(MovieCatalogItem::id)
    }

    fun details(id: String, base: String = DEFAULT_BASE, type: String = "movie"): MovieCatalogItem {
        require(id.matches(Regex("tt[0-9]{1,16}")))
        require(type in setOf("movie", "series"))
        val item = fetch(URL("${origin(base)}/meta/$type/$id.json")).getJSONObject("meta")
        require(item.getString("id") == id)
        val runtime = item.optString("runtime")
        val minutes = Regex("(?i)^(\\d{1,4})\\s*min(?:utes?)?$").matchEntire(runtime.trim())
            ?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
        return MovieCatalogItem(id, item.getString("name").trim().take(256),
            item.optString("releaseInfo").take(64),
            item.optString("poster").takeIf { it.startsWith("https://") }?.take(2048),
            item.optString("description").take(5000), minutes?.times(60), type)
    }

    private fun origin(base: String): URL = URL(base.trimEnd('/')).also {
        require(it.protocol == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.query == null && it.ref == null)
    }

    private fun fetch(url: URL): JSONObject {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "Catalog unavailable" }
            require(connection.contentLengthLong < 0 || connection.contentLengthLong <= MAX_BYTES)
            val data = connection.inputStream.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_BYTES)
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
            return JSONObject(data)
        } finally {
            connection.disconnect()
        }
    }
}
