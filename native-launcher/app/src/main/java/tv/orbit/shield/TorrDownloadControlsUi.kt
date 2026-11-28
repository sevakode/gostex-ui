package tv.orbit.shield

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Parts of [TorrDownloadUi] without their own restart scope; the parent owns every state object.

/** Focus return after the link form closes, and the link form itself. */
@Composable @NonRestartableComposable
internal fun TorrDownloadHttpEntryUi(
    activity: MainActivity,
    showHttpDownloadState: MutableState<Boolean>,
    httpReturnFocusState: MutableState<FocusRequester?>,
    httpInitialAddressState: MutableState<String>,
    httpSessionState: MutableState<HttpDownloadSession?>,
    httpRebindIdState: MutableState<String?>,
    httpRebindReturnIdState: MutableState<String?>,
    httpEntryFocusState: MutableState<FocusRequester?>,
    folderGrantsState: State<Set<TorrDownloadFolderAccess.Identity>?>,
    showFinishedState: MutableState<Boolean>,
    jobPageState: MutableState<Int>,
) {
    var showHttpDownload by showHttpDownloadState
    var httpReturnFocus by httpReturnFocusState
    var httpInitialAddress by httpInitialAddressState
    var httpSession by httpSessionState
    var httpRebindId by httpRebindIdState
    var httpRebindReturnId by httpRebindReturnIdState
    var httpEntryFocus by httpEntryFocusState
    val folderGrants by folderGrantsState
    var showFinished by showFinishedState
    var jobPage by jobPageState
    LaunchedEffect(showHttpDownload, httpReturnFocus, activity.resumeVersion) {
        val focus = httpReturnFocus
        if (!showHttpDownload && focus != null &&
            activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            withFrameNanos { }
            if (runCatching { focus.requestFocus() }.getOrDefault(false)) httpReturnFocus = null
        }
    }
    if (showHttpDownload) {
        val savedFolder = TorrDownloadFolderAccess.identity(activity.torrDownloadFolder)
        val folderLabel = savedFolder?.takeIf { folderGrants?.contains(it) == true }?.documentId?.let {
            it.substringAfterLast('/').substringAfter(':').ifBlank { it }
        }
        HttpDownloadUi(activity, folderLabel, httpInitialAddress, onSubmit = {
            showFinished = false
            if (httpRebindId == null) jobPage = 0
        }, onDismiss = dismiss@{
            if (!showHttpDownload) return@dismiss
            showHttpDownload = false
            httpInitialAddress = ""
            httpSession = null
            val rebindId = httpRebindId
            httpRebindId = null
            if (rebindId == null) httpReturnFocus = httpEntryFocus else httpRebindReturnId = rebindId
            httpEntryFocus = null
        }, session = httpSession, onRebind = httpRebindId?.let { id ->
            { admission ->
                runCatching {
                    TorrDownloadStore.bindSession(activity, id, admission)
                    activity.startTorrDownload(id)
                }.isSuccess
            }
        })
    }
}

/** Add buttons and the magnet/options toggles. */
@Composable @NonRestartableComposable
internal fun TorrDownloadAddButtonsUi(
    activity: MainActivity,
    wide: Boolean,
    initialFocus: FocusRequester,
    httpLinkFocus: FocusRequester,
    browserFocus: FocusRequester,
    showMagnetState: MutableState<Boolean>,
    showOptionsState: MutableState<Boolean>,
    addHttpLink: () -> Unit,
    openBrowser: () -> Unit,
) {
    var showMagnet by showMagnetState
    var showOptions by showOptionsState
    if (wide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingRow(tr(R.string.torr_download_add_file), modifier = Modifier.weight(1f).focusRequester(initialFocus), onClick = activity::chooseTorrFile)
            SettingRow(tr(R.string.http_download_add_link), modifier = Modifier.weight(1f).focusRequester(httpLinkFocus), onClick = addHttpLink)
            SettingRow(tr(R.string.browser_download_entry), modifier = Modifier.weight(1f).focusRequester(browserFocus), onClick = { openBrowser() })
            SettingRow(tr(if (showMagnet) R.string.downloads_magnet_hide else R.string.downloads_magnet_show), modifier = Modifier.weight(1f), onClick = { showMagnet = !showMagnet })
            SettingRow(tr(if (showOptions) R.string.downloads_options_hide else R.string.downloads_options_show), modifier = Modifier.weight(1f), onClick = { showOptions = !showOptions })
        }
    } else {
        SettingRow(tr(R.string.torr_download_add_file), modifier = Modifier.focusRequester(initialFocus), onClick = activity::chooseTorrFile)
        SettingRow(tr(R.string.http_download_add_link), modifier = Modifier.focusRequester(httpLinkFocus), onClick = addHttpLink)
        SettingRow(tr(R.string.browser_download_entry), modifier = Modifier.focusRequester(browserFocus), onClick = { openBrowser() })
        SettingRow(tr(if (showMagnet) R.string.downloads_magnet_hide else R.string.downloads_magnet_show), onClick = { showMagnet = !showMagnet })
        SettingRow(tr(if (showOptions) R.string.downloads_options_hide else R.string.downloads_options_show), onClick = { showOptions = !showOptions })
    }
}

/** Magnet link field and submit button. */
@Composable @NonRestartableComposable
internal fun TorrDownloadMagnetUi(
    activity: MainActivity,
    scope: CoroutineScope,
    magnetState: MutableState<String>,
    busyState: MutableState<Boolean>,
    magnetSubmitFocus: FocusRequester,
) {
    var magnet by magnetState
    var busy by busyState
    Text(tr(R.string.torr_download_magnet), color = Color.LightGray, fontSize = 13.sp)
    BasicTextField(magnet, { if (it.length <= 8192) magnet = it }, modifier = Modifier.fillMaxWidth().background(Color(0xFF29292B)).padding(10.dp),
        singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 14.sp))
    SettingRow(tr(R.string.torr_download_add_magnet), modifier = Modifier.focusRequester(magnetSubmitFocus), onClick = {
        if (!busy && magnet.isNotBlank()) {
            busy = true
            activity.torrDownloadMessage = ""
            scope.launch {
                try {
                    val entry = withContext(Dispatchers.IO) { TorrServerCatalog.addMagnet(magnet.trim()) }
                    magnet = ""; activity.torrCatalogChanged(); activity.torrDownloadMessage = activity.getString(R.string.torr_download_torrent_added)
                    activity.findTorrentCover(entry)
                } catch (_: Exception) { activity.torrDownloadMessage = activity.getString(R.string.torr_download_failed) }
                finally { busy = false }
            }
        }
    })
}
