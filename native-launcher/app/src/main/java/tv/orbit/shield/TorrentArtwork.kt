package tv.orbit.shield

import android.content.Context
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import android.content.SharedPreferences

/** One optional, user-triggered lookup per newly added torrent; no background catalog scan. */
internal object TorrentArtwork {
    const val KEY = "downloads.autoCover"
    private const val MAX_BYTES = 4 * 1024 * 1024
    fun enabled(context: Context) = context.getSharedPreferences("orbit", Context.MODE_PRIVATE).readBooleanOrDefault(KEY, true)
    fun enabled(context: Context, value: Boolean) { context.getSharedPreferences("orbit", Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply() }
    fun id(hash: String): String { require(hash.matches(Regex("[a-fA-F0-9]{40}"))); return "torrent:${hash.lowercase(java.util.Locale.ROOT)}" }
    data class Found(val bytes: ByteArray, val sourceName: String, val sourceUrl: String)
    suspend fun find(context: Context, title: String): Found? {
        val q = TorrentArtworkPolicy.query(title)
        if (q.length < 3) return null
        val results = (MovieCatalogClient.search(q) + MovieCatalogClient.search(q, type = "series"))
            .filter { TorrentArtworkPolicy.normalized(it.title) == TorrentArtworkPolicy.normalized(q) && it.poster != null }
            .distinctBy { it.id }
        val options = OnlineCoverPreferences.read(context)
        val client = if (options.apiKey.isNotEmpty() && CoverProvider.STEAMGRIDDB in options.providers)
            OnlineCoverClient(context, options.apiKey, options.landscapeFirst) else null
        try {
            val games = if (client == null) emptyList() else {
                client.search(CoverProvider.STEAMGRIDDB, "all", q).games.filter { TorrentArtworkPolicy.normalized(it.title) == TorrentArtworkPolicy.normalized(q) }
            }
            // A title that identifies both a film and a game is ambiguous.
            val match = TorrentArtworkPolicy.uniqueSource(results.size, games.size) ?: return null
            if (match == "game" && client != null) {
                val image = client.grids(games.single()).firstOrNull() ?: return null
                return Found(client.image(image, preview = false), image.provider.label, image.sourceUrl)
            }
            if (results.size != 1) return null
            return loadPoster(results.single().poster ?: return null)
        } finally { client?.close() }
    }
    private fun loadPoster(poster: String): Found? {
        val url = URL(poster)
        if (url.protocol != "https" || url.host != "images.metahub.space" || url.userInfo != null || url.port !in listOf(-1, 443)) return null
        val connection = url.openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 8000; connection.readTimeout = 10000; connection.instanceFollowRedirects = false
            if (connection.responseCode != 200 || connection.contentLengthLong > MAX_BYTES) return null
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(output.size() + n <= MAX_BYTES)
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
            return Found(bytes, "Cinemeta", poster)
        } finally { connection.disconnect() }
    }
}

@Composable internal fun rememberTorrentAutoCover(): Boolean {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("orbit", Context.MODE_PRIVATE) }
    var enabled by remember(context) { mutableStateOf(TorrentArtwork.enabled(context)) }
    DisposableEffect(context, prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == TorrentArtwork.KEY) enabled = TorrentArtwork.enabled(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        enabled = TorrentArtwork.enabled(context)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}
