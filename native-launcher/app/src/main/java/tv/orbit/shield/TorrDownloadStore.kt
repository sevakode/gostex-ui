package tv.orbit.shield

import android.content.Context
import android.net.Uri
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class HttpDownloadDuplicate : IOException("Download link already recorded")
internal class HttpDownloadSessionRequired : IOException("Download session required")

internal data class TorrDownloadJob(
    val id: String, val hash: String, val index: Int, val total: Long,
    val name: String, val tree: String, val document: String,
    val finalName: String, val offset: Long = 0, val etag: String? = null,
    val state: String = "paused", val error: String = "",
    val parentId: String = "", val grouped: Boolean = false,
    val prefetchMovieId: String = "",
    val http: HttpDownloadOrigin? = null,
) {
    val requiresHttpRestart: Boolean get() = http != null && http.expectedEtag == null && offset > 0 && offset < total
    val requiresHttpSession: Boolean get() = http?.sessionBound == true && offset < total

    // Don't surface network validators or locators if a record is incidentally logged.
    override fun toString(): String = "TorrDownloadJob(id=$id, state=$state, offset=$offset, total=$total)"
}

/** Download records are durable operational state, not a second settings store. */
internal object TorrDownloadStore {
    private const val MAX_ACTIVE_JOBS = TorrDownloadJournalCodec.MAX_ACTIVE_JOBS
    private const val MAX_RECORDS = TorrDownloadJournalCodec.MAX_RECORDS
    private const val SESSION_LIFETIME_NANOS = 30L * 60 * 1_000_000_000
    private class SessionGrant(val session: HttpDownloadSession, val createdAt: Long = System.nanoTime())
    private var journal: TorrDownloadJournal? = null
    private val mutable = MutableStateFlow<List<TorrDownloadJob>>(emptyList())
    val jobs: StateFlow<List<TorrDownloadJob>> = mutable
    private var initialized = false
    private var recovered = false
    private val removingTorrents = mutableSetOf<String>()
    private val activeJobs = mutableSetOf<String>()
    private val discardingJobs = mutableSetOf<String>()
    private val sessions = ConcurrentHashMap<String, SessionGrant>()

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
        persist(mutable.value.filterNot { it.id == id })
    }

    @Synchronized fun finishDiscard(context: Context, id: String) {
        init(context)
        require(id in discardingJobs && mutable.value.any { it.id == id && it.state in setOf("paused", "error") })
        persist(mutable.value.filterNot { it.id == id })
        sessions.remove(id)
    }

    @Synchronized fun endDiscard(id: String) { discardingJobs.remove(id) }

    /** Reserve the hash while a confirmed removal is in flight, without holding the store lock over I/O. */
    @Synchronized fun beginTorrentRemoval(context: Context, hash: String): Boolean {
        init(context)
        require(hash.matches(Regex("[0-9a-fA-F]{40}")))
        val key = hash.lowercase(Locale.ROOT)
        if (key in removingTorrents || mutable.value.any { it.http == null && it.hash.equals(hash, ignoreCase = true) && (it.state != "done" || it.id in activeJobs || it.id in discardingJobs) }) return false
        removingTorrents += key
        return true
    }

    @Synchronized fun endTorrentRemoval(hash: String) { removingTorrents.remove(hash.lowercase(Locale.ROOT)) }

    @Synchronized fun init(context: Context) {
        if (initialized) return
        var opened: TorrDownloadJournal? = null
        val parsed = try {
            opened = TorrDownloadSqliteJournal.open(context)
            opened.read().also(TorrDownloadJournalCodec::validate)
        } catch (e: Exception) {
            // Failed migration/open never becomes an empty queue. A retry must use
            // a fresh handle; existing media references and preferences stay intact.
            runCatching { opened?.close() }
            throw IOException("Cannot read download queue", e)
        }
        journal = opened
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
        if (recovered != mutable.value) persist(recovered)
        this.recovered = true
    }

    /** Check batch admission against pending work, independently of saved media. */
    @Synchronized fun requireActiveCapacity(context: Context, additional: Int) {
        init(context)
        require(additional >= 0 && additional <= MAX_ACTIVE_JOBS)
        require(mutable.value.count { it.state != "done" } <= MAX_ACTIVE_JOBS - additional &&
            mutable.value.size <= MAX_RECORDS - additional)
    }

    /** Cheap admission before allocating the new SAF partial; add rechecks under the same lock. */
    @Synchronized fun requireHttpAdmission(context: Context, tree: String, resourceKey: String) {
        require(Uri.parse(tree).scheme == "content" && TorrDownloadJournalCodec.validHttpResourceKey(resourceKey))
        requireActiveCapacity(context, 1)
        requireNoHttpDuplicate(tree, resourceKey)
    }

    private fun requireNoHttpDuplicate(tree: String, resourceKey: String) {
        if (mutable.value.any { it.tree == tree && it.http?.resourceKey == resourceKey }) throw HttpDownloadDuplicate()
    }

    private fun canUseSource(job: TorrDownloadJob): Boolean = job.http != null || job.hash.lowercase(Locale.ROOT) !in removingTorrents

    @Synchronized fun add(context: Context, job: TorrDownloadJob, admission: HttpDownloadAdmissionResult? = null) {
        init(context)
        require(canUseSource(job) && mutable.value.size < MAX_RECORDS &&
            (job.state == "done" || mutable.value.count { it.state != "done" } < MAX_ACTIVE_JOBS) && mutable.value.none {
            it.id == job.id || it.document == job.document ||
                (it.tree == job.tree && it.parentId == job.parentId && it.finalName.equals(job.finalName, ignoreCase = true))
        })
        TorrDownloadJournalCodec.validate(listOf(job))
        job.http?.let {
            require(it.expectedEtag != null || job.state != "done") // Restart-only files complete through finalization.
            requireNoHttpDuplicate(job.tree, it.resourceKey)
        }
        val grant = if (job.http?.sessionBound == true) checkedSession(job, admission) else null
        if (grant != null) sessions[job.id] = grant
        try { persist(mutable.value + job) }
        catch (e: Exception) {
            sessions.remove(job.id)
            throw e
        }
    }

    @Synchronized fun bindSession(context: Context, id: String, admission: HttpDownloadAdmissionResult) {
        init(context)
        val job = mutable.value.firstOrNull { it.id == id } ?: throw HttpDownloadSessionRequired()
        require(id !in activeJobs && id !in discardingJobs)
        sessions[id] = checkedSession(job, admission)
    }

    private fun checkedSession(job: TorrDownloadJob, admission: HttpDownloadAdmissionResult?): SessionGrant {
        val http = job.http
        val session = admission?.session ?: throw HttpDownloadSessionRequired()
        require(http != null && job.requiresHttpSession && job.state in setOf("paused", "error") &&
            session.url == admission.url && http.resourceKey == admission.resourceKey &&
            job.total == admission.size && session.matchesSize(job.total) && http.expectedEtag == admission.strongEtag)
        return SessionGrant(session)
    }

    // Compose can ask this while a checkpoint is being committed on the worker.
    fun sessionFor(id: String): HttpDownloadSession? {
        val grant = sessions[id] ?: return null
        if (System.nanoTime() - grant.createdAt < SESSION_LIFETIME_NANOS) return grant.session
        sessions.remove(id, grant)
        return null
    }

    @Synchronized fun invalidateSession(id: String) { sessions.remove(id) }

    @Synchronized fun update(context: Context, id: String, transform: (TorrDownloadJob) -> TorrDownloadJob): TorrDownloadJob {
        init(context)
        val old = mutable.value.firstOrNull { it.id == id } ?: throw IOException("Download not found")
        val updated = transform(old)
        if (updated == old) return old
        require(updated.id == old.id && updated.hash == old.hash && updated.index == old.index && updated.total == old.total &&
            updated.tree == old.tree && updated.parentId == old.parentId && updated.grouped == old.grouped && updated.finalName == old.finalName && updated.prefetchMovieId == old.prefetchMovieId)
        if (old.http == null) require(updated.http == null)
        else {
            require(updated.name == old.name)
            if (old.state == "finalizing" && updated.state == "done") {
                require(old.offset == old.total && updated.offset == old.offset && updated.http == old.http.copy(url = null))
            } else {
                require((updated.state == "done") == (old.state == "done"))
                require(updated.http == old.http && updated.document == old.document && updated.offset >= old.offset)
            }
        }
        TorrDownloadJournalCodec.validate(listOf(updated))
        persist(mutable.value.map { if (it.id == id) updated else it })
        if (updated.offset == updated.total) sessions.remove(id)
        return updated
    }

    /** Call only after the full HTTP response is accepted, before truncating the owned partial. */
    @Synchronized fun resetHttpCheckpoint(context: Context, id: String): TorrDownloadJob {
        init(context)
        val old = mutable.value.firstOrNull { it.id == id } ?: throw IOException("Download not found")
        require(id in activeJobs && old.state == "running" && old.requiresHttpRestart)
        val updated = old.copy(offset = 0)
        persist(mutable.value.map { if (it.id == id) updated else it })
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
        persist(mutable.value.filterNot { it.id == id })
        sessions.remove(id)
    }

    @Synchronized fun pauseAll(context: Context) {
        init(context)
        val paused = mutable.value.map { if (it.state == "running" || it.state == "queued") it.copy(state = "paused") else it }
        if (paused != mutable.value) persist(paused)
    }

    @Synchronized fun queue(context: Context, ids: List<String>, restartId: String? = null) {
        init(context)
        require(ids.isNotEmpty() && ids.distinct().size == ids.size)
        val selected = mutable.value.filter { it.id in ids }
        require(selected.size == ids.size && selected.all { it.state in setOf("paused", "error", "queued") && canUseSource(it) && it.id !in discardingJobs })
        if (selected.any { it.requiresHttpSession && sessionFor(it.id) == null }) {
            throw HttpDownloadSessionRequired()
        }
        // A paused worker can still commit its tail. An offset of zero may become
        // nonzero before the next worker starts, invalidating an ordinary queue action.
        require(selected.none { it.id in activeJobs && it.http?.let { source -> source.expectedEtag == null || source.sessionBound } == true })
        // Discarding progress must be explicit for one partial, never a side effect of a batch start.
        if (restartId == null) require(selected.none { it.requiresHttpRestart })
        else require(selected.size == 1 && selected[0].id == restartId && selected[0].requiresHttpRestart)
        persist(mutable.value.map { if (it.id in ids) it.copy(state = "queued", error = "") else it })
    }

    private fun persist(jobs: List<TorrDownloadJob>) {
        val previous = mutable.value
        if (jobs == previous) return
        TorrDownloadJournalCodec.validateStructure(jobs)
        val currentJournal = journal ?: throw IOException("Download queue is not initialized")
        // The backend commits all changed records and their order atomically.
        // A failed transaction leaves the observable queue exactly as it was.
        currentJournal.write(previous, jobs)
        mutable.value = jobs
    }
}
