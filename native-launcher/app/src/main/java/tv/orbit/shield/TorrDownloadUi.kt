package tv.orbit.shield

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun TorrDownloadUi(activity: MainActivity, initialFocus: FocusRequester, wide: Boolean = false) {
    val scope = rememberCoroutineScope()
    val jobs by TorrDownloadStore.jobs.collectAsState()
    val liveProgress by TorrDownloadLiveProgress.current.collectAsStateWithLifecycle()
    var entries by remember { mutableStateOf<List<TorrCatalogEntry>>(emptyList()) }
    var selected by remember { mutableStateOf<TorrCatalogEntry?>(null) }
    var magnet by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var preparingHash by remember { mutableStateOf<String?>(null) }
    var metadataRetryInFlight by remember { mutableStateOf(false) }
    var metadataRetryGeneration by remember { mutableStateOf(0L) }
    var failed by remember { mutableStateOf(false) }
    var journalFailed by remember { mutableStateOf(false) }
    var journalLoading by remember { mutableStateOf(true) }
    var removingJobId by remember { mutableStateOf<String?>(null) }
    var discardErrorId by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(0) }
    var filePage by remember { mutableStateOf(0) }
    var jobPage by rememberSaveable { mutableStateOf(0) }
    var showFinished by rememberSaveable { mutableStateOf(false) }
    var showHttpDownload by remember { mutableStateOf(false) }
    var httpInitialAddress by remember { mutableStateOf("") }
    var httpSession by remember { mutableStateOf<HttpDownloadSession?>(null) }
    var httpRebindId by remember { mutableStateOf<String?>(null) }
    var browserRebindId by rememberSaveable { mutableStateOf<String?>(null) }
    var httpRebindReturnId by remember { mutableStateOf<String?>(null) }
    var returnToHttpLink by remember { mutableStateOf(false) }
    var openingHttp by remember { mutableStateOf(false) }
    val httpLinkFocus = remember { FocusRequester() }
    var folderAccessRequest by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var folderAccessReturnFocus by rememberSaveable { mutableStateOf<String?>(null) }
    var folderAccessBusy by remember { mutableStateOf(false) }
    var folderGrants by remember { mutableStateOf<Set<TorrDownloadFolderAccess.Identity>?>(null) }
    fun needsSiteSession(job: TorrDownloadJob) = job.requiresHttpSession && TorrDownloadStore.sessionFor(job.id) == null
    val folderSnapshotGeneration = remember { intArrayOf(0) }
    LaunchedEffect(activity.resumeVersion) {
        val generation = ++folderSnapshotGeneration[0]
        val snapshot = withContext(Dispatchers.IO) { TorrDownloadFolderAccess.snapshot(activity.contentResolver) }
        if (generation == folderSnapshotGeneration[0]) folderGrants = snapshot
    }
    val folderAccessPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val saved = folderAccessRequest
        folderAccessRequest = null
        val request = saved?.takeIf { it.size == 3 }?.let { TorrDownloadFolderAccess.Request(it[0], it[1], it[2]) }
        folderAccessBusy = true
        ++folderSnapshotGeneration[0]
        scope.launch {
            try {
                val ready = TorrDownloadStartup.prepare(activity.applicationContext)
                val result = withContext(Dispatchers.IO) {
                    val current = if (ready && !PortableMaintenance.running) TorrDownloadStore.jobs.value
                        .firstOrNull { it.id == request?.id && it.state == "error" && it.error == "storage" }
                        ?.let { TorrDownloadFolderAccess.Request(it.id, it.tree, it.document) } else null
                    TorrDownloadFolderAccess.restore(activity.contentResolver, request, current, uri)
                }
                ++folderSnapshotGeneration[0]
                folderGrants = result.grants
                activity.torrDownloadMessage = activity.getString(when (result.outcome) {
                    TorrDownloadFolderAccess.Outcome.RESTORED -> {
                        val job = TorrDownloadStore.jobs.value.firstOrNull { it.id == request?.id }
                        when {
                            job != null && needsSiteSession(job) -> R.string.http_download_session_pick_same
                            job?.requiresHttpRestart == true -> R.string.http_download_restart_access_restored
                            else -> R.string.torr_folder_access_restored
                        }
                    }
                    TorrDownloadFolderAccess.Outcome.CANCELLED -> R.string.torr_folder_access_cancelled
                    TorrDownloadFolderAccess.Outcome.DIFFERENT_FOLDER -> R.string.torr_folder_access_different
                    TorrDownloadFolderAccess.Outcome.STALE -> R.string.torr_folder_access_stale
                    TorrDownloadFolderAccess.Outcome.UNAVAILABLE -> R.string.torr_folder_access_failed
                })
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { activity.torrDownloadMessage = activity.getString(R.string.torr_folder_access_failed) }
            finally {
                folderAccessBusy = false
                folderAccessReturnFocus = request?.id
                val index = TorrDownloadStore.jobs.value.filter { it.state != "done" }.indexOfFirst { it.id == request?.id }
                if (index >= 0) { showFinished = false; jobPage = index / 20 }
            }
        }
    }
    fun restoreFolderAccess(job: TorrDownloadJob) {
        if (folderAccessBusy || folderAccessRequest != null || PortableMaintenance.running) return
        val probe = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)
        val resolved = runCatching { activity.packageManager.resolveActivity(probe,
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo }.getOrNull()
        if (resolved == null || !resolved.enabled || !resolved.applicationInfo.enabled ||
            resolved.packageName == "com.android.tv.frameworkpackagestubs") {
            activity.torrDownloadMessage = activity.getString(R.string.document_picker_unavailable)
            return
        }
        folderAccessRequest = arrayListOf(job.id, job.tree, job.document)
        activity.torrDownloadMessage = activity.getString(R.string.torr_folder_access_hint)
        try { folderAccessPicker.launch(android.net.Uri.parse(job.tree)) }
        catch (_: Exception) {
            folderAccessRequest = null
            folderAccessReturnFocus = job.id
            activity.torrDownloadMessage = activity.getString(R.string.document_picker_unavailable)
        }
    }
    var hiddenFinished by remember(activity) { mutableStateOf(TorrDownloadHistory.read(activity)) }
    DisposableEffect(activity) {
        val stopObserving = TorrDownloadHistory.observe(activity) { hiddenFinished = it }
        onDispose { stopObserving() }
    }
    val finishedTabFocus = remember { FocusRequester() }
    var finishedFocusId by remember { mutableStateOf<String?>(null) }
    var chosenFiles by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var showOptions by remember { mutableStateOf(false) }
    var showMagnet by remember { mutableStateOf(false) }
    var focusBrowserMagnet by remember { mutableStateOf(false) }
    val magnetSubmitFocus = remember { FocusRequester() }
    val browser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val rebindId = browserRebindId
        browserRebindId = null
        val sessionId = result.data?.getStringExtra(TvBrowserActivity.RESULT_SESSION)
        val session = sessionId?.let(BrowserDownloadDrafts::take)
        if (result.resultCode == Activity.RESULT_OK && !PortableMaintenance.running) {
            val link = result.data?.getStringExtra(TvBrowserActivity.RESULT_LINK).orEmpty()
            val magnetLink = TvBrowserOptions.magnet(link)
            val webLink = TvBrowserOptions.webUrl(link)
            when {
                session != null -> {
                    httpSession = session
                    httpRebindId = rebindId
                    showHttpDownload = true
                }
                sessionId != null || rebindId != null -> {
                    activity.torrDownloadMessage = activity.getString(R.string.http_download_session_unavailable)
                    httpRebindReturnId = rebindId
                }
                magnetLink != null -> {
                    magnet = magnetLink
                    showMagnet = true
                    focusBrowserMagnet = true
                }
                webLink != null -> {
                    httpInitialAddress = webLink.substringBefore('#')
                    showHttpDownload = true
                }
            }
        } else httpRebindReturnId = rebindId
    }
    fun openBrowser(rebindId: String? = null) {
        if (PortableMaintenance.running) return
        browserRebindId = rebindId
        runCatching {
            browser.launch(Intent(activity, TvBrowserActivity::class.java)
                .putExtra(TvBrowserActivity.RESELECT_DOWNLOAD, rebindId != null))
        }.onFailure {
            browserRebindId = null
            activity.torrDownloadMessage = activity.getString(R.string.browser_error_download)
        }
    }
    LaunchedEffect(focusBrowserMagnet, showMagnet, activity.resumeVersion) {
        if (focusBrowserMagnet && showMagnet && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            withFrameNanos { }
            if (runCatching { magnetSubmitFocus.requestFocus() }.getOrDefault(false)) focusBrowserMagnet = false
        }
    }
    var reserveMiB by remember { mutableStateOf(TorrDownloadSpace.read(activity)) }
    var prefetchCount by remember { mutableIntStateOf(TorrPrefetch.count(activity)) }
    var prefetchQuota by remember { mutableIntStateOf(TorrPrefetch.quota(activity)) }
    var autoRetry by remember { mutableStateOf(TorrDownloadRetryOptions.read(activity)) }
    val autoCover = rememberTorrentAutoCover()
    val showMediaTorrents = rememberTorrMediaVisibility()
    val mediaPrefs = remember(activity) { activity.getSharedPreferences("orbit", android.content.Context.MODE_PRIVATE) }
    var mediaRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(mediaPrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == MovieLibraryStore.KEY) mediaRevision++
        }
        mediaPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { mediaPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var linkedHashes by remember { mutableStateOf<Set<String>?>(null) }
    var linkCheckFailed by remember { mutableStateOf(false) }
    LaunchedEffect(activity.resumeVersion, activity.torrCatalogRevision, mediaRevision) {
        linkedHashes = null
        linkCheckFailed = false
        linkedHashes = try {
            withContext(Dispatchers.IO) { linkedTorrentHashes(MovieLibraryStore.read(activity.applicationContext)) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { linkCheckFailed = true; null }
    }
    LaunchedEffect(linkedHashes, showMediaTorrents) {
        val currentHash = selected?.hash?.lowercase(java.util.Locale.ROOT)
        if (!showMediaTorrents && currentHash != null && linkedHashes?.contains(currentHash) == true) {
            selected = null
            filePage = 0
            chosenFiles = emptySet()
        }
    }
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
        journalLoading = true
        journalFailed = try {
            !TorrDownloadStartup.prepare(activity.applicationContext)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { true }
        journalLoading = false
        refresh()
    }
    fun removeJobRecord(id: String, finished: Boolean = false) {
        if (journalLoading || journalFailed || removingJobId != null) return
        removingJobId = id
        scope.launch {
            try {
                withContext(Dispatchers.IO) { TorrDownloadStore.removeRecord(activity.applicationContext, id) }
                if (finished) finishedFocusId = id
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { activity.torrDownloadMessage = activity.getString(R.string.torr_download_failed) }
            finally { removingJobId = null }
        }
    }
    fun hideFinished(job: TorrDownloadJob) {
        if (journalLoading || journalFailed || removingJobId != null) return
        if (job.prefetchMovieId.isBlank()) { removeJobRecord(job.id, finished = true); return }
        removingJobId = job.id
        finishedFocusId = job.id
        scope.launch {
            try {
                withContext(Dispatchers.IO) { TorrDownloadHistory.hide(activity.applicationContext, job.id) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                finishedFocusId = null
                activity.torrDownloadMessage = activity.getString(R.string.torr_download_failed)
                if (showFinished && selected == null) {
                    withFrameNanos { }
                    runCatching { finishedTabFocus.requestFocus() }
                }
            }
            finally { removingJobId = null }
        }
    }
    fun leaveSelected() {
        if (metadataRetryInFlight) { metadataRetryGeneration++; busy = false }
        selected = null; filePage = 0; chosenFiles = emptySet()
    }
    BackHandler(enabled = selected != null) { leaveSelected() }
    LaunchedEffect(selected) {
        if (selected == null) { withFrameNanos { }; runCatching { initialFocus.requestFocus() } }
    }

    val current = selected
    if (current != null) {
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
                    activity.prepareTorrPlayback(current.hash, file.id)
                })
            }
            if (filePage > 0) SettingRow("‹", onClick = { filePage-- })
            if ((filePage + 1) * 20 < current.files.size) SettingRow("›", onClick = { filePage++ })
        }
        return
    }

    LaunchedEffect(showHttpDownload, returnToHttpLink, activity.resumeVersion) {
        if (!showHttpDownload && returnToHttpLink &&
            activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            withFrameNanos { }
            if (runCatching { httpLinkFocus.requestFocus() }.getOrDefault(false)) returnToHttpLink = false
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
        }, onDismiss = {
            showHttpDownload = false
            httpInitialAddress = ""
            httpSession = null
            val rebindId = httpRebindId
            httpRebindId = null
            if (rebindId == null) returnToHttpLink = true else httpRebindReturnId = rebindId
        }, session = httpSession, onRebind = httpRebindId?.let { id ->
            { admission ->
                runCatching {
                    TorrDownloadStore.bindSession(activity, id, admission)
                    activity.startTorrDownload(id)
                }.isSuccess
            }
        })
    }
    fun addHttpLink() {
        if (PortableMaintenance.running) return
        showHttpDownload = true
    }
    if (wide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingRow(tr(R.string.torr_download_add_file), modifier = Modifier.weight(1f).focusRequester(initialFocus), onClick = activity::chooseTorrFile)
            SettingRow(tr(R.string.http_download_add_link), modifier = Modifier.weight(1f).focusRequester(httpLinkFocus), onClick = ::addHttpLink)
            SettingRow(tr(R.string.browser_download_entry), modifier = Modifier.weight(1f), onClick = { openBrowser() })
            SettingRow(tr(if (showMagnet) R.string.downloads_magnet_hide else R.string.downloads_magnet_show), modifier = Modifier.weight(1f), onClick = { showMagnet = !showMagnet })
            SettingRow(tr(if (showOptions) R.string.downloads_options_hide else R.string.downloads_options_show), modifier = Modifier.weight(1f), onClick = { showOptions = !showOptions })
        }
    } else {
        SettingRow(tr(R.string.torr_download_add_file), modifier = Modifier.focusRequester(initialFocus), onClick = activity::chooseTorrFile)
        SettingRow(tr(R.string.http_download_add_link), modifier = Modifier.focusRequester(httpLinkFocus), onClick = ::addHttpLink)
        SettingRow(tr(R.string.browser_download_entry), onClick = { openBrowser() })
        SettingRow(tr(if (showMagnet) R.string.downloads_magnet_hide else R.string.downloads_magnet_show), onClick = { showMagnet = !showMagnet })
        SettingRow(tr(if (showOptions) R.string.downloads_options_hide else R.string.downloads_options_show), onClick = { showOptions = !showOptions })
    }
    if (activity.torrDownloadMessage.isNotBlank()) Text(activity.torrDownloadMessage,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = Color.LightGray, fontSize = 13.sp)
    if (showOptions) {
    val folder = activity.torrDownloadFolder
    val folderLabel = remember(folder) {
        runCatching { android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(folder)).substringAfterLast('/') }.getOrDefault("")
    }
    SettingRow(tr(R.string.torr_download_choose_folder), folderLabel.ifBlank { tr(R.string.torr_download_folder_hint) }, onClick = activity::chooseTorrFolder)
    SettingRow(tr(R.string.prefetch_setting), if (prefetchCount == 0) tr(R.string.disabled) else tr(R.string.prefetch_count, prefetchCount), onClick = {
        val choices = TorrPrefetch.counts
        val labels = choices.map { if (it == 0) activity.getString(R.string.disabled) else activity.getString(R.string.prefetch_count, it) }.toTypedArray()
        android.app.AlertDialog.Builder(activity).setTitle(R.string.prefetch_setting).setSingleChoiceItems(labels, choices.indexOf(prefetchCount)) { dialog, index ->
            runCatching { TorrPrefetch.setCount(activity, choices[index]) }.onSuccess { prefetchCount = choices[index] }
                .onFailure { activity.torrDownloadMessage = activity.getString(R.string.torr_download_failed) }
            dialog.dismiss()
        }.setNegativeButton(R.string.cancel, null).show()
    })
    SettingRow(tr(R.string.prefetch_quota), android.text.format.Formatter.formatShortFileSize(activity, prefetchQuota.toLong() * 1024 * 1024 * 1024), onClick = {
        val choices = TorrPrefetch.quotas
        val labels = choices.map { android.text.format.Formatter.formatShortFileSize(activity, it.toLong() * 1024 * 1024 * 1024) }.toTypedArray()
        android.app.AlertDialog.Builder(activity).setTitle(R.string.prefetch_quota).setSingleChoiceItems(labels, choices.indexOf(prefetchQuota)) { dialog, index ->
            runCatching { TorrPrefetch.setQuota(activity, choices[index]) }.onSuccess { prefetchQuota = choices[index] }
                .onFailure { activity.torrDownloadMessage = activity.getString(R.string.torr_download_failed) }
            dialog.dismiss()
        }.setNegativeButton(R.string.cancel, null).show()
    })
    Text(tr(R.string.prefetch_folder_hint), color = Color.LightGray, fontSize = 12.sp)
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
    val resumePrefs = remember(activity) { activity.getSharedPreferences("orbit", android.content.Context.MODE_PRIVATE) }
    var resumeInterrupted by remember(activity) { mutableStateOf(TorrDownloadResumeOptions.read(activity)) }
    var savingResumeOption by remember { mutableStateOf(false) }
    DisposableEffect(activity, resumePrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == TorrDownloadResumeOptions.KEY) resumeInterrupted = TorrDownloadResumeOptions.read(activity)
        }
        resumePrefs.registerOnSharedPreferenceChangeListener(listener)
        resumeInterrupted = TorrDownloadResumeOptions.read(activity)
        onDispose { resumePrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SettingRow(tr(R.string.torr_download_resume_on_open), tr(if (resumeInterrupted) R.string.enabled else R.string.disabled), onClick = {
        if (savingResumeOption) return@SettingRow
        val enabled = !resumeInterrupted
        savingResumeOption = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { TorrDownloadResumeOptions.write(activity.applicationContext, enabled) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                activity.torrDownloadMessage = activity.getString(R.string.torr_download_failed)
            } finally {
                savingResumeOption = false
            }
        }
    })
    Text(tr(R.string.torr_download_resume_on_open_hint), color = Color.LightGray, fontSize = 12.sp)
    SettingRow(tr(R.string.torr_media_show_setting), tr(if (showMediaTorrents) R.string.enabled else R.string.disabled), onClick = {
        TorrMediaVisibility.write(activity, !showMediaTorrents)
    })
    Text(tr(R.string.torr_media_show_hint), color = Color.LightGray, fontSize = 12.sp)
    SettingRow(tr(R.string.downloads_auto_cover), tr(if (autoCover) R.string.enabled else R.string.disabled), onClick = {
        TorrentArtwork.enabled(activity, !autoCover)
    })
    Text(tr(R.string.downloads_auto_cover_hint), color = Color.LightGray, fontSize = 12.sp)
    DownloadWriteLimits(activity)
    }
    if (showMagnet) {
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
    if (failed) {
        Text(tr(R.string.torr_download_unavailable), color = Color.LightGray, fontSize = 13.sp)
        SettingRow(tr(R.string.torr_download_open), onClick = activity::openTorrServer)
    }
    Text(tr(R.string.torr_download_queue), color = Color.White, fontSize = 16.sp)
    val availableJobs = if (journalLoading || journalFailed) emptyList() else jobs
    val activeJobs = availableJobs.filter { it.state != "done" }
    val finishedJobs = TorrDownloadHistory.completed(availableJobs, hiddenFinished)
    val folderFocusMissing = folderAccessReturnFocus != null && activeJobs.none { it.id == folderAccessReturnFocus }
    LaunchedEffect(folderFocusMissing, folderAccessBusy, journalLoading) {
        if (folderFocusMissing && !folderAccessBusy && !journalLoading) {
            withFrameNanos { }
            runCatching { initialFocus.requestFocus() }
            folderAccessReturnFocus = null
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingRow((if (!showFinished) "✓ " else "") + tr(R.string.torr_download_active_tab, activeJobs.size), modifier = Modifier.weight(1f), isSelected = !showFinished, onClick = { showFinished = false; jobPage = 0 })
        SettingRow((if (showFinished) "✓ " else "") + tr(R.string.torr_download_finished_tab, finishedJobs.size), modifier = Modifier.weight(1f).focusRequester(finishedTabFocus), isSelected = showFinished, onClick = { showFinished = true; jobPage = 0 })
    }
    val visibleJobs = if (showFinished) finishedJobs else activeJobs
    LaunchedEffect(visibleJobs.size, showFinished, finishedFocusId) {
        jobPage = torrDownloadHistoryPage(jobPage, visibleJobs.size)
        val removed = finishedFocusId
        if (!showFinished) finishedFocusId = null
        else if (removed != null && visibleJobs.none { it.id == removed }) {
            // The clicked control disappeared. A stable D-pad target also handles an empty last page.
            withFrameNanos { }
            runCatching { finishedTabFocus.requestFocus() }
            finishedFocusId = null
        }
    }
    val pausedCount = activeJobs.count { it.state == "paused" && !it.requiresHttpRestart && !needsSiteSession(it) }
    if (!showFinished && !journalLoading && !journalFailed && pausedCount > 1) SettingRow(tr(R.string.torr_download_resume_paused, pausedCount), onClick = {
        val paused = TorrDownloadStore.jobs.value.filter {
            it.state == "paused" && !it.requiresHttpRestart && !needsSiteSession(it)
        }.map { it.id }
        if (paused.isNotEmpty()) runCatching { activity.startTorrDownloads(paused) }
            .onFailure { activity.torrDownloadMessage = activity.getString(R.string.torr_download_failed) }
    })
    if (journalFailed) Text(tr(R.string.torr_download_failed), color = Color.LightGray, fontSize = 13.sp)
    if (journalLoading) Text(tr(R.string.torr_download_loading), color = Color.LightGray, fontSize = 13.sp)
    if (!journalLoading && !journalFailed && visibleJobs.isEmpty()) Text(tr(if (showFinished) R.string.torr_download_empty_finished else R.string.torr_download_empty_queue), color = Color.LightGray, fontSize = 13.sp)
    visibleJobs.drop(jobPage * 20).take(20).forEach { job ->
        val jobFocus = remember(job.id) { FocusRequester() }
        LaunchedEffect(job.id, httpRebindReturnId, activity.resumeVersion) {
            if (httpRebindReturnId == job.id && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                withFrameNanos { }
                if (runCatching { jobFocus.requestFocus() }.getOrDefault(false)) httpRebindReturnId = null
            }
        }
        var restartOffset by remember(job.id) { mutableStateOf<Long?>(null) }
        val restoreAccess = job.state == "error" && job.error == "storage" &&
            TorrDownloadFolderAccess.needsAccess(job.tree, folderGrants)
        fun dismissRestart() {
            restartOffset = null
            scope.launch {
                withFrameNanos { }
                runCatching { jobFocus.requestFocus() }
            }
        }
        restartOffset?.let { offset ->
            ConfirmHttpDownloadRestart(onDismiss = ::dismissRestart, onConfirm = {
                // A focus/queue refresh may have changed the row while the prompt was open.
                val current = TorrDownloadStore.jobs.value.firstOrNull { it.id == job.id }
                val confirmed = restartOffset == offset
                dismissRestart()
                if (confirmed && current != null && current.requiresHttpRestart && current.offset == offset &&
                    current.state in setOf("paused", "error") &&
                    !(current.error == "storage" && TorrDownloadFolderAccess.needsAccess(current.tree, folderGrants)) &&
                    !PortableMaintenance.running &&
                    !folderAccessBusy && folderAccessRequest == null && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    runCatching { activity.startTorrDownload(current.id, restartId = current.id) }
                        .onFailure { activity.torrDownloadMessage = activity.getString(R.string.torr_download_failed) }
                }
            })
        }
        LaunchedEffect(folderAccessReturnFocus == job.id, folderAccessBusy) {
            if (folderAccessReturnFocus == job.id && !folderAccessBusy) {
                withFrameNanos { }
                runCatching { jobFocus.requestFocus() }
                folderAccessReturnFocus = null
            }
        }
        val label = when (job.state) {
            "queued" -> R.string.torr_download_queued
            "running" -> when (job.error) {
                "retrying" -> R.string.torr_download_retrying
                "waiting_network" -> R.string.torr_download_waiting_network
                else -> R.string.torr_download_running
            }
            "paused" -> R.string.torr_download_paused
            "done" -> R.string.torr_download_done
            "finalizing" -> R.string.torr_download_finalizing
            else -> (if (job.http != null) httpDownloadJobErrorResource(job.error) else null) ?: when (job.error) {
                "space" -> R.string.torr_download_low_space
                "space_unavailable" -> R.string.torr_download_space_unknown
                "storage" -> R.string.torr_download_storage_error
                "video_invalid" -> R.string.torr_download_video_invalid
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
        val displayOffset = downloadDisplayOffset(job, liveProgress)
        val percent = if (job.total == 0L) { if (job.state == "done") 100 else 0 } else (displayOffset.toDouble() / job.total * 100).toInt().coerceIn(0, 100)
        val httpTorrent = job.http != null && job.finalName.endsWith(".torrent", ignoreCase = true)
        val action = when (job.state) {
            "queued", "running" -> tr(R.string.torr_download_pause)
            "paused", "error" -> if (job.error == "video_invalid") "" else tr(when {
                restoreAccess -> R.string.torr_folder_access_restore
                needsSiteSession(job) -> R.string.http_download_sign_in_again
                job.requiresHttpRestart -> R.string.http_download_restart
                else -> R.string.torr_download_resume
            })
            "done" -> when {
                httpTorrent -> tr(R.string.browser_import_torrent)
                job.http != null -> tr(R.string.http_download_open)
                else -> ""
            }
            else -> ""
        }
        val summary = "${tr(label)} · $percent%" + if (action.isNotEmpty()) " · $action" else ""
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingRow(job.name, summary, modifier = Modifier.weight(1f).focusRequester(jobFocus), onClick = {
                if (restoreAccess) { restoreFolderAccess(job); return@SettingRow }
                if (folderAccessBusy || folderAccessRequest != null) return@SettingRow
                runCatching {
                    when (job.state) {
                        "queued", "running" -> TorrDownloadActions.pause(activity, job.id)
                        "paused", "error" -> when {
                            job.error == "video_invalid" -> Unit // The separate removal action asks for confirmation.
                            needsSiteSession(job) -> openBrowser(job.id)
                            job.requiresHttpRestart -> restartOffset = job.offset
                            else -> activity.startTorrDownload(job.id)
                        }
                        "done" -> if (job.http != null && !openingHttp && !PortableMaintenance.running) {
                            openingHttp = true
                            scope.launch {
                                try {
                                    if (httpTorrent) {
                                        val document = withContext(Dispatchers.IO) { TorrDownloadDocuments.verifyCompletedHttp(activity, job) }
                                        activity.importTorrentUri(document)
                                    } else openCompletedHttpDownload(activity, job)
                                }
                                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (_: Exception) { activity.torrDownloadMessage = activity.getString(R.string.http_download_open_failed) }
                                finally { openingHttp = false }
                            }
                        } else Unit
                        else -> Unit
                    }
                }.onFailure { activity.torrDownloadMessage = activity.getString(R.string.torr_download_failed) }
            })
            val hideDescription = tr(R.string.torr_download_hide_finished)
            if (job.state == "done") SettingRow("×", modifier = Modifier.width(72.dp).semantics { contentDescription = hideDescription }, trailing = "", onClick = { hideFinished(job) })
        }
        if (job.state == "error" && job.error == "storage") Text(
            tr(when {
                restoreAccess -> R.string.torr_folder_access_hint
                job.requiresHttpRestart -> R.string.http_download_restart_access_check
                else -> R.string.torr_folder_access_check
            }),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = Color.LightGray, fontSize = 12.sp)
        if (job.state in setOf("running", "queued", "paused") && job.total > 0) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0xFF38383C))) {
                Box(Modifier.fillMaxWidth(percent / 100f).height(3.dp).background(Color(0xFFCCFF00)))
            }
        }
        if (job.state == "done" && PlatformRegistry.all.any { it.accepts(job.finalName) })
            SettingRow(tr(if (job.finalName.endsWith(".zip", ignoreCase = true)) R.string.game_archive_title else R.string.torr_game_add),
                onClick = { activity.addDownloadedGame(job) })
        if (job.state == "paused" || job.state == "error") SettingRow(
            tr(R.string.torr_download_remove), tr(R.string.torr_download_keep_partial), onClick = {
                android.app.AlertDialog.Builder(activity)
                    .setTitle(R.string.torr_download_remove_incomplete_title)
                    .setMessage(R.string.torr_download_remove_incomplete_message)
                    .setPositiveButton(R.string.torr_download_remove) { _, _ ->
                        removeJobRecord(job.id)
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
    if ((jobPage + 1) * 20 < visibleJobs.size) SettingRow("›", onClick = { jobPage++ })
    if (visibleJobs.any { it.state == "done" }) Text(tr(R.string.torr_download_keep_file), color = Color.LightGray, fontSize = 13.sp)
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

@Composable
private fun DownloadWriteLimits(activity: MainActivity) {
    val context = activity.applicationContext
    val prefs = remember(context) { context.getSharedPreferences("orbit", android.content.Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var regularRate by remember(context) { mutableIntStateOf(TorrDownloadWriteRate.read(context)) }
    var playbackRate by remember(context) { mutableIntStateOf(TorrDownloadWriteRate.readPlayback(context)) }
    var saving by remember { mutableStateOf(false) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == TorrDownloadWriteRate.KEY) regularRate = TorrDownloadWriteRate.read(context)
            if (key == null || key == TorrDownloadWriteRate.PLAYBACK_KEY) playbackRate = TorrDownloadWriteRate.readPlayback(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        regularRate = TorrDownloadWriteRate.read(context)
        playbackRate = TorrDownloadWriteRate.readPlayback(context)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun rateLabel(rate: Int, defaultLabel: Int): String = if (rate == 0) activity.getString(defaultLabel)
        else activity.getString(R.string.torr_status_rate_value, android.text.format.Formatter.formatShortFileSize(activity, rate.toLong() * 1024))

    fun pickRate(title: Int, defaultLabel: Int, rate: Int, write: (android.content.Context, Int) -> Unit) {
        if (saving) return
        val choices = TorrDownloadWriteRate.choices
        val labels = choices.map { rateLabel(it, defaultLabel) }.toTypedArray()
        android.app.AlertDialog.Builder(activity).setTitle(title)
            .setSingleChoiceItems(labels, choices.indexOf(rate)) { dialog, index ->
                dialog.dismiss()
                val choice = choices[index]
                saving = true
                scope.launch {
                    try { withContext(Dispatchers.IO) { write(context, choice) } }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { activity.torrDownloadMessage = activity.getString(R.string.torr_download_failed) }
                    finally { saving = false }
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    SettingRow(tr(R.string.torr_write_limit), rateLabel(regularRate, R.string.torr_write_unlimited), onClick = {
        pickRate(R.string.torr_write_limit, R.string.torr_write_unlimited, regularRate, TorrDownloadWriteRate::write)
    })
    Text(tr(R.string.torr_write_limit_note), color = Color.LightGray, fontSize = 13.sp)
    SettingRow(tr(R.string.torr_write_playback_limit), rateLabel(playbackRate, R.string.torr_write_playback_regular), onClick = {
        pickRate(R.string.torr_write_playback_limit, R.string.torr_write_playback_regular, playbackRate, TorrDownloadWriteRate::writePlayback)
    })
    Text(tr(R.string.torr_write_playback_hint), color = Color.LightGray, fontSize = 12.sp)
}
