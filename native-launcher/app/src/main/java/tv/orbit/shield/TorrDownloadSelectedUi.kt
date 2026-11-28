package tv.orbit.shield

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Files of the selected TorrServer entry. Extracted from [TorrDownloadUi] without its own restart scope;
 * all state and the coroutine scope belong to the parent so they survive leaving this screen.
 */
@Composable @NonRestartableComposable
internal fun TorrDownloadSelectedUi(
    activity: MainActivity,
    current: TorrCatalogEntry,
    scope: CoroutineScope,
    fileFocus: FocusRequester,
    busyState: MutableState<Boolean>,
    selectedState: MutableState<TorrCatalogEntry?>,
    filePageState: MutableState<Int>,
    chosenFilesState: MutableState<Set<Int>>,
    preparingHashState: MutableState<String?>,
    metadataRetryInFlightState: MutableState<Boolean>,
    metadataRetryGenerationState: MutableState<Long>,
    journalLoadingState: State<Boolean>,
    journalFailedState: State<Boolean>,
    leaveSelected: () -> Unit,
    refresh: () -> Unit,
) {
    var busy by busyState
    var selected by selectedState
    var filePage by filePageState
    var chosenFiles by chosenFilesState
    var preparingHash by preparingHashState
    var metadataRetryInFlight by metadataRetryInFlightState
    var metadataRetryGeneration by metadataRetryGenerationState
    val journalLoading by journalLoadingState
    val journalFailed by journalFailedState
    LaunchedEffect(current.hash, current.files.isEmpty()) { runCatching { fileFocus.requestFocus() } }
    SettingRow(tr(R.string.torr_download_back), modifier = Modifier.focusRequester(fileFocus), onClick = { leaveSelected() })
    Text(current.title, color = Color.White, fontSize = 16.sp)
    val coverId = remember(current.hash) { TorrentArtwork.id(current.hash) }
    val cover = activity.managedCovers[coverId]
    CoverArtwork(cover?.manual?.takeIf { it.isNotEmpty() } ?: cover?.original.orEmpty(), Modifier.width(180.dp).height(102.dp), fallbackTitle = current.title)
    SettingRow(tr(R.string.downloads_choose_cover), onClick = { activity.chooseCover(coverId) })
    SettingRow(tr(R.string.movies_add_torrent_media), onClick = {
        activity.startActivity(android.content.Intent(activity, MovieLibraryActivity::class.java).apply {
            putExtra(MovieLibraryActivity.EXTRA_TORRENT_HASH, current.hash)
            putExtra(MovieLibraryActivity.EXTRA_TORRENT_TITLE, current.title)
        })
    })
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
        if (!busy) {
            busy = true
            scope.launch {
                val linked = try {
                    withContext(Dispatchers.IO) { linkedTorrentMovieCount(MovieLibraryStore.read(activity), current.hash) }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                finally { busy = false }
                if (selected?.hash != current.hash || activity.isFinishing || activity.isDestroyed) return@launch
                if (linked == null || linked > 0) {
                    val dialog = android.app.AlertDialog.Builder(activity)
                        .setTitle(R.string.torr_media_protected_title)
                        .setMessage(if (linked == null) R.string.torr_media_check_failed else R.string.torr_media_protected_message)
                        .setNegativeButton(R.string.cancel, null)
                    if (linked != null) dialog.setPositiveButton(R.string.torr_media_hide_action) { _, _ ->
                        TorrMediaVisibility.write(activity, false)
                        selected = null
                    }
                    dialog.show()
                    return@launch
                }
                val extra = ""
                val message = listOf(activity.getString(R.string.torr_remove_server_message), extra)
                    .filter { it.isNotEmpty() }.joinToString("\n\n")
                android.app.AlertDialog.Builder(activity)
                    .setTitle(R.string.torr_remove_server_title)
                    .setMessage(message)
                    .setPositiveButton(R.string.torr_remove_server) { _, _ ->
                        if (!busy) {
                            busy = true
                            scope.launch {
                                var acquired = false
                                var mediaReserved = false
                                var removed = false
                                try {
                                    if (journalLoading || journalFailed) throw IllegalStateException("Queue unavailable")
                                    acquired = TorrDownloadStore.beginTorrentRemoval(activity, current.hash)
                                    if (!acquired) activity.torrDownloadMessage = activity.getString(R.string.torr_remove_server_pending)
                                    else {
                                        // Reserve the media relationship without holding a lock across HTTP.
                                        withContext(Dispatchers.IO + NonCancellable) {
                                            mediaReserved = MovieLibraryStore.reserveTorrentRemoval(activity.applicationContext, current.hash)
                                        }
                                        if (!mediaReserved) activity.torrDownloadMessage = activity.getString(R.string.torr_media_protected_message)
                                        else {
                                            withContext(Dispatchers.IO + NonCancellable) { TorrServerCatalog.remove(current.hash) }
                                            selected = null; chosenFiles = emptySet(); filePage = 0
                                            activity.torrDownloadMessage = activity.getString(R.string.torr_remove_server_done)
                                            removed = true
                                        }
                                    }
                                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (_: Exception) { activity.torrDownloadMessage = activity.getString(R.string.torr_download_failed) }
                                finally {
                                    if (mediaReserved) MovieLibraryStore.endTorrentRemoval(current.hash)
                                    if (acquired) TorrDownloadStore.endTorrentRemoval(current.hash)
                                    busy = false
                                }
                                if (removed) refresh()
                            }
                        }
                    }.setNegativeButton(R.string.cancel, null).show()
            }
        }
    })
    if (current.files.isEmpty()) {
        Text(tr(R.string.torr_download_no_files), color = Color.LightGray, fontSize = 13.sp)
        SettingRow(tr(R.string.torr_download_refresh), if (busy) tr(R.string.torr_download_preparing_files) else "", onClick = {
            if (!busy && !metadataRetryInFlight) {
                busy = true
                metadataRetryInFlight = true
                val generation = ++metadataRetryGeneration
                preparingHash = current.hash
                activity.torrDownloadMessage = ""
                scope.launch {
                    try {
                        val loaded = withContext(Dispatchers.IO) { TorrServerCatalog.loadFiles(current.hash) }
                        if (generation == metadataRetryGeneration && selected?.hash == current.hash) { selected = loaded; filePage = 0; chosenFiles = emptySet() }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: TorrMetadataTimeout) {
                        if (generation == metadataRetryGeneration && selected?.hash == current.hash) activity.torrDownloadMessage = activity.getString(R.string.movies_torrent_timeout)
                    } catch (_: Exception) {
                        if (generation == metadataRetryGeneration && selected?.hash == current.hash) activity.torrDownloadMessage = activity.getString(R.string.torr_download_failed)
                    } finally {
                        metadataRetryInFlight = false
                        preparingHash = null
                        if (generation == metadataRetryGeneration) busy = false
                    }
                }
            }
        })
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
                activity.prepareTorrPlayback(current.hash, file.id, file.size)
            })
        }
        if (filePage > 0) SettingRow("‹", onClick = { filePage-- })
        if ((filePage + 1) * 20 < current.files.size) SettingRow("›", onClick = { filePage++ })
    }
}
