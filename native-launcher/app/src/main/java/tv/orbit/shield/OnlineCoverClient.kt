package tv.orbit.shield

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class CoverHttpError(val status: Int) : Exception("HTTP $status")
internal class CoverKeyRequired : Exception()
internal data class CoverProviderResult(val games: List<OnlineCoverGame> = emptyList(), val images: List<OnlineCoverImage> = emptyList()) {
    val isEmpty get() = games.isEmpty() && images.isEmpty()
}

/** Created only for an open picker. Closing or stopping it disconnects every active request.
 * One focused preview, no network on Home and no new networking/image-loader dependency. */
internal class OnlineCoverClient(context: Context, private val apiKey: String, private val landscapeFirst: Boolean = true) : AutoCloseable {
    private val assets = context.assets
    private val cache = File(context.cacheDir, "online-covers").apply { mkdirs() }
    private val active = Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())
    @Volatile private var closed = false
    override fun close() { closed = true; synchronized(active) { active.toList() }.forEach { it.disconnect() } }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun allowed(url: String) = coverImageUrl(url) || runCatching {
        val u = URI(url); u.scheme == "https" && u.host == "www.steamgriddb.com" && u.port == -1 &&
            u.userInfo == null && u.path.startsWith("/api/v2/")
    }.getOrDefault(false)
    private suspend fun request(url: String, limit: Int): ByteArray = withContext(Dispatchers.IO) {
        var current = url
        val deadline = System.nanoTime() + 25_000_000_000L
        repeat(4) { redirects ->
            currentCoroutineContext().ensureActive(); check(!closed);if(System.nanoTime()>deadline) throw java.net.SocketTimeoutException()
            require(allowed(current))
            val connection = URI(current).toURL().openConnection() as HttpURLConnection
            active.add(connection)
            if (closed) { active.remove(connection); connection.disconnect(); error("Closed") }
            try {
                connection.connectTimeout = 8_000; connection.readTimeout = 12_000
                connection.instanceFollowRedirects = false; connection.useCaches = false
                connection.setRequestProperty("User-Agent", "GOSTEX/0.27 CoverPicker")
                connection.setRequestProperty("Accept-Encoding", "gzip")
                if (URI(current).host == "www.steamgriddb.com") {
                    if (apiKey.isEmpty()) throw CoverKeyRequired()
                    connection.setRequestProperty("Authorization", "Bearer $apiKey")
                }
                val status = connection.responseCode
                if (status in setOf(301,302,303,307,308)) {
                    check(redirects < 3)
                    current = URI(current).resolve(connection.getHeaderField("Location") ?: error("Redirect")).toString()
                } else {
                    if (status !in 200..299) throw CoverHttpError(status)
                    require(connection.contentLengthLong <= limit || connection.contentLengthLong == -1L)
                    val input = connection.inputStream.let { if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(it) else it }
                    return@withContext input.use {
                        val bytes = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive(); check(!closed);if(System.nanoTime()>deadline) throw java.net.SocketTimeoutException()
                            val count = it.read(buffer); if (count < 0) break
                            require(bytes.size() + count <= limit); bytes.write(buffer, 0, count)
                        }
                        bytes.toByteArray()
                    }
                }
            } finally { active.remove(connection); connection.disconnect() }
        }
        error("Too many redirects")
    }
    private fun fileKey(namespace: String, identity: String) = File(cache, namespace + hash(identity))
    private suspend fun cached(namespace: String, identity: String, ttl: Long, refresh: Boolean = false, load: suspend () -> ByteArray): ByteArray {
        val file = fileKey(namespace, identity)
        if (!refresh && file.exists() && file.length() <= 10 * 1024 * 1024 && System.currentTimeMillis() - file.lastModified() in 0..ttl) {
            return file.readBytes()
        }
        val bytes = load(); currentCoroutineContext().ensureActive(); check(!closed)
        // A failed refresh does not erase a good cached response. Only complete files are published.
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) } catch (e: Exception) { atomic.failWrite(stream); throw e }
        val files = cache.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.lastModified() }
        var used = files.sumOf { it.length() }
        var count = files.size
        for (old in files) {
            if (used <= 24L * 1024 * 1024 && count <= 128) break
            val length = old.length()
            if (old != file && old.delete()) { used -= length;count-- }
        }
        return bytes
    }
    suspend fun search(provider: CoverProvider, platform: String, query: String, refresh: Boolean = false): CoverProviderResult = withContext(Dispatchers.IO) {
        val title = query.trim().take(160); require(title.isNotEmpty())
        when (provider) {
            CoverProvider.STEAMGRIDDB -> {
                if (apiKey.isEmpty()) throw CoverKeyRequired()
                val bytes = cached("api-", hash(apiKey) + "search|$title", 86_400_000L, refresh) {
                    request("https://www.steamgriddb.com/api/v2/search/autocomplete/${encode(title)}", 512 * 1024)
                }
                CoverProviderResult(games = OnlineCoverParser.games(bytes.toString(Charsets.UTF_8)))
            }
            CoverProvider.LIBRETRO -> {
                val system = libretroSystems[platform] ?: return@withContext CoverProviderResult()
                // Compact build-time snapshot: never download/scan an entire provider directory on Shield.
                // Read only the requested console; no index is retained on Home or in a second in-memory catalog.
                val bytes = assets.open("libretro-covers/" + hash(system).take(16) + ".idx").use { raw ->
                    GZIPInputStream(raw).use { stream ->
                        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while(true) {
                            currentCoroutineContext().ensureActive();check(!closed)
                            val count=stream.read(buffer);if(count<0) break
                            require(output.size()+count<=4*1024*1024);output.write(buffer,0,count)
                        }
                        output.toByteArray()
                    }
                }
                val rows = org.json.JSONObject(bytes.toString(Charsets.UTF_8))
                    .optJSONArray(coverTitleKey(coverSearchTitle(title)))
                val matches = if(rows==null) emptyList() else (0 until minOf(rows.length(),16)).map { rows.getString(it) }
                CoverProviderResult(images = matches.map { name ->
                    val url = "https://thumbnails.libretro.com/${encode(system)}/Named_Boxarts/${encode(name)}"
                    OnlineCoverImage(provider, name.removeSuffix(".png"), url, url)
                })
            }
        }
    }
    suspend fun grids(game: OnlineCoverGame, refresh: Boolean = false): List<OnlineCoverImage> = withContext(Dispatchers.IO) {
        require(game.id > 0); if (apiKey.isEmpty()) throw CoverKeyRequired()
        // Ask for horizontal grids first: an unfiltered first page may be filled with portrait art.
        // This also avoids downloading metadata for dozens of variants the TV picker does not need.
        if (landscapeFirst) {
        val landscape = cached("api-", hash(apiKey) + "landscape|${game.id}", 86_400_000L, refresh) {
            request("https://www.steamgriddb.com/api/v2/grids/game/${game.id}?dimensions=920x430,460x215&types=static&nsfw=false&humor=false&epilepsy=false", 1024 * 1024)
        }
        val preferred = OnlineCoverParser.images(landscape.toString(Charsets.UTF_8), game.title)
        if (preferred.isNotEmpty()) return@withContext preferred
        }
        val bytes = cached("api-", hash(apiKey) + "grids|${game.id}", 86_400_000L, refresh) {
            request("https://www.steamgriddb.com/api/v2/grids/game/${game.id}?types=static&nsfw=false&humor=false&epilepsy=false", 1024 * 1024)
        }
        OnlineCoverParser.images(bytes.toString(Charsets.UTF_8), game.title)
    }
    suspend fun image(image: OnlineCoverImage, preview: Boolean): ByteArray = withContext(Dispatchers.IO) {
        val url = if (preview) image.preview else image.url
        require(coverImageUrl(url))
        cached("image-", url, 604_800_000L) { request(url, 10 * 1024 * 1024) }
    }
    suspend fun preview(image: OnlineCoverImage): Bitmap = withContext(Dispatchers.IO) {
        val bytes = image(image, true)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth in 32..8192 && bounds.outHeight in 32..8192 && bounds.outWidth.toLong() * bounds.outHeight <= 33_554_432L)
        var sample = 1
        while (bounds.outWidth.toLong()*bounds.outHeight/sample/sample > 1_048_576L) sample *= 2
        currentCoroutineContext().ensureActive()
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Invalid image")
    }
}
