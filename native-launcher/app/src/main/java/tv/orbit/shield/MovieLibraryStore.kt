package tv.orbit.shield

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

internal data class MovieSource(val label: String, val uri: String, val fileIndex: Int? = null, val sizeBytes: Long? = null, val filenameHint: String? = null)
internal data class SavedMovie(
    val id: String,
    val title: String,
    val year: String,
    val poster: String?,
    val sources: List<MovieSource> = emptyList(),
    val durationSeconds: Long? = null,
    val mediaType: String = "movie",
)

/** User collection is independent of the ROM library; invalid stored data is never overwritten. */
internal object MovieLibraryStore {
    internal const val KEY = "movies.library.v1"
    private const val MAX_MOVIES = 1000
    private const val MAX_BYTES = 4 * 1024 * 1024
    private val torrentRemovals = mutableSetOf<String>()

    private fun prefs(context: Context) = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)

    @Synchronized fun read(context: Context): List<SavedMovie> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        require(raw.length <= MAX_BYTES) { "Movie library is too large" }
        val json = JSONObject(raw)
        require(json.getInt("schema") == 1)
        val array = json.getJSONArray("movies")
        require(array.length() <= MAX_MOVIES)
        val movies = List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val sourceArray = item.getJSONArray("sources")
            require(sourceArray.length() <= 100)
            val sources = List(sourceArray.length()) { sourceIndex ->
                val source = sourceArray.getJSONObject(sourceIndex)
                val uri = source.getString("uri")
                require(validSource(uri))
                val fileIndex = if (source.has("fileIndex") && !source.isNull("fileIndex")) {
                    source.getInt("fileIndex").also { require(it >= 0) }
                } else null
                val size = if (source.has("sizeBytes") && !source.isNull("sizeBytes")) {
                    source.getLong("sizeBytes").also { require(it > 0) }
                } else null
                val filenameHint = if (source.has("filenameHint") && !source.isNull("filenameHint")) {
                    source.getString("filenameHint").also { require(validFilenameHint(it)) }
                } else null
                MovieSource(source.getString("label").also { require(it.length <= 256) }, uri, fileIndex, size, filenameHint)
            }
            SavedMovie(item.getString("id").also { require(it.isNotBlank() && it.length <= 256) },
                item.getString("title").also { require(it.isNotBlank() && it.length <= 256) },
                item.getString("year").also { require(it.length <= 64) },
                item.optString("poster").takeIf { it.isNotEmpty() }?.also { require(it.length <= 2048) }, sources,
                if (item.has("durationSeconds") && !item.isNull("durationSeconds"))
                    item.getLong("durationSeconds").also { require(it > 0) } else null,
                item.optString("mediaType", "movie").also { require(it in setOf("movie", "series")) })
        }
        require(movies.map { it.id }.distinct().size == movies.size)
        return movies
    }

    fun validFilenameHint(value: String): Boolean =
        value.isNotBlank() && value.length <= 512 && value.none { it.isISOControl() }

    fun validSource(value: String): Boolean {
        if (value.length !in 1..8192) return false
        return runCatching {
        val uri = Uri.parse(value)
        when (uri.scheme?.lowercase()) {
            "magnet" -> value.substringAfter('?', "").split('&').any { parameter ->
                val pair = parameter.split('=', limit = 2)
                pair.size == 2 && pair[0].equals("xt", ignoreCase = true) &&
                    Uri.decode(pair[1]).matches(Regex("(?i)urn:btih:(?:[0-9a-f]{40}|[a-z2-7]{32})"))
            }
            "https", "http" -> !uri.host.isNullOrBlank() && uri.userInfo == null
            "content" -> !uri.authority.isNullOrBlank()
            "acestream" -> !uri.schemeSpecificPart.isNullOrBlank()
            "torrserver" -> value.matches(Regex("(?i)torrserver://[0-9a-f]{40}"))
            else -> false
        }
        }.getOrDefault(false)
    }

    @Synchronized fun save(context: Context, movie: SavedMovie): Boolean {
        // Read first and fail closed if stored JSON is damaged or has a newer schema.
        val existing = read(context)
        require(movie.id.isNotBlank() && movie.id.length <= 256 && movie.title.isNotBlank() && movie.title.length <= 256)
        require(movie.year.length <= 64 && (movie.poster == null || movie.poster.length <= 2048))
        // A server removal is reserved only after checking the current library.
        // Do not add a new dependent source while the network removal is in flight.
        require(torrentRemovals.none { hash -> movie.sources.any { it.uri.equals("torrserver://$hash", ignoreCase = true) } })
        require(movie.sources.size <= 100 && movie.sources.all {
            it.label.length <= 256 && validSource(it.uri) && (it.fileIndex == null || it.fileIndex >= 0) &&
                (it.sizeBytes == null || it.sizeBytes > 0) && (it.filenameHint == null || validFilenameHint(it.filenameHint))
        })
        require(movie.durationSeconds == null || movie.durationSeconds > 0)
        require(movie.mediaType in setOf("movie", "series"))
        val updated = if (existing.any { it.id == movie.id }) existing.map { if (it.id == movie.id) movie else it }
            else existing + movie
        require(updated.size <= MAX_MOVIES)
        return write(context, updated)
    }

    /** Reserve only unreferenced server entries; never hold this lock during network I/O. */
    @Synchronized fun reserveTorrentRemoval(context: Context, hash: String): Boolean {
        require(hash.matches(Regex("(?i)[0-9a-f]{40}")))
        val normalized = hash.lowercase(java.util.Locale.ROOT)
        if (normalized in torrentRemovals || read(context).any { movie ->
                movie.sources.any { it.uri.equals("torrserver://$normalized", ignoreCase = true) }
            }) return false
        torrentRemovals.add(normalized)
        return true
    }

    @Synchronized fun endTorrentRemoval(hash: String) {
        torrentRemovals.remove(hash.lowercase(java.util.Locale.ROOT))
    }

    @Synchronized fun remove(context: Context, id: String): Boolean {
        val existing = read(context)
        return write(context, existing.filterNot { it.id == id })
    }

    /** Remove only the library link, leaving the torrent and its files untouched. */
    @Synchronized fun removeSource(context: Context, movieId: String, source: MovieSource): Boolean {
        val existing = read(context)
        val movie = existing.firstOrNull { it.id == movieId } ?: return false
        val remaining = movie.sources.filterNot { it.uri == source.uri && it.fileIndex == source.fileIndex }
        if (remaining.size == movie.sources.size) return false
        return write(context, existing.map { if (it.id == movieId) it.copy(sources = remaining) else it })
    }

    private fun write(context: Context, updated: List<SavedMovie>): Boolean {
        val array = JSONArray()
        updated.forEach { entry ->
            val sources = JSONArray()
            entry.sources.forEach {
                sources.put(JSONObject().put("label", it.label).put("uri", it.uri)
                    .put("fileIndex", it.fileIndex ?: JSONObject.NULL).put("sizeBytes", it.sizeBytes ?: JSONObject.NULL)
                    .put("filenameHint", it.filenameHint ?: JSONObject.NULL))
            }
            array.put(JSONObject().put("id", entry.id).put("title", entry.title).put("year", entry.year)
                .put("poster", entry.poster ?: "").put("sources", sources)
                .put("durationSeconds", entry.durationSeconds ?: JSONObject.NULL).put("mediaType", entry.mediaType))
        }
        val encoded = JSONObject().put("schema", 1).put("movies", array).toString()
        require(encoded.length <= MAX_BYTES)
        return prefs(context).edit().putString(KEY, encoded).commit()
    }
}
