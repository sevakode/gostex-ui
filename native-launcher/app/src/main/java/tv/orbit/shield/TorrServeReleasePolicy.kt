package tv.orbit.shield

import org.json.JSONObject
import java.net.URI

/** Data from GitHub is untrusted until both the asset digest and APK signing identity match. */
internal object TorrServeReleasePolicy {
    const val MAX_BYTES = 64L * 1024 * 1024
    private val hosts = setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    data class Asset(val url: String, val bytes: Long, val sha256: String)
    fun allowedUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host?.lowercase() in hosts && uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) && uri.rawFragment == null
    }.getOrDefault(false)
    fun asset(json: String): Asset {
        val items = JSONObject(json).getJSONArray("assets")
        val matches = (0 until items.length()).map { items.getJSONObject(it) }.filter {
            it.optString("name").matches(Regex("TorrServe_[A-Za-z0-9._-]+\\.apk"))
        }
        require(matches.size == 1)
        val item = matches.single()
        val length = item.getLong("size").also { require(it in 1..MAX_BYTES) }
        val digest = item.getString("digest").also { require(it.startsWith("sha256:")) }.removePrefix("sha256:").lowercase()
        require(digest.matches(Regex("[a-f0-9]{64}")))
        val url = item.getString("browser_download_url")
        val uri = URI(url)
        require(allowedUrl(url) && uri.host == "github.com" && uri.rawQuery == null &&
            uri.path.startsWith("/YouROK/TorrServe/releases/download/"))
        return Asset(url, length, digest)
    }
}
