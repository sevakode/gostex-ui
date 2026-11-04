package tv.orbit.shield

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException

internal data class GrafanaDashboard(val uid: String, val title: String, val folder: String, val url: String)

/** The installation path matters too: two Grafanas can share one hostname. */
internal data class GrafanaCatalogServer(val baseUrl: String, val requestedOrgId: Long?) {
    fun endpoint(path: String): String = baseUrl + path
    fun scope(orgId: Long): String = "$baseUrl|$orgId"

    fun accepts(url: String): Boolean {
        val candidate = GrafanaPreferences.normalizeUrl(url)?.let { runCatching { URI(it) }.getOrNull() } ?: return false
        val base = URI(baseUrl)
        return sameOrigin(base, candidate) && candidate.rawPath.orEmpty().startsWith(base.rawPath) &&
            candidate.normalize().rawPath == candidate.rawPath &&
            candidate.path.split('/').none { it == "." || it == ".." || '\\' in it }
    }

    companion object {
        fun fromUrl(value: String): GrafanaCatalogServer? {
            val normalized = GrafanaPreferences.normalizeUrl(value)?.takeIf { it.isNotBlank() } ?: return null
            val uri = URI(normalized)
            if (uri.normalize().rawPath != uri.rawPath || uri.path.orEmpty().split('/').any { it == "." || it == ".." }) return null
            val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
            val route = segments.indexOfFirst { it in setOf("d", "d-solo", "dashboard", "dashboards", "playlists", "login", "goto") }
            val baseSegments = if (route < 0) segments else segments.take(route)
            val basePath = "/" + if (baseSegments.isEmpty()) "" else baseSegments.joinToString("/") + "/"
            val scheme = uri.scheme.lowercase(Locale.ROOT)
            val port = uri.port.takeUnless { it == defaultPort(scheme) } ?: -1
            val origin = URI(scheme, null, uri.host.lowercase(Locale.ROOT), port, null, null, null).toASCIIString()
            val orgValues = queryParts(uri).filter { it.first == "orgId" }.map { decode(it.second) }
            val org = orgValues.singleOrNull()?.toLongOrNull()?.takeIf { it > 0 }
            if (orgValues.isNotEmpty() && org == null) return null
            return GrafanaCatalogServer(origin + basePath, org)
        }

        private fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80
        private fun sameOrigin(a: URI, b: URI): Boolean =
            a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) &&
                (if (a.port == -1) defaultPort(a.scheme.lowercase(Locale.ROOT)) else a.port) ==
                (if (b.port == -1) defaultPort(b.scheme.lowercase(Locale.ROOT)) else b.port)
    }
}

private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault("")
private fun queryParts(uri: URI): List<Pair<String, String>> = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.map {
    decode(it.substringBefore('=')) to it.substringAfter('=', "")
}

internal fun validGrafanaUid(uid: String): Boolean = uid.matches(Regex("[A-Za-z0-9_-]{1,128}"))

/** Common viewing choices travel; variables require the same dashboard and explicit organization. */
internal fun grafanaDashboardUrl(server: GrafanaCatalogServer, orgId: Long, dashboard: GrafanaDashboard, currentUrl: String): String? {
    if (orgId <= 0 || !validGrafanaUid(dashboard.uid) || !server.accepts(dashboard.url)) return null
    val target = URI(dashboard.url)
    val relative = target.rawPath.removePrefix(URI(server.baseUrl).rawPath)
    if (relative.substringBefore('/') != "d" || relative.split('/').getOrNull(1) != dashboard.uid) return null
    val current = GrafanaPreferences.normalizeUrl(currentUrl)?.takeIf { server.accepts(it) }?.let(::URI)
    val currentQuery = current?.let(::queryParts).orEmpty()
    val currentOrg = currentQuery.filter { it.first == "orgId" }.singleOrNull()?.second
        ?.let(::decode)?.toLongOrNull()?.takeIf { it > 0 }
    val currentRoute = current?.rawPath?.removePrefix(URI(server.baseUrl).rawPath)?.split('/')
    // A UID can exist in different organizations. A missing or ambiguous org cannot
    // establish that datasource/filter variables belong to the newly resolved organization.
    val sameDashboard = currentOrg == orgId && currentRoute?.firstOrNull() == "d" &&
        currentRoute.getOrNull(1) == dashboard.uid
    val keep = currentQuery.filter { (key, value) ->
        value.length <= 1024 && (key in setOf("from", "to", "refresh", "timezone") || (sameDashboard && key.startsWith("var-")))
    }.distinctBy { it.first }
    // Raw values came from an already validated URI, so encoded timezones/variables stay intact.
    val query = (listOf("orgId=$orgId") + keep.map { (key, value) ->
        java.net.URLEncoder.encode(key, "UTF-8") + "=" + value
    } + "kiosk=true").joinToString("&")
    return GrafanaPreferences.normalizeUrl(target.toString().substringBefore('?').substringBefore('#') + "?" + query)
}

internal data class GrafanaCatalogResult(
    val server: GrafanaCatalogServer,
    val orgId: Long,
    val dashboards: List<GrafanaDashboard>,
    val truncated: Boolean
) {
    val scope: String get() = server.scope(orgId)
}

internal enum class GrafanaCatalogFailure { SIGN_IN, NETWORK, RESPONSE }
internal class GrafanaCatalogException(val reason: GrafanaCatalogFailure) : IOException()

/**
 * Dashboard discovery reads documented /api/org and /api/search with WebView session cookies.
 * Owns its connection so leaving the dialog disconnects immediately, including during a read.
 * A server-requested renewal of the existing browser session is bounded to one attempt per load.
 * No redirects, separate credentials, TLS overrides, background polling or persisted catalog cache.
 */
internal class GrafanaCatalogRequest(
    private val server: GrafanaCatalogServer,
    private val cookies: CookieManager
) : Closeable {
    private val lock = Any()
    private var closed = false
    private var connection: HttpURLConnection? = null
    private var rotationAttempted = false

    override fun close() {
        val active = synchronized(lock) { closed = true; connection.also { connection = null } }
        active?.disconnect()
    }

    fun load(): GrafanaCatalogResult {
        try {
            val orgId = JSONObject(get("api/org", server.requestedOrgId)).optLong("id", -1)
            if (orgId <= 0 || (server.requestedOrgId != null && orgId != server.requestedOrgId))
                throw GrafanaCatalogException(GrafanaCatalogFailure.RESPONSE)
            val dashboards = linkedMapOf<String, GrafanaDashboard>()
            var truncated = false
            pages@ for (page in 1..MAX_PAGES) {
                val json = JSONArray(get("api/search?type=dash-db&limit=$PAGE_SIZE&page=$page", orgId))
                var added = 0
                for (index in 0 until json.length()) {
                    val item = json.optJSONObject(index) ?: continue
                    if (item.optString("type") != "dash-db") continue
                    val uid = item.optString("uid")
                    val title = item.optString("title").cleanLabel()
                    if (!validGrafanaUid(uid) || title.isBlank()) continue
                    val rawUrl = item.optString("url")
                    val url = runCatching { URI(server.baseUrl).resolve(rawUrl).toString() }.getOrNull() ?: continue
                    val dashboard = GrafanaDashboard(uid, title, item.optString("folderTitle").cleanLabel(), url)
                    if (grafanaDashboardUrl(server, orgId, dashboard, "") == null) continue
                    if (dashboards.putIfAbsent(uid, dashboard) == null) added++
                    if (dashboards.size >= PAGE_SIZE * MAX_PAGES) {
                        truncated = true
                        break@pages
                    }
                }
                if (json.length() < PAGE_SIZE) break
                // Protect against legacy/proxy implementations that ignore the page parameter.
                if (added == 0 || page == MAX_PAGES) { truncated = true; break }
            }
            return GrafanaCatalogResult(server, orgId, dashboards.values.sortedWith(
                compareBy<GrafanaDashboard> { it.title.lowercase(Locale.ROOT) }.thenBy { it.folder.lowercase(Locale.ROOT) }.thenBy { it.uid }
            ), truncated)
        } catch (exception: GrafanaCatalogException) {
            throw exception
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: IOException) {
            synchronized(lock) { if (closed) throw CancellationException() }
            throw GrafanaCatalogException(GrafanaCatalogFailure.NETWORK)
        } catch (_: Exception) {
            synchronized(lock) { if (closed) throw CancellationException() }
            throw GrafanaCatalogException(GrafanaCatalogFailure.RESPONSE)
        }
    }

    private fun get(path: String, orgId: Long?): String {
        var response = exchange(path, orgId)
        // Match Grafana's browser handling exactly; ordinary access failures must not rotate sessions.
        // https://github.com/grafana/grafana/blob/main/public/app/core/services/backend_srv.ts
        val needsRotation = response.status == 401 && response.cookiePresent && response.isJson &&
            runCatching { JSONObject(response.body).optString("messageId") == "session.token.rotate" }.getOrDefault(false)
        if (needsRotation && !rotationAttempted) {
            rotationAttempted = true
            requireJsonSuccess(exchange("api/user/auth-tokens/rotate", orgId, rotate = true))
            // Disk I/O stays on the catalog's IO worker. The next GET and WebView share the new cookie.
            cookies.flush()
            response = exchange(path, orgId)
        }
        return requireJsonSuccess(response)
    }

    private data class HttpResult(val status: Int, val contentType: String, val body: String, val cookiePresent: Boolean) {
        val isJson: Boolean get() = contentType.substringBefore(';').trim() == "application/json"
    }

    private fun requireJsonSuccess(response: HttpResult): String {
        if (response.status in 300..399 || response.status == 401 || response.status == 403)
            throw GrafanaCatalogException(GrafanaCatalogFailure.SIGN_IN)
        if (response.status !in 200..299) throw GrafanaCatalogException(GrafanaCatalogFailure.NETWORK)
        if (!response.isJson) throw GrafanaCatalogException(
            if (response.contentType.contains("text/html")) GrafanaCatalogFailure.SIGN_IN else GrafanaCatalogFailure.RESPONSE
        )
        return response.body
    }

    private fun exchange(path: String, orgId: Long?, rotate: Boolean = false): HttpResult {
        val url = server.endpoint(path)
        val request = URI(url).toURL().openConnection() as HttpURLConnection
        synchronized(lock) {
            if (closed) { request.disconnect(); throw CancellationException() }
            connection = request
        }
        try {
            request.requestMethod = if (rotate) "POST" else "GET"
            request.instanceFollowRedirects = false
            request.connectTimeout = 10_000
            request.readTimeout = 15_000
            request.useCaches = false
            request.setRequestProperty("Accept", "application/json")
            request.setRequestProperty("User-Agent", "GOSTEX-Grafana-Catalog")
            orgId?.let { request.setRequestProperty("X-Grafana-Org-Id", it.toString()) }
            // CookieManager performs path/domain/secure matching for this exact API URL.
            val cookie = cookies.getCookie(url)?.takeIf { it.isNotBlank() && '\r' !in it && '\n' !in it }
            cookie?.let { request.setRequestProperty("Cookie", it) }
            if (rotate) {
                val origin = URI(server.baseUrl).let { URI(it.scheme, null, it.host, it.port, null, null, null).toASCIIString() }
                request.setRequestProperty("Origin", origin)
                request.setRequestProperty("Content-Type", "application/json")
                request.doOutput = true
                request.setFixedLengthStreamingMode(2)
                request.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
            }
            val status = request.responseCode
            val contentType = request.contentType.orEmpty().lowercase(Locale.ROOT)
            // Only successful same-origin responses can update the existing browser session here.
            if (status in 200..299) request.headerFields.filterKeys { it.equals("Set-Cookie", true) }.values.flatten()
                .forEach { cookies.setCookie(url, it) }
            val success = status in 200..299
            val inspectRotation = status == 401 && !rotate && contentType.substringBefore(';').trim() == "application/json"
            val stream = if (success) request.inputStream else if (inspectRotation) request.errorStream else null
            val limit = if (success) MAX_RESPONSE_BYTES else MAX_ERROR_BYTES
            val bytes = stream?.use {
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    synchronized(lock) { if (closed) throw CancellationException() }
                    val count = stream.read(buffer)
                    if (count == -1) break
                    if (output.size() + count > limit) {
                        if (success) throw GrafanaCatalogException(GrafanaCatalogFailure.RESPONSE)
                        // An oversized error is an ordinary failure, never evidence to renew a session.
                        output.reset()
                        break
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: byteArrayOf()
            return HttpResult(status, contentType, bytes.toString(Charsets.UTF_8), cookie != null)
        } finally {
            synchronized(lock) { if (connection === request) connection = null }
            request.disconnect()
        }
    }

    private fun String.cleanLabel(): String = filterNot(Char::isISOControl).trim().take(280)

    private companion object {
        const val PAGE_SIZE = 200
        const val MAX_PAGES = 25
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val MAX_ERROR_BYTES = 16 * 1024
    }
}

/** Local stars never mutate the server's dashboards or its account favorites. */
internal object GrafanaCatalogPreferences {
    const val PREFIX = "grafana.catalog."
    private const val MAX_FAVORITES = 5000
    private fun prefs(context: Context) = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)
    internal fun scopeKey(scope: String): String = MessageDigest.getInstance("SHA-256")
        .digest(scope.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun favoritesKey(scope: String) = PREFIX + scopeKey(scope) + ".favorites"
    private fun filterKey(scope: String) = PREFIX + scopeKey(scope) + ".favoritesOnly"

    fun readFavorites(context: Context, scope: String): Set<String> = runCatching {
        prefs(context).getStringSet(favoritesKey(scope), emptySet()).orEmpty().filter(::validGrafanaUid).take(MAX_FAVORITES).toSet()
    }.getOrDefault(emptySet())

    fun toggleFavorite(context: Context, scope: String, uid: String) {
        if (scope.isBlank() || !validGrafanaUid(uid)) return
        val updated = readFavorites(context, scope).toMutableSet()
        if (!updated.remove(uid) && updated.size < MAX_FAVORITES) updated.add(uid)
        prefs(context).edit().putStringSet(favoritesKey(scope), updated).apply()
    }

    fun readFavoritesOnly(context: Context, scope: String): Boolean = runCatching {
        prefs(context).getBoolean(filterKey(scope), false)
    }.getOrDefault(false)

    fun setFavoritesOnly(context: Context, scope: String, value: Boolean) {
        if (scope.isNotBlank()) prefs(context).edit().putBoolean(filterKey(scope), value).apply()
    }
}
