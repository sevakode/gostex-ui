package tv.orbit.shield

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class GameArchiveController(
    private val activity: MainActivity,
    private val launchPicker: (String, String?, () -> Unit) -> Boolean,
    private val addFolder: (String, Uri) -> Unit,
) {
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
    var picking by mutableStateOf(false)
        private set

    private val sourcePicker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        picking = false
        if (uri != null) inspect(platform, uri)
    }
    private val destinationPicker = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        picking = false
        val preview = plan
        if (uri != null && preview != null) extract(preview, uri)
    }

    fun chooseSource(platformId: String) {
        if (busy || picking || PortableMaintenance.running) return
        platform = platformId
        clear()
        picking = launchPicker(Intent.ACTION_OPEN_DOCUMENT, "*/*") {
            sourcePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
    }

    fun inspect(platformId: String, source: Uri) {
        if (busy || PortableMaintenance.running) return
        platform = platformId
        clear()
        busy = true
        operation = activity.lifecycleScope.launch {
            try {
                plan = GameArchiveStorage.inspect(activity, source)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                activity.notice = activity.getString(R.string.game_archive_invalid)
            } finally {
                busy = false
                operation = null
            }
        }
    }

    fun chooseDestination() {
        if (busy || picking || plan == null) return
        picking = launchPicker(Intent.ACTION_OPEN_DOCUMENT_TREE, null) { destinationPicker.launch(null) }
    }

    private fun extract(preview: GameArchiveStorage.Plan, destination: Uri) {
        if (busy || PortableMaintenance.running) return
        busy = true
        progress = 0
        operation = activity.lifecycleScope.launch {
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
            } finally {
                busy = false
                operation = null
            }
        }
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
    DisposableEffect(activity, plan, busy, finished) {
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
