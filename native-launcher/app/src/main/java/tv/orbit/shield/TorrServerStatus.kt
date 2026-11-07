package tv.orbit.shield

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.TimeUnit

internal data class TorrCacheInfo(val disk: Boolean, val sizeBytes: Long?)

internal sealed interface TorrServerStatus {
    data class Online(val version: String, val torrents: Int, val bytesPerSecond: Long, val cache: TorrCacheInfo? = null) : TorrServerStatus
    data object AuthenticationRequired : TorrServerStatus
    data object Unavailable : TorrServerStatus
}

/** Read-only, on-demand status; never accepts a remote host, redirects or commands. */
internal object TorrServerStatusClient {
    private const val MAX_BYTES = 1024 * 1024
    private const val MAX_TORRENTS = 500
    private const val MAX_RATE = 1_000_000_000L

    fun probe(port: Int = 8090): TorrServerStatus {
        require(port in 1..65535)
        return try {
            val version = request(port, "/echo", null).trim()
            if (!version.matches(Regex("[A-Za-z0-9._-]{1,48}"))) return TorrServerStatus.Unavailable
            val torrents = JSONArray(request(port, "/torrents", "{\"action\":\"list\"}"))
            if (torrents.length() > MAX_TORRENTS) return TorrServerStatus.Unavailable
            var rate = 0L
            for (index in 0 until torrents.length()) {
                val item = torrents.getJSONObject(index)
                val speed = item.optDouble("download_speed", 0.0)
                if (speed.isFinite() && speed >= 0.0) rate = (rate + speed.coerceAtMost(MAX_RATE.toDouble()).toLong()).coerceAtMost(MAX_RATE)
            }
            // Cache diagnostics are optional: an older server may not expose settings.
            // Read only the two relevant values; never persist or log the response.
            val cache = runCatching {
                val settings = JSONObject(request(port, "/settings", "{\"action\":\"get\"}"))
                val disk = settings.opt("UseDisk") as? Boolean ?: return@runCatching null
                val number = settings.opt("CacheSize") as? Number
                val bytes = number?.toDouble()?.takeIf { it.isFinite() && it >= 1.0 && it <= 64.0 * 1024 * 1024 * 1024 && it % 1.0 == 0.0 }?.toLong()
                TorrCacheInfo(disk, bytes)
            }.getOrNull()
            TorrServerStatus.Online(version, torrents.length(), rate, cache)
        } catch (_: AuthenticationException) {
            TorrServerStatus.AuthenticationRequired
        } catch (_: Exception) {
            TorrServerStatus.Unavailable
        }
    }

    private fun request(port: Int, path: String, body: String?): String {
        val connection = URL("http://127.0.0.1:$port$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 2500
            connection.readTimeout = 3500
            connection.useCaches = false
            if (body != null) {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status == 401 || status == 403) throw AuthenticationException()
            if (status != 200) throw IOException("Unexpected response")
            if (connection.contentLengthLong > MAX_BYTES) throw IOException("Response too large")
            val bytes = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
                while (true) {
                    if (System.nanoTime() - deadline >= 0) throw IOException("Response timed out")
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > MAX_BYTES) throw IOException("Response too large")
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            return String(bytes, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private class AuthenticationException : IOException()
}
