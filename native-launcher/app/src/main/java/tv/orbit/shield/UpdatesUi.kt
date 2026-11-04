package tv.orbit.shield

import android.app.AlertDialog
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/** A persistent foreground action supplements the transient discovery notice without taking focus. */
@Composable internal fun UpdateAvailableAction(activity: MainActivity, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val release = activity.updates.release ?: return
    SettingRow(tr(R.string.updates_available_version, release.versionName),
        tr(if (activity.updates.ready) R.string.updates_ready else R.string.updates_open_settings), modifier, onClick = onOpen)
}

@Composable internal fun UpdateSettings(activity: MainActivity, initialFocus: FocusRequester) {
    val updates = activity.updates
    val options = updates.options
    val resources = LocalResources.current
    val urlFocus = remember { FocusRequester() }
    var editUrl by remember { mutableStateOf(false) }
    var returnToUrl by remember { mutableStateOf(false) }
    Text(tr(R.string.updates_current_version, updates.versionName), color = Color(0xFFCCFF00), fontSize = 14.sp)
    Text(tr(updates.status), color = Color(0xFFADADB0), fontSize = 13.sp)
    updates.release?.let { Text(tr(R.string.updates_available_version, it.versionName), color = Color.White, fontSize = 14.sp) }
    SettingRow(tr(R.string.updates_check_now), tr(if (updates.busy) R.string.updates_busy else R.string.updates_manual_hint),
        Modifier.focusRequester(initialFocus), onClick = { updates.check(true) })
    // Keep row identity stable as the update transitions through checking/download/ready.
    SettingRow(tr(if (updates.ready) R.string.updates_install else R.string.updates_download),
        tr(when { updates.busy -> R.string.updates_busy; updates.release == null -> R.string.updates_no_release; updates.ready -> R.string.updates_confirm_hint; else -> R.string.updates_download_hint }),
        onClick = { if (updates.ready) updates.installUpdate() else updates.downloadUpdate() })
    Text(tr(R.string.updates_confirmation_note), color = Color(0xFFADADB0), fontSize = 12.sp)
    SettingRow(tr(R.string.updates_checks), tr(if (options.checksEnabled) R.string.enabled else R.string.disabled),
        onClick = { UpdatePreferences.setBoolean(activity, "updates.checksEnabled", !options.checksEnabled) })
    SettingRow(tr(R.string.updates_auto_download), tr(if (options.autoDownload) R.string.enabled else R.string.disabled),
        onClick = { UpdatePreferences.setBoolean(activity, "updates.autoDownload", !options.autoDownload) })
    SettingRow(tr(R.string.updates_interval), tr(R.string.updates_interval_hours, options.checkIntervalHours), onClick = {
        val choices = listOf(1, 6, 24)
        UpdatePreferences.setInterval(activity, choices[(choices.indexOf(options.checkIntervalHours) + 1) % choices.size])
    })
    SettingRow(tr(R.string.updates_channel), if (options.feedUrl.isEmpty()) tr(R.string.updates_channel_unset) else options.feedUrl,
        Modifier.focusRequester(urlFocus), onClick = { editUrl = true })
    Text(tr(R.string.updates_channel_hint), color = Color(0xFFADADB0), fontSize = 12.sp)
    SettingRow(tr(R.string.updates_debug), tr(if (options.debugEnabled) R.string.enabled else R.string.disabled),
        onClick = { UpdatePreferences.setBoolean(activity, "updates.debugEnabled", !options.debugEnabled) })
    Text(tr(R.string.updates_debug_hint), color = Color(0xFFADADB0), fontSize = 12.sp)
    SettingRow(tr(R.string.updates_export), onClick = { activity.chooseDiagnosticExport() })
    SettingRow(tr(R.string.updates_clear_debug), onClick = { updates.clearDiagnostics() })
    LaunchedEffect(returnToUrl) {
        if (returnToUrl) { withFrameNanos { }; runCatching { urlFocus.requestFocus() }; returnToUrl = false }
    }
    if (editUrl) DisposableEffect(activity, resources) {
        val field = EditText(activity).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setText(options.feedUrl); setSelection(text.length)
            val inset = (20 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset / 2, inset, inset / 2)
        }
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.updates_channel)
            .setMessage(R.string.updates_channel_hint).setView(field)
            .setPositiveButton(R.string.updates_save_channel, null).setNegativeButton(R.string.cancel, null).create()
        dialog.setOnDismissListener { editUrl = false; returnToUrl = true }
        dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (UpdatePreferences.setFeed(activity, field.text.toString())) dialog.dismiss()
            else { field.error = resources.getString(R.string.updates_channel_invalid); field.requestFocus() }
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); true } else false
        }
        field.requestFocus()
        field.post { if (dialog.isShowing && field.isAttachedToWindow) activity.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) }
        onDispose { dialog.setOnDismissListener(null); dialog.dismiss() }
    }
}
