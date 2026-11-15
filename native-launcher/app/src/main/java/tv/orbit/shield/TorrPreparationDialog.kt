package tv.orbit.shield

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/** Shared preparation UI for the media library and direct torrent playback. */
internal object TorrPreparationDialog {
    fun matchingDownload(activity: ComponentActivity, hash: String, fileId: Int, fileSize: Long): TorrDownloadJob? {
        val folder = activity.getSharedPreferences("orbit", Context.MODE_PRIVATE).getString("downloads.folder", "")
        return TorrDownloadStore.jobs.value.filter {
            it.http == null && it.hash.equals(hash, true) && it.index == fileId && it.total == fileSize
        }.let { jobs -> jobs.singleOrNull { it.tree == folder } ?: jobs.singleOrNull() }
    }

    /** The transfer owns all network reads. This dialog observes only its local document. */
    fun showGrowing(activity: ComponentActivity, job: TorrDownloadJob, onOpen: (Uri) -> Unit, onDismiss: () -> Unit): AlertDialog {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.movies_growing_title)
            .setMessage(activity.getString(R.string.movies_growing_waiting))
            .setNegativeButton(R.string.movies_cancel, null)
            .setPositiveButton(R.string.movies_buffer_open, null)
            .create()
        var monitor: Job? = null
        var opening: Job? = null
        var invalid = false
        var message = ""
        fun current() = TorrDownloadStore.jobs.value.firstOrNull { it.id == job.id }
        fun visible() = dialog.isShowing && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        fun canRead(now: TorrDownloadJob) = now.state == "done" ||
            (now.state in setOf("running", "finalizing") && TorrGrowingVideo.ready(now.id))
        fun open(now: TorrDownloadJob) {
            if (!visible() || opening?.isActive == true || invalid) return
            opening = activity.lifecycleScope.launch {
                val uri = if (now.state == "done") {
                    val intact = withContext(Dispatchers.IO) { TorrPrefetch.playable(activity, now) }
                    if (!intact) {
                        invalid = current() == now
                        null
                    } else if (current() == now) Uri.parse(now.document) else null
                } else TorrGrowingVideo.open(activity, now.id)
                if (uri == null) return@launch
                if (!visible()) { TorrGrowingVideo.close(activity, uri); return@launch }
                dialog.dismiss()
                onOpen(uri)
            }
        }
        dialog.setOnDismissListener { monitor?.cancel(); opening?.cancel(); onDismiss() }
        dialog.show()
        val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        button.isEnabled = false
        button.setOnClickListener {
            val now = current() ?: return@setOnClickListener
            if (canRead(now)) open(now)
            else if (now.state in setOf("paused", "error") && now.error != "video_invalid") {
                runCatching { TorrDownloadActions.start(activity, now.id) }.onFailure {
                    android.widget.Toast.makeText(activity, R.string.torr_download_failed, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        monitor = activity.lifecycleScope.launch {
            activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive && visible()) {
                    val now = current()
                    val readable = now != null && canRead(now) && !invalid
                    val resumable = now != null && now.state in setOf("paused", "error") && now.error != "video_invalid"
                    val summary = activity.getString(when {
                        invalid || now?.error == "video_invalid" -> R.string.torr_download_video_invalid
                        now == null -> R.string.movies_growing_unavailable
                        readable -> R.string.movies_growing_ready
                        resumable -> R.string.movies_growing_paused
                        now.state == "queued" -> R.string.movies_growing_queued
                        else -> R.string.movies_growing_waiting
                    })
                    val text = if (now == null) summary else {
                        val offset = downloadDisplayOffset(now, TorrDownloadLiveProgress.current.value)
                        val percent = if (now.total > 0) (offset.toDouble() * 100 / now.total).toInt().coerceIn(0, 100) else 0
                        val status = when (now.state) {
                            "queued" -> R.string.torr_download_queued
                            "running" -> R.string.torr_download_running
                            "finalizing" -> R.string.torr_download_finalizing
                            "paused" -> R.string.torr_download_paused
                            "done" -> R.string.prefetch_ready
                            else -> R.string.torr_download_error
                        }
                        summary + "\n\n" + activity.getString(R.string.prefetch_buffer_progress,
                            activity.getString(status),
                            android.text.format.Formatter.formatShortFileSize(activity, offset),
                            android.text.format.Formatter.formatShortFileSize(activity, now.total), percent)
                    }
                    if (text != message) { message = text; dialog.setMessage(text) }
                    button.setText(if (resumable) R.string.movies_growing_resume else R.string.movies_buffer_open)
                    button.isEnabled = readable || resumable
                    if (readable && TorrPlaybackPreferences.autoPlay(activity)) open(requireNotNull(now))
                    delay(400)
                }
            }
        }
        return dialog
    }

    fun show(activity: ComponentActivity, hash: String, fileId: Int, onOpen: () -> Unit, onDismiss: () -> Unit, startPreload: Boolean = true, trackDownload: Boolean = false, downloadPreparation: Job? = null,
             damagedLocalCopy: Boolean = false): AlertDialog {
        val warning = if (damagedLocalCopy) activity.getString(R.string.prefetch_damaged_stream) else ""
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.movies_buffer_title)
            .setMessage(listOf(warning, activity.getString(R.string.movies_buffer_starting)).filter { it.isNotEmpty() }.joinToString("\n\n"))
            .setNegativeButton(R.string.movies_cancel, null)
            .setPositiveButton(R.string.movies_buffer_open, null)
            .create()
        var monitor: Job? = null
        var preload: Job? = null
        var downloadMonitor: Job? = null
        var preparationCompletion: kotlinx.coroutines.DisposableHandle? = null
        var lastStatus = ""
        var preloadMessage = ""
        var downloadMessage = if (trackDownload) activity.getString(R.string.prefetch_preparing) else ""
        fun updateMessage() {
            if (dialog.isShowing) dialog.setMessage(listOf(warning, lastStatus, downloadMessage, preloadMessage).filter { it.isNotEmpty() }.joinToString("\n\n"))
        }
        dialog.setOnDismissListener {
            monitor?.cancel(); preload?.cancel(); downloadMonitor?.cancel(); preparationCompletion?.dispose(); onDismiss()
        }
        dialog.show()
        if (trackDownload) {
            fun updateDownload(jobs: List<TorrDownloadJob> = TorrDownloadStore.jobs.value,
                progress: TorrDownloadProgress? = TorrDownloadLiveProgress.current.value) {
                val job = jobs.firstOrNull { it.hash.equals(hash, true) && it.index == fileId }
                downloadMessage = if (job == null) {
                    activity.getString(when {
                        downloadPreparation?.isActive == true -> R.string.prefetch_preparing
                        TorrPrefetch.count(activity) == 0 -> R.string.prefetch_status_disabled
                        !TorrPrefetch.destinationAvailable(activity) -> R.string.prefetch_status_folder
                        else -> R.string.prefetch_not_queued
                    })
                } else {
                    val status = when (job.state) {
                        "queued" -> R.string.torr_download_queued
                        "running" -> R.string.torr_download_running
                        "paused" -> R.string.torr_download_paused
                        "finalizing" -> R.string.torr_download_finalizing
                        "done" -> R.string.prefetch_ready
                        else -> if (job.error == "video_invalid") R.string.torr_download_video_invalid else R.string.torr_download_error
                    }
                    val displayOffset = downloadDisplayOffset(job, progress)
                    val downloaded = android.text.format.Formatter.formatShortFileSize(activity, displayOffset)
                    val total = android.text.format.Formatter.formatShortFileSize(activity, job.total)
                    val percent = if (job.total > 0) (displayOffset.toDouble() * 100 / job.total).toInt().coerceIn(0, 100) else 0
                    activity.getString(R.string.prefetch_buffer_progress, activity.getString(status), downloaded, total, percent)
                }
                updateMessage()
            }
            updateDownload()
            downloadMonitor = activity.lifecycleScope.launch {
                activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    combine(TorrDownloadStore.jobs, TorrDownloadLiveProgress.current) { jobs, progress ->
                        jobs to progress
                    }.collect { (jobs, progress) -> updateDownload(jobs, progress) }
                }
            }
            preparationCompletion = downloadPreparation?.invokeOnCompletion {
                activity.lifecycleScope.launch {
                    if (dialog.isShowing && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) updateDownload()
                }
            }
        }
        var opened = false
        var preloadReady = false
        var preloadCandidate = false
        var sawPreload = false
        var lastEdgeProbe = 0L
        fun openOnce() {
            if (opened || !dialog.isShowing) return
            opened = true
            dialog.dismiss()
            onOpen()
        }
        fun autoOpenIfReady(): Boolean {
            if (!shouldAutoOpenPrepared(preloadReady, TorrPlaybackPreferences.autoPlay(activity),
                    activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))) return false
            openOnce()
            return true
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { openOnce() }
        monitor = activity.lifecycleScope.launch {
            while (isActive && dialog.isShowing) {
                // Preloading may continue when the user switches apps, but the
                // visible status and edge probes are unnecessary in the background.
                if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    delay(2000)
                    continue
                }
                // /stream?stat also renews the server's inactivity timer. Keep
                // this torrent/cache alive only while this window is visible.
                val entry = runCatching { withContext(Dispatchers.IO) { TorrServerCatalog.loadFiles(hash, readTimeoutMs = 4000) } }.getOrNull()
                if (isActive && dialog.isShowing && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    if (startPreload && entry?.serverState == 2) sawPreload = true
                    lastStatus = entry?.let { torrentStatusText(activity, it, false) } ?: activity.getString(R.string.movies_status_unavailable)
                    updateMessage()
                    val fileSize = entry?.files?.singleOrNull { it.id == fileId }?.size
                    val readyNow = selectedTorrentPreloadReady(entry?.serverState, entry?.cachedBytes, entry?.preloadBytes, fileSize)
                    val observed = observedTorrentPreloadReady(startPreload, sawPreload,
                        entry?.serverState, entry?.cachedBytes, entry?.preloadBytes, fileSize)
                    val now = SystemClock.elapsedRealtime()
                    if (!preloadReady && readyNow && (preloadCandidate || observed) && fileSize != null &&
                        (lastEdgeProbe == 0L || now - lastEdgeProbe >= 10_000L)) {
                        lastEdgeProbe = now
                        val edgesReady = runCatching { withContext(Dispatchers.IO) {
                            TorrServerCatalog.canReadVideoEdges(hash, fileId, fileSize)
                        } }.getOrDefault(false)
                        if (!isActive || !dialog.isShowing) return@launch
                        if (edgesReady) {
                            preloadReady = true
                            preloadMessage = activity.getString(R.string.movies_buffer_finished)
                            updateMessage()
                        }
                    }
                    if (autoOpenIfReady()) return@launch
                }
                delay(2000)
            }
        }
        if (startPreload) preload = activity.lifecycleScope.launch {
            val entry = try { TorrServerCatalog.preload(hash, fileId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (!isActive || !dialog.isShowing) return@launch
            // TorrServer can return HTTP 200 after an aborted preload. Require its
            // reported cache threshold as well; it does not guarantee future throughput.
            preloadCandidate = completedSelectedPreload(entry?.serverState,
                entry?.files?.singleOrNull { it.id == fileId }?.size)
            preloadMessage = activity.getString(R.string.movies_buffer_waiting)
            updateMessage()
        }
        return dialog
    }
}

internal fun torrentStatusText(context: Context, entry: TorrCatalogEntry, snapshot: Boolean): String {
    val unknown = context.getString(R.string.movies_buffer_unknown)
    fun size(value: Long?) = value?.let { android.text.format.Formatter.formatFileSize(context, it) } ?: unknown
    val speed = entry.downloadBytesPerSecond?.let { size(it) + "/s" } ?: unknown
    val state = when (entry.serverState) {
        0 -> R.string.movies_state_added
        1 -> R.string.movies_state_metadata
        2 -> R.string.movies_state_preload
        3 -> R.string.movies_state_working
        4 -> R.string.movies_state_closed
        5 -> R.string.movies_state_stored
        else -> R.string.movies_buffer_unknown
    }
    val values = context.getString(R.string.movies_status_metrics, context.getString(state), size(entry.cachedBytes),
        size(entry.preloadBytes), speed, entry.connectedSeeders?.toString() ?: unknown)
    return values + "\n\n" + context.getString(if (snapshot) R.string.movies_status_snapshot else R.string.movies_status_open_hint)
}
