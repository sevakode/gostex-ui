package tv.orbit.shield

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private val DownloadFieldText = TextStyle(color = Color.White, fontSize = 15.sp)

@Composable
internal fun HttpDownloadUi(activity: MainActivity, folderLabel: String?, initialAddress: String, onSubmit: () -> Unit, onDismiss: () -> Unit) {
    // Keep the link and its cleartext consent in memory only, scoped to this dialog.
    var address by remember { mutableStateOf(initialAddress) }
    var check by remember { mutableStateOf<Job?>(null) }
    var admission by remember { mutableStateOf<HttpDownloadAdmissionResult?>(null) }
    var name by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<HttpDownloadAdmissionError?>(null) }
    var destinationError by remember { mutableStateOf("") }
    val fieldFocus = remember { FocusRequester() }
    val primaryFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    fun close() {
        check?.cancel()
        address = ""
        admission = null
        name = ""
        onDismiss()
    }
    val closeOnStop by rememberUpdatedState(::close)

    DisposableEffect(activity) {
        // Going Home should not leave a network check or a private draft running.
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) closeOnStop() }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    fun verify(allowCleartext: Boolean = false) {
        if (check != null || address.isBlank() || PortableMaintenance.running) return
        keyboard?.hide()
        failure = null
        val requested = address
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = HttpDownloadAdmission.probeCancellable(requested, allowCleartext)
                ensureActive()
                name = suggestHttpDownloadName(result)
                address = ""
                admission = result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: HttpDownloadAdmissionException) { failure = error.reason }
            catch (_: Exception) { failure = HttpDownloadAdmissionError.NETWORK }
            finally { check = null }
        }
        check = task
        task.start()
    }
    fun finishTyping() {
        keyboard?.hide()
        runCatching { primaryFocus.requestFocus() }
    }
    fun download(result: HttpDownloadAdmissionResult, chooseFolder: Boolean) {
        val validated = try { validateHttpDownloadName(name) }
        catch (_: HttpDownloadInvalidName) {
            destinationError = activity.getString(R.string.http_download_invalid_name)
            return
        }
        keyboard?.hide()
        if (activity.chooseHttpDestination(result, validated, chooseFolder)) {
            onSubmit()
            close()
        } else destinationError = activity.torrDownloadMessage
    }

    val needsConsent = failure == HttpDownloadAdmissionError.CLEARTEXT_CONFIRMATION
    val verified = admission
    val checking = check != null
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = 580.dp).fillMaxWidth(0.9f).background(Color(0xFF161E24)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(tr(if (verified == null) R.string.http_download_add_link else R.string.http_download_save_file), color = Color.White, fontSize = 22.sp)
            when {
                checking -> HttpDownloadFeedback(tr(R.string.http_download_checking))
                verified != null -> {
                    val size = android.text.format.Formatter.formatShortFileSize(activity, verified.size)
                    val label = folderLabel.orEmpty()
                    val summary = if (label.isBlank()) size else tr(R.string.http_download_save_to, size, label)
                    Text(summary, color = Color.LightGray, fontSize = 14.sp)
                    if (verified.strongEtag == null) HttpDownloadFeedback(tr(R.string.http_download_restart_only_hint))
                    HttpDownloadField(name, { if (it.length <= 255) { name = it; destinationError = "" } },
                        tr(R.string.http_download_name), fieldFocus, KeyboardType.Text, ::finishTyping)
                    if (destinationError.isNotEmpty()) HttpDownloadFeedback(destinationError)
                    SettingRow(tr(if (folderLabel.isNullOrBlank()) R.string.torr_download_choose_folder else R.string.http_download_start),
                        modifier = Modifier.focusRequester(primaryFocus), onClick = { download(verified, false) })
                    if (!folderLabel.isNullOrBlank()) {
                        SettingRow(tr(R.string.http_download_other_folder), onClick = { download(verified, true) })
                    }
                }
                else -> {
                    HttpDownloadField(address, {
                        if (it.length <= 8192) { address = it; failure = null }
                    }, tr(R.string.http_download_url), fieldFocus, KeyboardType.Uri, ::finishTyping)
                    failure?.let { HttpDownloadFeedback(tr(httpAdmissionErrorResource(it))) }
                    SettingRow(tr(if (needsConsent) R.string.http_download_allow_http else R.string.http_download_check),
                        modifier = Modifier.focusRequester(primaryFocus), onClick = { verify(needsConsent) })
                }
            }
            Row(Modifier.fillMaxWidth()) {
                SettingRow(tr(R.string.cancel), modifier = Modifier.focusRequester(cancelFocus), trailing = "", onClick = ::close)
            }
        }
        LaunchedEffect(checking, verified != null, needsConsent, failure != null) {
            withFrameNanos { }
            val requester = when {
                checking -> cancelFocus
                verified != null || failure != null -> primaryFocus
                else -> fieldFocus
            }
            runCatching { requester.requestFocus() }
        }
    }
}

@Composable
internal fun ConfirmHttpDownloadRestart(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = 580.dp).fillMaxWidth(0.9f).background(Color(0xFF161E24)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(tr(R.string.http_download_restart), color = Color.White, fontSize = 22.sp)
            Text(tr(R.string.http_download_restart_confirm), color = Color.LightGray, fontSize = 14.sp)
            SettingRow(tr(R.string.cancel), modifier = Modifier.focusRequester(cancelFocus), trailing = "", onClick = onDismiss)
            SettingRow(tr(R.string.http_download_restart), trailing = "", onClick = onConfirm)
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { cancelFocus.requestFocus() }
        }
    }
}

@Composable
private fun HttpDownloadField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    focus: FocusRequester,
    type: KeyboardType,
    onDone: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Text(label, color = Color.LightGray, fontSize = 13.sp)
    BasicTextField(value, onChange, modifier = Modifier
        .fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused }
        .semantics { contentDescription = label },
        singleLine = true, textStyle = DownloadFieldText, cursorBrush = SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = type, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().background(Color(0xFF29292B))
                .border(2.dp, if (focused) Color(0xFFCCFF00) else Color.Transparent).padding(12.dp)) { inner() }
        })
}

@Composable
private fun HttpDownloadFeedback(message: String) {
    Text(message, color = Color.LightGray, fontSize = 14.sp,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

private fun httpAdmissionErrorResource(reason: HttpDownloadAdmissionError): Int = when (reason) {
    HttpDownloadAdmissionError.INVALID_LINK -> R.string.http_download_invalid_link
    HttpDownloadAdmissionError.CLEARTEXT_CONFIRMATION -> R.string.http_download_http_warning
    HttpDownloadAdmissionError.REDIRECT -> R.string.http_download_redirect
    HttpDownloadAdmissionError.AUTH -> R.string.http_download_source_auth
    HttpDownloadAdmissionError.NOT_FOUND -> R.string.http_download_not_found
    HttpDownloadAdmissionError.UNSUPPORTED -> R.string.http_download_unsupported
    HttpDownloadAdmissionError.RESPONSE -> R.string.http_download_source_response
    HttpDownloadAdmissionError.TIMEOUT -> R.string.http_download_source_timeout
    HttpDownloadAdmissionError.TLS -> R.string.http_download_tls
    HttpDownloadAdmissionError.NETWORK -> R.string.http_download_source_connect
}

internal fun httpDownloadJobErrorResource(reason: String): Int? = when (reason) {
    "source_timeout" -> R.string.http_download_source_timeout
    "source_connect" -> R.string.http_download_source_connect
    "source_auth" -> R.string.http_download_source_auth
    "source_io", "source_incomplete" -> R.string.http_download_source_interrupted
    "source_response" -> R.string.http_download_source_response
    else -> null
}
