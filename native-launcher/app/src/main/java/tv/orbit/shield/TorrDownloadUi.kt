package tv.orbit.shield

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun TorrDownloadUi(activity: MainActivity, initialFocus: FocusRequester) {
    val scope = rememberCoroutineScope()
    val jobs by TorrDownloadStore.jobs.collectAsState()
    var entries by remember { mutableStateOf<List<TorrCatalogEntry>>(emptyList()) }
    var selected by remember { mutableStateOf<TorrCatalogEntry?>(null) }
    var magnet by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var preparingHash by remember { mutableStateOf<String?>(null) }
    var metadataError by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var journalFailed by remember { mutableStateOf(false) }
    var discardErrorId by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(0) }
    var filePage by remember { mutableStateOf(0) }
    var jobPage by remember { mutableStateOf(0) }
    var chosenFiles by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var reserveMiB by remember { mutableStateOf(TorrDownloadSpace.read(activity)) }
    var writeRateKiB by remember { mutableStateOf(TorrDownloadWriteRate.read(activity)) }
    var autoRetry by remember { mutableStateOf(TorrDownloadRetryOptions.read(activity)) }
    val fileFocus = remember { FocusRequester() }
    val revision = activity.torrCatalogRevision

    fun refresh() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                entries = withContext(Dispatchers.IO) { TorrServerCatalog.list() }
                failed = false
                page = page.coerceAtMost(((entries.size - 1).coerceAtLeast(0)) / 20)
            } catch (_: Exception) { entries = emptyList(); failed = true }
            finally { busy = false }
        }
    }
    LaunchedEffect(revision) {
        journalFailed = runCatching { TorrDownloadStore.recover(activity) }.isFailure
        refresh()
    }
    BackHandler(enabled = selected != null) { selected = null; filePage = 0; chosenFiles = emptySet() }
    LaunchedEffect(selected) {
        if (selected == null) { withFrameNanos { }; runCatching { initialFocus.requestFocus() } }
    }

    val current = selected
    if (current != null) {
        LaunchedEffect(current.hash) { runCatching { fileFocus.requestFocus() } }
        SettingRow(tr(R.string.torr_download_back), modifier = Modifier.focusRequester(fileFocus), onClick = { selected = null; filePage = 0; chosenFiles = emptySet() })
        Text(current.title, color = Color.White, fontSize = 16.sp)
        val rate = current.downloadBytesPerSecond?.let { android.text.format.Formatter.formatShortFileSize(activity, it) }
        val seeders = current.connectedSeeders
        if (rate != null || seeders != null) {
            val details = listOfNotNull(rate?.let { tr(R.string.torr_status_rate, it) }, seeders?.let { tr(R.string.torr_status_seeders, it) }).joinToString(" · ")
            Text(details, color = Color.LightGray, fontSize = 13.sp)
            Text(tr(R.string.torr_status_snapshot), color = Color.LightGray, fontSize = 12.sp)
        } else Text(tr(R.string.torr_status_unknown), color = Color.LightGray, fontSize = 13.sp)
        SettingRow(tr(R.string.torr_status_refresh), if (busy) tr(R.string.torr_download_loading) else "", onClick = {
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        val snapshot = withContext(Dispatchers.IO) { TorrServerCatalog.get(current.hash) }
                        if (selected?.hash == current.hash) selected = current.copy(downloadBytesPerSecond = snapshot.downloadBytesPerSecond, connectedSeeders = snapshot.connectedSeeders)
                    } catch (_: Exception) { activity.torrDownloadMessage = activity.getString(R.string.torr_download_failed) }
                    finally { busy = false }
                }
            }
        })
        if (activity.torrDownloadMessage.isNotBlank()) Text(activity.torrDownloadMessage,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = Color.LightGray, fontSize = 13.sp)
        SettingRow(tr(R.string.torr_remove_server), onClick = {
            if (!busy) android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.torr_remove_server_title)
                .setMessage(R.string.torr_remove_server_message)
                .setPositiveButton(R.string.torr_remove_server) { _, _ ->
                    if (!busy) {
                        busy = true
                        scope.launch {
                            var acquired = false
                            var removed = false
                            try {
                                if (journalFailed) throw IllegalStateException("Queue unavailable")
                                acquired = TorrDownloadStore.beginTorrentRemoval(activity, current.hash)
                                if (!acquired) activity.torrDownloadMessage = activity.getString(R.string.torr_remove_server_pending)
                                else {
                                    withContext(Dispatchers.IO) { TorrServerCatalog.remove(current.hash) }
                                    selected = null; chosenFiles = emptySet(); filePage = 0
                                    activity.torrDownloadMessage = activity.getString(R.string.torr_remove_server_done)
                                    removed = true
                                }
                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { activity.torrDownloadMessage = activity.getString(R.string.torr_download_failed) }
                            finally {
                                if (acquired) TorrDownloadStore.endTorrentRemoval(current.hash)
                                busy = false
                            }
                            if (removed) refresh()
                        }
                    }
                }.setNegativeButton(R.string.cancel, null).show()
        })
        if (current.files.isEmpty()) {
            Text(tr(R.string.torr_download_no_files), color = Color.LightGray, fontSize = 13.sp)
        } else {
            Text(tr(R.string.torr_download_choose_file), color = Color.LightGray, fontSize = 13.sp)
            if (chosenFiles.isNotEmpty()) SettingRow(tr(R.string.torr_download_selected, chosenFiles.size), onClick = {
                if (!busy) activity.chooseTorrDestination(current, current.files.filter { it.id in chosenFiles })
            })
            if (current.files.size in 2..100) SettingRow(tr(R.string.torr_download_all, current.files.size), onClick = {
                if (!busy) activity.chooseTorrDestination(current, current.files)
            })
            current.files.drop(filePage * 20).take(20).forEach { file ->
                val checked = file.id in chosenFiles
                SettingRow(file.path, android.text.format.Formatter.formatShortFileSize(activity, file.size), isSelected = checked, trailing = if (checked) "✓" else "○", onClick = {
                    chosenFiles = if (checked) chosenFiles - file.id else chosenFiles + file.id
                })
                if (TorrPlayback.isVideo(file.path)) SettingRow(tr(R.string.torr_play_file), file.path, onClick = {
                    runCatching {
                        val url = android.net.Uri.parse(TorrPlayback.url(current.hash, file.id))
                        activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(url, "video/*"))
                    }.onFailure { activity.torrDownloadMessage = activity.getString(R.string.torr_play_failed) }
                })
            }
            if (filePage > 0) SettingRow("‹", onClick = { filePage-- })
            if ((filePage + 1) * 20 < current.files.size) SettingRow("›", onClick = { filePage++ })
        }
        return
    }

    SettingRow(tr(R.string.torr_download_add_file), modifier = Modifier.focusRequester(initialFocus), onClick = activity::chooseTorrFile)
    if (activity.torrDownloadMessage.isNotBlank()) Text(activity.torrDownloadMessage,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = Color.LightGray, fontSize = 13.sp)
    val folder = activity.torrDownloadFolder
    val folderLabel = remember(folder) {
        runCatching { android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(folder)).substringAfterLast('/') }.getOrDefault("")
    }
    SettingRow(tr(R.string.torr_download_choose_folder), folderLabel.ifBlank { tr(R.string.torr_download_folder_hint) }, onClick = activity::chooseTorrFolder)
    val reserveLabel = if (reserveMiB == 0) tr(R.string.torr_download_reserve_off)
        else android.text.format.Formatter.formatShortFileSize(activity, reserveMiB.toLong() * 1024 * 1024)
    SettingRow(tr(R.string.torr_download_reserve), reserveLabel, onClick = {
        val choices = TorrDownloadSpace.choices
        val labels = choices.map { if (it == 0) activity.getString(R.string.torr_download_reserve_off)
            else android.text.format.Formatter.formatShortFileSize(activity, it.toLong() * 1024 * 1024) }.toTypedArray()
        android.app.AlertDialog.Builder(activity).setTitle(R.string.torr_download_reserve)
            .setSingleChoiceItems(labels, choices.indexOf(reserveMiB)) { dialog, index ->
                runCatching { TorrDownloadSpace.write(activity, choices[index]) }
                    .onSuccess { reserveMiB = choices[index] }
                    .onFailure { activity.torrDownloadMessage = activity.getString(R.string.torr_download_failed) }
                dialog.dismiss()
            }.setNegativeButton(R.string.cancel, null).show()
    })
    SettingRow(tr(R.string.torr_download_auto_retry), tr(if (autoRetry) R.string.enabled else R.string.disabled), onClick = {
        val next = !autoRetry
        runCatching { TorrDownloadRetryOptions.write(activity, next) }
            .onSuccess { autoRetry = next }
            .onFailure { activity.torrDownloadMessage = activity.getString(R.string.torr_download_failed) }
    })
    val writeRateLabel = if (writeRateKiB == 0) tr(R.string.torr_write_unlimited)
        else tr(R.string.torr_status_rate_value, android.text.format.Formatter.formatShortFileSize(activity, writeRateKiB.toLong() * 1024))
    SettingRow(tr(R.string.torr_write_limit), writeRateLabel, onClick = {
        val choices = TorrDownloadWriteRate.choices
        val labels = choices.map { if (it == 0) activity.getString(R.string.torr_write_unlimited)
            else activity.getString(R.string.torr_status_rate_value, android.text.format.Formatter.formatShortFileSize(activity, it.toLong() * 1024)) }.toTypedArray()
        android.app.AlertDialog.Builder(activity).setTitle(R.string.torr_write_limit)
            .setSingleChoiceItems(labels, choices.indexOf(writeRateKiB)) { dialog, index ->
                runCatching { TorrDownloadWriteRate.write(activity, choices[index]) }
                    .onSuccess { writeRateKiB = choices[index] }
                    .onFailure { activity.torrDownloadMessage = activity.getString(R.string.torr_download_failed) }
                dialog.dismiss()
            }.setNegativeButton(R.string.cancel, null).show()
    })
    Text(tr(R.string.torr_write_limit_note), color = Color.LightGray, fontSize = 13.sp)
    Text(tr(R.string.torr_download_magnet), color = Color.LightGray, fontSize = 13.sp)
    BasicTextField(magnet, { if (it.length <= 8192) magnet = it }, modifier = Modifier.fillMaxWidth().background(Color(0xFF29292B)).padding(10.dp),
        singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 14.sp))
    SettingRow(tr(R.string.torr_download_add_magnet), onClick = {
        if (!busy && magnet.isNotBlank()) {
            busy = true
            activity.torrDownloadMessage = ""
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { TorrServerCatalog.addMagnet(magnet.trim()) }
                    magnet = ""; activity.torrCatalogChanged(); activity.torrDownloadMessage = activity.getString(R.string.torr_download_torrent_added)
                } catch (_: Exception) { activity.torrDownloadMessage = activity.getString(R.string.torr_download_failed) }
                finally { busy = false }
            }
        }
    })
    SettingRow(tr(R.string.torr_download_refresh), if (busy) tr(R.string.torr_download_loading) else "", onClick = { refresh() })
    if (failed) {
        Text(tr(R.string.torr_download_unavailable), color = Color.LightGray, fontSize = 13.sp)
        SettingRow(tr(R.string.torr_download_open), onClick = activity::openTorrServer)
    }
    Text(tr(R.string.torr_download_queue), color = Color.White, fontSize = 16.sp)
    val pausedCount = jobs.count { it.state == "paused" }
    if (!journalFailed && pausedCount > 1) SettingRow(tr(R.string.torr_download_resume_paused, pausedCount), onClick = {
        val paused = TorrDownloadStore.jobs.value.filter { it.state == "paused" }.map { it.id }
        if (paused.isNotEmpty()) runCatching { activity.startTorrDownloads(paused) }
            .onFailure { activity.torrDownloadMessage = activity.getString(R.string.torr_download_failed) }
    })
    if (journalFailed) Text(tr(R.string.torr_download_failed), color = Color.LightGray, fontSize = 13.sp)
    if (jobs.isEmpty()) Text(tr(R.string.torr_download_empty_queue), color = Color.LightGray, fontSize = 13.sp)
    jobs.drop(jobPage * 20).take(20).forEach { job ->
        val label = when (job.state) {
            "queued" -> R.string.torr_download_queued
            "running" -> if (job.error == "retrying") R.string.torr_download_retrying else R.string.torr_download_running
            "paused" -> R.string.torr_download_paused
            "done" -> R.string.torr_download_done
            "finalizing" -> R.string.torr_download_finalizing
            else -> when (job.error) {
                "space" -> R.string.torr_download_low_space
                "space_unavailable" -> R.string.torr_download_space_unknown
                "storage" -> R.string.torr_download_storage_error
                "finalize", "finalizing" -> R.string.torr_download_finalize_error
                "source_timeout" -> R.string.torr_download_source_timeout
                "source_connect" -> R.string.torr_download_source_connect
                "source_auth" -> R.string.torr_download_source_auth
                "source_io", "source_incomplete" -> R.string.torr_download_source_interrupted
                "source_response" -> R.string.torr_download_source_response
                "source_changed" -> R.string.torr_download_source_changed
                else -> R.string.torr_download_error
            }
        }
        val percent = if (job.total == 0L) { if (job.state == "done") 100 else 0 } else (job.offset.toDouble() / job.total * 100).toInt().coerceIn(0, 100)
        val action = when (job.state) {
            "queued", "running" -> tr(R.string.torr_download_pause)
            "paused", "error" -> tr(R.string.torr_download_resume)
            else -> ""
        }
        val summary = "${tr(label)} · $percent%" + if (action.isNotEmpty()) " · $action" else ""
        SettingRow(job.name, summary, onClick = {
            runCatching {
                when (job.state) {
                    "queued", "running" -> TorrDownloadActions.pause(activity, job.id)
                    "paused", "error" -> activity.startTorrDownload(job.id)
                }
            }.onFailure { activity.torrDownloadMessage = activity.getString(R.string.torr_download_failed) }
        })
        if (job.state == "done") SettingRow(tr(R.string.torr_download_remove), tr(R.string.torr_download_keep_file), onClick = {
            runCatching { TorrDownloadStore.removeRecord(activity, job.id) }
                .onFailure { activity.torrDownloadMessage = activity.getString(R.string.torr_download_failed) }
        })
        if (job.state == "paused" || job.state == "error") SettingRow(
            tr(R.string.torr_download_remove), tr(R.string.torr_download_keep_partial), onClick = {
                android.app.AlertDialog.Builder(activity)
                    .setTitle(R.string.torr_download_remove_incomplete_title)
                    .setMessage(R.string.torr_download_remove_incomplete_message)
                    .setPositiveButton(R.string.torr_download_remove) { _, _ ->
                        runCatching { TorrDownloadStore.removeRecord(activity, job.id) }
                            .onFailure { activity.torrDownloadMessage = activity.getString(R.string.torr_download_failed) }
                    }
                    .setNegativeButton(R.string.cancel, null).show()
            })
        if (job.state == "paused" || job.state == "error") SettingRow(
            tr(R.string.torr_discard_partial), onClick = {
                if (!busy) android.app.AlertDialog.Builder(activity)
                    .setTitle(R.string.torr_discard_title)
                    .setMessage(R.string.torr_discard_message)
                    .setPositiveButton(R.string.torr_discard_partial) { _, _ ->
                        if (!busy) {
                            busy = true
                            discardErrorId = null
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO + NonCancellable) { TorrDownloadDocuments.discard(activity.applicationContext, job.id) }
                                    activity.torrDownloadMessage = activity.getString(R.string.torr_discard_done)
                                } catch (_: Exception) {
                                    activity.torrDownloadMessage = activity.getString(R.string.torr_discard_failed)
                                    discardErrorId = job.id
                                } finally { busy = false }
                            }
                        }
                    }.setNegativeButton(R.string.cancel, null).show()
            })
        if (discardErrorId == job.id) Text(tr(R.string.torr_discard_failed),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = Color.LightGray, fontSize = 13.sp)
    }
    if (jobPage > 0) SettingRow("‹", onClick = { jobPage-- })
    if ((jobPage + 1) * 20 < jobs.size) SettingRow("›", onClick = { jobPage++ })
    if (jobs.any { it.state == "done" }) Text(tr(R.string.torr_download_keep_file), color = Color.LightGray, fontSize = 13.sp)
    Text(tr(R.string.torr_download_choose_torrent), color = Color.White, fontSize = 16.sp)
    if (busy) Text(tr(R.string.torr_download_loading), color = Color.LightGray, fontSize = 13.sp)
    if (entries.isEmpty() && !busy && !failed) Text(tr(R.string.torr_download_empty), color = Color.LightGray, fontSize = 13.sp)
    entries.drop(page * 20).take(20).forEach { entry ->
        SettingRow(entry.title, if (preparingHash == entry.hash) tr(R.string.torr_download_preparing_files) else "", onClick = {
            if (!busy) {
                busy = true
                preparingHash = entry.hash
                metadataError = null
                activity.torrDownloadMessage = ""
                scope.launch {
                    try { selected = withContext(Dispatchers.IO) { TorrServerCatalog.loadFiles(entry.hash) }; filePage = 0; chosenFiles = emptySet() }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: TorrMetadataTimeout) { metadataError = entry.hash to R.string.movies_torrent_timeout }
                    catch (_: Exception) { metadataError = entry.hash to R.string.torr_download_failed }
                    finally { preparingHash = null; busy = false }
                }
            }
        })
        if (metadataError?.first == entry.hash) Text(tr(metadataError!!.second),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = Color.LightGray, fontSize = 13.sp)
    }
    if (page > 0) SettingRow("‹", onClick = { page-- })
    if ((page + 1) * 20 < entries.size) SettingRow("›", onClick = { page++ })
}
