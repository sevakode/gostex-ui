package tv.orbit.shield

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One active transfer at a time; the service exists only for explicit downloads. */
internal class TorrDownloadService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val workerActive = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private inner class Transfer(val id: String, val http: HttpDownloadAbort?) {
        val pauseToken: String = UUID.randomUUID().toString()
        val paused = AtomicBoolean(false)
        @Volatile var retryWait: TorrDownloadRetryWait? = null
        private val notificationPause = lazy(LazyThreadSafetyMode.NONE) {
            val identity = Uri.Builder().scheme("gostex-download").authority(packageName)
                .appendPath(id).appendPath(pauseToken).build()
            val intent = Intent(this@TorrDownloadService, TorrDownloadService::class.java)
                .setAction(ACTION_PAUSE_NOTIFICATION).setData(identity)
                .putExtra("id", id).putExtra("pauseToken", pauseToken)
            val pending = PendingIntent.getService(this@TorrDownloadService, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            Notification.Action.Builder(Icon.createWithResource(this@TorrDownloadService, android.R.drawable.ic_media_pause),
                getString(R.string.torr_download_pause), pending).build()
        }

        fun pause() {
            paused.set(true)
            retryWait?.cancel()
            http?.cancel()
        }
        fun pauseAction(): Notification.Action = notificationPause.value
        fun clearPauseAction() {
            if (notificationPause.isInitialized()) runCatching { notificationPause.value.actionIntent?.cancel() }
        }
    }
    @Volatile private var activeTransfer: Transfer? = null
    private var storeAvailable = false
    private var participantRegistered = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.torr_download_channel), NotificationManager.IMPORTANCE_LOW))
        if (PortableRestoreStartup.ready) {
            participantRegistered = PortableMaintenance.registerParticipant(this) {
                stopTransfer()
                if (participantRegistered && TorrDownloadStartup.ready) runCatching { TorrDownloadStore.pauseAll(this) }
                stopSelf()
            }
            storeAvailable = participantRegistered
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != ACTION_START) {
            if (intent != null && (action == ACTION_PAUSE || action == ACTION_PAUSE_NOTIFICATION)) pauseTransfer(intent)
            synchronized(this) { if (!workerActive.get()) stopSelfResult(startId) }
            return START_NOT_STICKY
        }
        synchronized(notificationLock) {
            startForeground(NOTIFICATION, notification(getString(R.string.torr_download_preparing)))
            notifiedProgress = null
        }
        if (!storeAvailable) { stopSelf(); return START_NOT_STICKY }
        launchWorker()
        return START_NOT_STICKY
    }

    private fun pauseTransfer(intent: Intent) {
        // A late in-app or notification action has no work to pause in a cold service.
        if (!storeAvailable || !workerActive.get() || !TorrDownloadStartup.ready) return
        val id = intent.getStringExtra("id") ?: return
        val transfer = activeTransfer?.takeIf { it.id == id }
        val fromNotification = intent.action == ACTION_PAUSE_NOTIFICATION
        if (fromNotification && (transfer == null || transfer.pauseToken != intent.getStringExtra("pauseToken"))) return
        val paused = runCatching {
            TorrDownloadStore.update(this, id) { job ->
                val canPause = if (fromNotification) activeTransfer === transfer && job.state == "running"
                    else job.state == "running" || job.state == "queued"
                if (canPause) job.copy(state = "paused") else job
            }
        }.getOrNull()
        if (paused?.state == "paused" && transfer != null) {
            transfer.pause()
            notificationUpdate(transfer, paused)
        }
    }

    @Synchronized private fun launchWorker() {
        if (!workerActive.compareAndSet(false, true)) return
        executor.execute {
            var prepared = false
            try {
                prepared = try { kotlinx.coroutines.runBlocking { TorrDownloadStartup.prepare(applicationContext) } }
                    catch (_: kotlinx.coroutines.CancellationException) { false }
                if (!prepared) return@execute
                runCatching {
                    TorrFinalizeProof.prune(this) { id ->
                        TorrDownloadStore.jobs.value.any { it.id == id && it.state != "done" }
                    }
                }
                while (true) {
                    if (stopping.get() || isDestroyedForMaintenance || PortableMaintenance.running) break
                    val next = TorrDownloadStore.jobs.value.firstOrNull { it.state == "queued" } ?: break
                    if (!TorrDownloadStore.claimWorker(this, next.id)) {
                        // A prior service instance may still be closing its stream after Pause.
                        // Keep this request alive without spinning until that worker releases it.
                        if (TorrDownloadStore.jobs.value.any { it.id == next.id && it.state == "queued" }) {
                            try { Thread.sleep(200) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
                        }
                        continue
                    }
                    val transfer = Transfer(next.id, next.http?.let { HttpDownloadAbort() })
                    activeTransfer = transfer
                    val cancelled = { stopping.get() || transfer.paused.get() || Thread.currentThread().isInterrupted }
                    var phase = "transfer"
                    var growingCompleted = false
                    try {
                        var job = TorrDownloadStore.update(this, next.id) {
                            if (it.state == "queued") it.copy(state = "running", error = "") else it
                        }
                        if (job.state != "running") continue
                        notificationUpdate(transfer, job)
                        if (job.offset < job.total) {
                            var end: TorrDownloadCheckpoint? = null
                            var retries = 0
                            val playbackRetry = TorrDownloadEngine.PlaybackRetry()
                            while (end == null && !cancelled()) {
                                try {
                                    phase = "storage"
                                    TorrDownloadDocuments.verifyPartial(this, job)
                                    val document = Uri.parse(job.document)
                                    if (job.offset >= SavedVideoIntegrity.HEADER_BYTES && !job.requiresHttpRestart) {
                                        requireVideoHeader(job, document) {
                                            if (cancelled()) throw kotlinx.coroutines.CancellationException()
                                        }
                                    }
                                    end = TorrSafTarget.open(contentResolver, document).use { target ->
                                        TorrDownloadPlaybackRate(this).use { rate ->
                                            val throttle = TorrDownloadThrottle(rate::current)
                                            phase = "transfer"
                                            val checkpoint = TorrDownloadCheckpoint(job.offset, job.etag)
                                            val save: (TorrDownloadCheckpoint) -> Unit = { saved ->
                                                job = TorrDownloadStore.update(this, job.id) { it.copy(offset = saved.offset, etag = saved.etag) }
                                            }
                                            val space = {
                                                val reserve = TorrDownloadSpace.read(this).toLong() * 1024 * 1024
                                                if (reserve == 0L) null else (target.availableBytes() - reserve).coerceAtLeast(0L)
                                            }
                                            val beforeWrite: (Int) -> Boolean = { count -> throttle.beforeWrite(count, cancelled) }
                                            val progress: (Long) -> Unit = { offset ->
                                                TorrDownloadLiveProgress.report(job.id, offset)
                                                notificationUpdate(transfer, job, offset)
                                            }
                                            val http = job.http
                                            val growing = http == null && job.total > 0 &&
                                                TorrPlayback.isVideo(job.finalName) && TorrGrowingVideo.bind(job, target.fileDescriptor)
                                            val readable: ((Long, Long) -> Unit)? = if (growing) { prefix, tail ->
                                                TorrGrowingVideo.update(job, prefix, tail)
                                            } else null
                                            if (http == null) TorrDownloadEngine.transfer(
                                                TorrDownloadSource(job.hash, job.index, job.total), target, checkpoint,
                                                cancelled = cancelled, saveCheckpoint = save, spaceAllowance = space,
                                                beforeWrite = beforeWrite, reportProgress = progress, readable = readable,
                                                playbackRetry = playbackRetry,
                                            ) else TorrDownloadEngine.transfer(
                                                http, job.total, target, checkpoint, cancelled = cancelled,
                                                saveCheckpoint = save, spaceAllowance = space, beforeWrite = beforeWrite,
                                                reportProgress = progress, abort = transfer.http,
                                                resetForRestart = if (job.requiresHttpRestart) ({
                                                    job = TorrDownloadStore.resetHttpCheckpoint(this, job.id)
                                                    TorrDownloadLiveProgress.clear(job.id)
                                                }) else null,
                                                session = if (http.sessionBound) TorrDownloadStore.sessionFor(job.id) else null,
                                            )
                                        }
                                    }
                                } catch (error: TorrSourceFailure) {
                                    if (cancelled()) break
                                    // A source that cannot resume must never silently start over.
                                    if (job.http?.let { it.expectedEtag == null } == true) throw error
                                    val delay = torrRetryDelay(error.reason, retries, TorrDownloadRetryOptions.read(this)) ?: throw error
                                    retries++
                                    job = TorrDownloadStore.update(this, job.id) { if (it.state == "running") it.copy(error = "retrying") else it }
                                    if (job.state != "running") break
                                    notificationUpdate(transfer, job)
                                    val wait = TorrDownloadRetryWait(this, cancelled)
                                    transfer.retryWait = wait
                                    val result = try {
                                        wait.await(delay) {
                                            job = TorrDownloadStore.update(this, job.id) {
                                                if (it.state == "running") it.copy(error = "waiting_network") else it
                                            }
                                            notificationUpdate(transfer, job)
                                        }
                                    } finally {
                                        transfer.retryWait = null
                                    }
                                    if (cancelled()) break
                                    if (result != TorrDownloadRetryWait.Result.READY || !TorrDownloadRetryOptions.read(this)) throw error
                                    job = TorrDownloadStore.update(this, job.id) { if (it.state == "running") it.copy(error = "") else it }
                                    if (job.state != "running") break
                                    notificationUpdate(transfer, job)
                                } catch (error: kotlinx.coroutines.CancellationException) {
                                    if (cancelled()) break else throw error
                                }
                            }
                            if (end?.offset != job.total || cancelled()) {
                                TorrDownloadStore.update(this, job.id) { if (it.state == "running") it.copy(state = "paused") else it }
                                continue
                            }
                        }
                        if (cancelled()) {
                            TorrDownloadStore.update(this, job.id) { if (it.state == "running") it.copy(state = "paused") else it }
                            continue
                        }
                        job = TorrDownloadStore.update(this, job.id) { if (it.state == "running") it.copy(state = "finalizing") else it }
                        if (job.state != "finalizing") continue
                        notificationUpdate(transfer, job)
                        phase = "finalize"
                        val result = finalizeDocument(job) {
                            if (cancelled() || PortableMaintenance.running) throw kotlinx.coroutines.CancellationException()
                        }
                        val completed = TorrDownloadStore.update(this, job.id) {
                            check(it == job) { "Download finalization changed" }
                            it.copy(document = result.toString(), state = "done", error = "", http = it.http?.copy(url = null))
                        }
                        growingCompleted = true
                        TorrGrowingVideo.finish(job.id, success = true)
                        runCatching { TorrFinalizeProof.clear(this, job.id) }
                        if (completed.prefetchMovieId.isNotBlank() && !cancelled()) {
                            // A failed follow-up must not turn a completed download into an error.
                            runCatching { TorrPrefetch.continueAfter(this, completed) }
                        }
                    } catch (error: Exception) {
                        val reason = when (error) {
                            is TorrDownloadLowSpace -> "space"
                            is TorrDownloadSpaceUnavailable -> "space_unavailable"
                            is TorrVideoIntegrityFailure -> "video_invalid"
                            is TorrSourceFailure -> error.reason
                            is TorrStorageFailure -> "storage"
                            else -> phase
                        }
                        if (reason == "source_auth" && next.http?.sessionBound == true) TorrDownloadStore.invalidateSession(next.id)
                        runCatching { TorrDownloadStore.update(this, next.id) { if (it.state == "running" || it.state == "finalizing") it.copy(state = "error", error = reason) else it } }
                    } finally {
                        if (!growingCompleted) TorrGrowingVideo.finish(next.id, success = false)
                        TorrDownloadLiveProgress.clear(next.id)
                        activeTransfer = null
                        transfer.clearPauseAction()
                        TorrDownloadStore.releaseWorker(next.id)
                    }
                }
            } finally {
                synchronized(this@TorrDownloadService) {
                    workerActive.set(false)
                    // A new request cannot race with the idle decision.
                    if (prepared && !stopping.get() && !isDestroyedForMaintenance && !PortableMaintenance.running && TorrDownloadStore.jobs.value.any { it.state == "queued" }) launchWorker()
                    else stopSelf()
                }
                if (isDestroyedForMaintenance) unregisterParticipantOnMain()
            }
        }
    }

    private fun finalizeDocument(job: TorrDownloadJob, checkActive: () -> Unit): Uri {
        val tree = Uri.parse(job.tree)
        val parentId = job.parentId.ifBlank { DocumentsContract.getTreeDocumentId(tree) }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE)
        var finalMatch: Uri? = null
        var finalName: String? = null
        var partialStillPresent = false
        val partialId = DocumentsContract.getDocumentId(Uri.parse(job.document))
        contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == ".gostex-${job.id}.partial") partialStillPresent = true
                if (cursor.getString(1).equals(job.finalName, ignoreCase = true)) {
                    if (cursor.isNull(2) || cursor.getLong(2) != job.total || finalMatch != null) throw IOException("Destination name conflict")
                    finalMatch = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                    finalName = cursor.getString(1)
                }
            }
        } ?: throw IOException("Cannot inspect destination")
        val completed = finalMatch
        val completedId = completed?.let(DocumentsContract::getDocumentId)
        val changedId = completedId != null && completedId != partialId
        if (completed != null) {
            val candidateId = checkNotNull(completedId)
            val originalIdMatches = torrCanRecoverRenamed(partialId, candidateId, job.finalName, finalName.orEmpty(), partialStillPresent)
            val canVerifyNewId = changedId && !partialStillPresent && finalName == job.finalName && TorrFinalizeProof.supports(job)
            if ((!originalIdMatches && !canVerifyNewId) || !portableSafDirectChild(contentResolver, tree, parentId, candidateId)) {
                throw IOException("Destination name conflict or interrupted rename")
            }
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            if (job.grouped && Build.VERSION.SDK_INT >= 29 &&
                !DocumentsContract.isChildDocument(contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, rootId), DocumentsContract.buildDocumentUriUsingTree(tree, parentId))) {
                throw IOException("Download directory moved outside selected folder")
            }
        } else TorrDownloadDocuments.verifyPartial(this, job)
        // A durable offset is not proof that the underlying USB document still
        // has those bytes. It may have been truncated or replaced while the app
        // was stopped. Never expose it as a completed ROM until checked again.
        if (job.offset != job.total) throw IOException("Incomplete checkpoint")
        val document = completed ?: Uri.parse(job.document)
        TorrSafTarget.open(contentResolver, document).use { target ->
            TorrDownloadEngine.verifyCompleted(job.total, target, TorrDownloadCheckpoint(job.offset, job.etag),
                requireStrongEtag = job.http?.let { it.expectedEtag != null } ?: true)
        }
        requireVideoHeader(job, document, checkActive)
        if (completed != null) {
            if (changedId && !TorrFinalizeProof.matches(this, job, completed, checkActive)) {
                throw IOException("Renamed download content does not match")
            }
            return completed
        }
        if (TorrFinalizeProof.supports(job)) TorrFinalizeProof.record(this, job, document, checkActive)
        checkActive()
        val renamed = DocumentsContract.renameDocument(contentResolver, document, job.finalName)
            ?: throw IOException("Cannot finish download")
        if (renamed.scheme != "content" || renamed.authority != tree.authority) {
            throw IOException("Unexpected renamed document")
        }
        val renamedId = DocumentsContract.getDocumentId(renamed)
        if (!portableSafDirectChild(contentResolver, tree, parentId, renamedId)) {
            throw IOException("Unexpected renamed document")
        }
        val result = DocumentsContract.buildDocumentUriUsingTree(tree, renamedId)
        val name = contentResolver.query(result, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        if (name != job.finalName) throw IOException("Provider renamed document unexpectedly")
        // A provider can return a different document. Check what the library will open.
        val size = contentResolver.openFileDescriptor(result, "r")?.use { descriptor ->
            val actual = descriptor.statSize
            if (descriptor.canDetectErrors()) descriptor.checkError()
            actual
        }
        if (size != job.total) throw TorrStorageFailure()
        requireVideoHeader(job, result)
        return result
    }

    private class TorrVideoIntegrityFailure : IOException("Invalid video header")

    private fun requireVideoHeader(job: TorrDownloadJob, document: Uri, checkActive: () -> Unit = {}) {
        when (SavedVideoIntegrity.check(contentResolver, document, job.finalName, job.total, checkActive)) {
            SavedVideoIntegrity.Result.VALID -> Unit
            SavedVideoIntegrity.Result.INVALID -> throw TorrVideoIntegrityFailure()
            SavedVideoIntegrity.Result.UNAVAILABLE -> throw TorrStorageFailure()
        }
    }

    private val openDownloads by lazy {
        val intent = Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_DOWNLOADS)
        PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notification(text: String, pause: Notification.Action? = null): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(getString(R.string.torr_download_channel))
        .setContentText(text).setOnlyAlertOnce(true).setOngoing(true)
        .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(openDownloads)
        .apply { if (pause != null) addAction(pause) }.build()
    private val notificationLock = Any()
    private data class NotificationProgress(val attempt: String, val percent: Int, val canPause: Boolean, val status: Int?)
    private var notifiedProgress: NotificationProgress? = null
    private fun notificationUpdate(transfer: Transfer, job: TorrDownloadJob, offset: Long = job.offset) {
        val percent = if (job.total > 0) (offset.coerceIn(0L, job.total).toDouble() / job.total * 100).toInt().coerceIn(0, 100) else 0
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        synchronized(notificationLock) {
            if (activeTransfer !== transfer) return
            val canPause = job.state == "running" && !transfer.paused.get()
            val status = if (canPause) when (job.error) {
                "waiting_network" -> R.string.torr_download_waiting_network
                "retrying" -> R.string.torr_download_retrying
                else -> null
            } else null
            val progress = NotificationProgress(transfer.pauseToken, percent, canPause, status)
            if (notifiedProgress == progress) return
            // Notification permission/system availability can change during a transfer.
            // A display failure must not abort a valid download or its durable checkpoint.
            val progressText = if (job.http?.sessionBound == true) "$percent%" else "${job.name} · $percent%"
            val body = if (status == null) progressText else "$progressText · ${getString(status)}"
            if (runCatching {
                    val pause = if (canPause) transfer.pauseAction() else null
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(body, pause))
                }.isSuccess) {
                notifiedProgress = progress
            }
        }
    }

    private fun stopTransfer() { stopping.set(true); activeTransfer?.pause() }
    override fun onTimeout(startId: Int, fgsType: Int) { stopTransfer(); stopSelf() }
    @Volatile private var isDestroyedForMaintenance = false
    private fun unregisterParticipantOnMain() {
        Handler(Looper.getMainLooper()).post {
            if (participantRegistered) { PortableMaintenance.unregisterParticipant(this); participantRegistered = false }
        }
    }
    override fun onDestroy() {
        isDestroyedForMaintenance = true
        stopTransfer()
        executor.shutdown()
        if (!workerActive.get()) unregisterParticipantOnMain()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "tv.orbit.shield.download.START"
        const val ACTION_PAUSE = "tv.orbit.shield.download.PAUSE"
        private const val ACTION_PAUSE_NOTIFICATION = "tv.orbit.shield.download.NOTIFICATION_PAUSE"
        private const val CHANNEL = "torr-downloads"
        private const val NOTIFICATION = 42371
    }
}
