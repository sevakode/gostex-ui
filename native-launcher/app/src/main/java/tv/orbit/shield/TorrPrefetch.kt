package tv.orbit.shield

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

    /** Reconnect previously saved files whose queue records were removed, without starting transfers. */
    suspend fun recoverSaved(context: Context, movieId: String, hashes: List<String>) {
        currentCoroutineContext().ensureActive()
        if (PortableMaintenance.running || !destinationAvailable(context)) return
        val tree = Uri.parse(context.getSharedPreferences("orbit", 0).getString("downloads.folder", ""))
        // Validate the durable journal before any discovery; never replace a damaged journal.
        TorrDownloadStore.recover(context)
        for (hash in hashes.map { it.lowercase(java.util.Locale.ROOT) }.distinct().take(10)) {
            currentCoroutineContext().ensureActive()
            if (PortableMaintenance.running) return
            val managed = runCatching { TorrDownloadGroups.hasRecord(context, tree, hash) }.getOrDefault(false)
            if (!managed) continue
            // action=get reads the server's cached metadata. /stream would open the torrent and contact peers.
            val entry = try { TorrServerCatalog.status(hash, timeoutMs = 1000) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { continue }
            recoverEntry(context, movieId, entry)
        }
    }

    suspend fun recoverEntry(context: Context, movieId: String, entry: TorrCatalogEntry) {
        currentCoroutineContext().ensureActive()
        if (PortableMaintenance.running || !destinationAvailable(context)) return
        val tree = Uri.parse(context.getSharedPreferences("orbit", 0).getString("downloads.folder", ""))
        TorrDownloadStore.recover(context)
        val existing = TorrDownloadStore.jobs.value.filter { it.tree == tree.toString() && it.hash.equals(entry.hash, true) }.map { it.index }.toSet()
        val files = entry.files.filter { TorrPlayback.isVideo(it.path) && it.size > 0 && it.id !in existing }.take(100)
        if (files.isEmpty()) return
        val coroutine = currentCoroutineContext()
        try {
            TorrDownloadDocuments.recoverCompletedFiles(context, tree, entry, files, movieId) {
                coroutine.ensureActive()
                if (PortableMaintenance.running || context.getSharedPreferences("orbit", 0).getString("downloads.folder", "") != tree.toString())
                    throw kotlinx.coroutines.CancellationException("Download discovery no longer active")
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Missing or inaccessible folders are left untouched; playback still works.
        }
    }

    /** The caller is a visible activity and starts its player after this method returns. */
    suspend fun start(context: Context, movieId: String, entry: TorrCatalogEntry, videos: List<TorrCatalogFile>, currentId: Int, canStart: () -> Boolean) {
        if (count(context) == 0 || PortableMaintenance.running) return
        val folder = context.getSharedPreferences("orbit", 0).getString("downloads.folder", "").orEmpty()
        val tree = runCatching { Uri.parse(folder) }.getOrNull() ?: return
        if (tree.scheme != "content" || context.contentResolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission && it.isWritePermission }) return
        val jobs = withContext(Dispatchers.IO) { prepare(context, movieId, tree, entry, videos, currentId, includeCurrent = true) }
        val pending = jobs.filter { it.state != "done" }
        if (pending.isNotEmpty() && canStart()) TorrDownloadActions.startMany(context, pending.map { it.id })
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
        val pending = jobs.filter { it.state != "done" }
        if (pending.isNotEmpty()) TorrDownloadStore.queue(context, pending.map { it.id })
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
        val pending = jobs.filter { it.state != "done" }
        if (pending.isNotEmpty() && canStart() && count(context) > 0) TorrDownloadActions.startMany(context, pending.map { it.id })
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
            val file = videos.first { it.id == candidate.id }
            val job = try { TorrDownloadDocuments.enqueue(context, tree, entry, file, movieId) }
                catch (_: TorrDownloadDestinationExists) {
                    try { TorrDownloadDocuments.recoverCompleted(context, tree, entry, file, movieId) }
                    catch (_: Exception) { break }
                } catch (_: Exception) { break }
            if (job.state == "done") { prepared += job; continue }
            val enough = runCatching {
                TorrSafTarget.open(context.contentResolver, Uri.parse(job.document)).use { target ->
                    val free = target.availableBytes()
                    free >= reserve && candidate.bytes <= free - reserve - prepared.filter { it.state != "done" }.sumOf { it.total }
                }
            }.getOrDefault(false)
            if (!enough) { runCatching { TorrDownloadDocuments.discard(context, job.id) }; break }
            prepared += job
        }
        return prepared
    }

    fun savedStatus(context: Context, job: TorrDownloadJob, checkActive: () -> Unit = {}): SavedVideoIntegrity.Result {
        if (job.state != "done") return SavedVideoIntegrity.Result.UNAVAILABLE
        checkActive()
        return try {
            val uri = Uri.parse(job.document)
            val resolver = context.contentResolver
            val size = resolver.query(uri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
            } ?: -1L
            checkActive()
            when {
                size < 0 -> SavedVideoIntegrity.Result.UNAVAILABLE
                size != job.total -> SavedVideoIntegrity.Result.INVALID
                TorrPlayback.isVideo(job.finalName) -> SavedVideoIntegrity.check(resolver, uri, job.finalName, size, checkActive)
                resolver.openAssetFileDescriptor(uri, "r")?.use { true } == true -> SavedVideoIntegrity.Result.VALID
                else -> SavedVideoIntegrity.Result.UNAVAILABLE
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            checkActive()
            SavedVideoIntegrity.Result.UNAVAILABLE
        }
    }

    fun playable(context: Context, job: TorrDownloadJob): Boolean =
        savedStatus(context, job) == SavedVideoIntegrity.Result.VALID
}
