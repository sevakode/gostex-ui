package tv.orbit.shield

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class GameArchiveController(
    private val activity: MainActivity,
    private val launchPicker: (String, String?, () -> Unit) -> Boolean,
    private val addFolder: (String, Uri) -> Boolean,
) {
    private enum class Picker { SOURCE, DESTINATION, EXPIRED_SOURCE, EXPIRED_DESTINATION }
    var plan by mutableStateOf<GameArchiveStorage.Plan?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var progress by mutableLongStateOf(0L)
        private set
    var result by mutableStateOf<Uri?>(null)
        private set
    var recovery by mutableStateOf<GameArchiveRecovery.Job?>(null)
        private set
    var recoveryOpen by mutableStateOf(false)
        private set
    var discardFailed by mutableStateOf(false)
        private set
    private var activeRecovery by mutableStateOf<GameArchiveRecovery.Job?>(null)
    val extracting: Boolean get() = plan != null || activeRecovery != null
    val totalBytes: Long? get() = plan?.totalBytes ?: activeRecovery?.totalBytes
    private var expectedImport: Uri? = null
    private var platform = ""
    private var operation: Job? = null
    private var pendingPicker by mutableStateOf<Picker?>(null)
    val picking: Boolean get() = pendingPicker != null
    var dialogRevision by mutableIntStateOf(0)
        private set

    private val sourcePicker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val request = pendingPicker
        if (request == Picker.SOURCE || request == Picker.EXPIRED_SOURCE) {
            pendingPicker = null
            if (request == Picker.EXPIRED_SOURCE || PortableMaintenance.running) expiredNotice()
            else if (uri != null) inspect(platform, uri)
        }
    }
    private val destinationPicker = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val request = pendingPicker
        if (request == Picker.DESTINATION || request == Picker.EXPIRED_DESTINATION) {
            pendingPicker = null
            val preview = plan
            if (request == Picker.EXPIRED_DESTINATION || PortableMaintenance.running || preview == null) expiredNotice()
            else if (uri != null) extract(preview, uri)
        }
    }

    fun chooseSource(platformId: String) {
        if (busy || picking || PortableMaintenance.running) return
        if (openRecovery()) return
        if (PlatformRegistry.find(platformId) == null) {
            activity.notice = activity.getString(R.string.platform_unknown)
            return
        }
        platform = platformId
        clear()
        pendingPicker = Picker.SOURCE
        val started = launchPicker(Intent.ACTION_OPEN_DOCUMENT, "*/*") {
            sourcePicker.launch(arrayOf(
                "application/zip", "application/x-7z-compressed", "application/vnd.rar", "application/x-rar-compressed",
                "application/octet-stream", "*/*",
            ))
        }
        if (!started) pendingPicker = null
    }

    fun inspect(platformId: String, source: Uri) {
        if (busy || picking || PortableMaintenance.running) return
        if (openRecovery()) return
        if (PlatformRegistry.find(platformId) == null) {
            activity.notice = activity.getString(R.string.platform_unknown)
            return
        }
        platform = platformId
        clear()
        startOperation {
            try {
                plan = GameArchiveStorage.inspect(activity, source)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: TorrDownloadLowSpace) {
                activity.notice = activity.getString(R.string.torr_download_low_space)
            } catch (_: Exception) {
                activity.notice = activity.getString(R.string.game_archive_invalid)
            }
        }
    }

    fun chooseDestination() {
        if (busy || picking || plan == null || PortableMaintenance.running) return
        pendingPicker = Picker.DESTINATION
        val started = launchPicker(Intent.ACTION_OPEN_DOCUMENT_TREE, null) { destinationPicker.launch(null) }
        if (!started) {
            pendingPicker = null
            // AlertDialog dismisses its positive button even when no picker can open.
            dialogRevision++
        }
    }

    private fun extract(preview: GameArchiveStorage.Plan, destination: Uri) {
        if (busy || PortableMaintenance.running) return
        runExtraction { report -> GameArchiveStorage.extract(activity, preview, destination, platform, report) }
    }

    fun openRecovery(): Boolean {
        if (busy || picking || PortableMaintenance.running || recovery == null) return false
        clear()
        discardFailed = false
        recoveryOpen = true
        dialogRevision++
        return true
    }

    fun resumeRecovery() {
        val job = recovery ?: return
        if (busy || picking || PortableMaintenance.running) return
        recoveryOpen = false
        activeRecovery = job
        platform = job.platform
        runExtraction { report -> GameArchiveStorage.resume(activity, job, report) }
    }

    fun discardRecovery() {
        val job = recovery ?: return
        if (busy || picking || PortableMaintenance.running) return
        recoveryOpen = false
        startOperation {
            try {
                GameArchiveStorage.discard(activity, job)
                readRecovery()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                readRecovery()
                recoveryOpen = recovery != null
                discardFailed = recoveryOpen
            }
        }
    }

    fun forgetRecovery() {
        val job = recovery ?: return
        if (busy || picking || PortableMaintenance.running || !discardFailed) return
        recoveryOpen = false
        startOperation {
            try {
                withContext(Dispatchers.IO) { GameArchiveRecovery.clear(activity, job) }
                readRecovery()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                activity.notice = activity.getString(R.string.game_archive_recovery_unavailable)
            }
        }
    }

    private fun runExtraction(prepare: suspend ((Long) -> Unit) -> Uri) {
        progress = 0
        startOperation {
            try {
                var lastProgressAt = 0L
                result = prepare { bytes ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastProgressAt >= 250 || bytes == totalBytes) {
                        progress = bytes
                        lastProgressAt = now
                    }
                }
            } catch (cancelled: CancellationException) {
                clear()
                throw cancelled
            } catch (_: Exception) {
                clear()
                activity.notice = activity.getString(R.string.game_archive_failed)
            } finally {
                withContext(NonCancellable) { readRecovery() }
            }
        }
    }

    private suspend fun readRecovery() {
        try {
            recovery = withContext(Dispatchers.IO) { GameArchiveRecovery.read(activity) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            activity.notice = activity.getString(R.string.game_archive_recovery_unavailable)
        }
    }

    private fun startOperation(action: suspend () -> Unit) {
        busy = true
        // Enter cleanup immediately, including when the lifecycle is already cancelled.
        // The participant belongs to this exact worker, even after Activity recreation.
        operation = activity.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val worker = checkNotNull(currentCoroutineContext()[Job])
            val participant = Any()
            var registered = false
            try {
                registered = PortableMaintenance.registerParticipant(participant) { worker.cancel() }
                currentCoroutineContext().ensureActive()
                if (registered) action()
            } finally {
                if (registered) PortableMaintenance.unregisterParticipant(participant)
                busy = false
                operation = null
            }
        }
    }

    fun save(state: Bundle) {
        state.putString("gameArchivePlatform", platform)
        state.putString("gameArchivePicker", pendingPicker?.name)
        state.putBoolean("gameArchiveHadWork", busy || plan != null || result != null)
        state.putString("gameArchiveExpectedImport", expectedImport?.toString())
    }

    fun restore(state: Bundle?) {
        expectedImport = state?.getString("gameArchiveExpectedImport")?.let(Uri::parse)
        platform = state?.getString("gameArchivePlatform").orEmpty()
            .takeIf { PlatformRegistry.find(it) != null }.orEmpty()
        pendingPicker = when (state?.getString("gameArchivePicker")) {
            Picker.SOURCE.name -> if (platform.isNotEmpty()) Picker.SOURCE else Picker.EXPIRED_SOURCE
            Picker.EXPIRED_SOURCE.name -> Picker.EXPIRED_SOURCE
            Picker.DESTINATION.name, Picker.EXPIRED_DESTINATION.name -> Picker.EXPIRED_DESTINATION
            else -> null
        }
        // A preview is process-local. Never start extraction from a stale folder result.
        activity.lifecycleScope.launch {
            readRecovery()
            if (recovery == null && state?.getBoolean("gameArchiveHadWork") == true &&
                !busy && !picking && plan == null && result == null) expiredNotice()
        }
    }

    suspend fun cancelAndJoin() {
        val worker = operation
        worker?.cancel()
        withContext(NonCancellable) { worker?.join() }
        pendingPicker = when (pendingPicker) {
            Picker.SOURCE -> Picker.EXPIRED_SOURCE
            Picker.DESTINATION -> Picker.EXPIRED_DESTINATION
            else -> pendingPicker
        }
        clear()
    }

    private fun expiredNotice() {
        clear()
        activity.notice = activity.getString(R.string.game_archive_expired)
    }

    fun addResult() {
        val folder = result ?: return
        if (addFolder(platform, folder)) {
            expectedImport = folder
            clear()
        } else dialogRevision++
    }

    fun folderImported(tree: Uri?) {
        val expected = expectedImport.also { expectedImport = null } ?: return
        if (tree == null || busy || PortableMaintenance.running) return
        val folderId = runCatching { DocumentsContract.getDocumentId(expected) }.getOrNull() ?: return
        if (tree.authority != expected.authority ||
            runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() != folderId) return
        startOperation {
            try {
                withContext(Dispatchers.IO) {
                    val job = GameArchiveRecovery.read(activity) ?: return@withContext
                    if (job.phase == GameArchiveRecovery.Phase.READY && job.finalId == folderId &&
                        DocumentsContract.buildDocumentUriUsingTree(job.tree, folderId) == expected)
                        GameArchiveRecovery.clear(activity, job)
                }
                readRecovery()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                activity.notice = activity.getString(R.string.game_archive_recovery_unavailable)
            }
        }
    }

    fun cancel() {
        recoveryOpen = false
        operation?.cancel()
        if (!busy) clear()
    }

    fun stop() {
        if (busy) operation?.cancel()
    }

    private fun clear() {
        plan = null
        result = null
        activeRecovery = null
        progress = 0
    }
}

internal fun GameArchiveStorage.Plan.previewMessage(activity: MainActivity): String {
    val formatLabel = when (archive.format) {
        GameArchiveFormat.ZIP -> R.string.game_archive_format_zip
        GameArchiveFormat.SEVEN_Z -> R.string.game_archive_format_seven_z
        GameArchiveFormat.RAR -> R.string.game_archive_format_rar
    }
    return activity.getString(R.string.game_archive_confirm, activity.getString(formatLabel), files,
        Formatter.formatFileSize(activity, totalBytes))
}

@Composable
internal fun GameArchiveHost(activity: MainActivity) {
    val archive = activity.gameArchive
    val plan = archive.plan
    val finished = archive.result
    val busy = archive.busy
    val total = archive.totalBytes
    val progress = archive.progress
    val recovery = archive.recovery?.takeIf { archive.recoveryOpen }
    var confirmDiscard by remember(recovery) { mutableStateOf(false) }
    if (archive.picking) return
    if (!busy && plan == null && finished == null && recovery == null) return
    // Keep one progress dialog across chunk updates.
    val progressBar = remember(activity, busy) {
        ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
    }
    SideEffect {
        progressBar.isIndeterminate = total == null || total <= 0
        if (total != null && total > 0) progressBar.progress =
            (progress.toDouble() / total * 1000).toInt().coerceIn(0, 1000)
    }
    DisposableEffect(activity, plan, busy, finished, recovery, confirmDiscard, archive.discardFailed, archive.dialogRevision) {
        val title = plan?.title ?: archive.recovery?.title ?: activity.getString(R.string.game_archive_title)
        val builder = AlertDialog.Builder(activity).setTitle(title)
        when {
            busy -> builder.setMessage(if (archive.extracting) R.string.game_archive_working else R.string.game_archive_inspecting)
                .setView(progressBar).setNegativeButton(if (archive.extracting) R.string.game_archive_pause else R.string.cancel) { _, _ -> archive.cancel() }
            finished != null -> builder.setMessage(R.string.game_archive_ready)
                .setPositiveButton(R.string.import_game_folder) { _, _ -> archive.addResult() }
                .setNegativeButton(R.string.close) { _, _ -> archive.cancel() }
            recovery != null && archive.discardFailed -> builder.setMessage(R.string.game_archive_discard_failed)
                .setPositiveButton(R.string.game_archive_keep_files) { _, _ -> archive.forgetRecovery() }
                .setNegativeButton(R.string.close) { _, _ -> archive.cancel() }
            recovery != null && confirmDiscard -> builder.setMessage(R.string.game_archive_discard_confirm)
                .setPositiveButton(R.string.game_archive_discard) { _, _ ->
                    confirmDiscard = false
                    archive.discardRecovery()
                }
                .setNegativeButton(R.string.cancel) { _, _ -> confirmDiscard = false }
            recovery != null && recovery.phase == GameArchiveRecovery.Phase.READY ->
                builder.setMessage(R.string.game_archive_recover_ready)
                    .setPositiveButton(R.string.import_game_folder) { _, _ -> archive.resumeRecovery() }
                    .setNeutralButton(R.string.game_archive_dismiss_ready) { _, _ -> archive.discardRecovery() }
                    .setNegativeButton(R.string.close) { _, _ -> archive.cancel() }
            recovery != null -> builder.setMessage(R.string.game_archive_recover)
                .setPositiveButton(R.string.game_archive_resume) { _, _ -> archive.resumeRecovery() }
                .setNeutralButton(R.string.game_archive_discard) { _, _ -> confirmDiscard = true }
                .setNegativeButton(R.string.close) { _, _ -> archive.cancel() }
            plan != null -> builder.setMessage(plan.previewMessage(activity))
                .setPositiveButton(R.string.game_archive_destination) { _, _ -> archive.chooseDestination() }
                .setNegativeButton(R.string.cancel) { _, _ -> archive.cancel() }
        }
        val dialog = builder.create()
        dialog.setOnCancelListener { if (confirmDiscard) confirmDiscard = false else archive.cancel() }
        dialog.show()
        onDispose { dialog.setOnCancelListener(null); dialog.dismiss() }
    }
}
