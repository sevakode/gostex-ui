package tv.orbit.shield

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Storage-independent record format and the only legacy preferences migration parser. */
internal object TorrDownloadJournalCodec {
    const val ACTIVE_KEY = "downloads.torrserver.jobs"
    const val COMPLETED_KEY = "downloads.torrserver.completed"
    const val MAX_ACTIVE_JOBS = 100
    const val MAX_RECORDS = 2000
    const val MAX_JOURNAL_BYTES = 8 * 1024 * 1024

    fun encode(job: TorrDownloadJob): String = JSONObject().apply {
        put("id", job.id); put("hash", job.hash); put("index", job.index); put("total", job.total)
        put("name", job.name); put("tree", job.tree); put("document", job.document); put("finalName", job.finalName)
        put("offset", job.offset); put("etag", job.etag ?: JSONObject.NULL); put("state", job.state); put("error", job.error)
        put("parentId", job.parentId); put("grouped", job.grouped); put("prefetchMovieId", job.prefetchMovieId)
    }.toString()

    fun decode(text: String): TorrDownloadJob {
        require(text.length <= MAX_JOURNAL_BYTES)
        val obj = JSONObject(text)
        val job = TorrDownloadJob(obj.getString("id"), obj.getString("hash"), obj.getInt("index"), obj.getLong("total"),
            obj.getString("name"), obj.getString("tree"), obj.getString("document"), obj.getString("finalName"),
            obj.getLong("offset"), if (obj.isNull("etag")) null else obj.getString("etag"), obj.getString("state"), obj.optString("error", ""),
            obj.optString("parentId", ""), obj.optBoolean("grouped", false), obj.optString("prefetchMovieId", ""))
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
    }

    /** Both legacy layouts retain their original record order and validation limits. */
    fun readLegacy(activeRaw: String?, completedRaw: String?): List<TorrDownloadJob> {
        fun read(raw: String?, limit: Int): List<TorrDownloadJob> {
            if (raw == null) return emptyList()
            require(raw.length <= MAX_JOURNAL_BYTES)
            val array = JSONArray(raw)
            require(array.length() <= limit)
            return (0 until array.length()).map { decode(array.getJSONObject(it).toString()) }
        }
        val active = read(activeRaw, if (completedRaw == null) MAX_RECORDS else MAX_ACTIVE_JOBS)
        val completed = read(completedRaw, MAX_RECORDS)
        if (completedRaw != null) require(active.none { it.state == "done" } && completed.all { it.state == "done" })
        return (active + completed).also(::validate)
    }

    private fun validateJob(job: TorrDownloadJob) {
        UUID.fromString(job.id)
        TorrDownloadSource(job.hash, job.index, job.total)
        require(job.offset in 0..job.total && job.name.length in 1..512 && job.finalName.length in 1..255)
        require(Uri.parse(job.tree).scheme == "content" && Uri.parse(job.document).scheme == "content")
        require(job.state in setOf("queued", "running", "paused", "done", "error", "finalizing"))
        require(job.error.length <= 128 && (job.etag == null || job.etag.length <= 8192))
        require(job.parentId.length <= 4096 && (!job.grouped || job.parentId.isNotBlank()))
        require(job.prefetchMovieId.length <= 256 && job.prefetchMovieId.none { it.isISOControl() })
    }
}
