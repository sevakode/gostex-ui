package tv.orbit.shield

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID

internal data class TorrCatalogFile(val id: Int, val path: String, val size: Long)
internal class TorrMetadataTimeout : IOException("Torrent metadata request timed out")
internal class TorrEntryMissing : IOException("Torrent not present in server catalog")
private class TorrHttpNotFound : IOException("Server endpoint or torrent not found")
internal data class TorrCatalogEntry(
    val hash: String, val title: String, val files: List<TorrCatalogFile>,
    val downloadBytesPerSecond: Long? = null, val connectedSeeders: Int? = null,
)

/** Explicit user operations against the local server only, never background polling. */
internal object TorrServerCatalog {
    private const val MAX_RESPONSE = 2 * 1024 * 1024
    private const val MAX_TORRENT = 4 * 1024 * 1024
    private const val MAX_ITEMS = 500
    private const val MAX_FILES = 5000
    private val HASH = Regex("[0-9a-fA-F]{40}")

    fun list(port: Int = 8090): List<TorrCatalogEntry> {
        val data = request(port, "/torrents", "application/json", "{\"action\":\"list\"}".toByteArray())
        val array = JSONArray(data)
        if (array.length() > MAX_ITEMS) throw IOException("Too many torrents")
        return (0 until array.length()).map { parseEntry(array.getJSONObject(it)) }
    }

    fun get(hash: String, port: Int = 8090): TorrCatalogEntry {
        require(HASH.matches(hash))
        val body = JSONObject().put("action", "get").put("hash", hash).toString().toByteArray()
        val data = try { request(port, "/torrents", "application/json", body) }
        catch (missing: TorrHttpNotFound) {
            // Verify that the catalog endpoint itself works before claiming a missing entry.
            val entries = list(port)
            if (entries.none { it.hash.equals(hash, ignoreCase = true) }) throw TorrEntryMissing()
            throw IOException("Server could not retrieve a listed torrent", missing)
        }
        return parseEntry(JSONObject(data)).also {
            if (!it.hash.equals(hash, ignoreCase = true)) throw IOException("Unexpected torrent identity")
        }
    }

    /** Removes only the server entry; downloaded documents are never touched here. */
    fun remove(hash: String, port: Int = 8090) {
        require(HASH.matches(hash))
        val body = JSONObject().put("action", "rem").put("hash", hash).toString().toByteArray()
        request(port, "/torrents", "application/json", body)
    }

    /** Explicitly opens the torrent to obtain metadata; can contact peers. */
    fun loadFiles(hash: String, port: Int = 8090, readTimeoutMs: Int = 45000): TorrCatalogEntry {
        require(HASH.matches(hash) && port in 1..65535 && readTimeoutMs in 1..120000)
        val connection = URL("http://127.0.0.1:$port/stream?link=$hash&stat=1").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            if (connection.responseCode != 200) throw IOException("Cannot obtain torrent metadata")
            if (connection.contentLengthLong > MAX_RESPONSE) throw IOException("Server response too large")
            return parseEntry(JSONObject(connection.inputStream.use { String(readLimited(it, MAX_RESPONSE), Charsets.UTF_8) }))
        } catch (_: SocketTimeoutException) {
            throw TorrMetadataTimeout()
        } finally {
            connection.disconnect()
        }
    }

    fun addMagnet(magnet: String, port: Int = 8090): TorrCatalogEntry {
        require(magnet.length <= 8192 && magnet.startsWith("magnet:?", ignoreCase = true))
        require(!magnet.contains('\r') && !magnet.contains('\n'))
        return addLink(magnet, port)
    }

    fun addTorrentUrl(value: String, port: Int = 8090): TorrCatalogEntry {
        require(value.length in 1..8192 && !value.contains('\r') && !value.contains('\n'))
        val uri = java.net.URI(value)
        require(uri.scheme?.lowercase(java.util.Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.path?.endsWith(".torrent", ignoreCase = true) == true)
        return addLink(value, port)
    }

    private fun addLink(link: String, port: Int): TorrCatalogEntry {
        val body = JSONObject().put("action", "add").put("link", link).put("save_to_db", true).toString().toByteArray()
        return parseEntry(JSONObject(request(port, "/torrents", "application/json", body)))
    }

    fun upload(input: InputStream, port: Int = 8090): TorrCatalogEntry {
        val torrent = readLimited(input, MAX_TORRENT)
        if (torrent.isEmpty()) throw IOException("Empty torrent")
        val boundary = "gostex-" + UUID.randomUUID().toString()
        val out = ByteArrayOutputStream()
        fun line(value: String) = out.write(value.toByteArray(Charsets.UTF_8))
        line("--$boundary\r\nContent-Disposition: form-data; name=\"save\"\r\n\r\ntrue\r\n")
        line("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"upload.torrent\"\r\nContent-Type: application/x-bittorrent\r\n\r\n")
        out.write(torrent)
        line("\r\n--$boundary--\r\n")
        val response = request(port, "/torrent/upload", "multipart/form-data; boundary=$boundary", out.toByteArray()).trim()
        val obj = if (response.startsWith("[")) JSONArray(response).getJSONObject(0) else JSONObject(response)
        return parseEntry(obj)
    }

    private fun parseEntry(obj: JSONObject): TorrCatalogEntry {
        val hash = obj.optString("hash", "")
        if (!HASH.matches(hash)) throw IOException("Invalid torrent identity")
        val title = obj.optString("title", "").take(512).ifBlank { obj.optString("name", "").take(512).ifBlank { hash } }
        val files = obj.optJSONArray("file_stats") ?: JSONArray()
        if (files.length() > MAX_FILES) throw IOException("Too many files")
        val entries = (0 until files.length()).map { i ->
            val file = files.getJSONObject(i)
            val id = file.optInt("id", -1)
            val path = file.optString("path", "")
            val size = file.optLong("length", -1)
            if (id < 1 || path.isBlank() || path.length > 4096 || size < 0) throw IOException("Invalid file metadata")
            TorrCatalogFile(id, path, size)
        }
        if (entries.map { it.id }.distinct().size != entries.size) throw IOException("Ambiguous server file IDs")
        // Missing statistics mean unknown, not zero. Ignore invalid values from
        // older or incompatible servers instead of inventing a diagnosis.
        fun nonNegativeNumber(key: String, max: Double): Double? {
            val value = obj.opt(key) as? Number ?: return null
            return value.toDouble().takeIf { it.isFinite() && it >= 0 && it <= max }
        }
        val rate = nonNegativeNumber("download_speed", 1_000_000_000.0)?.toLong()
        val seeds = nonNegativeNumber("connected_seeders", 1_000_000.0)?.takeIf { it % 1.0 == 0.0 }?.toInt()
        return TorrCatalogEntry(hash, title, entries, rate, seeds)
    }

    private fun request(port: Int, path: String, type: String, body: ByteArray): String {
        require(port in 1..65535)
        val connection = URL("http://127.0.0.1:$port$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", type)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status == 404) throw TorrHttpNotFound()
            if (status != 200) throw IOException("Server request failed: $status")
            if (connection.contentLengthLong > MAX_RESPONSE) throw IOException("Server response too large")
            return connection.inputStream.use { String(readLimited(it, MAX_RESPONSE), Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: InputStream, maximum: Int): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (result.size() + count > maximum) throw IOException("Input is too large")
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }
}
