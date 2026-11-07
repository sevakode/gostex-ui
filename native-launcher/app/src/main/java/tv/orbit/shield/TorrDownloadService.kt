package tv.orbit.shield

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One active transfer at a time; the service exists only for explicit downloads. */
internal class TorrDownloadService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val workerActive = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    @Volatile private var activeId: String? = null
    private var storeAvailable = false
    private var participantRegistered = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.torr_download_channel), NotificationManager.IMPORTANCE_LOW))
        if (PortableRestoreStartup.ready) {
            participantRegistered = PortableMaintenance.registerParticipant(this) {
                stopping.set(true)
                if (participantRegistered) runCatching { TorrDownloadStore.pauseAll(this) }
                stopSelf()
            }
            if (participantRegistered) storeAvailable = runCatching { TorrDownloadStore.recover(this) }.isSuccess
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION, notification(getString(R.string.torr_download_preparing)))
        if (!storeAvailable) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_PAUSE) {
            val id = intent.getStringExtra("id")
            if (id != null) {
                val paused = runCatching { TorrDownloadStore.update(this, id) { if (it.state == "running" || it.state == "queued") it.copy(state = "paused") else it } }.getOrNull()
                if (id == activeId && paused?.state == "paused") stopping.set(true)
            }
        }
        launchWorker()
        return START_NOT_STICKY
    }

    @Synchronized private fun launchWorker() {
        if (!workerActive.compareAndSet(false, true)) return
        executor.execute {
            try {
                while (true) {
                    if (isDestroyedForMaintenance || PortableMaintenance.running) break
                    val next = TorrDownloadStore.jobs.value.firstOrNull { it.state == "queued" } ?: break
                    if (!TorrDownloadStore.claimWorker(this, next.id)) {
                        // A prior service instance may still be closing its stream after Pause.
                        // Keep this request alive without spinning until that worker releases it.
                        if (TorrDownloadStore.jobs.value.any { it.id == next.id && it.state == "queued" }) {
                            try { Thread.sleep(200) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
                        }
                        continue
                    }
                    activeId = next.id
                    stopping.set(false)
                    var phase = "transfer"
                    try {
                        var job = TorrDownloadStore.update(this, next.id) {
                            if (it.state == "queued") it.copy(state = "running", error = "") else it
                        }
                        if (job.state != "running") continue
                        notificationUpdate(job)
                        if (job.offset < job.total) {
                            phase = "storage"
                            TorrDownloadDocuments.verifyPartial(this, job)
                            TorrSafTarget.open(contentResolver, Uri.parse(job.document)).use { target ->
                                val throttle = TorrDownloadThrottle({ TorrDownloadWriteRate.read(this) })
                                phase = "transfer"
                                var end: TorrDownloadCheckpoint? = null
                                var retries = 0
                                while (end == null && !stopping.get()) {
                                    try {
                                        end = TorrDownloadEngine.transfer(
                                            TorrDownloadSource(job.hash, job.index, job.total), target,
                                            TorrDownloadCheckpoint(job.offset, job.etag),
                                            cancelled = { stopping.get() || Thread.currentThread().isInterrupted },
                                            saveCheckpoint = { checkpoint ->
                                                job = TorrDownloadStore.update(this, job.id) { it.copy(offset = checkpoint.offset, etag = checkpoint.etag) }
                                                notificationUpdate(job)
                                            },
                                            spaceAllowance = {
                                                val reserve = TorrDownloadSpace.read(this).toLong() * 1024 * 1024
                                                if (reserve == 0L) null else (target.availableBytes() - reserve).coerceAtLeast(0L)
                                            },
                                            beforeWrite = { count -> throttle.beforeWrite(count) { stopping.get() } },
                                        )
                                    } catch (error: TorrSourceFailure) {
                                        val delay = torrRetryDelay(error.reason, retries, TorrDownloadRetryOptions.read(this)) ?: throw error
                                        retries++
                                        job = TorrDownloadStore.update(this, job.id) { if (it.state == "running") it.copy(error = "retrying") else it }
                                        if (job.state != "running" || !waitForRetry(delay)) break
                                        if (!TorrDownloadRetryOptions.read(this)) throw error
                                        job = TorrDownloadStore.update(this, job.id) { if (it.state == "running") it.copy(error = "") else it }
                                        if (job.state != "running") break
                                    }
                                }
                                if (end?.offset != job.total || stopping.get()) {
                                    TorrDownloadStore.update(this, job.id) { if (it.state == "running") it.copy(state = "paused") else it }
                                    continue
                                }
                            }
                        }
                        if (stopping.get()) {
                            TorrDownloadStore.update(this, job.id) { if (it.state == "running") it.copy(state = "paused") else it }
                            continue
                        }
                        job = TorrDownloadStore.update(this, job.id) { if (it.state == "running") it.copy(state = "finalizing") else it }
                        if (job.state != "finalizing") continue
                        phase = "finalize"
                        val result = finalizeDocument(job)
                        TorrDownloadStore.update(this, job.id) { it.copy(document = result.toString(), state = "done", error = "") }
                    } catch (error: Exception) {
                        val reason = when (error) {
                            is TorrDownloadLowSpace -> "space"
                            is TorrDownloadSpaceUnavailable -> "space_unavailable"
                            is TorrSourceFailure -> error.reason
                            is TorrStorageFailure -> "storage"
                            else -> phase
                        }
                        runCatching { TorrDownloadStore.update(this, next.id) { if (it.state == "running" || it.state == "finalizing") it.copy(state = "error", error = reason) else it } }
                    } finally {
                        activeId = null
                        TorrDownloadStore.releaseWorker(next.id)
                    }
                }
            } finally {
                synchronized(this@TorrDownloadService) {
                    workerActive.set(false)
                    // A new request cannot race with the idle decision.
                    if (!isDestroyedForMaintenance && !PortableMaintenance.running && TorrDownloadStore.jobs.value.any { it.state == "queued" }) launchWorker()
                    else stopSelf()
                }
                if (isDestroyedForMaintenance) unregisterParticipantOnMain()
            }
        }
    }

    private fun waitForRetry(delayMs: Long): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + delayMs
        while (!stopping.get() && !Thread.currentThread().isInterrupted) {
            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
            if (remaining <= 0) return true
            try { Thread.sleep(minOf(remaining, 200L)) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
        }
        return false
    }

    private fun finalizeDocument(job: TorrDownloadJob): Uri {
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
        if (completed != null) {
            val completedId = DocumentsContract.getDocumentId(completed)
            if (!torrCanRecoverRenamed(partialId, completedId, job.finalName, finalName.orEmpty(), partialStillPresent) ||
                !portableSafDirectChild(contentResolver, tree, parentId, completedId)) {
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
        TorrSafTarget.open(contentResolver, completed ?: Uri.parse(job.document)).use { target ->
            TorrDownloadEngine.transfer(
                TorrDownloadSource(job.hash, job.index, job.total), target,
                TorrDownloadCheckpoint(job.offset, job.etag), saveCheckpoint = { throw IOException("Incomplete checkpoint") },
            )
        }
        if (completed != null) return completed
        val result = DocumentsContract.renameDocument(contentResolver, Uri.parse(job.document), job.finalName)
            ?: throw IOException("Cannot finish download")
        if (result.authority != tree.authority || !portableSafDirectChild(contentResolver, tree, parentId, DocumentsContract.getDocumentId(result))) {
            throw IOException("Unexpected renamed document")
        }
        val name = contentResolver.query(result, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        if (name != job.finalName) throw IOException("Provider renamed document unexpectedly")
        return result
    }

    private fun notification(text: String): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(getString(R.string.torr_download_channel))
        .setContentText(text).setOnlyAlertOnce(true).setOngoing(true).build()
    private fun notificationUpdate(job: TorrDownloadJob) {
        val percent = if (job.total > 0) (job.offset.toDouble() / job.total * 100).toInt().coerceIn(0, 100) else 0
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification("${job.name} · $percent%"))
    }

    override fun onTimeout(startId: Int, fgsType: Int) { stopping.set(true); stopSelf() }
    @Volatile private var isDestroyedForMaintenance = false
    private fun unregisterParticipantOnMain() {
        Handler(Looper.getMainLooper()).post {
            if (participantRegistered) { PortableMaintenance.unregisterParticipant(this); participantRegistered = false }
        }
    }
    override fun onDestroy() {
        isDestroyedForMaintenance = true
        stopping.set(true)
        executor.shutdown()
        if (!workerActive.get()) unregisterParticipantOnMain()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "tv.orbit.shield.download.START"
        const val ACTION_PAUSE = "tv.orbit.shield.download.PAUSE"
        private const val CHANNEL = "torr-downloads"
        private const val NOTIFICATION = 42371
    }
}
