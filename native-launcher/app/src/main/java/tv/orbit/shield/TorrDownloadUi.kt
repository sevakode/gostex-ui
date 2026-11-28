package tv.orbit.shield

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The screen is split into non-restartable children so ART can compile each method; the parent
// keeps every remembered state, so the children read and write the same state objects as before.
@Composable internal fun TorrDownloadUi(activity: MainActivity, initialFocus: FocusRequester, wide: Boolean = false) {
    val scope = rememberCoroutineScope()
    val jobsState = TorrDownloadStore.jobs.collectAsState()
    val liveProgressState = TorrDownloadLiveProgress.current.collectAsStateWithLifecycle()
    val entriesState = remember { mutableStateOf<List<TorrCatalogEntry>>(emptyList()) }
    var entries by entriesState
    val selectedState = remember { mutableStateOf<TorrCatalogEntry?>(null) }
    var selected by selectedState
    val magnetState = remember { mutableStateOf("") }
    var magnet by magnetState
    val busyState = remember { mutableStateOf(false) }
    var busy by busyState
    val preparingHashState = remember { mutableStateOf<String?>(null) }
    val metadataRetryInFlightState = remember { mutableStateOf(false) }
    var metadataRetryInFlight by metadataRetryInFlightState
    val metadataRetryGenerationState = remember { mutableStateOf(0L) }
    var metadataRetryGeneration by metadataRetryGenerationState
    val failedState = remember { mutableStateOf(false) }
    var failed by failedState
    val journalFailedState = remember { mutableStateOf(false) }
    var journalFailed by journalFailedState
    val journalLoadingState = remember { mutableStateOf(true) }
    var journalLoading by journalLoadingState
    var removingJobId by remember { mutableStateOf<String?>(null) }
    val discardErrorIdState = remember { mutableStateOf<String?>(null) }
    val pageState = remember { mutableStateOf(0) }
    var page by pageState
    val filePageState = remember { mutableStateOf(0) }
    var filePage by filePageState
    val jobPageState = rememberSaveable { mutableStateOf(0) }
    var jobPage by jobPageState
    val showFinishedState = rememberSaveable { mutableStateOf(false) }
    var showFinished by showFinishedState
    val showHttpDownloadState = remember { mutableStateOf(false) }
    var showHttpDownload by showHttpDownloadState
    val httpInitialAddressState = remember { mutableStateOf("") }
    var httpInitialAddress by httpInitialAddressState
    val httpSessionState = remember { mutableStateOf<HttpDownloadSession?>(null) }
    var httpSession by httpSessionState
    val httpRebindIdState = remember { mutableStateOf<String?>(null) }
    var httpRebindId by httpRebindIdState
    var browserRebindId by rememberSaveable { mutableStateOf<String?>(null) }
    val httpRebindReturnIdState = remember { mutableStateOf<String?>(null) }
    var httpRebindReturnId by httpRebindReturnIdState
    val httpEntryFocusState = remember { mutableStateOf<FocusRequester?>(null) }
    var httpEntryFocus by httpEntryFocusState
    val httpReturnFocusState = remember { mutableStateOf<FocusRequester?>(null) }
    val openingHttpState = remember { mutableStateOf(false) }
    val httpLinkFocus = remember { FocusRequester() }
    val browserFocus = remember { FocusRequester() }
    val folderAccessRequestState = rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var folderAccessRequest by folderAccessRequestState
    val folderAccessReturnFocusState = rememberSaveable { mutableStateOf<String?>(null) }
    var folderAccessReturnFocus by folderAccessReturnFocusState
    // Whether the completed row's game button, rather than the row itself, started the restore.
    val folderAccessFromGameState = rememberSaveable { mutableStateOf(false) }
    var folderAccessFromGame by folderAccessFromGameState
    val folderAccessBusyState = remember { mutableStateOf(false) }
    var folderAccessBusy by folderAccessBusyState
    val folderGrantsState = remember { mutableStateOf<Set<TorrDownloadFolderAccess.Identity>?>(null) }
    var folderGrants by folderGrantsState
    // Completed files only need to be read; their recorded folder may hold a read-only grant.
    var folderReadGrants by remember { mutableStateOf<Set<TorrDownloadFolderAccess.Identity>?>(null) }
    fun needsSiteSession(job: TorrDownloadJob) = job.requiresHttpSession && TorrDownloadStore.sessionFor(job.id) == null
    fun needsFolderAccess(job: TorrDownloadJob) =
        TorrDownloadFolderAccess.needsRestore(job.state, job.error, job.tree, folderGrants, folderReadGrants)
    fun captureFolderRequest(job: TorrDownloadJob) =
        TorrDownloadFolderAccess.Request(job.id, job.tree, job.document, job.state, job.error)
    val folderSnapshotGeneration = remember { intArrayOf(0) }
    LaunchedEffect(activity.resumeVersion) {
        val generation = ++folderSnapshotGeneration[0]
        val snapshot = withContext(Dispatchers.IO) { TorrDownloadFolderAccess.snapshotGrants(activity.contentResolver) }
        if (generation == folderSnapshotGeneration[0]) {
            folderGrants = snapshot?.readWrite
            folderReadGrants = snapshot?.read
        }
    }
    val hiddenFinishedState = remember(activity) { mutableStateOf(TorrDownloadHistory.read(activity)) }
    var hiddenFinished by hiddenFinishedState
    DisposableEffect(activity) {
        val stopObserving = TorrDownloadHistory.observe(activity) { hiddenFinished = it }
        onDispose { stopObserving() }
    }
    val folderAccessPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val saved = folderAccessRequest
        folderAccessRequest = null
        val request = saved?.takeIf { it.size == 5 }
            ?.let { TorrDownloadFolderAccess.Request(it[0], it[1], it[2], it[3], it[4]) }
        folderAccessBusy = true
        ++folderSnapshotGeneration[0]
        scope.launch {
            try {
                val ready = TorrDownloadStartup.prepare(activity.applicationContext)
                val result = withContext(Dispatchers.IO) {
                    val current = if (ready && !PortableMaintenance.running) TorrDownloadStore.jobs.value
                        .firstOrNull { it.id == request?.id }?.let(::captureFolderRequest) else null
                    TorrDownloadFolderAccess.restore(activity.contentResolver, request, current, uri)
                }
                ++folderSnapshotGeneration[0]
                folderGrants = result.grants
                folderReadGrants = result.readGrants
                activity.torrDownloadMessage = activity.getString(when (result.outcome) {
                    TorrDownloadFolderAccess.Outcome.RESTORED -> {
                        val job = TorrDownloadStore.jobs.value.firstOrNull { it.id == request?.id }
                        when {
                            request?.state == "done" -> if (job != null && downloadGameReadsFolder(job))
                                R.string.torr_folder_access_game_restored else R.string.torr_folder_access_finished_restored
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
                val all = TorrDownloadStore.jobs.value
                if (request?.state == "done") {
                    // Return to the same completed row without touching the job, file or default folder.
                    val index = TorrDownloadHistory.completed(all, hiddenFinished).indexOfFirst { it.id == request?.id }
                    if (index >= 0) { showFinished = true; jobPage = index / 20 }
                } else {
                    val index = all.filter { it.state != "done" }.indexOfFirst { it.id == request?.id }
                    if (index >= 0) { showFinished = false; jobPage = index / 20 }
                }
            }
        }
    }
    fun restoreFolderAccess(job: TorrDownloadJob, fromGame: Boolean) {
        if (folderAccessBusy || folderAccessRequest != null || PortableMaintenance.running || !needsFolderAccess(job)) return
        val probe = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)
        val resolved = runCatching { activity.packageManager.resolveActivity(probe,
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo }.getOrNull()
        if (resolved == null || !resolved.enabled || !resolved.applicationInfo.enabled ||
            resolved.packageName == "com.android.tv.frameworkpackagestubs") {
            activity.torrDownloadMessage = activity.getString(R.string.document_picker_unavailable)
            return
        }
        folderAccessFromGame = fromGame
        folderAccessRequest = arrayListOf(job.id, job.tree, job.document, job.state, job.error)
        activity.torrDownloadMessage = activity.getString(
            if (job.state == "done") finishedFolderAccessHint(job) else R.string.torr_folder_access_hint)
        try { folderAccessPicker.launch(android.net.Uri.parse(job.tree)) }
        catch (_: Exception) {
            folderAccessRequest = null
            folderAccessReturnFocus = job.id
            activity.torrDownloadMessage = activity.getString(R.string.document_picker_unavailable)
        }
    }
    val finishedTabFocus = remember { FocusRequester() }
    val finishedFocusIdState = remember { mutableStateOf<String?>(null) }
    var finishedFocusId by finishedFocusIdState
    val chosenFilesState = remember { mutableStateOf<Set<Int>>(emptySet()) }
    var chosenFiles by chosenFilesState
    val showOptionsState = remember { mutableStateOf(false) }
    val showOptions by showOptionsState
    val showMagnetState = remember { mutableStateOf(false) }
    var showMagnet by showMagnetState
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
                    httpEntryFocus = if (rebindId == null) browserFocus else null
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
                    httpEntryFocus = browserFocus
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
    val reserveMiBState = remember { mutableStateOf(TorrDownloadSpace.read(activity)) }
    val prefetchCountState = remember { mutableIntStateOf(TorrPrefetch.count(activity)) }
    val prefetchQuotaState = remember { mutableIntStateOf(TorrPrefetch.quota(activity)) }
    val autoRetryState = remember { mutableStateOf(TorrDownloadRetryOptions.read(activity)) }
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
    val linkedHashesState = remember { mutableStateOf<Set<String>?>(null) }
    var linkedHashes by linkedHashesState
    val linkCheckFailedState = remember { mutableStateOf(false) }
    var linkCheckFailed by linkCheckFailedState
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
        TorrDownloadSelectedUi(activity, current, scope, fileFocus, busyState, selectedState, filePageState, chosenFilesState,
            preparingHashState, metadataRetryInFlightState, metadataRetryGenerationState, journalLoadingState, journalFailedState,
            leaveSelected = { leaveSelected() }, refresh = { refresh() })
        return
    }

    TorrDownloadHttpEntryUi(activity, showHttpDownloadState, httpReturnFocusState, httpInitialAddressState, httpSessionState,
        httpRebindIdState, httpRebindReturnIdState, httpEntryFocusState, folderGrantsState, showFinishedState, jobPageState)
    fun addHttpLink() {
        if (PortableMaintenance.running) return
        httpEntryFocus = httpLinkFocus
        showHttpDownload = true
    }
    TorrDownloadAddButtonsUi(activity, wide, initialFocus, httpLinkFocus, browserFocus, showMagnetState, showOptionsState,
        addHttpLink = ::addHttpLink, openBrowser = { openBrowser() })
    if (activity.torrDownloadMessage.isNotBlank()) Text(activity.torrDownloadMessage,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = Color.LightGray, fontSize = 13.sp)
    if (showOptions) {
        TorrDownloadOptionsUi(activity, scope, reserveMiBState, prefetchCountState, prefetchQuotaState, autoRetryState,
            showMediaTorrents, autoCover)
    }
    if (showMagnet) {
        TorrDownloadMagnetUi(activity, scope, magnetState, busyState, magnetSubmitFocus)
    }
    if (failed) {
        Text(tr(R.string.torr_download_unavailable), color = Color.LightGray, fontSize = 13.sp)
        SettingRow(tr(R.string.torr_download_open), onClick = activity::openTorrServer)
    }
    val visibleJobs = torrDownloadQueueHeader(activity, initialFocus, jobsState, journalLoadingState, journalFailedState,
        hiddenFinishedState, folderAccessReturnFocusState, folderAccessBusyState, folderAccessRequestState, showFinishedState,
        jobPageState, finishedFocusIdState, finishedTabFocus, needsSiteSession = ::needsSiteSession,
        needsFolderAccess = ::needsFolderAccess)
    val firstJobFocus = remember { FocusRequester() }
    visibleJobs.drop(jobPage * 20).take(20).forEachIndexed { index, job ->
        TorrDownloadJobRowUi(activity, job, scope, liveProgressState, httpRebindReturnIdState, folderAccessReturnFocusState,
            folderAccessFromGameState, folderAccessBusyState, folderAccessRequestState, busyState, discardErrorIdState,
            openingHttpState, if (index == 0) firstJobFocus else null,
            needsSiteSession = ::needsSiteSession, needsFolderAccess = ::needsFolderAccess,
            restoreFolderAccess = ::restoreFolderAccess, openBrowser = { openBrowser(it) },
            removeJobRecord = { removeJobRecord(it) }, hideFinished = ::hideFinished)
    }
    TorrDownloadJobPagerUi(jobPageState, visibleJobs.size, firstJobFocus)
    if (visibleJobs.any { it.state == "done" }) Text(tr(R.string.torr_download_keep_file), color = Color.LightGray, fontSize = 13.sp)
    TorrDownloadCatalogUi(activity, scope, entriesState, linkedHashesState, linkCheckFailedState, showMediaTorrents, failedState,
        busyState, pageState, preparingHashState, metadataRetryInFlightState, selectedState, filePageState, chosenFilesState,
        refresh = { refresh() })
}

/** Download settings; the remembered resume option resets when the section is hidden, as before. */
@Composable @NonRestartableComposable
private fun TorrDownloadOptionsUi(
    activity: MainActivity,
    scope: CoroutineScope,
    reserveMiBState: MutableState<Int>,
    prefetchCountState: MutableIntState,
    prefetchQuotaState: MutableIntState,
    autoRetryState: MutableState<Boolean>,
    showMediaTorrents: Boolean,
    autoCover: Boolean,
) {
    var reserveMiB by reserveMiBState
    var prefetchCount by prefetchCountState
    var prefetchQuota by prefetchQuotaState
    var autoRetry by autoRetryState
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

/**
 * Queue page buttons. A page turn can remove the pressed button (first or last page), so focus then moves to the
 * pager button that remains, or to the first row; only a pager press requests focus.
 */
@Composable @NonRestartableComposable
private fun TorrDownloadJobPagerUi(jobPageState: MutableState<Int>, jobCount: Int, firstJobFocus: FocusRequester) {
    var jobPage by jobPageState
    val count by rememberUpdatedState(jobCount)
    val previousFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    val previousInView = remember { BringIntoViewRequester() }
    val nextInView = remember { BringIntoViewRequester() }
    val pagerScope = rememberCoroutineScope()
    // -1 after "‹", 1 after "›", 0 when no pager focus is pending.
    var pagerFocus by remember { mutableIntStateOf(0) }
    LaunchedEffect(pagerFocus) {
        if (pagerFocus == 0) return@LaunchedEffect
        withFrameNanos { }
        val hasPrevious = jobPageState.value > 0
        val hasNext = (jobPageState.value + 1) * 20 < count
        val target = when {
            pagerFocus < 0 && hasPrevious -> previousFocus
            pagerFocus > 0 && hasNext -> nextFocus
            hasPrevious -> previousFocus
            hasNext -> nextFocus
            else -> firstJobFocus
        }
        pagerFocus = 0
        val onFirstRow = !runCatching { target.requestFocus() }.getOrDefault(false)
        if (onFirstRow) runCatching { firstJobFocus.requestFocus() }
        // A button that keeps focus is not scrolled by the focus change, and a page turn changes the
        // content height after the request, so scroll once the new layout is in place. Resetting
        // pagerFocus restarts this effect, so the scroll runs in its own scope.
        else if (target !== firstJobFocus) pagerScope.launch {
            val inView = if (target === previousFocus) previousInView else nextInView
            withFrameNanos { }
            withFrameNanos { }
            runCatching { inView.bringIntoView() }
            delay(150)
            runCatching { inView.bringIntoView() }
        }
    }
    if (jobPage > 0) SettingRow("‹", modifier = Modifier.bringIntoViewRequester(previousInView).focusRequester(previousFocus),
        onClick = { jobPage--; pagerFocus = -1 })
    if ((jobPage + 1) * 20 < jobCount) SettingRow("›", modifier = Modifier.bringIntoViewRequester(nextInView).focusRequester(nextFocus),
        onClick = { jobPage++; pagerFocus = 1 })
}

/** Queue title, tabs and batch controls; returns the jobs of the selected tab for the rows below. */
@Composable @NonRestartableComposable
private fun torrDownloadQueueHeader(
    activity: MainActivity,
    initialFocus: FocusRequester,
    jobsState: State<List<TorrDownloadJob>>,
    journalLoadingState: State<Boolean>,
    journalFailedState: State<Boolean>,
    hiddenFinishedState: State<Set<String>>,
    folderAccessReturnFocusState: MutableState<String?>,
    folderAccessBusyState: State<Boolean>,
    folderAccessRequestState: State<ArrayList<String>?>,
    showFinishedState: MutableState<Boolean>,
    jobPageState: MutableState<Int>,
    finishedFocusIdState: MutableState<String?>,
    finishedTabFocus: FocusRequester,
    needsSiteSession: (TorrDownloadJob) -> Boolean,
    needsFolderAccess: (TorrDownloadJob) -> Boolean,
): List<TorrDownloadJob> {
    val jobs by jobsState
    val journalLoading by journalLoadingState
    val journalFailed by journalFailedState
    val hiddenFinished by hiddenFinishedState
    var folderAccessReturnFocus by folderAccessReturnFocusState
    val folderAccessBusy by folderAccessBusyState
    val folderAccessRequest by folderAccessRequestState
    var showFinished by showFinishedState
    var jobPage by jobPageState
    var finishedFocusId by finishedFocusIdState
    Text(tr(R.string.torr_download_queue), color = Color.White, fontSize = 16.sp)
    val availableJobs = if (journalLoading || journalFailed) emptyList() else jobs
    val activeJobs = availableJobs.filter { it.state != "done" }
    val finishedJobs = TorrDownloadHistory.completed(availableJobs, hiddenFinished)
    val folderFocusMissing = folderAccessReturnFocus != null &&
        (if (showFinished) finishedJobs else activeJobs).none { it.id == folderAccessReturnFocus }
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
    fun canResumeInBatch(job: TorrDownloadJob) = job.state == "paused" && !job.requiresHttpRestart &&
        !needsSiteSession(job) && !needsFolderAccess(job)
    val pausedCount = activeJobs.count(::canResumeInBatch)
    if (!showFinished && !journalLoading && !journalFailed && pausedCount > 1) SettingRow(tr(R.string.torr_download_resume_paused, pausedCount), onClick = {
        if (folderAccessBusy || folderAccessRequest != null) return@SettingRow
        val paused = TorrDownloadStore.jobs.value.filter(::canResumeInBatch).map { it.id }
        if (paused.isNotEmpty()) runCatching { activity.startTorrDownloads(paused) }
            .onFailure { activity.torrDownloadMessage = activity.getString(R.string.torr_download_failed) }
    })
    if (journalFailed) Text(tr(R.string.torr_download_failed), color = Color.LightGray, fontSize = 13.sp)
    if (journalLoading) Text(tr(R.string.torr_download_loading), color = Color.LightGray, fontSize = 13.sp)
    if (!journalLoading && !journalFailed && visibleJobs.isEmpty()) Text(tr(if (showFinished) R.string.torr_download_empty_finished else R.string.torr_download_empty_queue), color = Color.LightGray, fontSize = 13.sp)
    return visibleJobs
}

/** One queue row with its status, actions and confirmations; per-job state is keyed by the job id as before. */
@Composable @NonRestartableComposable
private fun TorrDownloadJobRowUi(
    activity: MainActivity,
    job: TorrDownloadJob,
    scope: CoroutineScope,
    liveProgressState: State<TorrDownloadProgress?>,
    httpRebindReturnIdState: MutableState<String?>,
    folderAccessReturnFocusState: MutableState<String?>,
    folderAccessFromGameState: MutableState<Boolean>,
    folderAccessBusyState: State<Boolean>,
    folderAccessRequestState: State<ArrayList<String>?>,
    busyState: MutableState<Boolean>,
    discardErrorIdState: MutableState<String?>,
    openingHttpState: MutableState<Boolean>,
    pageFocus: FocusRequester?,
    needsSiteSession: (TorrDownloadJob) -> Boolean,
    needsFolderAccess: (TorrDownloadJob) -> Boolean,
    restoreFolderAccess: (job: TorrDownloadJob, fromGame: Boolean) -> Unit,
    openBrowser: (String) -> Unit,
    removeJobRecord: (String) -> Unit,
    hideFinished: (TorrDownloadJob) -> Unit,
) {
    val liveProgress by liveProgressState
    var httpRebindReturnId by httpRebindReturnIdState
    var folderAccessReturnFocus by folderAccessReturnFocusState
    var folderAccessFromGame by folderAccessFromGameState
    val folderAccessBusy by folderAccessBusyState
    val folderAccessRequest by folderAccessRequestState
    var busy by busyState
    var discardErrorId by discardErrorIdState
    var openingHttp by openingHttpState
    val jobFocus = remember(job.id) { FocusRequester() }
    val jobInView = remember(job.id) { BringIntoViewRequester() }
    val gameFocus = remember(job.id) { FocusRequester() }
    val gameInView = remember(job.id) { BringIntoViewRequester() }
    LaunchedEffect(job.id, httpRebindReturnId, activity.resumeVersion) {
        if (httpRebindReturnId == job.id && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            withFrameNanos { }
            if (runCatching { jobFocus.requestFocus() }.getOrDefault(false)) httpRebindReturnId = null
        }
    }
    var restartOffset by remember(job.id) { mutableStateOf<Long?>(null) }
    val restoreAccess = needsFolderAccess(job)
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
                !PortableMaintenance.running &&
                !folderAccessBusy && folderAccessRequest == null && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                if (needsFolderAccess(current)) restoreFolderAccess(current, false)
                else {
                    runCatching { activity.startTorrDownload(current.id, restartId = current.id) }
                        .onFailure { activity.torrDownloadMessage = activity.getString(R.string.torr_download_failed) }
                }
            }
        })
    }
    LaunchedEffect(folderAccessReturnFocus == job.id, folderAccessBusy) {
        if (folderAccessReturnFocus == job.id && !folderAccessBusy) {
            withFrameNanos { }
            // Return to the control that started the restore: the game button or the row itself.
            val game = folderAccessFromGame && job.state == "done" && downloadGameAction(job) != null
            folderAccessFromGame = false
            if (!game || !runCatching { gameFocus.requestFocus() }.getOrDefault(false)) runCatching { jobFocus.requestFocus() }
            // The control may keep focus while a new status line above it pushes it off-screen.
            runCatching { (if (game) gameInView else jobInView).bringIntoView() }
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
            restoreAccess -> tr(R.string.torr_folder_access_restore)
            httpTorrent -> tr(R.string.browser_import_torrent)
            job.http != null -> tr(R.string.http_download_open)
            else -> ""
        }
        else -> ""
    }
    val summary = "${tr(label)} · $percent%" + if (action.isNotEmpty()) " · $action" else ""
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingRow(job.name, summary, modifier = Modifier.weight(1f).bringIntoViewRequester(jobInView).focusRequester(jobFocus)
            .then(if (pageFocus != null) Modifier.focusRequester(pageFocus) else Modifier), onClick = {
            if (restoreAccess) { restoreFolderAccess(job, false); return@SettingRow }
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
    if (restoreAccess || (job.state == "error" && TorrDownloadFolderAccess.canRestore(job.state, job.error))) Text(
        tr(when {
            restoreAccess && job.state == "done" -> finishedFolderAccessHint(job)
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
    val gameAction = if (job.state == "done") downloadGameAction(job) else null
    if (gameAction != null) {
        // Without the read grant the import or archive check could only fail; regrant the exact folder first.
        SettingRow(tr(gameAction), if (restoreAccess && downloadGameReadsFolder(job)) tr(R.string.torr_folder_access_restore) else "",
            modifier = Modifier.bringIntoViewRequester(gameInView).focusRequester(gameFocus),
            onClick = {
                if (needsFolderAccess(job) && downloadGameReadsFolder(job)) restoreFolderAccess(job, true)
                else activity.addDownloadedGame(job)
            })
    }
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

/** The completed-row game action, matching [MainActivity.addDownloadedGame]; null when the file is not a game. */
private fun downloadGameAction(job: TorrDownloadJob): Int? = when {
    GameArchiveFormat.supportsName(job.finalName) -> R.string.game_archive_title
    PlatformRegistry.all.any { it.accepts(job.finalName) } -> R.string.torr_game_add
    else -> null
}

/**
 * Archive inspection and single-file import read the recorded document through its download tree, so they need
 * its read grant. Disc sheets (cue/gdi/m3u) instead ask for their own library folder in the picker.
 */
private fun downloadGameReadsFolder(job: TorrDownloadJob): Boolean = downloadGameAction(job) != null &&
    (GameArchiveFormat.supportsName(job.finalName) ||
        job.finalName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) !in setOf("cue", "gdi", "m3u"))

private fun finishedFolderAccessHint(job: TorrDownloadJob): Int =
    if (downloadGameReadsFolder(job)) R.string.torr_folder_access_game_hint else R.string.torr_folder_access_finished_hint

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
