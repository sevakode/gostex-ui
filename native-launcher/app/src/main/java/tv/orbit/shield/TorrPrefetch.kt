package tv.orbit.shield

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Uses the existing user-selected download folder and the same single transfer worker. */
internal object TorrPrefetch {
    private const val COUNT = "downloads.prefetch.count"
    private const val QUOTA = "downloads.prefetch.quotaGiB"
    val counts = listOf(0, 1, 2, 3)
    val quotas = listOf(4, 8, 16, 32, 64)
    fun count(context: Context): Int = runCatching { context.getSharedPreferences("orbit", 0).getInt(COUNT, 1) }.getOrDefault(1).takeIf { it in counts } ?: 1
    fun quota(context: Context): Int = runCatching { context.getSharedPreferences("orbit", 0).getInt(QUOTA, 8) }.getOrDefault(8).takeIf { it in quotas } ?: 8
    fun setCount(context: Context, value: Int) { require(value in counts); check(context.getSharedPreferences("orbit", 0).edit().putInt(COUNT, value).commit()) }
    fun setQuota(context: Context, value: Int) { require(value in quotas); check(context.getSharedPreferences("orbit", 0).edit().putInt(QUOTA, value).commit()) }

    fun destinationAvailable(context: Context): Boolean {
        val folder = context.getSharedPreferences("orbit", 0).getString("downloads.folder", "").orEmpty()
        val tree = runCatching { Uri.parse(folder) }.getOrNull() ?: return false
        return tree.scheme == "content" && context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }
    }

    /** The caller is a visible activity and starts its player after this method returns. */
    suspend fun start(context: Context, movieId: String, entry: TorrCatalogEntry, videos: List<TorrCatalogFile>, currentId: Int, canStart: () -> Boolean) {
        if (count(context) == 0 || PortableMaintenance.running) return
        val folder = context.getSharedPreferences("orbit", 0).getString("downloads.folder", "").orEmpty()
        val tree = runCatching { Uri.parse(folder) }.getOrNull() ?: return
        if (tree.scheme != "content" || context.contentResolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission && it.isWritePermission }) return
        val jobs = withContext(Dispatchers.IO) { prepare(context, movieId, tree, entry, videos, currentId, includeCurrent = true) }
        if (jobs.isNotEmpty() && canStart()) TorrDownloadActions.startMany(context, jobs.map { it.id })
    }

    /** Called by the already-running foreground worker after one offline episode completes. */
    fun continueAfter(context: Context, completed: TorrDownloadJob) {
        if (completed.prefetchMovieId.isBlank() || count(context) == 0 || PortableMaintenance.running) return
        val current = TorrDownloadStore.jobs.value.firstOrNull { it.id == completed.id && it.state == "done" } ?: return
        val folder = context.getSharedPreferences("orbit", 0).getString("downloads.folder", "").orEmpty()
        if (folder != current.tree) return
        val tree = Uri.parse(folder)
        if (tree.scheme != "content" || context.contentResolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission && it.isWritePermission }) return
        val entry = TorrServerCatalog.loadFiles(current.hash)
        val videos = entry.files.filter { TorrPlayback.isVideo(it.path) }
        // Re-check preferences and the finished record after the network request.
        if (count(context) == 0 || PortableMaintenance.running || TorrDownloadStore.jobs.value.none { it.id == current.id && it.state == "done" }) return
        val jobs = prepare(context, current.prefetchMovieId, tree, entry, videos, current.index)
        if (jobs.isNotEmpty()) TorrDownloadStore.queue(context, jobs.map { it.id })
    }

    /** Refill a queue stopped by its space limit when the user deletes a saved episode. */
    suspend fun refillAfterDelete(context: Context, removed: TorrDownloadJob, canStart: () -> Boolean) {
        if (removed.prefetchMovieId.isBlank() || count(context) == 0 || PortableMaintenance.running) return
        val folder = context.getSharedPreferences("orbit", 0).getString("downloads.folder", "").orEmpty()
        if (folder != removed.tree) return
        val tree = Uri.parse(folder)
        if (tree.scheme != "content" || context.contentResolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission && it.isWritePermission }) return
        val jobs = withContext(Dispatchers.IO) {
            val related = TorrDownloadStore.jobs.value.filter { it.prefetchMovieId == removed.prefetchMovieId && it.hash.equals(removed.hash, true) }
            if (related.any { it.state in setOf("running", "queued", "paused", "error", "finalizing") }) return@withContext emptyList()
            val entry = TorrServerCatalog.loadFiles(removed.hash)
            val videos = entry.files.filter { TorrPlayback.isVideo(it.path) }
            if (!orderedEpisodeFiles(videos.map { it.path })) return@withContext emptyList()
            val anchors = (related.filter { it.state == "done" }.map { it.index } + removed.index).toSet()
            val anchor = videos.lastOrNull { it.id in anchors } ?: return@withContext emptyList()
            prepare(context, removed.prefetchMovieId, tree, entry, videos, anchor.id)
        }
        if (jobs.isNotEmpty() && canStart() && count(context) > 0) TorrDownloadActions.startMany(context, jobs.map { it.id })
    }

    @Synchronized private fun prepare(context: Context, movieId: String, tree: Uri, entry: TorrCatalogEntry, videos: List<TorrCatalogFile>, currentId: Int, includeCurrent: Boolean = false): List<TorrDownloadJob> {
        TorrDownloadStore.recover(context)
        val existing = TorrDownloadStore.jobs.value
        val used = existing.filter { it.prefetchMovieId.isNotBlank() }.fold(0L) { sum, job ->
            if (Long.MAX_VALUE - sum < job.total) Long.MAX_VALUE else sum + job.total
        }
        val reserve = TorrDownloadSpace.read(context).toLong() * 1024 * 1024
        val candidates = planEpisodePrefetch(videos.map { PrefetchEpisode(it.id, it.path, it.size) }, currentId,
            count(context), existing.filter { it.hash.equals(entry.hash, true) }.map { it.index }.toSet(),
            Long.MAX_VALUE, reserve, quota(context).toLong() * 1024 * 1024 * 1024, used, includeCurrent)
        val prepared = mutableListOf<TorrDownloadJob>()
        for (candidate in candidates) {
            val job = try { TorrDownloadDocuments.enqueue(context, tree, entry, videos.first { it.id == candidate.id }, movieId) } catch (_: Exception) { break }
            val enough = runCatching {
                TorrSafTarget.open(context.contentResolver, Uri.parse(job.document)).use { target ->
                    val free = target.availableBytes()
                    free >= reserve && candidate.bytes <= free - reserve - prepared.sumOf { it.total }
                }
            }.getOrDefault(false)
            if (!enough) { runCatching { TorrDownloadDocuments.discard(context, job.id) }; break }
            prepared += job
        }
        return prepared
    }

    fun playable(context: Context, job: TorrDownloadJob): Boolean = job.state == "done" && runCatching {
        val uri = Uri.parse(job.document)
        val size = context.contentResolver.query(uri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        } ?: -1L
        size == job.total && context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } == true
    }.getOrDefault(false)
}
