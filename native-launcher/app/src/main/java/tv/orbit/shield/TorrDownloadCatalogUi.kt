package tv.orbit.shield

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** TorrServer entries below the queue. Part of [TorrDownloadUi] without its own restart scope. */
@Composable @NonRestartableComposable
internal fun TorrDownloadCatalogUi(
    activity: MainActivity,
    scope: CoroutineScope,
    entriesState: State<List<TorrCatalogEntry>>,
    linkedHashesState: State<Set<String>?>,
    linkCheckFailedState: State<Boolean>,
    showMediaTorrents: Boolean,
    failedState: State<Boolean>,
    busyState: MutableState<Boolean>,
    pageState: MutableState<Int>,
    preparingHashState: MutableState<String?>,
    metadataRetryInFlightState: State<Boolean>,
    selectedState: MutableState<TorrCatalogEntry?>,
    filePageState: MutableState<Int>,
    chosenFilesState: MutableState<Set<Int>>,
    refresh: () -> Unit,
) {
    val entries by entriesState
    val linkedHashes by linkedHashesState
    val linkCheckFailed by linkCheckFailedState
    val failed by failedState
    var busy by busyState
    var page by pageState
    var preparingHash by preparingHashState
    val metadataRetryInFlight by metadataRetryInFlightState
    var selected by selectedState
    var filePage by filePageState
    var chosenFiles by chosenFilesState
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(tr(R.string.torr_download_choose_torrent), modifier = Modifier.weight(1f), color = Color.White, fontSize = 16.sp)
        SettingRow(tr(R.string.torr_download_refresh), if (busy) tr(R.string.torr_download_loading) else "", modifier = Modifier.width(150.dp), onClick = { refresh() })
    }
    if (busy) Text(tr(R.string.torr_download_loading), color = Color.LightGray, fontSize = 13.sp)
    val visibleEntries = remember(entries, linkedHashes, showMediaTorrents, linkCheckFailed) {
        if (showMediaTorrents || linkCheckFailed) entries
        else if (linkedHashes == null) emptyList()
        else entries.filterNot { it.hash.lowercase(java.util.Locale.ROOT) in linkedHashes!! }
    }
    LaunchedEffect(visibleEntries.size) { page = page.coerceAtMost(((visibleEntries.size - 1).coerceAtLeast(0)) / 20) }
    if (linkCheckFailed) Text(tr(R.string.torr_media_check_failed), color = Color.LightGray, fontSize = 12.sp)
    if (linkedHashes == null && !linkCheckFailed && !showMediaTorrents) Text(tr(R.string.torr_download_loading), color = Color.LightGray, fontSize = 13.sp)
    if (visibleEntries.isEmpty() && !busy && !failed && (linkedHashes != null || linkCheckFailed || showMediaTorrents)) Text(
        tr(if (entries.isNotEmpty() && linkedHashes != null && !showMediaTorrents) R.string.torr_media_all_hidden else R.string.torr_download_empty),
        color = Color.LightGray, fontSize = 13.sp)
    visibleEntries.drop(page * 20).take(20).forEach { entry ->
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        val coverId = remember(entry.hash) { TorrentArtwork.id(entry.hash) }
        val cover = activity.managedCovers[coverId]
        val artwork = cover?.manual?.takeIf { it.isNotEmpty() } ?: cover?.original.orEmpty()
        if (artwork.isEmpty()) Box(Modifier.width(100.dp).height(57.dp).background(Color(0xFF29292B)), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text("↓", color = Color.LightGray, fontSize = 24.sp)
        } else CoverArtwork(artwork, Modifier.width(100.dp).height(57.dp))
        Spacer(Modifier.width(10.dp))
        SettingRow(entry.title, if (preparingHash == entry.hash) tr(R.string.torr_download_preparing_files) else "", modifier = Modifier.weight(1f), onClick = {
            if (!busy && !metadataRetryInFlight) {
                busy = true
                preparingHash = entry.hash
                activity.torrDownloadMessage = ""
                scope.launch {
                    try { selected = withContext(Dispatchers.IO) { TorrServerCatalog.loadFiles(entry.hash) }; filePage = 0; chosenFiles = emptySet() }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: TorrMetadataTimeout) {
                        selected = entry; activity.torrDownloadMessage = activity.getString(R.string.movies_torrent_timeout)
                    }
                    catch (_: Exception) {
                        selected = entry; activity.torrDownloadMessage = activity.getString(R.string.torr_download_failed)
                    }
                    finally { preparingHash = null; busy = false }
                }
            }
        })
        Spacer(Modifier.width(8.dp))
        CompactIconButton(CompactIcon.Add, tr(R.string.movies_add_torrent_media), enabled = !busy, onClick = {
            activity.startActivity(android.content.Intent(activity, MovieLibraryActivity::class.java).apply {
                putExtra(MovieLibraryActivity.EXTRA_TORRENT_HASH, entry.hash)
                putExtra(MovieLibraryActivity.EXTRA_TORRENT_TITLE, entry.title)
            })
        })
        }
    }
    if (page > 0) SettingRow("‹", onClick = { page-- })
    if ((page + 1) * 20 < visibleEntries.size) SettingRow("›", onClick = { page++ })
}
