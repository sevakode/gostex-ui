package tv.orbit.shield

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class GameArchiveController(
    private val activity: MainActivity,
    private val launchPicker: (String, String?, () -> Unit) -> Boolean,
    private val addFolder: (String, Uri) -> Unit,
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
        if (PlatformRegistry.find(platformId) == null) {
            activity.notice = activity.getString(R.string.platform_unknown)
            return
        }
        platform = platformId
        clear()
        pendingPicker = Picker.SOURCE
        val started = launchPicker(Intent.ACTION_OPEN_DOCUMENT, "*/*") {
            sourcePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        if (!started) pendingPicker = null
    }

    fun inspect(platformId: String, source: Uri) {
        if (busy || picking || PortableMaintenance.running) return
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
        progress = 0
        startOperation {
            try {
                var lastProgressAt = 0L
                result = GameArchiveStorage.extract(activity, preview, destination) { bytes ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastProgressAt >= 250 || bytes == preview.totalBytes) {
                        progress = bytes
                        lastProgressAt = now
                    }
                }
            } catch (cancelled: CancellationException) {
                clear()
                activity.notice = activity.getString(R.string.game_archive_cancelled)
                throw cancelled
            } catch (_: Exception) {
                clear()
                activity.notice = activity.getString(R.string.game_archive_failed)
            }
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
    }

    fun restore(state: Bundle?) {
        if (state == null) return
        platform = state.getString("gameArchivePlatform").orEmpty()
            .takeIf { PlatformRegistry.find(it) != null }.orEmpty()
        pendingPicker = when (state.getString("gameArchivePicker")) {
            Picker.SOURCE.name -> if (platform.isNotEmpty()) Picker.SOURCE else Picker.EXPIRED_SOURCE
            Picker.EXPIRED_SOURCE.name -> Picker.EXPIRED_SOURCE
            Picker.DESTINATION.name, Picker.EXPIRED_DESTINATION.name -> Picker.EXPIRED_DESTINATION
            else -> null
        }
        // A preview is process-local. Never start extraction from a stale folder result.
        if (!picking && state.getBoolean("gameArchiveHadWork")) expiredNotice()
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
        clear()
        addFolder(platform, folder)
    }

    fun cancel() {
        operation?.cancel()
        if (!busy) clear()
    }

    fun stop() {
        if (busy) operation?.cancel()
    }

    private fun clear() {
        plan = null
        result = null
        progress = 0
    }
}

@Composable
internal fun GameArchiveHost(activity: MainActivity) {
    val archive = activity.gameArchive
    val plan = archive.plan
    val finished = archive.result
    val busy = archive.busy
    if (archive.picking) return
    if (!busy && plan == null && finished == null) return
    // Keep one progress dialog across chunk updates.
    val progressBar = remember(activity, busy) {
        ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
    }
    SideEffect {
        progressBar.isIndeterminate = plan == null || plan.totalBytes <= 0
        if (plan != null && plan.totalBytes > 0) progressBar.progress =
            (archive.progress.toDouble() / plan.totalBytes * 1000).toInt().coerceIn(0, 1000)
    }
    DisposableEffect(activity, plan, busy, finished, archive.dialogRevision) {
        val builder = AlertDialog.Builder(activity).setTitle(plan?.title ?: activity.getString(R.string.game_archive_title))
        when {
            busy -> builder.setMessage(if (plan == null) R.string.game_archive_inspecting else R.string.game_archive_working)
                .setView(progressBar).setNegativeButton(R.string.cancel) { _, _ -> archive.cancel() }
            finished != null -> builder.setMessage(R.string.game_archive_ready)
                .setPositiveButton(R.string.import_game_folder) { _, _ -> archive.addResult() }
                .setNegativeButton(R.string.close) { _, _ -> archive.cancel() }
            plan != null -> builder.setMessage(activity.getString(R.string.game_archive_confirm, plan.files,
                Formatter.formatFileSize(activity, plan.totalBytes)))
                .setPositiveButton(R.string.game_archive_destination) { _, _ -> archive.chooseDestination() }
                .setNegativeButton(R.string.cancel) { _, _ -> archive.cancel() }
        }
        val dialog = builder.create()
        dialog.setOnCancelListener { archive.cancel() }
        dialog.show()
        onDispose { dialog.setOnCancelListener(null); dialog.dismiss() }
    }
}
