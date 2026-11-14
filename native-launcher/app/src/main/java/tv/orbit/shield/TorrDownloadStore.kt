package tv.orbit.shield

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class TorrDownloadJob(
    val id: String, val hash: String, val index: Int, val total: Long,
    val name: String, val tree: String, val document: String,
    val finalName: String, val offset: Long = 0, val etag: String? = null,
    val state: String = "paused", val error: String = "",
    val parentId: String = "", val grouped: Boolean = false,
    val prefetchMovieId: String = "",
)

/** Download records are durable operational state, not a second settings store. */
internal object TorrDownloadStore {
    private const val KEY = "downloads.torrserver.jobs"
    private const val COMPLETED_KEY = "downloads.torrserver.completed"
    private const val MAX_ACTIVE_JOBS = 100
    private const val MAX_RECORDS = 2000
    private const val MAX_JOURNAL_BYTES = 8 * 1024 * 1024
    private var splitJournal = false
    private val mutable = MutableStateFlow<List<TorrDownloadJob>>(emptyList())
    val jobs: StateFlow<List<TorrDownloadJob>> = mutable
    private var initialized = false
    private var recovered = false
    private val removingTorrents = mutableSetOf<String>()
    private val activeJobs = mutableSetOf<String>()
    private val discardingJobs = mutableSetOf<String>()

    @Synchronized fun claimWorker(context: Context, id: String): Boolean {
        init(context)
        if (mutable.value.none { it.id == id && it.state == "queued" } || id in activeJobs || id in discardingJobs) return false
        activeJobs += id
        return true
    }

    @Synchronized fun releaseWorker(id: String) { activeJobs.remove(id) }

    @Synchronized fun beginDiscard(context: Context, id: String): TorrDownloadJob? {
        init(context)
        val job = mutable.value.firstOrNull { it.id == id && it.state in setOf("paused", "error") } ?: return null
        if (id in activeJobs || id in discardingJobs) return null
        discardingJobs += id
        return job
    }

    @Synchronized fun beginDeletePrefetch(context: Context, id: String): TorrDownloadJob? {
        init(context)
        val job = mutable.value.firstOrNull { it.id == id && it.state == "done" && it.prefetchMovieId.isNotBlank() } ?: return null
        if (id in activeJobs || id in discardingJobs) return null
        discardingJobs += id
        return job
    }

    @Synchronized fun finishDeletePrefetch(context: Context, id: String) {
        init(context)
        require(id in discardingJobs && mutable.value.any { it.id == id && it.state == "done" && it.prefetchMovieId.isNotBlank() })
        persist(context, mutable.value.filterNot { it.id == id })
    }

    @Synchronized fun finishDiscard(context: Context, id: String) {
        init(context)
        require(id in discardingJobs && mutable.value.any { it.id == id && it.state in setOf("paused", "error") })
        persist(context, mutable.value.filterNot { it.id == id })
    }

    @Synchronized fun endDiscard(id: String) { discardingJobs.remove(id) }

    /** Reserve the hash while a confirmed removal is in flight, without holding the store lock over I/O. */
    @Synchronized fun beginTorrentRemoval(context: Context, hash: String): Boolean {
        init(context)
        require(hash.matches(Regex("[0-9a-fA-F]{40}")))
        val key = hash.lowercase(Locale.ROOT)
        if (key in removingTorrents || mutable.value.any { it.hash.equals(hash, ignoreCase = true) && (it.state != "done" || it.id in activeJobs || it.id in discardingJobs) }) return false
        removingTorrents += key
        return true
    }

    @Synchronized fun endTorrentRemoval(hash: String) { removingTorrents.remove(hash.lowercase(Locale.ROOT)) }

    @Synchronized fun init(context: Context) {
        if (initialized) return
        val prefs = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)
        val parsed = try {
            val activeRaw = prefs.getString(KEY, null)
            val completedRaw = prefs.getString(COMPLETED_KEY, null)
            fun read(raw: String?, limit: Int): List<TorrDownloadJob> {
                if (raw == null) return emptyList()
                require(raw.length <= MAX_JOURNAL_BYTES)
                val array = JSONArray(raw)
                require(array.length() <= limit)
                return (0 until array.length()).map { decode(array.getJSONObject(it)) }
            }
            // Older installations keep both states in KEY. The first mutation migrates
            // them together with the completed index in one SharedPreferences commit.
            val active = read(activeRaw, if (completedRaw == null) MAX_RECORDS else MAX_ACTIVE_JOBS)
            val completed = read(completedRaw, MAX_RECORDS)
            if (completedRaw != null) require(active.none { it.state == "done" } && completed.all { it.state == "done" })
            (active + completed).also { records ->
                require(records.size <= MAX_RECORDS && records.count { it.state != "done" } <= MAX_ACTIVE_JOBS)
                require(records.map { it.id }.distinct().size == records.size)
                require(records.map { it.document }.distinct().size == records.size)
            }.also { splitJournal = completedRaw != null }
        } catch (e: Exception) {
            // Neither a damaged active journal nor a damaged completed index may be
            // reinterpreted as empty: media still depends on those document references.
            throw IOException("Cannot read download queue", e)
        }
        mutable.value = parsed
        initialized = true
    }

    @Synchronized fun recover(context: Context) {
        init(context)
        if (recovered) return
        // Android can kill the service; never claim it is still running or start
        // data-sync work from a background boot receiver on recent Android.
        val recovered = mutable.value.map {
            when (it.state) {
                "running", "queued" -> it.copy(state = "paused")
                "finalizing" -> it.copy(state = "error", error = "finalizing")
                else -> it
            }
        }
        if (recovered != mutable.value) persist(context, recovered)
        this.recovered = true
    }

    /** Check batch admission against pending work, independently of saved media. */
    @Synchronized fun requireActiveCapacity(context: Context, additional: Int) {
        init(context)
        require(additional >= 0 && additional <= MAX_ACTIVE_JOBS)
        require(mutable.value.count { it.state != "done" } <= MAX_ACTIVE_JOBS - additional &&
            mutable.value.size <= MAX_RECORDS - additional)
    }

    @Synchronized fun add(context: Context, job: TorrDownloadJob) {
        init(context)
        require(job.hash.lowercase(Locale.ROOT) !in removingTorrents && mutable.value.size < MAX_RECORDS &&
            (job.state == "done" || mutable.value.count { it.state != "done" } < MAX_ACTIVE_JOBS) && mutable.value.none {
            it.id == job.id || it.document == job.document ||
                (it.tree == job.tree && it.parentId == job.parentId && it.finalName.equals(job.finalName, ignoreCase = true))
        })
        decode(encode(job))
        persist(context, mutable.value + job)
    }

    @Synchronized fun update(context: Context, id: String, transform: (TorrDownloadJob) -> TorrDownloadJob): TorrDownloadJob {
        init(context)
        val old = mutable.value.firstOrNull { it.id == id } ?: throw IOException("Download not found")
        val updated = transform(old)
        require(updated.id == old.id && updated.hash == old.hash && updated.index == old.index && updated.total == old.total &&
            updated.tree == old.tree && updated.parentId == old.parentId && updated.grouped == old.grouped && updated.finalName == old.finalName && updated.prefetchMovieId == old.prefetchMovieId)
        decode(encode(updated))
        persist(context, mutable.value.map { if (it.id == id) updated else it })
        return updated
    }

    @Synchronized fun removeRecord(context: Context, id: String) {
        init(context)
        val current = mutable.value.firstOrNull { it.id == id } ?: return
        require(current.state != "running" && current.state != "queued" && current.state != "finalizing" && id !in activeJobs && id !in discardingJobs)
        // A completed media record is also the library's offline reference. History
        // visibility may hide it; only explicit saved-file deletion may remove it.
        require(current.state != "done" || current.prefetchMovieId.isBlank())
        // Removing an ordinary queue record never deletes the user document.
        persist(context, mutable.value.filterNot { it.id == id })
    }

    @Synchronized fun pauseAll(context: Context) {
        init(context)
        val paused = mutable.value.map { if (it.state == "running" || it.state == "queued") it.copy(state = "paused") else it }
        if (paused != mutable.value) persist(context, paused)
    }

    @Synchronized fun queue(context: Context, ids: List<String>) {
        init(context)
        require(ids.isNotEmpty() && ids.distinct().size == ids.size)
        val selected = mutable.value.filter { it.id in ids }
        require(selected.size == ids.size && selected.all { it.state in setOf("paused", "error", "queued") && it.hash.lowercase(Locale.ROOT) !in removingTorrents && it.id !in discardingJobs })
        persist(context, mutable.value.map { if (it.id in ids) it.copy(state = "queued", error = "") else it })
    }

    private fun persist(context: Context, jobs: List<TorrDownloadJob>) {
        val active = jobs.filter { it.state != "done" }
        val completed = jobs.filter { it.state == "done" }
        require(jobs.size <= MAX_RECORDS && active.size <= MAX_ACTIVE_JOBS)
        fun encodeJournal(records: List<TorrDownloadJob>): String {
            val array = JSONArray(); records.forEach { array.put(encode(it)) }
            return array.toString().also { require(it.length <= MAX_JOURNAL_BYTES) }
        }
        val prefs = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)
        val previousActive = prefs.getString(KEY, null)
        val previousCompleted = prefs.getString(COMPLETED_KEY, null)
        val editor = prefs.edit()
        // Re-encode only the short active queue on a progress checkpoint. Android
        // still writes the shared preferences XML; this avoids repeated JSON encoding,
        // not the underlying file write. A dedicated journal needs a separate migration.
        if (!splitJournal || active != mutable.value.filter { it.state != "done" })
            editor.putString(KEY, encodeJournal(active))
        if (!splitJournal || completed != mutable.value.filter { it.state == "done" })
            editor.putString(COMPLETED_KEY, encodeJournal(completed))
        if (!editor.commit()) {
            // Android updates the in-memory preferences before disk commit returns.
            // Restore the previous pair as well as keeping the published flow unchanged.
            val rollback = prefs.edit()
            if (previousActive == null) rollback.remove(KEY) else rollback.putString(KEY, previousActive)
            if (previousCompleted == null) rollback.remove(COMPLETED_KEY) else rollback.putString(COMPLETED_KEY, previousCompleted)
            rollback.commit()
            throw IOException("Cannot save download queue")
        }
        splitJournal = true
        mutable.value = jobs
    }

    private fun encode(job: TorrDownloadJob) = JSONObject().apply {
        put("id", job.id); put("hash", job.hash); put("index", job.index); put("total", job.total)
        put("name", job.name); put("tree", job.tree); put("document", job.document); put("finalName", job.finalName)
        put("offset", job.offset); put("etag", job.etag ?: JSONObject.NULL); put("state", job.state); put("error", job.error)
        put("parentId", job.parentId); put("grouped", job.grouped); put("prefetchMovieId", job.prefetchMovieId)
    }

    private fun decode(obj: JSONObject): TorrDownloadJob {
        val job = TorrDownloadJob(obj.getString("id"), obj.getString("hash"), obj.getInt("index"), obj.getLong("total"),
            obj.getString("name"), obj.getString("tree"), obj.getString("document"), obj.getString("finalName"),
            obj.getLong("offset"), if (obj.isNull("etag")) null else obj.getString("etag"), obj.getString("state"), obj.optString("error", ""),
            obj.optString("parentId", ""), obj.optBoolean("grouped", false), obj.optString("prefetchMovieId", ""))
        UUID.fromString(job.id)
        TorrDownloadSource(job.hash, job.index, job.total)
        require(job.offset in 0..job.total && job.name.length in 1..512 && job.finalName.length in 1..255)
        require(Uri.parse(job.tree).scheme == "content" && Uri.parse(job.document).scheme == "content")
        require(job.state in setOf("queued", "running", "paused", "done", "error", "finalizing"))
        require(job.error.length <= 128 && (job.etag == null || job.etag.length <= 8192))
        require(job.parentId.length <= 4096 && (!job.grouped || job.parentId.isNotBlank()))
        require(job.prefetchMovieId.length <= 256 && job.prefetchMovieId.none { it.isISOControl() })
        return job
    }
}
