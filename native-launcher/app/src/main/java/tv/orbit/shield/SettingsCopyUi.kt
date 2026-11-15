package tv.orbit.shield

import android.app.AlertDialog
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal class SettingsCopyController(
    private val activity: MainActivity,
    private val launchPicker: (String, String?, () -> Unit) -> Boolean,
) {
    var preview by mutableStateOf<PortableSettingsTransfer.Preview?>(null)
        private set
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf<Int?>(null)
    val status: Int? get() = if (busy) R.string.settings_copy_busy else message
    private var pageRevision = 0

    private val exportPicker = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) start(R.string.settings_copy_export_failed) {
            PortableSettingsTransfer.export(activity, uri)
            message = R.string.settings_copy_exported
        }
    }
    private val importPicker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) start(R.string.settings_copy_import_failed) {
            val revision = pageRevision
            val candidate = PortableSettingsTransfer.preview(activity, uri)
            if (pageRevision == revision) {
                if (candidate.count == 0) message = R.string.settings_copy_no_changes
                else preview = candidate
            }
        }
    }

    fun chooseExport() {
        if (busy) return
        launchPicker(Intent.ACTION_CREATE_DOCUMENT, "application/json") { exportPicker.launch("gostex-settings.json") }
    }

    fun chooseImport() {
        if (busy) return
        launchPicker(Intent.ACTION_OPEN_DOCUMENT, "*/*") {
            importPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
    }

    fun apply(candidate: PortableSettingsTransfer.Preview) {
        if (preview !== candidate) return
        preview = null
        start(R.string.settings_copy_apply_failed) {
            val applied = PortableSettingsTransfer.apply(activity, candidate)
            message = if (applied) R.string.settings_copy_applied else R.string.settings_copy_stale
            if (applied && candidate.needsRecreation) activity.recreate()
        }
    }

    fun dismiss(candidate: PortableSettingsTransfer.Preview) {
        if (preview === candidate) preview = null
    }

    fun leave() {
        pageRevision++
        preview = null
    }

    private fun start(failure: Int, operation: suspend () -> Unit) {
        if (busy || PortableMaintenance.running) return
        busy = true
        message = null
        activity.lifecycleScope.launch {
            try {
                operation()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message = failure
            } finally {
                busy = false
            }
        }
    }
}

@Composable
internal fun SettingsCopySettings(activity: MainActivity, exportFocus: Modifier, importFocus: Modifier) {
    val controller = activity.settingsCopy
    DisposableEffect(controller) { onDispose { controller.leave() } }
    SettingRow(tr(R.string.settings_copy_export), modifier = exportFocus, onClick = controller::chooseExport)
    SettingRow(tr(R.string.settings_copy_import), modifier = importFocus, onClick = controller::chooseImport)
    controller.status?.let { Text(tr(it), color = Color(0xFFADADB0), fontSize = 13.sp) }
    controller.preview?.let { review ->
        DisposableEffect(activity, review) {
            val dialog = AlertDialog.Builder(activity)
                .setTitle(R.string.settings_copy_import)
                .setMessage(activity.resources.getQuantityString(R.plurals.settings_copy_confirm, review.count, review.count))
                .setPositiveButton(R.string.settings_copy_apply) { _, _ -> controller.apply(review) }
                .setNegativeButton(R.string.cancel, null)
                .create()
            dialog.setOnDismissListener { controller.dismiss(review) }
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
            onDispose {
                dialog.setOnDismissListener(null)
                dialog.dismiss()
            }
        }
    }
}
