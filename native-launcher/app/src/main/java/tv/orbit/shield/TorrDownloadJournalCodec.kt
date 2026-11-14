package tv.orbit.shield

import android.net.Uri
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/** Storage-independent record format and the only legacy preferences migration parser. */
internal object TorrDownloadJournalCodec {
    const val ACTIVE_KEY = "downloads.torrserver.jobs"
    const val COMPLETED_KEY = "downloads.torrserver.completed"
    const val MAX_ACTIVE_JOBS = 100
    const val MAX_RECORDS = 2000
    const val MAX_JOURNAL_BYTES = 8 * 1024 * 1024
    private val HTTP_RESOURCE_KEY = Regex("[0-9a-f]{64}")
    private val HTTP_RECORD_KEYS = setOf("id", "http", "total", "name", "tree", "document", "finalName", "offset", "etag",
        "state", "error", "parentId", "grouped", "prefetchMovieId")

    fun validHttpResourceKey(value: String): Boolean = HTTP_RESOURCE_KEY.matches(value)

    fun encode(job: TorrDownloadJob): String = JSONObject().apply {
        put("id", job.id)
        if (job.http == null) { put("hash", job.hash); put("index", job.index) }
        else put("http", JSONObject().apply {
            put("resourceKey", job.http.resourceKey)
            put("expectedEtag", job.http.expectedEtag)
            if (job.state != "done") job.http.url?.let { put("url", it.serialized) }
        })
        put("total", job.total)
        put("name", job.name); put("tree", job.tree); put("document", job.document); put("finalName", job.finalName)
        put("offset", job.offset); put("etag", job.etag ?: JSONObject.NULL); put("state", job.state); put("error", job.error)
        put("parentId", job.parentId); put("grouped", job.grouped); put("prefetchMovieId", job.prefetchMovieId)
    }.toString()

    fun decode(text: String): TorrDownloadJob {
        require(text.length <= MAX_JOURNAL_BYTES)
        val obj = try { JSONObject(text) }
        catch (_: JSONException) { throw IllegalArgumentException("Invalid download journal record") }
        if (!obj.has("http")) return decodeJob(obj, null)
        // Android's JSON errors can contain input text; don't attach the raw exception to a URL-bearing row.
        return try {
            require(obj.keys().asSequence().all(HTTP_RECORD_KEYS::contains))
            // Android JSONObject's getters coerce strings and truncate doubles.
            require(listOf("total", "offset").all { obj.opt(it).let { value -> value is Int || value is Long } })
            require(listOf("id", "name", "tree", "document", "finalName", "state").all { obj.opt(it) is String })
            require(listOf("error", "parentId", "prefetchMovieId").all { !obj.has(it) || obj.opt(it) is String })
            require(!obj.has("grouped") || obj.opt("grouped") == false)
            require(obj.isNull("etag") || obj.opt("etag") is String)
            val payload = obj.opt("http") as? JSONObject ?: throw IllegalArgumentException()
            val key = payload.opt("resourceKey") as? String ?: throw IllegalArgumentException()
            val etag = payload.opt("expectedEtag") as? String ?: throw IllegalArgumentException()
            require(payload.keys().asSequence().all { it == "resourceKey" || it == "expectedEtag" || it == "url" })
            val url = if (payload.has("url")) HttpDownloadUrl.parse(payload.opt("url") as? String ?: throw IllegalArgumentException()) else null
            decodeJob(obj, HttpDownloadOrigin(url, key, etag))
        } catch (_: Exception) { throw IllegalArgumentException("Invalid HTTP download record") }
    }

    private fun decodeJob(obj: JSONObject, http: HttpDownloadOrigin?): TorrDownloadJob {
        val job = TorrDownloadJob(obj.getString("id"), if (http == null) obj.getString("hash") else "",
            if (http == null) obj.getInt("index") else 0, obj.getLong("total"),
            obj.getString("name"), obj.getString("tree"), obj.getString("document"), obj.getString("finalName"),
            obj.getLong("offset"), if (obj.isNull("etag")) null else obj.getString("etag"), obj.getString("state"), obj.optString("error", ""),
            obj.optString("parentId", ""), obj.optBoolean("grouped", false), obj.optString("prefetchMovieId", ""), http)
        validateJob(job)
        return job
    }

    fun validate(jobs: List<TorrDownloadJob>) {
        validateStructure(jobs)
        jobs.forEach(::validateJob)
    }

    /** Unchanged immutable records have already passed record validation. */
    fun validateStructure(jobs: List<TorrDownloadJob>) {
        require(jobs.size <= MAX_RECORDS && jobs.count { it.state != "done" } <= MAX_ACTIVE_JOBS)
        require(jobs.map { it.id }.distinct().size == jobs.size)
        require(jobs.map { it.document }.distinct().size == jobs.size)
        var httpDestinations: MutableSet<Pair<String, String>>? = null
        for (job in jobs) job.http?.let {
            val keys = httpDestinations ?: mutableSetOf<Pair<String, String>>().also { new -> httpDestinations = new }
            require(keys.add(job.tree to it.resourceKey))
        }
    }

    /** Both legacy layouts retain their original record order and validation limits. */
    fun readLegacy(activeRaw: String?, completedRaw: String?): List<TorrDownloadJob> {
        fun read(raw: String?, limit: Int): List<TorrDownloadJob> {
            if (raw == null) return emptyList()
            require(raw.length <= MAX_JOURNAL_BYTES)
            val array = JSONArray(raw)
            require(array.length() <= limit)
            return (0 until array.length()).map {
                val record = array.getJSONObject(it)
                require(!record.has("http")) { "HTTP downloads are not supported in legacy preferences" }
                decode(record.toString())
            }
        }
        val active = read(activeRaw, if (completedRaw == null) MAX_RECORDS else MAX_ACTIVE_JOBS)
        val completed = read(completedRaw, MAX_RECORDS)
        if (completedRaw != null) require(active.none { it.state == "done" } && completed.all { it.state == "done" })
        return (active + completed).also(::validate)
    }

    private fun validateJob(job: TorrDownloadJob) {
        UUID.fromString(job.id)
        val http = job.http
        if (http == null) TorrDownloadSource(job.hash, job.index, job.total)
        else {
            require(job.hash.isEmpty() && job.index == 0 && job.total > 0 && !job.grouped && job.prefetchMovieId.isEmpty())
            require(validHttpResourceKey(http.resourceKey) && isStrongHttpEtag(http.expectedEtag))
            require(if (job.state == "done") http.url == null else http.url != null)
            require(if (job.offset == 0L) job.etag == null || job.etag == http.expectedEtag else job.etag == http.expectedEtag)
            require(job.state !in setOf("finalizing", "done") || job.offset == job.total)
        }
        require(job.offset in 0..job.total && job.name.length in 1..512 && job.finalName.length in 1..255)
        require(Uri.parse(job.tree).scheme == "content" && Uri.parse(job.document).scheme == "content")
        require(job.state in setOf("queued", "running", "paused", "done", "error", "finalizing"))
        require(job.error.length <= 128 && (job.etag == null || job.etag.length <= 8192))
        require(job.parentId.length <= 4096 && (!job.grouped || job.parentId.isNotBlank()))
        require(job.prefetchMovieId.length <= 256 && job.prefetchMovieId.none { it.isISOControl() })
    }
}
