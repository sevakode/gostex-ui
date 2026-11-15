package tv.orbit.shield

import android.content.Context
import org.json.JSONArray
import java.net.URI
import java.net.URLEncoder

internal enum class BrowserSearchProvider(val id: String, val displayName: String, private val endpoint: String) {
    DUCKDUCKGO("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q="),
    GOOGLE("google", "Google", "https://www.google.com/search?q="),
    BING("bing", "Bing", "https://www.bing.com/search?q=");

    fun search(query: String): String = endpoint + URLEncoder.encode(query, Charsets.UTF_8.name())
}

internal object TvBrowserOptions {
    const val QUICK_DOWNLOAD = "browser.quickDownload"
    private const val SEARCH_PROVIDER = "browser.searchProvider"
    private const val BOOKMARKS = "browser.bookmarks"
    private const val MAX_BOOKMARKS = 32
    private const val MAX_INPUT = 8192
    private val explicitScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    private fun prefs(context: Context) = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)

    fun searchProvider(context: Context): BrowserSearchProvider {
        val id = runCatching { prefs(context).getString(SEARCH_PROVIDER, null) }.getOrNull()
        return BrowserSearchProvider.entries.firstOrNull { it.id == id } ?: BrowserSearchProvider.DUCKDUCKGO
    }

    fun setSearchProvider(context: Context, provider: BrowserSearchProvider) {
        prefs(context).edit().putString(SEARCH_PROVIDER, provider.id).apply()
    }

    fun quickDownload(context: Context): Boolean = runCatching {
        prefs(context).getBoolean(QUICK_DOWNLOAD, false)
    }.getOrDefault(false)

    fun setQuickDownload(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(QUICK_DOWNLOAD, enabled).apply()
    }

    fun webUrl(input: String): String? {
        if (input.length !in 1..MAX_INPUT || input.any(Char::isISOControl) || '\\' in input) return null
        val candidate = input.trim()
        // A page can navigate to a fragment. Validate the same origin and target syntax as downloads.
        return runCatching {
            HttpDownloadUrl.parse(candidate.substringBefore('#'))
            URI(candidate).toASCIIString().takeIf { it.length <= MAX_INPUT }
        }.getOrNull()
    }

    fun magnet(input: String): String? {
        if (input.length !in 1..MAX_INPUT || input.any(Char::isISOControl) || '\\' in input) return null
        val candidate = input.trim()
        if (!candidate.startsWith("magnet:?", ignoreCase = true) || !MovieLibraryStore.validSource(candidate)) return null
        return runCatching { URI(candidate).toASCIIString() }
            .getOrNull()?.takeIf { it.length <= MAX_INPUT && '#' !in it }
    }

    fun address(context: Context, input: String): String? {
        if (input.length !in 1..MAX_INPUT || input.any(Char::isISOControl) || '\\' in input) return null
        val candidate = input.trim()
        if (candidate.isEmpty() || candidate.startsWith('/')) return null
        webUrl(candidate)?.let { return it }

        // Distinguish common bare hosts from words and from unrecognized explicit schemes.
        val bareUrl = runCatching { URI("https://$candidate") }.getOrNull()
        val host = bareUrl?.host
        if (host != null && ('.' in host || host.startsWith('[') || host.equals("localhost", ignoreCase = true))) {
            return webUrl(bareUrl.toString())
        }
        if (explicitScheme.containsMatchIn(candidate)) return null
        return webUrl(searchProvider(context).search(candidate))
    }

    fun bookmarks(context: Context): List<String> = runCatching {
        val saved = JSONArray(prefs(context).getString(BOOKMARKS, "[]") ?: "[]")
        (0 until minOf(saved.length(), MAX_BOOKMARKS)).mapNotNull { index ->
            (saved.opt(index) as? String)?.let(::webUrl)
        }.distinct()
    }.getOrDefault(emptyList())

    fun toggleBookmark(context: Context, url: String): Boolean {
        val candidate = requireNotNull(webUrl(url))
        val existing = bookmarks(context)
        val added = candidate !in existing
        require(!added || existing.size < MAX_BOOKMARKS)
        val updated = if (added) existing + candidate else existing - candidate
        prefs(context).edit().putString(BOOKMARKS, JSONArray(updated).toString()).apply()
        return added
    }
}
