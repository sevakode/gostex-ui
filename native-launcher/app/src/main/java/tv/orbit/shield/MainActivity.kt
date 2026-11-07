package tv.orbit.shield

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.border
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Ink = Color(0xFF0A0A0A)
private val Panel = Color(0xFF1F1F21)
private val Muted = Color(0xFFADADB0)
private val Lime = Color(0xFFCCFF00)
private val White = Color(0xFFF5F5F5)

@Composable private fun platformTitle(id: String): String = PlatformRegistry.find(id)?.let { tr(it.titleRes) } ?: id

data class Game(
    val id: String, val title: String, val platform: String, val emulator: String,
    val component: String, val files: List<String>, val tree: String,
    val cover: String, val scene: String, val genre: String, val year: String,
    val developer: String, val players: String, val demo: Boolean = false, val description: String = ""
)

private data class PortablePreviewDecisionSession(
    val commitId: String,
    val live: PortableLiveLibrary,
    val roots: List<LibraryRootRecord>,
    val plan: PortableRestorePlan,
    val conflicts: Set<PortableRestoreConflict>,
)

private class LegacyRecoveryNotice(val messageId: Int) : Exception()

class MainActivity : ComponentActivity() {
    private val updatesLazy = lazy { LauncherUpdates(this) }
    internal val updates: LauncherUpdates by updatesLazy
    private val diagnosticExport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (PortableMaintenance.running) return@registerForActivityResult
        if (uri != null) updates.exportDiagnostics(uri)
    }
    internal fun chooseDiagnosticExport() {
        launchDocumentPicker(Intent.ACTION_CREATE_DOCUMENT, "application/zip") { diagnosticExport.launch("gostex-diagnostics.zip") }
    }
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(if (PortableRestoreStartup.ready && !PortableMaintenance.running) Language.context(base) else base)
    }
    fun setLanguage(code: String) {
        if (code !in setOf("ru", "en", "es")) return
        val changed = code != resources.configuration.locales[0].language
        if (getSharedPreferences("orbit", MODE_PRIVATE).edit().putString("language", code).commit() && changed) recreate()
    }
    private var pending: Pair<Game, Int>? = null
    internal val notices = LauncherNoticeState()
    internal var torrCatalogRevision by mutableIntStateOf(0)
        private set
    internal fun torrCatalogChanged() { torrCatalogRevision++ }
    internal var torrDownloadFolder by mutableStateOf("")
        private set
    internal var torrDownloadMessage by mutableStateOf("")
    internal var incomingTorrentUri by mutableStateOf<Uri?>(null)
    private var torrentPlaybackDialog: android.app.AlertDialog? = null
    private var torrPreparing = false
    internal fun openTorrServer() {
        val intent = packageManager.getLeanbackLaunchIntentForPackage("ru.yourok.torrserve")
            ?: packageManager.getLaunchIntentForPackage("ru.yourok.torrserve")
        if (intent == null || runCatching { startActivity(intent) }.isFailure) torrDownloadMessage = getString(R.string.torr_download_unavailable)
    }
    private var pendingTorrNotificationStart: List<String>? = null
    private val torrNotificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        val ids = pendingTorrNotificationStart
        pendingTorrNotificationStart = null
        if (ids != null) runCatching { TorrDownloadActions.startMany(this, ids) }
            .onFailure { torrDownloadMessage = getString(R.string.torr_download_failed) }
    }
    internal fun startTorrDownload(id: String) = startTorrDownloads(listOf(id))
    private var torrGameImporting = false
    internal fun addDownloadedGame(job: TorrDownloadJob) {
        if (PortableMaintenance.running || torrGameImporting || job.state != "done") return
        val systems = PlatformRegistry.all.filter { it.accepts(job.finalName) }
        if (systems.isEmpty()) { torrDownloadMessage = getString(R.string.torr_game_unsupported); return }
        val emulator = LibraryPreferences.read(this).ndsEmulator
        val labels = systems.map { "${getString(it.titleRes)} · ${PlatformRegistry.resolve(it.id, emulator)?.emulator ?: it.emulator}" }.toTypedArray()
        android.app.AlertDialog.Builder(this).setTitle(R.string.choose_system).setItems(labels) { _, index ->
            val platform = systems[index].id
            val extension = job.finalName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
            if (extension in setOf("cue", "gdi", "m3u")) {
                android.app.AlertDialog.Builder(this).setTitle(R.string.import_game_folder)
                    .setMessage(R.string.import_file_companions_hint)
                    .setPositiveButton(R.string.import_game_folder) { _, _ ->
                        val initial = runCatching { DocumentsContract.buildDocumentUriUsingTree(Uri.parse(job.tree), job.parentId.ifBlank { DocumentsContract.getTreeDocumentId(Uri.parse(job.tree)) }) }.getOrNull()
                        chooseLibraryFolder(platform, LibrarySource.FILES, initial)
                    }.setNegativeButton(R.string.cancel, null).show()
            } else {
                torrGameImporting = true
                torrDownloadMessage = getString(R.string.torr_game_checking)
                importScope.launch {
                    try {
                        val root = withContext(Dispatchers.IO) {
                            check(TorrDownloadStore.jobs.value.any { it.id == job.id && it.state == "done" && it.document == job.document && it.tree == job.tree })
                            val tree = Uri.parse(job.tree)
                            val document = Uri.parse(job.document)
                            check(document.authority == tree.authority && contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission })
                            val parentId = job.parentId.ifBlank { DocumentsContract.getTreeDocumentId(tree) }
                            check(portableSafDirectChild(contentResolver, tree, parentId, DocumentsContract.getDocumentId(document)))
                            val valid = contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { cursor ->
                                cursor.moveToFirst() && cursor.getString(0) == job.finalName && !cursor.isNull(1) && cursor.getLong(1) == job.total && !cursor.moveToNext()
                            } ?: false
                            check(valid)
                            val candidate = LibraryImport.Root(platform, document.toString(), LibrarySource.SINGLE_FILE)
                            check(LibraryImport.scan(this@MainActivity, candidate, bundled, showHints = false).isNotEmpty())
                            candidate
                        }
                        check(!PortableMaintenance.running && TorrDownloadStore.jobs.value.any { it.id == job.id && it.state == "done" && it.document == job.document })
                        LibraryImport.addRoot(this@MainActivity, root)
                        refreshLibrary()
                        torrDownloadMessage = getString(R.string.torr_game_added)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { torrDownloadMessage = getString(R.string.torr_game_failed) }
                    finally { torrGameImporting = false }
                }
            }
        }.setNegativeButton(R.string.cancel, null).show()
    }
    internal fun startTorrDownloads(ids: List<String>) {
        val prefs = getSharedPreferences("orbit", MODE_PRIVATE)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("downloads.notificationPrompted", false)) {
            pendingTorrNotificationStart = ids
            prefs.edit().putBoolean("downloads.notificationPrompted", true).apply()
            torrNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else TorrDownloadActions.startMany(this, ids)
    }
    private var pendingTorrDownload: Pair<TorrCatalogEntry, List<TorrCatalogFile>>? = null
    private val torrFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importTorrentUri(uri)
    }
    internal fun importTorrentUri(uri: Uri, openMedia: Boolean = false) {
        if (!PortableMaintenance.running) importScope.launch {
            try {
                val entry = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { TorrServerCatalog.upload(it) }
                        ?: throw java.io.IOException("Cannot open torrent")
                }
                onTorrentImported(entry, openMedia)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { torrDownloadMessage = getString(R.string.torr_download_failed) }
        }
    }
    internal fun importMagnet(uri: Uri, openMedia: Boolean = false) {
        val magnet = uri.toString()
        if (PortableMaintenance.running || !MovieLibraryStore.validSource(magnet) ||
            !uri.scheme.equals("magnet", ignoreCase = true) || magnet.contains('\r') || magnet.contains('\n')) return
        importScope.launch {
            try {
                val entry = withContext(Dispatchers.IO) { TorrServerCatalog.addMagnet(magnet) }
                onTorrentImported(entry, openMedia)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { torrDownloadMessage = getString(R.string.torr_download_failed) }
        }
    }

    private fun onTorrentImported(entry: TorrCatalogEntry, openMedia: Boolean) {
        torrCatalogRevision++
        torrDownloadMessage = getString(R.string.torr_download_torrent_added)
        findTorrentCover(entry)
        // Never interrupt another app if the import finished after the user left.
        if (openMedia && !isFinishing && !isDestroyed &&
            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            runCatching { startActivity(Intent(this, MovieLibraryActivity::class.java).apply {
                putExtra(MovieLibraryActivity.EXTRA_TORRENT_HASH, entry.hash)
                putExtra(MovieLibraryActivity.EXTRA_TORRENT_TITLE, entry.title)
            }) }.onSuccess { torrDownloadMessage = "" }
                .onFailure { torrDownloadMessage = getString(R.string.torr_download_failed) }
        }
    }

    internal fun prepareTorrPlayback(hash: String, fileId: Int) {
        if (PortableMaintenance.running) return
        torrentPlaybackDialog?.dismiss()
        var dialog: android.app.AlertDialog? = null
        dialog = TorrPreparationDialog.show(this, hash, fileId, onOpen = {
            runCatching {
                val url = Uri.parse(TorrPlayback.url(hash, fileId))
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(url, "video/*"))
            }.onFailure { torrDownloadMessage = getString(R.string.torr_play_failed) }
        }, onDismiss = { if (torrentPlaybackDialog === dialog) torrentPlaybackDialog = null })
        torrentPlaybackDialog = dialog
    }

    internal fun findTorrentCover(entry: TorrCatalogEntry) {
        if (!TorrentArtwork.enabled(this) || PortableMaintenance.running) return
        val id = runCatching { TorrentArtwork.id(entry.hash) }.getOrNull() ?: return
        if (managedCovers[id]?.let { it.manual.isNotEmpty() || it.original.isNotEmpty() } == true) return
        importScope.launch {
            try {
                val found = withContext(Dispatchers.IO) { TorrentArtwork.find(this@MainActivity, entry.title) } ?: return@launch
                if (!TorrentArtwork.enabled(this@MainActivity) || PortableMaintenance.running) return@launch
                coverEdits++
                try {
                    managedCovers = withContext(Dispatchers.IO) { ManagedCoverStore.importTorrentAutomatic(this@MainActivity, id, found.bytes, found.sourceName, found.sourceUrl) }
                    coverRevision++
                } finally { coverEdits-- }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { /* No cover is better than an incorrect one. */ }
        }
    }
    private val mediaCoverLock = Mutex()
    private val mediaCoverAttempts = MediaCoverRetry()
    internal suspend fun fetchMediaCover(movie: SavedMovie) {
        val id = "movie:${movie.id}"
        val poster = movie.poster ?: return
        fun enabled() = getSharedPreferences("orbit", MODE_PRIVATE).readBooleanOrDefault(MediaNavigation.AUTO_COVER_KEY, true)
        if (!enabled() || managedCovers[id]?.let { it.manual.isNotEmpty() || it.original.isNotEmpty() } == true) return
        if (!mediaCoverAttempts.begin(id, poster, android.os.SystemClock.elapsedRealtime())) return
        var failedAttempt = false
        try {
            mediaCoverLock.withLock {
                if (!enabled() || PortableMaintenance.running) return@withLock
                val found = withContext(Dispatchers.IO) { TorrentArtwork.loadPoster(poster) }
                if (found == null) { failedAttempt = true; return@withLock }
                if (!enabled() || PortableMaintenance.running) return@withLock
                coverEdits++
                try {
                    managedCovers = withContext(Dispatchers.IO) { ManagedCoverStore.importTorrentAutomatic(this@MainActivity, id, found.bytes, found.sourceName, found.sourceUrl) }
                    coverRevision++
                } finally { coverEdits-- }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { failedAttempt = true /* Keep the title placeholder. */ }
        finally { mediaCoverAttempts.finish(id, poster, android.os.SystemClock.elapsedRealtime(), failedAttempt) }
    }
    private val torrFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val request = pendingTorrDownload
        pendingTorrDownload = null
        if (uri != null && !PortableMaintenance.running) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (!getSharedPreferences("orbit", MODE_PRIVATE).edit().putString("downloads.folder", uri.toString()).commit()) throw java.io.IOException("Cannot save folder")
                torrDownloadFolder = uri.toString()
                if (request != null) enqueueTorrDownload(uri, request.first, request.second)
            } catch (_: Exception) { torrDownloadMessage = getString(R.string.torr_download_failed) }
        }
    }
    private fun enqueueTorrDownload(uri: Uri, entry: TorrCatalogEntry, files: List<TorrCatalogFile>) {
        if (torrPreparing) return
        torrPreparing = true
        torrDownloadMessage = getString(R.string.torr_download_preparing)
        importScope.launch {
            try {
                val jobs = withContext(Dispatchers.IO) { TorrDownloadDocuments.enqueueAll(this@MainActivity, uri, entry, files) }
                startTorrDownloads(jobs.map { it.id })
                torrDownloadMessage = getString(R.string.torr_download_added)
            } catch (error: TorrDownloadPartialBatch) {
                torrDownloadMessage = getString(R.string.torr_download_partial_batch, error.prepared)
            } catch (_: TorrDownloadDestinationExists) {
                torrDownloadMessage = getString(R.string.torr_download_exists)
            } catch (_: Exception) { torrDownloadMessage = getString(R.string.torr_download_failed) }
            finally { torrPreparing = false }
        }
    }
    internal fun chooseTorrFolder() {
        pendingTorrDownload = null
        torrDownloadMessage = ""
        if (!launchDocumentPicker(Intent.ACTION_OPEN_DOCUMENT_TREE) { torrFolderPicker.launch(torrFolderInitialUri()) }) torrDownloadMessage = getString(R.string.document_picker_unavailable)
    }
    private fun torrFolderInitialUri(): Uri? {
        val previous = runCatching {
            val tree = Uri.parse(torrDownloadFolder)
            require(tree.scheme == "content")
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        }.getOrNull()
        // An initial location is only a hint to Android's picker, not a storage grant or a destination.
        // It avoids starting at a volume root that recent Android versions cannot grant.
        return previous ?: DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:Documents"
        )
    }
    internal fun chooseTorrFile() {
        torrDownloadMessage = ""
        if (!launchDocumentPicker(Intent.ACTION_OPEN_DOCUMENT, "*/*") { torrFilePicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) }) torrDownloadMessage = getString(R.string.document_picker_unavailable)
    }
    internal fun chooseTorrDestination(entry: TorrCatalogEntry, files: List<TorrCatalogFile>) {
        if (torrPreparing) return
        torrDownloadMessage = ""
        val existing = runCatching { Uri.parse(torrDownloadFolder) }.getOrNull()
        val granted = existing != null && contentResolver.persistedUriPermissions.any { it.uri == existing && it.isReadPermission && it.isWritePermission }
        if (granted) { enqueueTorrDownload(existing!!, entry, files); return }
        pendingTorrDownload = entry to files
        if (!launchDocumentPicker(Intent.ACTION_OPEN_DOCUMENT_TREE) { torrFolderPicker.launch(torrFolderInitialUri()) }) {
            pendingTorrDownload = null
            torrDownloadMessage = getString(R.string.document_picker_unavailable)
        }
    }
    var notice: String
        get() = notices.current?.message.orEmpty()
        set(value) { notices.show(value) }
    /** Android TV images may ship a no-op picker stub or no document picker at all. */
    private fun launchDocumentPicker(action: String, mime: String? = null, launch: () -> Unit): Boolean {
        if (PortableMaintenance.running) return false
        val probe = Intent(action).apply {
            if (action != Intent.ACTION_OPEN_DOCUMENT_TREE) addCategory(Intent.CATEGORY_OPENABLE)
            if (mime != null) type = mime
        }
        val resolved = runCatching { packageManager.resolveActivity(probe, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo }.getOrNull()
        if (resolved == null || !resolved.enabled || !resolved.applicationInfo.enabled ||
            resolved.packageName == "com.android.tv.frameworkpackagestubs") {
            notice = getString(R.string.document_picker_unavailable)
            return false
        }
        return try { launch(); true }
        catch (_: android.content.ActivityNotFoundException) { notice = getString(R.string.document_picker_unavailable); false }
        catch (_: SecurityException) { notice = getString(R.string.document_picker_unavailable); false }
    }
    internal var portablePreviewBusy by mutableStateOf(false)
        private set
    internal var portablePreviewMessage by mutableStateOf("")
        private set
    internal var portableBackups by mutableStateOf<List<PortableBackupCandidate>>(emptyList())
        private set
    internal var portablePreviewTitles by mutableStateOf<List<String>>(emptyList())
        private set
    internal var portablePreviewExtra by mutableIntStateOf(0)
        private set
    internal var portablePreviewFocusRevision by mutableIntStateOf(0)
        private set
    internal var portableDecisionGroups by mutableStateOf<List<PortableRestoreDecisionGroup>>(emptyList())
        private set
    internal var portableDecisionChoices by mutableStateOf<Map<PortableRestoreDecisionGroup, PortableRestoreChoice>>(emptyMap())
        private set
    internal var portableDecisionRemaining by mutableIntStateOf(0)
        private set
    internal var portableDecisionRootBlocks by mutableIntStateOf(0)
        private set
    private var portableDecisionSession: PortablePreviewDecisionSession? = null
    internal val portableRestoreAvailable: Boolean
        get() = portableDecisionSession != null && !portablePreviewBusy && !portableCopyBusy &&
            portableDecisionRootBlocks == 0 && portableDecisionRemaining == 0 &&
            portableDecisionGroups.all { it in portableDecisionChoices }
    private var portableDecisionRevision = 0L
    private fun clearPortableDecisions() {
        portableDecisionRevision++
        portableDecisionSession = null
        portableDecisionGroups = emptyList()
        portableDecisionChoices = emptyMap()
        portableDecisionRemaining = 0
        portableDecisionRootBlocks = 0
    }
    internal fun choosePortableDecision(group: PortableRestoreDecisionGroup) {
        val session = portableDecisionSession ?: return
        if (portablePreviewBusy || group !in portableDecisionGroups) return
        val options = arrayOf(getString(R.string.portable_decision_keep), getString(R.string.portable_decision_backup))
        android.app.AlertDialog.Builder(this).setTitle(R.string.portable_decision_dialog)
            .setItems(options) { _, index ->
                if (portableDecisionSession !== session) return@setItems
                val next = portableDecisionChoices + (group to if (index == 0) PortableRestoreChoice.KEEP_CURRENT else PortableRestoreChoice.USE_BACKUP)
                portableDecisionChoices = next
                val revision = ++portableDecisionRevision
                importScope.launch {
                    try {
                        val remaining = withContext(Dispatchers.IO) {
                            val decisions = PortableRestoreDecisionGroups.decisions(session.conflicts, next)
                            resolvePortableRestore(session.live, session.roots, session.plan, decisions).conflicts.size
                        }
                        if (portableDecisionSession === session && portableDecisionRevision == revision) portableDecisionRemaining = remaining
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { if (portableDecisionSession === session && portableDecisionRevision == revision) clearPortableDecisions() }
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }
    private var portablePreviewTree: Uri? = null
    private var portablePreviewJob: Job? = null
    private var portablePreviewRevision = 0L
    private val portablePreviewPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (PortableMaintenance.running) return@registerForActivityResult
        if (uri != null && !portablePreviewBusy && !portableCopyBusy) {
            portablePreviewTree = uri
            clearPortableDecisions()
            portableBackups = emptyList()
            portablePreviewTitles = emptyList()
            portablePreviewExtra = 0
            portablePreviewBusy = true
            val revision = ++portablePreviewRevision
            portablePreviewMessage = getString(R.string.portable_preview_searching)
            portablePreviewJob = importScope.launch {
                try {
                    val found = discoverPortableBackups(contentResolver, uri)
                    if (portablePreviewRevision == revision) {
                        portableBackups = found
                        portablePreviewMessage = getString(if (found.isEmpty()) R.string.portable_preview_none else R.string.portable_preview_choose_copy)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    if (portablePreviewRevision == revision) portablePreviewMessage = getString(R.string.portable_preview_cancelled)
                    throw e
                } catch (_: Exception) {
                    if (portablePreviewRevision == revision) portablePreviewMessage = getString(R.string.portable_preview_error)
                } finally { if (portablePreviewRevision == revision) { portablePreviewBusy = false; portablePreviewJob = null } }
            }
        }
    }
    internal fun choosePortablePreviewFolder() {
        if (!portablePreviewBusy && !portableCopyBusy) launchDocumentPicker(Intent.ACTION_OPEN_DOCUMENT_TREE) { portablePreviewPicker.launch(null) }
    }
    internal fun cancelPortablePreview() { portablePreviewJob?.cancel() }
    internal fun releasePortablePreview() {
        portablePreviewRevision++
        portablePreviewJob?.cancel()
        portablePreviewJob = null
        portablePreviewBusy = false
        portablePreviewTree = null
        portableBackups = emptyList()
        portablePreviewTitles = emptyList()
        portablePreviewExtra = 0
        portablePreviewMessage = ""
        clearPortableDecisions()
    }
    internal fun inspectPortableCandidate(candidate: PortableBackupCandidate) {
        val tree = portablePreviewTree ?: return
        if (portablePreviewBusy || portableCopyBusy || candidate !in portableBackups) return
        portablePreviewBusy = true
        clearPortableDecisions()
        portablePreviewTitles = emptyList()
        portablePreviewExtra = 0
        portablePreviewMessage = getString(R.string.portable_preview_verifying)
        val revision = ++portablePreviewRevision
        portablePreviewJob = importScope.launch {
            try {
                var lastProgress = 0L
                val result = inspectPortableRestore(this@MainActivity, tree, candidate.documentId) { _, bytes, total ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (total > 0 && now - lastProgress >= 500L) {
                        lastProgress = now
                        val percent = ((bytes.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
                        importScope.launch {
                            if (portablePreviewBusy && portablePreviewRevision == revision) portablePreviewMessage = getString(R.string.portable_preview_progress, percent)
                        }
                    }
                }
                if (portablePreviewRevision != revision) return@launch
                val plan = result.plan
                if (plan == null) {
                    portablePreviewMessage = getString(R.string.portable_preview_broken, result.inspection.content.issues.size)
                } else {
                    val session = withContext(Dispatchers.IO) {
                        val live = PortableLiveCapture.capture(this@MainActivity)
                        val roots = LibraryImport.roots(this@MainActivity).map { LibraryRootRecord(it.platform, it.uri, it.source) }
                        val merge = PortableRestoreMerge.prepare(live, roots, plan)
                        PortablePreviewDecisionSession(candidate.documentId, live, roots, plan, merge.conflicts)
                    }
                    if (portablePreviewRevision != revision) return@launch
                    portableDecisionSession = session
                    portableDecisionGroups = PortableRestoreDecisionGroup.entries.filter { it in PortableRestoreDecisionGroups.groups(session.conflicts) }
                    portableDecisionRemaining = session.conflicts.size
                    portableDecisionRootBlocks = session.conflicts.count { it.kind == PortableRestoreConflict.Kind.ROOT }
                    portablePreviewTitles = plan.games.take(12).map { it.title }
                    portablePreviewExtra = (plan.games.size - portablePreviewTitles.size).coerceAtLeast(0)
                    portablePreviewMessage = getString(R.string.portable_preview_result, plan.games.size, plan.coverFiles.size,
                        session.conflicts.size, plan.unsupportedPlatforms.size)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (portablePreviewRevision == revision) portablePreviewMessage = getString(R.string.portable_preview_cancelled)
                throw e
            } catch (_: Exception) {
                if (portablePreviewRevision == revision) portablePreviewMessage = getString(R.string.portable_preview_error)
            } finally { if (portablePreviewRevision == revision) { portablePreviewBusy = false; portablePreviewJob = null; portablePreviewFocusRevision++ } }
        }
    }
    internal fun confirmPortableRestore() {
        val tree = portablePreviewTree ?: return
        val session = portableDecisionSession ?: return
        if (!portableRestoreAvailable || PortableMaintenance.running) return
        val choices = portableDecisionChoices.toMap()
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.portable_restore_confirm_title)
            .setMessage(R.string.portable_restore_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.portable_restore_action) { _, _ ->
                if (portableDecisionSession !== session || portablePreviewTree != tree || !portableRestoreAvailable ||
                    portableDecisionChoices != choices || PortableMaintenance.running) return@setPositiveButton
                try {
                    contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                    portablePreviewMessage = getString(R.string.portable_restore_permission_error)
                    return@setPositiveButton
                }
                val decisions = PortableRestoreDecisionGroups.decisions(session.conflicts, choices)
                val app = applicationContext
                PortableMaintenance.scope.launch {
                    val message = try {
                        PortableAndroidRestoreWorkflow.execute(app, tree, session.commitId, session.plan,
                            session.live, session.roots, decisions, drain = { drainForPortableMaintenance() })
                        R.string.portable_restore_success
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        R.string.portable_restore_cancelled
                    } catch (_: Exception) {
                        R.string.portable_restore_failed
                    }
                    if (portableMaintenanceSeen) PortableMaintenance.pendingOutcome = message
                    else if (!isFinishing && !isDestroyed) notice = getString(message)
                }
            }.show()
    }
    internal var portableCopyBusy by mutableStateOf(false)
        private set
    internal var portableCopyMessage by mutableStateOf("")
        private set
    private var portableCopyJob: Job? = null
    private var portableCopyRevision = 0L
    private val portableCopyPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (PortableMaintenance.running) return@registerForActivityResult
        if (uri != null && !portableCopyBusy) {
            portableCopyBusy = true
            val revision = ++portableCopyRevision
            portableCopyMessage = getString(R.string.portable_copy_preparing)
            portableCopyJob = importScope.launch {
                try {
                    val result = PortableAndroidExport.export(this@MainActivity, uri, onSourceVerification = { index, count ->
                        importScope.launch {
                            if (portableCopyBusy && portableCopyRevision == revision) portableCopyMessage = getString(R.string.portable_copy_verify_progress, index + 1, count)
                        }
                    }) { _, index, count ->
                        importScope.launch {
                            if (portableCopyBusy && portableCopyRevision == revision) portableCopyMessage = getString(R.string.portable_copy_progress, index + 1, count)
                        }
                    }
                    portableCopyMessage = getString(R.string.portable_copy_complete, result.fileCount)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    portableCopyMessage = getString(R.string.portable_copy_cancelled)
                    throw e
                } catch (e: PortableInstalled3dsUnsupported) {
                    portableCopyMessage = getString(R.string.portable_copy_installed_3ds, e.gameCount)
                } catch (_: Exception) {
                    portableCopyMessage = getString(R.string.portable_copy_failed)
                } finally {
                    portableCopyBusy = false
                    portableCopyJob = null
                }
            }
        }
    }
    internal fun choosePortableCopyFolder() {
        if (!portableCopyBusy && !portablePreviewBusy) launchDocumentPicker(Intent.ACTION_OPEN_DOCUMENT_TREE) { portableCopyPicker.launch(null) }
    }
    internal fun cancelPortableCopy() { portableCopyJob?.cancel() }
    var resumeVersion by mutableIntStateOf(0)
    var homeVersion by mutableIntStateOf(0)
    var library by mutableStateOf<List<Game>>(emptyList())
    var scanning by mutableStateOf(false)
    var legacyRecoveryPending by mutableStateOf(false)
        private set
    private var legacyRecoveryStateError = false
    private var allowMeteredRecoveryOnce = false
    internal var managedCovers by mutableStateOf<Map<String, ManagedCover>>(emptyMap())
    var coverEditorId by mutableStateOf<String?>(null)
    var coversBusy by mutableStateOf(false)
    private var coverEdits by mutableIntStateOf(0)
    val coverEditBusy: Boolean get() = coverEdits > 0
    private var coverRevision = 0L
    private var coverSyncPending = false
    var coversStatus by mutableStateOf("")
    private var pendingCoverId: String? = null
    private var coversLoop: Job? = null
    private var bundled = emptyList<Game>()
    internal fun metadataCatalogSize(): Int = bundled.size
    private val importRoot = SupervisorJob()
    private val importScope = CoroutineScope(importRoot + Dispatchers.Main)
    internal var portableMaintenanceSeen by mutableStateOf(false)
        private set
    internal suspend fun drainForPortableMaintenance() {
        check(PortableMaintenance.running && android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        portableMaintenanceSeen = true
        libraryVisible = false
        refreshPending = false
        coverSyncPending = false
        allowMeteredRecoveryOnce = false
        coversLoop?.cancel()
        if (updatesLazy.isInitialized()) updates.onStop()
        importRoot.cancel()
        withContext(kotlinx.coroutines.NonCancellable) {
            importRoot.join()
            while (coverEdits > 0) kotlinx.coroutines.delay(20)
        }
    }
    private fun installMaintenanceInputGuard() {
        val original = window.callback
        window.callback = object : android.view.Window.Callback by original {
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                if (!PortableMaintenance.running) return original.dispatchKeyEvent(event)
                if (event.action == android.view.KeyEvent.ACTION_UP && event.keyCode in setOf(android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent.KEYCODE_BUTTON_B)) PortableMaintenance.requestCancel()
                return true
            }
            override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean =
                if (PortableMaintenance.running) true else original.dispatchTouchEvent(event)
        }
    }
    internal var pendingEmulatorApk: java.io.File? = null
    internal var emulatorInstallJob: Job? = null
    private var importPlatform = ""
    private var importSource = LibrarySource.FILES
    private var importHintRoot: LibraryImport.Root? = null
    private var refreshPending = false
    private var libraryVisible = false
    private var importJob: Job? = null
    override fun onResume() { super.onResume(); pendingEmulatorApk?.delete(); pendingEmulatorApk = null; if (!PortableRestoreStartup.ready || PortableMaintenance.running) return; AgentStateHooks.attach(this); updates.onResume(); libraryVisible = true; resumeVersion++; refreshLibrary(); startCoverSync() }
    override fun onPause() { AndroidDiagnostics.event("launcher.pause"); super.onPause() }
    override fun onStop() {
        torrentPlaybackDialog?.dismiss()
        AgentStateHooks.detach(this)
        if (updatesLazy.isInitialized()) updates.onStop()
        libraryVisible = false
        allowMeteredRecoveryOnce = false
        coversLoop?.cancel()
        coverSyncPending = false
        refreshPending = false
        importJob?.cancel()
        super.onStop()
    }
    override fun onDestroy() { if (updatesLazy.isInitialized()) updates.close(); importScope.cancel(); super.onDestroy() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (PortableMaintenance.running) return
        setIntent(intent)
        acceptTorrentIntent(intent)
        AndroidDiagnostics.event("launcher.intent", if (intent.hasCategory(Intent.CATEGORY_HOME)) "home" else "other")
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homeVersion++
    }
    private fun acceptTorrentIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        val type = intent.type?.lowercase(java.util.Locale.ROOT)
        val torrentType = type in setOf("application/x-bittorrent", "application/x-torrent") ||
            (type == "application/octet-stream" && uri.lastPathSegment?.endsWith(".torrent", ignoreCase = true) == true)
        val magnet = uri.scheme.equals("magnet", ignoreCase = true) && MovieLibraryStore.validSource(uri.toString()) &&
            !uri.toString().contains('\r') && !uri.toString().contains('\n')
        if (intent.action == Intent.ACTION_VIEW && ((torrentType && uri.scheme == "content") || magnet)) {
            incomingTorrentUri = uri
        }
    }
    fun openSettings() {
        try { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
        catch (e: Exception) { notice = getString(R.string.settings_error, e.localizedMessage.orEmpty()) }
    }
    fun openNetworkSettings() {
        runCatching { startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) }
            .onFailure { openSettings() }
    }
    fun editGameCover(id: String) { if (library.any { it.id == id }) coverEditorId = id }
    private val coverPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (PortableMaintenance.running) return@registerForActivityResult
        val id = pendingCoverId
        pendingCoverId = null
        if (uri != null && id != null) importCoverImage(id, uri)
    }
    private fun importCoverImage(id: String, uri: Uri) {
        importScope.launch {
            coverEdits++
            try {
                managedCovers = withContext(Dispatchers.IO) { ManagedCoverStore.importManual(this@MainActivity, id, uri) }
                coverRevision++
                ManagedCoverPreferences.choice(this@MainActivity, id, CoverChoice.MANUAL)
                coversStatus = getString(R.string.cover_saved)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { notice = getString(R.string.cover_error, e.localizedMessage.orEmpty()) }
            finally { coverEdits-- }
            syncCovers(false)
        }
    }
    internal suspend fun applyOnlineCover(id: String, bytes: ByteArray, image: OnlineCoverImage) {
        check(!PortableMaintenance.running) { "Maintenance in progress" }
        coverEdits++
        try {
            managedCovers = withContext(Dispatchers.IO) { ManagedCoverStore.importOnline(this@MainActivity,id,bytes,image) }
            coverRevision++
            ManagedCoverPreferences.choice(this,id,CoverChoice.MANUAL)
            coversStatus = getString(R.string.cover_saved)
            syncCovers(false)
        } finally { coverEdits-- }
    }
    fun chooseSharedCover(id: String) {
        if (coverEditBusy) return
        val folder = ManagedCoverPreferences.read(this).folder
        if (folder.isEmpty()) { chooseCoversFolder(); return }
        importScope.launch {
            coverEdits++
            try {
                val images = withContext(Dispatchers.IO) { ManagedCoverStore.images(this@MainActivity, folder) }
                if (images.isEmpty()) notice = getString(R.string.covers_no_images)
                else android.app.AlertDialog.Builder(this@MainActivity).setTitle(getString(R.string.cover_choose_shared))
                    .setItems(images.map { it.first }.toTypedArray()) { _, index -> importCoverImage(id, images[index].second) }
                    .setNegativeButton(getString(R.string.cancel), null).show()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { notice = getString(R.string.cover_error, e.localizedMessage.orEmpty()) }
            finally { coverEdits-- }
        }
    }
    fun chooseCover(id: String) {
        if (coverEditBusy) return
        pendingCoverId = id
        if (!launchDocumentPicker(Intent.ACTION_OPEN_DOCUMENT, "*/*") {
            coverPicker.launch(arrayOf("image/png", "image/jpeg", "image/webp"))
        }) pendingCoverId = null
    }
    private val coversFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (PortableMaintenance.running) return@registerForActivityResult
        if (uri != null) try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val old = ManagedCoverPreferences.read(this).folder
            ManagedCoverPreferences.folder(this, uri.toString())
            if (old.isNotEmpty() && old != uri.toString() && runCatching { LibraryImport.roots(this).none { it.uri == old } }.getOrDefault(false)) {
                runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            }
            syncCovers(true)
            startCoverSync()
        } catch (e: Exception) { notice = getString(R.string.cover_error, e.localizedMessage.orEmpty()) }
    }
    fun chooseCoversFolder() {
        launchDocumentPicker(Intent.ACTION_OPEN_DOCUMENT_TREE) {
            coversFolderPicker.launch(ManagedCoverPreferences.read(this).folder.takeIf { it.isNotEmpty() }?.let(Uri::parse))
        }
    }
    fun disconnectCoversFolder() {
        val old = ManagedCoverPreferences.read(this).folder
        ManagedCoverPreferences.folder(this, "")
        coversLoop?.cancel()
        if (old.isNotEmpty() && runCatching { LibraryImport.roots(this).none { it.uri == old } }.getOrDefault(false)) runCatching {
            contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        coversStatus = ""
    }
    internal fun selectCoverChoice(id: String, choice: CoverChoice) {
        ManagedCoverPreferences.choice(this, id, choice)
        syncCovers(false)
    }
    fun setAutomaticCovers(enabled: Boolean) {
        ManagedCoverPreferences.automatic(this, enabled)
        startCoverSync()
    }
    fun startCoverSync() {
        if (PortableMaintenance.running) return
        coversLoop?.cancel()
        if (!libraryVisible) return
        coversLoop = importScope.launch {
            kotlinx.coroutines.delay(1200)
            do {
                performCoverSync(false)
                if (!ManagedCoverPreferences.read(this@MainActivity).automatic || ManagedCoverPreferences.read(this@MainActivity).folder.isEmpty()) break
                kotlinx.coroutines.delay(60_000)
            } while (libraryVisible)
        }
    }
    fun syncCovers(manual: Boolean) { if (!PortableMaintenance.running) importScope.launch { performCoverSync(manual) } }
    private suspend fun performCoverSync(manual: Boolean) {
        if (coversBusy) { coverSyncPending = true; return }
        val options = ManagedCoverPreferences.read(this)
        if (options.folder.isEmpty()) return
        coversBusy = true
        val startedRevision = coverRevision
        try {
            val currentGames = library.toList()
            val result = withContext(Dispatchers.IO) {
                ManagedCoverStore.sync(this@MainActivity, currentGames, options.folder, manual || options.automatic, forceExport = manual)
            }
            if (startedRevision == coverRevision) managedCovers = result.entries
            coversStatus = getString(R.string.covers_sync_result, result.applied, result.rejected)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            coversStatus = getString(R.string.cover_error, e.localizedMessage.orEmpty())
            if (manual) notice = coversStatus
        } finally {
            coversBusy = false
            val retry = coverSyncPending && libraryVisible
            coverSyncPending = false
            if (retry) syncCovers(false)
        }
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (PortableMaintenance.running) return@registerForActivityResult
        if (uri != null && importPlatform.isNotEmpty()) try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val root = LibraryImport.Root(importPlatform, uri.toString(), importSource)
            LibraryImport.addRoot(this, root)
            importHintRoot = root
            notice = ""
            refreshLibrary()
        } catch (e: Exception) { notice = getString(R.string.folder_error, e.localizedMessage.orEmpty()) }
    }
    private val gameFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (PortableMaintenance.running) return@registerForActivityResult
        val platform = importPlatform
        if (uri != null && PlatformRegistry.find(platform) != null) {
            val alreadyGranted = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            importScope.launch {
                var retained = false
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val root = LibraryImport.Root(platform, uri.toString(), LibrarySource.SINGLE_FILE)
                    // Validate before saving the source: CIA packages and multi-file discs
                    // cannot become phantom entries. No ROM data is copied.
                    val selected = withContext(Dispatchers.IO) { LibraryImport.scan(this@MainActivity, root, bundled, showHints = false) }
                    require(selected.isNotEmpty()) { getString(R.string.import_file_unavailable) }
                    LibraryImport.addRoot(this@MainActivity, root)
                    retained = true
                    notice = ""
                    refreshLibrary()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { notice = e.localizedMessage ?: getString(R.string.import_file_unavailable) }
                finally {
                    if (!retained && !alreadyGranted) runCatching {
                        contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
            }
        }
    }
    fun chooseFolder(platform: String) {
        if (platform == "all") {
            val emulator = LibraryPreferences.read(this).ndsEmulator
            val systems = PlatformRegistry.all.map { PlatformRegistry.resolve(it.id, emulator)!! }
            // AlertDialog's ListView scrolls and keeps D-pad focus as the catalog grows.
            val labels = systems.map { "${getString(it.titleRes)} · ${it.emulator}" }.toTypedArray()
            android.app.AlertDialog.Builder(this).setTitle(getString(R.string.choose_system)).setItems(labels) { _, index -> chooseFolder(systems[index].id) }.setNegativeButton(getString(R.string.cancel), null).show()
            return
        }
        if (PlatformRegistry.find(platform) == null) { notice = getString(R.string.platform_unknown); return }
        if (platform == "Nintendo 3DS") {
            android.app.AlertDialog.Builder(this).setTitle(R.string.three_ds_add_title)
                .setItems(arrayOf(getString(R.string.import_one_game), getString(R.string.three_ds_rom_folder), getString(R.string.three_ds_installed))) { _, index ->
                    if (index == 0) chooseLibraryFile(platform)
                    else if (index == 1) chooseLibraryFolder(platform, LibrarySource.FILES)
                    else android.app.AlertDialog.Builder(this).setTitle(R.string.three_ds_installed)
                        .setMessage(R.string.three_ds_data_folder_hint)
                        .setPositiveButton(R.string.three_ds_choose_folder) { _, _ -> chooseLibraryFolder(platform, LibrarySource.AZAHAR_INSTALLED) }
                        .setNegativeButton(R.string.cancel, null).show()
                }
                .setNeutralButton(R.string.three_ds_cia_help) { _, _ ->
                    android.app.AlertDialog.Builder(this).setTitle(R.string.three_ds_cia_help)
                        .setMessage(R.string.three_ds_cia_hint)
                        .setPositiveButton(R.string.three_ds_open_azahar) { _, _ ->
                            try {
                                val packageName = PlatformRegistry.find(platform)!!.component.substringBefore('/')
                                val intent = packageManager.getLeanbackLaunchIntentForPackage(packageName)
                                    ?: packageManager.getLaunchIntentForPackage(packageName)
                                if (intent != null) startActivity(intent)
                                else notice = getString(R.string.emulator_unavailable, "Azahar")
                            } catch (_: Exception) { notice = getString(R.string.emulator_unavailable, "Azahar") }
                        }.setNegativeButton(R.string.close, null).show()
                }.setNegativeButton(R.string.cancel, null).show()
            return
        }
        android.app.AlertDialog.Builder(this).setTitle(R.string.import_source_title)
            .setItems(arrayOf(getString(R.string.import_one_game), getString(R.string.import_game_folder))) { _, index ->
                if (index == 0) chooseLibraryFile(platform) else chooseLibraryFolder(platform, LibrarySource.FILES)
            }.setNegativeButton(R.string.cancel, null).show()
    }
    private fun chooseLibraryFile(platform: String) {
        importPlatform = platform
        importSource = LibrarySource.SINGLE_FILE
        launchDocumentPicker(Intent.ACTION_OPEN_DOCUMENT, "*/*") { gameFilePicker.launch(arrayOf("*/*")) }
    }
    private fun chooseLibraryFolder(platform: String, source: LibrarySource, preferred: Uri? = null) {
        importPlatform = platform
        importSource = source
        val existing = (rootsOrNotify() ?: return).lastOrNull { it.platform == platform && it.source == source }?.uri
        val initial = existing?.let { Uri.parse(it) } ?: bundled.firstOrNull { it.platform == platform && it.tree.isNotBlank() }?.tree?.let {
            DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", it)
        }
        launchDocumentPicker(Intent.ACTION_OPEN_DOCUMENT_TREE) {
            folderPicker.launch(preferred ?: initial?.let { DocumentsContract.buildDocumentUriUsingTree(it, DocumentsContract.getTreeDocumentId(it)) })
        }
    }
    private fun rootsOrNotify(): List<LibraryImport.Root>? = try { LibraryImport.roots(this) }
    catch (e: Exception) { notice = e.localizedMessage.orEmpty(); null }
    private fun legacyNetwork(): LegacyNetworkState = try {
        val manager = getSystemService(android.net.ConnectivityManager::class.java)
        val caps = manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        when {
            caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true -> LegacyNetworkState.OFFLINE
            manager?.isActiveNetworkMetered != false -> LegacyNetworkState.METERED
            else -> LegacyNetworkState.UNMETERED
        }
    } catch (_: Exception) { LegacyNetworkState.OFFLINE }

    fun retryLegacyRecovery() {
        if (legacyRecoveryStateError) {
            try {
                legacyRecoveryPending = LegacyRecoveryState.initialize(this, bundled)
                legacyRecoveryStateError = false
            } catch (_: Exception) { notice = getString(R.string.legacy_recovery_state_error); return }
        }
        if (!legacyRecoveryPending) { refreshLibrary(); return }
        when (legacyNetwork()) {
            LegacyNetworkState.OFFLINE -> notice = getString(R.string.legacy_recovery_offline)
            LegacyNetworkState.UNMETERED -> refreshLibrary()
            LegacyNetworkState.METERED -> android.app.AlertDialog.Builder(this)
                .setTitle(R.string.legacy_recovery_title).setMessage(R.string.legacy_recovery_metered_confirm)
                .setPositiveButton(R.string.legacy_recovery_continue) { _, _ ->
                    allowMeteredRecoveryOnce = true
                    refreshLibrary()
                }.setNegativeButton(R.string.cancel, null).show()
        }
    }
    fun manageFolders() {
        val roots = rootsOrNotify() ?: return
        if (roots.isEmpty()) { notice = getString(R.string.add_folder_hint); return }
        val names = roots.map {
            val label = if (it.source == LibrarySource.AZAHAR_INSTALLED) getString(R.string.three_ds_installed) else PlatformRegistry.title(this, it.platform)
            val uri = Uri.parse(it.uri)
            val path = runCatching { if (it.source == LibrarySource.SINGLE_FILE) DocumentsContract.getDocumentId(uri) else DocumentsContract.getTreeDocumentId(uri) }.getOrDefault(it.uri)
            "$label · ${path.substringAfter(':')}"
        }.toTypedArray()
        android.app.AlertDialog.Builder(this).setTitle(getString(R.string.import_sources_title)).setItems(names) { _, index ->
            android.app.AlertDialog.Builder(this).setTitle(getString(if (roots[index].source == LibrarySource.SINGLE_FILE) R.string.import_remove_file else R.string.remove_folder)).setMessage(getString(R.string.files_preserved))
                .setPositiveButton(getString(R.string.remove)) { _, _ ->
                    try { LibraryImport.removeRoot(this, roots[index]); refreshLibrary() }
                    catch (e: Exception) { notice = e.localizedMessage.orEmpty() }
                }.setNegativeButton(getString(R.string.cancel), null).show()
        }.setNegativeButton(getString(R.string.close), null).show()
    }
    fun refreshLibrary() {
        if (PortableMaintenance.running) return
        if (!libraryVisible) { refreshPending = true; return }
        if (scanning) { refreshPending = true; return }
        if (legacyRecoveryStateError) { notice = getString(R.string.legacy_recovery_state_error); return }
        refreshPending = false
        val meteredApproved = allowMeteredRecoveryOnce
        allowMeteredRecoveryOnce = false
        val roots = rootsOrNotify() ?: return
        val hintRoot = importHintRoot
        val recoveryRequired = legacyRecoveryPending
        val initialCatalog = bundled
        scanning = true
        // Enter try/finally before onStop can cancel the queued Main dispatcher work.
        // Otherwise cancellation before coroutine start leaves scanning=true forever.
        importJob = importScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = withContext(Dispatchers.IO) {
                    val previous = LibraryImport.read(this@MainActivity)
                    val catalog = if (recoveryRequired) {
                        val checkpoint = {
                            val network = legacyNetwork()
                            if (!legacyRecoveryAllowed(network, meteredApproved)) throw LegacyRecoveryNotice(
                                if (network == LegacyNetworkState.METERED) R.string.legacy_recovery_metered else R.string.legacy_recovery_offline)
                        }
                        try { LegacyCatalogRecovery.restore(this@MainActivity, networkCheckpoint = checkpoint) }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: LegacyRecoveryNotice) { throw e }
                        catch (_: Exception) {
                            // A network change can close the socket before the next transfer checkpoint.
                            checkpoint()
                            throw LegacyRecoveryNotice(R.string.legacy_recovery_failed)
                        }
                        LegacyCatalogBridge.load(this@MainActivity).also { recovered ->
                            check(recovered.any { it.tree.isNotBlank() && it.files.isNotEmpty() })
                            LibraryImport.migrateGrantedCatalogRoots(this@MainActivity, recovered)
                        }
                    } else initialCatalog
                    // Keep a private, verified bridge before any future catalog cleanup.
                    // A failed bridge must not erase or block the existing library.
                    try { LegacyCatalogBridge.capture(this@MainActivity) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { AndroidDiagnostics.event("library.legacy_catalog", "unavailable") }
                    val errors = ImportWarnings()
                    val activeRoots = if (recoveryRequired) LibraryImport.roots(this@MainActivity) else roots
                    val found = activeRoots.flatMap { root ->
                        val scanned = try { LibraryImport.scan(this@MainActivity, root, catalog, showHints = root == hintRoot) { warning ->
                            errors.add { "${PlatformRegistry.title(this@MainActivity, root.platform)}: $warning" }
                        } }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) {
                            errors.add(rootFailure = true) {
                                val reason = if (e is SecurityException) getString(
                                    if (root.source == LibrarySource.SINGLE_FILE) R.string.import_file_unavailable else R.string.import_folder_permission)
                                else e.localizedMessage.orEmpty()
                                "${PlatformRegistry.title(this@MainActivity, root.platform)}: $reason"
                            }
                            null
                        }
                        libraryRootSnapshot(root.uri, scanned, previous, root.platform)
                    }.let(::mergeImportedVariants)
                    currentCoroutineContext().ensureActive()
                    LibraryImport.write(this@MainActivity, found)
                    val (visibleErrors, omittedErrors) = errors.snapshot()
                    val noticeErrors = if (omittedErrors > 0) visibleErrors + getString(R.string.import_warnings_more, omittedErrors) else visibleErrors
                    Triple(found, noticeErrors, catalog)
                }
                library = result.first
                bundled = result.third
                if (recoveryRequired) legacyRecoveryPending = false
                if (importHintRoot == hintRoot) importHintRoot = null
                if (result.second.isNotEmpty()) notice = result.second.joinToString("; ")
                else if (recoveryRequired) notice = getString(R.string.legacy_recovery_done)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: LegacyRecoveryNotice) { notice = getString(e.messageId) }
            catch (e: Exception) { notice = getString(R.string.refresh_error, e.localizedMessage.orEmpty()) }
            finally {
                scanning = false
                if (refreshPending && libraryVisible && !isDestroyed) { refreshPending = false; refreshLibrary() }
            }
        }
    }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (PortableMaintenance.running) return@registerForActivityResult
        val request = pending
        pending = null
        if (uri == null) { notice = getString(R.string.access_cancelled); return@registerForActivityResult }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (request != null) {
                val expected = request.first.tree.let { if (it.startsWith("content:")) DocumentsContract.getTreeDocumentId(Uri.parse(it)) else it }
                if (DocumentsContract.getTreeDocumentId(uri) == expected) launchGame(request.first, request.second)
                else notice = getString(R.string.wrong_folder, expected.removePrefix("primary:"))
            }
        } catch (e: Exception) { notice = getString(R.string.access_error, e.localizedMessage.orEmpty()) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PortableRestoreStartup.ready && !PortableMaintenance.running) {
            torrDownloadFolder = getSharedPreferences("orbit", MODE_PRIVATE).getString("downloads.folder", "").orEmpty()
        }
        installMaintenanceInputGuard()
        if (!PortableRestoreStartup.ready) {
            val screen = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(48, 48, 48, 48)
                setBackgroundColor(android.graphics.Color.BLACK)
            }
            screen.addView(android.widget.TextView(this).apply {
                setText(R.string.portable_recovery_blocked)
                setTextColor(android.graphics.Color.WHITE)
                textSize = 20f
            })
            screen.addView(android.widget.Button(this).apply {
                setText(R.string.portable_recovery_retry)
                setOnClickListener { if (PortableRestoreStartup.initialize(applicationContext)) recreate() }
            })
            setContentView(screen)
            return
        }
        if (PortableMaintenance.running) {
            portableMaintenanceSeen = true
            setContent { PortableMaintenanceBarrier(this) {} }
            return
        }
        updates // Initialize bounded diagnostics and the preference listener once for this Activity.
        importScope.launch { withContext(Dispatchers.IO) { runCatching { TorrDownloadStore.recover(applicationContext) } } }
        if (savedInstanceState == null) acceptTorrentIntent(intent)
        else incomingTorrentUri = savedInstanceState.getString("incomingTorrentUri")?.let(Uri::parse)
        importPlatform = savedInstanceState?.getString("importPlatform").orEmpty()
        importSource = LibrarySource.read(savedInstanceState?.getString("importSource"), importPlatform)
        pendingCoverId = savedInstanceState?.getString("pendingCoverId")
        managedCovers = ManagedCoverStore.read(this)
        RemoteStartup.start(this)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        bundled = loadGames()
        try { legacyRecoveryPending = LegacyRecoveryState.initialize(this, bundled) }
        catch (_: Exception) { legacyRecoveryStateError = true; legacyRecoveryPending = true; notice = getString(R.string.legacy_recovery_state_error) }
        val rootKeys = try {
            if (!legacyRecoveryPending) LibraryImport.migrateGrantedCatalogRoots(this, bundled)
            LibraryImport.roots(this).map { it.uri to it.platform }.toSet()
        } catch (e: Exception) { notice = e.localizedMessage.orEmpty(); null }
        library = try { LibraryImport.read(this).let { saved ->
            if (rootKeys == null) saved else saved.filter { (it.tree to it.platform) in rootKeys }
        } }
        catch (e: Exception) { notice = e.localizedMessage.orEmpty(); emptyList() }
        ArtSchedule.install(this)
        PortableMaintenance.pendingOutcome?.let { outcome ->
            PortableMaintenance.pendingOutcome = null
            notice = getString(outcome)
        }
        setContent {
            PortableMaintenanceBarrier(this) {
                MaterialTheme(colorScheme = darkColorScheme(primary = Lime, surface = Ink, onSurface = White)) {
                    ControllerProvider { Orbit(this, library.filterNot { it.demo }) }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("importPlatform", importPlatform)
        outState.putString("importSource", importSource.value)
        outState.putString("pendingCoverId", pendingCoverId)
        outState.putString("incomingTorrentUri", incomingTorrentUri?.toString())
        super.onSaveInstanceState(outState)
    }

    private fun loadGames(): List<Game> {
        return LegacyCatalogBridge.load(this)
    }

    fun launchGame(game: Game, variant: Int = 0) {
        AndroidDiagnostics.event("launch.game")
        try {
            val spec = PlatformRegistry.resolve(game.platform, LibraryPreferences.read(this).ndsEmulator)
            if (spec == null) { notice = getString(R.string.platform_unknown); return }
            PlatformRegistry.unavailableReason(this, spec)?.let { if (!offerMissingEmulator(spec)) notice = it; return }
            val raw = game.files.getOrNull(variant)
            if (raw.isNullOrEmpty()) { notice = getString(R.string.game_file_missing); return }
            val uri = if (raw.startsWith("content:")) Uri.parse(raw) else if (raw.startsWith("/storage/emulated/0/Android/data/com.flycast.emulator/files/")) Uri.parse(raw) else {
                val tree = if (game.tree.startsWith("content:")) Uri.parse(game.tree) else DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", game.tree)
                val granted = contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
                if (!granted) {
                    pending = game to variant
                    notice = getString(R.string.grant_folder, game.tree.removePrefix("primary:"))
                    if (!launchDocumentPicker(Intent.ACTION_OPEN_DOCUMENT_TREE) {
                        picker.launch(DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)))
                    }) pending = null
                    return
                }
                val documentId = "primary:" + raw.removePrefix("/storage/emulated/0/")
                DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
            }
            val treeUri = when {
                game.tree.startsWith("content:") -> Uri.parse(game.tree).takeIf { uri -> DocumentsContract.isTreeUri(uri) &&
                    LibraryImport.roots(this).none { it.uri == game.tree && it.platform == game.platform && it.source == LibrarySource.SINGLE_FILE } }
                game.tree.startsWith("primary:") -> DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", game.tree)
                else -> null
            }
            val retroArch = if (spec.launchKind == LaunchKind.RETROARCH) RetroArchLauncher.arguments(this, spec, uri, treeUri) else null
            val intent = PlatformRegistry.launchIntent(spec, uri, treeUri, retroArch)
            startActivity(intent)
            getSharedPreferences("orbit", MODE_PRIVATE).edit().putLong("played:${game.id}", System.currentTimeMillis()).apply()
            notice = ""
        } catch (e: Exception) { AndroidDiagnostics.failure("launch.game", e); notice = getString(R.string.launch_error, game.title, e.localizedMessage.orEmpty()) }
    }

    fun apps(): List<TvApp> = loadTvApps(this)

    fun openApp(app: TvApp) {
        AndroidDiagnostics.event("launch.app")
        try {
            val selectedActivity = app.activityName.takeIf { it.isNotBlank() }?.let { name ->
                runCatching {
                    val component = android.content.ComponentName(app.packageName, name)
                    val info = packageManager.getActivityInfo(component, 0)
                    if (info.exported && info.enabled && info.applicationInfo.enabled)
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                            .setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    else null
                }.getOrNull()
            }
            val intent = selectedActivity ?: packageManager.getLeanbackLaunchIntentForPackage(app.packageName)
                ?: packageManager.getLaunchIntentForPackage(app.packageName)
            if (intent != null) startActivity(intent) else notice = getString(R.string.no_app_screen)
        } catch (e: Exception) { AndroidDiagnostics.failure("launch.app", e); notice = getString(R.string.app_error, app.name) }
    }
}

@Composable private fun Action(
    label: String, modifier: Modifier = Modifier, active: Boolean = false, primary: Boolean = false, compact: Boolean = false, icon: Boolean = false,
    onFocus: () -> Unit = {}, onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.touchSurface(onClick = onClick).onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Lime else if (active) Panel else Color.Transparent,
            contentColor = if (primary) Ink else if (active) White else Muted,
            focusedContainerColor = if (primary) White else Lime,
            focusedContentColor = Ink
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(1.dp, Color(0xFF424244))), focusedBorder = Border(BorderStroke(2.dp, Lime)))
    ) { Box(Modifier.padding(horizontal = if (compact) 10.dp else 16.dp, vertical = if (icon) 6.dp else 10.dp), contentAlignment = Alignment.Center) { Text(label.uppercase(), fontSize = if (icon) 20.sp else 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp) } }
}

@Composable private fun Orbit(activity: MainActivity, games: List<Game>) {
    val appearance = rememberCoverOptions()
    val libraryOptions = rememberLibraryOptions()
    val appsOptions = rememberAppsOptions()
    val coverOptions = rememberManagedCoverOptions()
    CompositionLocalProvider(
        LocalManagedCovers provides activity.managedCovers,
        LocalManagedCoverOptions provides coverOptions,
        LocalCoverStyle provides appearance.style,
        LocalFavoriteBadgeVisible provides appearance.favoriteBadgeVisible,
        LocalTileSize provides appearance.tileSize,
        LocalLibraryOptions provides libraryOptions,
        LocalAppsOptions provides appsOptions
    ) {
        OrbitContent(activity, games)
    }
}

@Composable private fun OrbitContent(activity: MainActivity, games: List<Game>) {
    val prefs = remember { activity.getSharedPreferences("orbit", android.content.Context.MODE_PRIVATE) }
    var tab by rememberSaveable { mutableStateOf("home") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val addGamesFocus = remember { FocusRequester() }
    val libraryOptions = LocalLibraryOptions.current
    val appsOptions = LocalAppsOptions.current
    val downloadsVisible = rememberDownloadsVisible()
    val mediaTabVisible = rememberMediaTabVisible()
    val visibleTabs = remember(libraryOptions.tabVisible, appsOptions.tabVisible, mediaTabVisible, tab) {
        buildList {
            add("home")
            if (libraryOptions.tabVisible) add("library")
            if (mediaTabVisible || tab == "movies") add("movies")
            if (appsOptions.tabVisible) add("apps")
        }
    }
    var searchReturnTarget by remember { mutableStateOf("") }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var settingsInitialSection by rememberSaveable { mutableStateOf("root") }
    val settingsFocus = remember { FocusRequester() }
    val updateFocus = remember { FocusRequester() }
    var settingsReturnFocus by remember { mutableStateOf<FocusRequester?>(null) }
    var drawerWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(settingsOpen) {
        if (settingsOpen) drawerWasOpen = true
        else if (drawerWasOpen) {
            withFrameNanos { }
            val restored = settingsReturnFocus?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false
            if (!restored) runCatching { settingsFocus.requestFocus() }
        }
    }
    var platform by remember { mutableStateOf("all") }
    var chosenId by rememberSaveable { mutableStateOf(HomePreferences.selectedId(activity)) }
    var detail by remember { mutableStateOf<Game?>(null) }
    val incomingTorrent = activity.incomingTorrentUri
    if (incomingTorrent != null) {
        LaunchedEffect(incomingTorrent) { tab = "downloads"; settingsOpen = false; detail = null }
        DisposableEffect(activity, incomingTorrent) {
            val magnet = incomingTorrent.scheme.equals("magnet", ignoreCase = true)
            val dialog = android.app.AlertDialog.Builder(activity)
                .setTitle(if (magnet) R.string.torr_download_add_magnet else R.string.torr_download_add_file)
                .setMessage(if (magnet) R.string.downloads_external_magnet_confirm else R.string.downloads_external_confirm)
                .setPositiveButton(R.string.downloads_external_queue) { _, _ ->
                    activity.incomingTorrentUri = null
                    if (magnet) activity.importMagnet(incomingTorrent) else activity.importTorrentUri(incomingTorrent)
                }
                .setNeutralButton(R.string.movies_add_torrent_media) { _, _ ->
                    activity.incomingTorrentUri = null
                    if (magnet) activity.importMagnet(incomingTorrent, openMedia = true)
                    else activity.importTorrentUri(incomingTorrent, openMedia = true)
                }
                .setNegativeButton(R.string.cancel) { _, _ -> activity.incomingTorrentUri = null }
                .setOnCancelListener { activity.incomingTorrentUri = null }
                .create()
            dialog.show()
            onDispose { dialog.dismiss() }
        }
    }
    val mediaOptions = rememberMediaPlayerOptions()
    val batteryVisible = rememberBatteryVisible()
    val mediaVisible = mediaOptions.visible
    var mediaOpen by rememberSaveable { mutableStateOf(false) }
    val mediaPlayback = rememberMediaPlayback(activity, (mediaVisible && detail == null) || settingsOpen || mediaOpen,
        showPaused = mediaOptions.showPaused || settingsOpen || mediaOpen)
    val headerMediaSession = mediaPlayback.session?.takeIf { mediaOptions.showPaused || it.isPlaying }
    val mediaFocus = remember { FocusRequester() }
    var mediaWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(mediaOpen) {
        if (mediaOpen) mediaWasOpen = true
        else if (mediaWasOpen) {
            withFrameNanos { }
            val restored = if (mediaVisible && headerMediaSession != null) runCatching { mediaFocus.requestFocus() }.getOrDefault(false) else false
            if (!restored) runCatching { settingsFocus.requestFocus() }
        }
    }
    val sectionOptions = rememberHomeSections()
    val favoriteRefs = remember(sectionOptions.favorites) { sectionOptions.favorites.toSet() }
    val favorites = remember(favoriteRefs) { favoriteRefs.filter { it.startsWith("game:") }.map { it.removePrefix("game:") }.toSet() }
    var variant by remember { mutableIntStateOf(0) }
    var versionPickerOpen by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    val feedState = remember { HomeFeedState() }
    var tileMenu by remember { mutableStateOf<TileMenuTarget?>(null) }
    var membershipItem by remember { mutableStateOf<String?>(null) }
    var favoriteOrderItem by remember { mutableStateOf<String?>(null) }
    var tileReturnFocus by remember { mutableStateOf<FocusRequester?>(null) }
    var tileReturnAnchor by remember { mutableStateOf<HomeFeedAnchor?>(null) }
    var tileRestore by remember { mutableIntStateOf(0) }
    fun dismissTileMenu() { tileMenu = null; tileRestore++ }
    fun openGame(game: Game, focus: FocusRequester) {
        tileReturnAnchor = feedState.anchor()
        tileReturnFocus = focus; detail = game; variant = 0
    }
    fun gameMenu(game: Game, focus: FocusRequester) {
        tileReturnAnchor = feedState.anchor()
        tileReturnFocus = focus; tileMenu = TileMenuTarget.GameItem(game)
    }
    fun appMenu(app: TvApp, focus: FocusRequester) {
        tileReturnAnchor = feedState.anchor()
        tileReturnFocus = focus; tileMenu = TileMenuTarget.AppItem(app)
    }
    fun movieMenu(movie: SavedMovie, focus: FocusRequester) {
        tileReturnAnchor = feedState.anchor()
        tileReturnFocus = focus; tileMenu = TileMenuTarget.MovieItem(movie)
    }
    LaunchedEffect(tileRestore) {
        if (tileRestore > 0 && detail == null && tileMenu == null && membershipItem == null && favoriteOrderItem == null) {
            withFrameNanos { }
            if (tab == "home") feedState.restore(tileReturnAnchor)
            else if (tileReturnFocus?.let { runCatching { it.requestFocus() }.getOrDefault(false) } != true)
                runCatching { firstFocus.requestFocus() }
        }
    }
    val libraryScroll = rememberLazyGridState()
    val appsScroll = rememberLazyGridState()
    var selectedAppPackage by rememberSaveable { mutableStateOf<String?>(null) }
    val loadedApps by produceState<List<TvApp>?>(null, activity.resumeVersion) {
        value = withContext(Dispatchers.IO) { activity.apps() }
    }
    val apps = loadedApps.orEmpty()
    val movieResult by produceState<Result<List<SavedMovie>>?>(null, activity.resumeVersion) {
        value = withContext(Dispatchers.IO) { runCatching { MovieLibraryStore.read(activity) } }
    }
    val movies = movieResult?.getOrDefault(emptyList()).orEmpty()
    val homeOptions = rememberHomeOptions()
    val clockOptions = rememberClockOptions()
    val clockInstant = rememberClockInstant(clockOptions.visible && clockOptions.zones.isNotEmpty() && detail == null)
    var moviePlayRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith("played:movie:")) moviePlayRevision++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val played = remember(games, activity.resumeVersion) {
        games.associate { it.id to runCatching { prefs.getLong("played:${it.id}", 0L) }.getOrDefault(0L) }
    }
    val moviePlayed = remember(movies, activity.resumeVersion, moviePlayRevision) {
        movies.associate { it.id to runCatching { prefs.getLong("played:movie:${it.id}", 0L) }.getOrDefault(0L) }
    }
    val homeGames = remember(games, favorites, played, homeOptions.order) {
        orderedHomeGames(games, favorites, played, homeOptions.order)
    }
    val shelfContents = remember(sectionOptions, homeGames, apps, played, movies, moviePlayed, libraryOptions.androidGamesVisible, libraryOptions.groupVersions) {
        homeShelfContents(sectionOptions, homeGames, apps, played, libraryOptions.androidGamesVisible, movies, moviePlayed, libraryOptions.groupVersions)
    }
    val chosen = games.firstOrNull { it.id == chosenId } ?: homeGames.firstOrNull()
    val locale = LocalConfiguration.current.locales[0].toLanguageTag()
    val platformLabels = remember(locale) { PlatformRegistry.all.associate { it.id to activity.getString(it.titleRes) } }
    val groups = remember(games) { games.groupBy(::gameGroupKey) }
    val sortedGames = remember(games, libraryOptions.groupVersions) { groupedGames(games.sortedBy { it.title.lowercase(java.util.Locale.ROOT) }, libraryOptions.groupVersions) }
    val scopedGames = remember(sortedGames, platform) {
        sortedGames.filter { platform == "all" || it.platform == platform }
    }
    val searchIndex = remember(scopedGames, platformLabels) { GameSearchIndex(scopedGames, platformLabels) }
    val filtered = remember(homeGames, tab, platform, searchIndex, searchQuery) {
        if (tab == "home") homeGames.filter { platform == "all" || it.platform == platform }
        else searchIndex.filter(searchQuery)
    }
    val androidGameApps = remember(apps, libraryOptions.androidGamesVisible) {
        if (libraryOptions.androidGamesVisible) apps.filter { it.isGame } else emptyList()
    }
    val scopedAndroidGames = remember(androidGameApps, platform) {
        if (platform == "all" || platform == "android-apps") androidGameApps else emptyList()
    }
    val filteredAndroidGames = remember(scopedAndroidGames, searchQuery) {
        val query = searchQuery.trim()
        scopedAndroidGames.filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
    }
    val libraryIsEmpty = filtered.isEmpty() && filteredAndroidGames.isEmpty()
    LaunchedEffect(platform, androidGameApps) {
        if (platform == "android-apps" && androidGameApps.isEmpty()) platform = "all"
    }
    LaunchedEffect(tab, visibleTabs) {
        if (tab != "downloads" && tab !in visibleTabs) {
            tab = "home"
            searchOpen = false
            platform = "all"
        }
    }
    LaunchedEffect(games) {
        if (games.none { it.id == chosenId }) chosenId = homeGames.firstOrNull()?.id
        if (detail != null && games.none { it.id == detail?.id }) detail = null
    }
    fun selectGame(game: Game) {
        chosenId = game.id
        HomePreferences.select(activity, game.id)
    }
    fun toggle(game: Game) {
        SectionPreferences.toggleFavorite(activity, homeGameKey(game.id))
    }
    fun applySearch(query: String) {
        searchQuery = query.trim()
        searchIndex.filter(searchQuery).firstOrNull()?.let(::selectGame)
        searchReturnTarget = "results"
        searchOpen = false
    }
    LaunchedEffect(activity.homeVersion) {
        if (activity.homeVersion == 0) return@LaunchedEffect
        settingsOpen = false
        mediaOpen = false
        searchOpen = false
        searchReturnTarget = ""
        detail = null
        tileMenu = null
        membershipItem = null
        favoriteOrderItem = null
        tab = "home"
        platform = "all"
        variant = 0
    }
    BackHandler {
        if (detail != null) { detail = null; tileRestore++ } else { tab = "home"; platform = "all" }
    }

    Box(Modifier.fillMaxSize().background(Ink).safeDrawingPadding().onPreviewKeyEvent { event ->
        if (tab == "home" && detail == null && !settingsOpen && !mediaOpen && !searchOpen && tileMenu == null && membershipItem == null && favoriteOrderItem == null && isHomeShelfKey(event.nativeKeyEvent)) {
            homeShelfStep(event.nativeKeyEvent)?.let(feedState::moveBy)
            true
        } else false
    }) {
        if (detail != null) {
            val g = detail!!
            val versions = remember(g, groups, libraryOptions.groupVersions) { gameVersions(g, groups, libraryOptions.groupVersions) }
            val selectedVersion = versions.getOrNull(variant) ?: versions.firstOrNull()
            val versionPaths = remember(versions) { versions.map { android.net.Uri.decode(it.game.files[it.fileIndex]) } }
            val fileNames = remember(versionPaths) { versionPaths.map { it.substringAfterLast('/') } }
            val labels = remember(versionPaths, fileNames) {
                val counts = fileNames.groupingBy { it }.eachCount()
                versionPaths.mapIndexed { index, path ->
                    val name = fileNames[index]
                    if ((counts[name] ?: 0) > 1) "$name · ${path.substringBeforeLast('/', "")}" else name
                }
            }
            val versionLabel = labels.getOrNull(variant) ?: labels.firstOrNull().orEmpty()
            if (versionPickerOpen) {
                DisposableEffect(activity, g.id, labels) {
                    val dialog = android.app.AlertDialog.Builder(activity)
                        .setTitle(R.string.other_version)
                        .setSingleChoiceItems(labels.toTypedArray(), variant.coerceIn(labels.indices)) { _, index ->
                            variant = index
                            versionPickerOpen = false
                        }
                        .setNegativeButton(R.string.cancel) { _, _ -> versionPickerOpen = false }
                        .setOnCancelListener { versionPickerOpen = false }
                        .setOnDismissListener { versionPickerOpen = false }
                        .create()
                    dialog.show()
                    onDispose { dialog.dismiss() }
                }
            }
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 36.dp).fillMaxWidth(.32f).fillMaxHeight(.78f).border(1.dp, Color(0xFF424244)).background(Panel)) {
                CoverArtwork(g.cover.ifEmpty { g.scene }, Modifier.fillMaxSize(), g.id)
            }
            Column(Modifier.fillMaxSize().padding(40.dp)) {
                Action(tr(R.string.back_library), onClick = { detail = null; tileRestore++ })
                Spacer(Modifier.height(26.dp))
                Text("${platformTitle(g.platform).uppercase()}   /   ${if (g.demo) "HOMEBREW" else tr(R.string.your_collection).uppercase()}", color = Lime, fontSize = 12.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(10.dp))
                Text(g.title.uppercase(), fontSize = 38.sp, lineHeight = 42.sp, fontWeight = FontWeight.Black, color = White, maxLines = 2, modifier = Modifier.width(540.dp))
                Spacer(Modifier.height(12.dp))
                Text(listOf(g.year, genre(g.genre), if (g.players.isNotEmpty()) tr(R.string.players, g.players) else "").filter { it.isNotEmpty() }.joinToString("   ·   "), color = White, fontSize = 15.sp)
                Spacer(Modifier.height(12.dp))
                val selectedEmulator = PlatformRegistry.resolve(g.platform, libraryOptions.ndsEmulator)?.emulator ?: g.emulator
                Text(g.developer.ifEmpty { PlatformRegistry.title(activity, g.platform) } + "\n" + tr(R.string.emulator, selectedEmulator), color = Muted, fontSize = 14.sp, lineHeight = 23.sp)
                Spacer(Modifier.height(12.dp))
                GameDescriptionPanel(g)
                Spacer(Modifier.weight(1f))
                if (versions.size > 1) {
                    Text(tr(R.string.variant, variant.coerceIn(0, versions.lastIndex) + 1, versions.size), color = Muted, fontSize = 12.sp)
                    Text(versionLabel, color = White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(540.dp))
                    Spacer(Modifier.height(8.dp))
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Action(tr(R.string.play), primary = true, modifier = Modifier.focusRequester(firstFocus), onClick = { selectedVersion?.let { activity.launchGame(it.game, it.fileIndex) } })
                    if (versions.size > 1) Action(tr(R.string.other_version), onClick = { versionPickerOpen = true })
                    Action(tr(R.string.cover_manage), onClick = { activity.editGameCover(g.id) })
                    Action(if (g.id in favorites) tr(R.string.favorite_on) else tr(R.string.favorite_off), onClick = { toggle(g) })
                }
                Spacer(Modifier.height(22.dp))
                ControlHints(R.string.control_select, R.string.back)
            }
            LaunchedEffect(g.id) { firstFocus.requestFocus() }
        } else {
            Column(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                  if (maxWidth < 600.dp) {
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(R.drawable.gostex_logo), contentDescription = "GOSTEX", modifier = Modifier.width(76.dp).height(34.dp))
                            Row(Modifier.weight(1f).padding(start = 8.dp, end = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                HeaderClock(clockOptions, clockInstant)
                                if (batteryVisible) { Spacer(Modifier.width(6.dp)); BatteryStatus(activity) { percent, charging -> activity.getString(if (charging) R.string.battery_charging else R.string.battery_level, percent) } }
                                if (mediaVisible) headerMediaSession?.let { session ->
                                    Spacer(Modifier.width(6.dp))
                                    MediaPlayerButton(session, Modifier.focusRequester(mediaFocus)) { mediaOpen = true }
                                }
                            }
                            if (rememberNetworkVisible()) {
                                NetworkStatusButton(activity)
                                Spacer(Modifier.width(6.dp))
                            }
                            CompactIconButton(CompactIcon.Settings, tr(R.string.settings), modifier = Modifier.focusRequester(settingsFocus),
                                onClick = { settingsInitialSection = "root"; settingsReturnFocus = null; settingsOpen = true })
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(visibleTabs, key = { it }) { label ->
                                    Action(tabTitle(label), compact = true, active = tab == label, onClick = { tab = label; platform = "all" })
                                }
                            }
                            if (downloadsVisible) { Spacer(Modifier.width(6.dp)); DownloadsShortcut(tab == "downloads") { tab = "downloads"; platform = "all" } }
                        }
                    }
                  } else {
                Row(Modifier.fillMaxWidth().padding(start = 36.dp, end = 36.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.gostex_logo), contentDescription = "GOSTEX", modifier = Modifier.width(100.dp).height(34.dp))
                    Spacer(Modifier.width(24.dp))
                    visibleTabs.forEach { label ->
                        Action(tabTitle(label), compact = true, active = tab == label, onClick = { tab = label; platform = "all" })
                        Spacer(Modifier.width(6.dp))
                    }
                    if (downloadsVisible) { DownloadsShortcut(tab == "downloads") { tab = "downloads"; platform = "all" }; Spacer(Modifier.width(6.dp)) }
                    Row(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        HeaderClock(clockOptions, clockInstant)
                        if (batteryVisible) { Spacer(Modifier.width(10.dp)); BatteryStatus(activity) { percent, charging -> activity.getString(if (charging) R.string.battery_charging else R.string.battery_level, percent) } }
                        if (mediaVisible) headerMediaSession?.let { session ->
                            Spacer(Modifier.width(10.dp))
                            MediaPlayerButton(session, Modifier.focusRequester(mediaFocus)) { mediaOpen = true }
                        }
                    }
                    if (rememberNetworkVisible()) {
                        NetworkStatusButton(activity)
                        Spacer(Modifier.width(6.dp))
                    }
                    val settingsLabel = tr(R.string.settings)
                    CompactIconButton(CompactIcon.Settings, settingsLabel, modifier = Modifier.focusRequester(settingsFocus),
                        onClick = { settingsInitialSection = "root"; settingsReturnFocus = null; settingsOpen = true })
                }
                  }
                }
                UpdateAvailableAction(activity, Modifier.padding(horizontal = 42.dp, vertical = 4.dp).focusRequester(updateFocus)) {
                    settingsInitialSection = "updates"
                    settingsReturnFocus = updateFocus
                    settingsOpen = true
                }
                WorldClockStrip(clockOptions, clockInstant) { zone, focus ->
                    settingsInitialSection = "clock/manage/$zone"
                    settingsReturnFocus = focus
                    settingsOpen = true
                }
                if (tab == "home") {
                    HomeFeed(feedState, shelfContents, favoriteRefs, ready = loadedApps != null,
                        inputEnabled = !settingsOpen && !mediaOpen && !searchOpen && tileMenu == null && membershipItem == null && favoriteOrderItem == null,
                        modifier = Modifier.weight(1f), onGameFocus = ::selectGame,
                        onGameOpen = ::openGame, onGameMenu = ::gameMenu,
                        onAppOpen = activity::openApp, onAppMenu = ::appMenu,
                        onMovieOpen = { openMediaLibrary(activity, it.id, playIfSingle = true) }, onMovieMenu = ::movieMenu,
                        onMovieCover = activity::fetchMediaCover,
                        onEmptySettings = { settingsInitialSection = "homeOptions"; settingsReturnFocus = null; settingsOpen = true })
                } else if (tab == "movies") {
                    MediaScreen(activity, movies, movieResult != null, movieResult?.isFailure == true,
                        inputEnabled = !settingsOpen && !mediaOpen && tileMenu == null && membershipItem == null && favoriteOrderItem == null,
                        favorites = favoriteRefs, onMenu = ::movieMenu, modifier = Modifier.weight(1f))
                } else if (tab == "downloads") {
                    val downloadsFocus = remember { FocusRequester() }
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val wide = maxWidth >= 600.dp
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 36.dp else 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(tr(R.string.torr_download_channel).uppercase(), color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            TorrDownloadUi(activity, downloadsFocus, wide)
                        }
                    }
                } else if (tab == "apps") {
                    AppsScreen(apps, ready = loadedApps != null, favorites = favoriteRefs,
                        selectedPackage = selectedAppPackage, onSelect = { selectedAppPackage = it },
                        scroll = appsScroll, selectedFocus = firstFocus,
                        inputEnabled = !settingsOpen && !mediaOpen && tileMenu == null && membershipItem == null && favoriteOrderItem == null,
                        onOpen = activity::openApp, onMenu = ::appMenu, modifier = Modifier.weight(1f))
                } else {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val compact = maxWidth < 600.dp
                        @Composable fun title() {
                            Text(tabTitle(tab).uppercase(), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = White)
                            Spacer(Modifier.width(16.dp)); Text(gameCount(filtered.size + filteredAndroidGames.size), color = Muted, fontSize = 13.sp)
                        }
                        @Composable fun actions() {
                            if (searchQuery.isNotBlank()) {
                                Text(searchQuery, Modifier.widthIn(max = 120.dp).padding(end = 12.dp), color = Lime,
                                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                CompactIconButton(CompactIcon.Clear, tr(R.string.clear_search), onClick = { applySearch("") })
                                Spacer(Modifier.width(6.dp))
                            }
                            CompactIconButton(CompactIcon.Search, tr(R.string.search_games), active = searchQuery.isNotBlank(), modifier = Modifier.focusRequester(searchFocus),
                                onClick = { searchReturnTarget = ""; searchOpen = true })
                            Spacer(Modifier.width(6.dp))
                            CompactIconButton(CompactIcon.Add, tr(if (activity.scanning) R.string.scanning else R.string.add_games), enabled = !activity.scanning,
                                onClick = { activity.chooseFolder(if (platform == "android-apps") "all" else platform) })
                            Spacer(Modifier.width(6.dp))
                            CompactIconButton(CompactIcon.Folder, tr(R.string.folders), onClick = { activity.manageFolders() })
                        }
                        if (compact) Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) { title() }
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
                        } else Row(Modifier.fillMaxWidth().padding(start = 36.dp, end = 36.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            title(); Spacer(Modifier.weight(1f)); actions()
                        }
                    }
                    if (games.isNotEmpty() || androidGameApps.isNotEmpty() || searchQuery.isNotBlank()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(3.dp)) {
                                item(key = "all") { Action(tr(R.string.all), compact = true, active = platform == "all", onClick = { platform = "all" }) }
                                if (androidGameApps.isNotEmpty()) item(key = "android-apps") {
                                    Action(tr(R.string.android_games_platform), compact = true, active = platform == "android-apps", onClick = { platform = "android-apps" })
                                }
                                items(PlatformRegistry.all, key = { it.id }) { p ->
                                    Action(tr(p.titleRes), compact = true, active = platform == p.id, onClick = { platform = p.id })
                                }
                            }
                        }
                        if (tab == "library") {
                            val selectedPlatform = PlatformRegistry.resolve(platform, libraryOptions.ndsEmulator)
                            val importHint = if (platform == "android-apps") tr(R.string.android_games_hint) else if (selectedPlatform == null) tr(R.string.platform_import_hint) else {
                                val formats = selectedPlatform.extensions.sorted().joinToString(" · ") { it.uppercase(java.util.Locale.ROOT) }
                                tr(R.string.platform_formats, selectedPlatform.emulator, formats) +
                                    (selectedPlatform.hintRes?.let { "\n" + tr(it) } ?: "")
                            }
                            Text(importHint, Modifier.padding(start = 36.dp, end = 36.dp, bottom = 6.dp), color = Muted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (libraryIsEmpty) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (searchQuery.isNotBlank()) Text(tr(R.string.search_empty), Modifier.padding(horizontal = 36.dp), color = Muted, fontSize = 18.sp)
                        else Column(Modifier.widthIn(max = 660.dp).padding(horizontal = 36.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(tr(R.string.library_empty_title), color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text(tr(R.string.library_import_steps, tr(R.string.add_games)), color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
                            Action(if (activity.scanning) tr(R.string.scanning) else tr(R.string.add_games), modifier = Modifier.focusRequester(addGamesFocus),
                                onClick = { if (!activity.scanning) activity.chooseFolder(if (games.isEmpty() || platform == "android-apps") "all" else platform) })
                            Text(tr(R.string.library_optional_hint), color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                    } else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                      LazyVerticalGrid(columns = GridCells.Fixed(TileLayout.columns(maxWidth, LocalTileSize.current)), state = libraryScroll, modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = TileLayout.screenInset + TileLayout.focusInset, end = TileLayout.screenInset + TileLayout.focusInset, top = 8.dp, bottom = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(TileLayout.gap), verticalArrangement = Arrangement.spacedBy(TileLayout.gap)) {
                        items(filtered, key = { "game:${it.id}" }) { g ->
                            val cardFocus = remember(g.id) { FocusRequester() }
                            Poster(g, Modifier.fillMaxWidth()
                                .then(if (g.id == chosen?.id || (chosen !in filtered && g == filtered.first())) Modifier.focusRequester(firstFocus) else Modifier)
                                .focusRequester(cardFocus), g.id in favorites, onFocus = { selectGame(g) },
                                onClick = { openGame(g, cardFocus) }, onLongClick = { gameMenu(g, cardFocus) })
                        }
                        items(filteredAndroidGames, key = { "app:${it.packageName}" }) { app ->
                            val cardFocus = remember(app.packageName) { FocusRequester() }
                            val first = filtered.isEmpty() && app == filteredAndroidGames.firstOrNull()
                            AppTile(app, modifier = Modifier.fillMaxWidth()
                                .then(if (first) Modifier.focusRequester(firstFocus) else Modifier)
                                .focusRequester(cardFocus), favorite = homeAppKey(app.packageName) in favoriteRefs,
                                onFocus = { selectedAppPackage = app.packageName },
                                onClick = { activity.openApp(app) }, onLongClick = { appMenu(app, cardFocus) })
                        }
                      }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 8.dp)) {
                    Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                        ControlHints(R.string.control_select, R.string.back, showHomeNavigation = tab == "home", showHoldMenu = true)
                    }
                    Spacer(Modifier.width(8.dp)); Text("GOSTEX", color = Lime, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, maxLines = 1)
                }
            }
            LaunchedEffect(tab, platform, homeOptions.order, searchQuery, searchOpen, libraryIsEmpty) {
                if (!settingsOpen && !mediaOpen && !searchOpen && tileMenu == null && membershipItem == null && favoriteOrderItem == null && tab == "library") {
                    if (searchReturnTarget == "button" || libraryIsEmpty) {
                        withFrameNanos { }
                        runCatching { if (libraryIsEmpty && searchQuery.isBlank()) addGamesFocus.requestFocus() else searchFocus.requestFocus() }
                    } else if (!libraryIsEmpty) {
                        val index = filtered.indexOfFirst { it.id == chosen?.id }.coerceAtLeast(0)
                        libraryScroll.scrollToItem(index)
                        withFrameNanos { }
                        runCatching { firstFocus.requestFocus() }
                    }
                    searchReturnTarget = ""
                }
            }
        }
        LauncherNoticeHost(activity.notices, activity, Modifier.align(Alignment.BottomCenter),
            active = !settingsOpen && !mediaOpen && !searchOpen && tileMenu == null && membershipItem == null && favoriteOrderItem == null && activity.coverEditorId == null,
            onRestoreFocus = {
                if (tab == "home" && detail == null) feedState.restore(feedState.anchor())
                else runCatching { firstFocus.requestFocus() }
            })
    }
    tileMenu?.let { target ->
        val menuGame = (target as? TileMenuTarget.GameItem)?.game
        val menuApp = (target as? TileMenuTarget.AppItem)?.app
        val menuMovie = (target as? TileMenuTarget.MovieItem)?.movie
        val itemKey = when (target) {
            is TileMenuTarget.GameItem -> homeGameKey(target.game.id)
            is TileMenuTarget.AppItem -> homeAppKey(target.app.packageName)
            is TileMenuTarget.MovieItem -> homeMovieKey(target.movie.id)
        }
        val currentSection = sectionOptions.sections.firstOrNull {
            tab == "home" && it.id == tileReturnAnchor?.shelf && it.kind == HomeSectionKind.CUSTOM
        }
        TileContextMenu(target, isFavorite = itemKey in favoriteRefs,
            currentSectionTitle = currentSection?.let { sectionTitle(it) },
            onPlay = { dismissTileMenu(); menuGame?.let { activity.launchGame(it, 0) } },
            onDetails = { tileMenu = null; detail = menuGame; variant = 0 },
            onCover = { dismissTileMenu(); menuGame?.let { activity.editGameCover(it.id) }; menuMovie?.let { activity.chooseCover(homeMovieKey(it.id)) } },
            onToggleFavorite = { SectionPreferences.toggleFavorite(activity, itemKey); dismissTileMenu() },
            onOpenApp = { dismissTileMenu(); menuApp?.let(activity::openApp); menuMovie?.let { openMediaLibrary(activity, it.id) } },
            onSections = { tileMenu = null; membershipItem = itemKey },
            onFavoriteOrder = { tileMenu = null; favoriteOrderItem = itemKey },
            onPrioritize = { currentSection?.let { SectionPreferences.prioritize(activity, it.id, itemKey) }; dismissTileMenu() },
            onAppInfo = {
                dismissTileMenu()
                menuApp?.let { app ->
                    runCatching { activity.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))) }
                        .onFailure { activity.notice = activity.getString(R.string.app_error, app.name) }
                }
            },
            onDismiss = ::dismissTileMenu)
    }
    membershipItem?.let { itemKey ->
        SectionMembershipDialog(itemKey, onDismiss = { membershipItem = null; tileRestore++ })
    }
    favoriteOrderItem?.let { itemKey ->
        FavoriteOrderDialog(itemKey, apps, games, movies, onDismiss = { favoriteOrderItem = null; tileRestore++ })
    }
    if (settingsOpen) SettingsDrawer(activity, apps, games, mediaPlayback, settingsInitialSection, onClose = { settingsOpen = false })
    if (mediaOpen) MediaPlayerPopover(activity, mediaPlayback, onDismiss = { mediaOpen = false })
    activity.coverEditorId?.let { id -> games.firstOrNull { it.id == id }?.let { game ->
        GameCoverEditor(activity, game, onClose = { activity.coverEditorId = null })
    } }
    if (searchOpen) GameSearchDrawer(searchQuery, searchIndex, scopedAndroidGames,
        scopeLabel = listOf(tabTitle(tab), (if (platform == "android-apps") tr(R.string.android_games_platform) else platformTitle(platform)).takeIf { platform != "all" }).filterNotNull().joinToString(" / "),
        onApply = ::applySearch, onClose = { searchReturnTarget = "button"; searchOpen = false })

}

@Composable private fun GameSearchDrawer(
    query: String, index: GameSearchIndex, androidGames: List<TvApp>, scopeLabel: String, onApply: (String) -> Unit, onClose: () -> Unit
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Keep editing reads inside the dialog composition. Updating the outer Dialog on every
        // selection/composition change resets window parameters and can restart older TV IMEs.
        val state = rememberTextFieldState(initialText = query)
        val inputFocus = remember { FocusRequester() }
        val resultsFocus = remember { FocusRequester() }
        var inputFocused by remember { mutableStateOf(false) }
        val draft = state.text.toString()
        val resultCount = remember(index, draft, androidGames) {
            index.filter(draft).size + androidGames.count { draft.isBlank() || it.name.contains(draft.trim(), ignoreCase = true) }
        }
        val keyboard = LocalSoftwareKeyboardController.current
        val submit = { keyboard?.hide(); onApply(draft) }
        val close = { keyboard?.hide(); onClose() }
        val dialogView = LocalView.current
        val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
        LaunchedEffect(dialogWindow) { dialogWindow?.setDimAmount(0f) }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f)).safeDrawingPadding().imePadding()) {
            Column(Modifier.align(Alignment.CenterEnd).widthIn(max = 440.dp).fillMaxWidth().fillMaxHeight().background(Ink)
                .border(1.dp, Color(0xFF424244)).verticalScroll(rememberScrollState()).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(R.string.search_games).uppercase(), Modifier.weight(1f), color = White, fontSize = 23.sp, fontWeight = FontWeight.Black)
                    val closeLabel = tr(R.string.close)
                    Action("×", icon = true, compact = true, modifier = Modifier.semantics { contentDescription = closeLabel }, onClick = close)
                }
                Text(scopeLabel, color = Lime, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val inputLabel = tr(R.string.search_placeholder)
                BasicTextField(
                    state = state,
                    modifier = Modifier.fillMaxWidth().focusRequester(inputFocus).onFocusChanged { inputFocused = it.isFocused }
                        // SHIELD's TV IME can immediately reopen after Back while the field retains
                        // focus. Leave editing on the first Back; the next Back dismisses the drawer.
                        .onPreInterceptKeyBeforeSoftKeyboard { event ->
                            if (event.key == Key.Back) {
                                if (event.type == KeyEventType.KeyUp) {
                                    resultsFocus.requestFocus()
                                    // Target this dialog's window token directly: floating TV
                                    // keyboards may not respond to an IME insets hide request.
                                    val input = dialogView.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                                        as android.view.inputmethod.InputMethodManager
                                    input.hideSoftInputFromWindow(dialogView.windowToken, 0)
                                }
                                true
                            } else false
                        }
                        .semantics { contentDescription = inputLabel }
                        .border(if (inputFocused) 2.dp else 1.dp, if (inputFocused) Lime else Muted).background(Panel).padding(14.dp),
                    textStyle = TextStyle(color = White, fontSize = 20.sp),
                    cursorBrush = SolidColor(Lime),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = InputTransformation.maxLength(120),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    onKeyboardAction = { submit() },
                    decorator = { inner ->
                        Box {
                            if (draft.isEmpty()) Text(inputLabel, color = Muted, fontSize = 20.sp)
                            inner()
                        }
                    }
                )
                Text(tr(R.string.search_hint), color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
                Text(tr(R.string.search_result_count, resultCount), color = White, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Action(tr(R.string.search_show_results), primary = true, modifier = Modifier.focusRequester(resultsFocus), onClick = submit)
                    Action(tr(R.string.clear_search), onClick = { keyboard?.hide(); onApply("") })
                }
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            inputFocus.requestFocus()
            keyboard?.show()
        }
    }
}

@Composable private fun gameCount(n: Int): String = androidx.compose.ui.res.pluralStringResource(R.plurals.game_count, n, n)

@Composable internal fun Poster(game: Game, modifier: Modifier, favorite: Boolean, onFocus: () -> Unit, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        Surface(onClick = onClick, onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(TileLayout.aspectRatio).touchSurface(onLongClick = onLongClick, onClick = onClick).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Panel, focusedContainerColor = Panel),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
            border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(1.dp, Color(0xFF424244))), focusedBorder = Border(BorderStroke(2.dp, Lime)))
        ) {
            Box(Modifier.fillMaxSize()) {
                CoverArtwork(game.cover.ifEmpty { game.scene }, Modifier.fillMaxSize(), game.id, fallbackTitle = game.title)
                Text(platformTitle(game.platform), Modifier.align(Alignment.BottomStart).padding(6.dp)
                    .background(Ink.copy(alpha = .88f)).padding(horizontal = 5.dp, vertical = 3.dp),
                    color = Lime, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (favorite) FavoriteBadge(Modifier.align(Alignment.TopEnd).padding(7.dp))
            }
        }
        Spacer(Modifier.height(TileLayout.titleGap))
        Text(game.title, color = if (focused) Lime else White, fontWeight = FontWeight.Bold, fontSize = TileLayout.titleSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun PortableMaintenanceBarrier(activity: MainActivity, content: @Composable () -> Unit) {
    val active by PortableMaintenance.active.collectAsState()
    if (active || activity.portableMaintenanceSeen) {
        LaunchedEffect(active) { if (!active && activity.portableMaintenanceSeen) activity.recreate() }
        val phase by PortableMaintenance.phase.collectAsState()
        val label = when (phase) {
            PortableMaintenancePhase.PREPARING -> R.string.portable_maintenance_checking
            PortableMaintenancePhase.CANCELLING -> R.string.portable_maintenance_cancelling
            PortableMaintenancePhase.SAVING -> R.string.portable_maintenance_saving
        }
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text(tr(label), color = Color.White, fontSize = 20.sp)
        }
    } else content()
}

private fun settingsParent(section: String): String = when {
    section == "clock" || section == "language" -> "localeGroup"
    section.startsWith("clock/") -> clockParent(section)
    section in setOf("appearance", "art", "grafana") -> "appearanceGroup"
    section == "portablePreview" -> "portableCopy"
    section in setOf("covers", "portableCopy") -> "libraryGroup"
    section in setOf("libraryGroup", "nowPlaying", "downloads") -> "mediaGroup"
    else -> "root"
}

private fun settingsTitle(section: String): Int = when {
        section == "libraryGroup" -> R.string.downloads_library_group
    section.startsWith("clock") -> clockSectionTitleRes(section)
    else -> when (section) {
        "portablePreview" -> R.string.portable_preview_title
        "portableCopy" -> R.string.portable_copy_title
        "covers" -> R.string.covers_title
        "nowPlaying" -> R.string.media_player
        "downloads" -> R.string.torr_download_channel
        "appearanceGroup" -> R.string.settings_appearance_group
        "mediaGroup" -> R.string.settings_media_group
        "controller" -> R.string.settings_controls_group
        "localeGroup" -> R.string.settings_locale_group
        "systemGroup" -> R.string.settings_system_group
        "updates" -> R.string.updates_title
        "language" -> R.string.language_heading
        "art" -> R.string.art_title
        "grafana" -> R.string.grafana_settings_title
        "homeOptions" -> R.string.sections_title
        "appearance" -> R.string.settings_cards_title
        else -> R.string.settings
    }
}

@Composable private fun SettingsDrawer(activity: MainActivity, apps: List<TvApp>, games: List<Game>, mediaPlayback: MediaPlaybackState, initialSection: String, onClose: () -> Unit) {
    var savedSection by rememberSaveable { mutableStateOf(initialSection) }
    val section = when {
        savedSection == "videoOptions" -> "homeOptions"
        savedSection.startsWith("clock/") || savedSection in setOf("root", "appearanceGroup", "mediaGroup", "libraryGroup", "controller", "localeGroup", "systemGroup", "language", "art", "grafana", "homeOptions", "appearance", "clock", "covers", "nowPlaying", "updates", "portableCopy", "portablePreview", "downloads") -> savedSection
        else -> "root"
    }
    // The route we leave identifies the exact parent row to restore on Back.
    // This also works when a clock chip opens its settings directly from Home.
    var returnRow by rememberSaveable { mutableStateOf("") }
    val navigate: (String) -> Unit = { target ->
        returnRow = section
        savedSection = target
    }
    val slide = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            activity.releasePortablePreview()
            scope.launch {
                slide.animateTo(1f, tween(180))
                onClose()
            }
        }
    }
    LaunchedEffect(Unit) { slide.animateTo(0f, tween(220)) }
    val back: () -> Unit = { if (section == "root" || section == initialSection) close() else navigate(settingsParent(section)) }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { dialogWindow?.setDimAmount(0f) }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f * (1f - slide.value))).safeDrawingPadding()) {
            SettingsPanel(activity, apps, games, mediaPlayback, close, back, section, returnRow, navigate, Modifier.align(Alignment.CenterEnd).widthIn(max = 380.dp).fillMaxWidth()
                .fillMaxHeight().offset(x = (380 * slide.value).dp))
        }
    }
}

@Composable private fun SettingsPanel(activity: MainActivity, apps: List<TvApp>, games: List<Game>, mediaPlayback: MediaPlaybackState, onClose: () -> Unit, onBack: () -> Unit, section: String, returnRow: String, onSection: (String) -> Unit, modifier: Modifier = Modifier) {
    val prefs = remember { activity.getSharedPreferences("orbit", android.content.Context.MODE_PRIVATE) }
    val appVersion = remember(activity) {
        @Suppress("DEPRECATION")
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        info.versionName.orEmpty()
    }
    var remoteAuto by remember { mutableStateOf(RemoteStartup.enabled(activity)) }
    val displayLocale = LocalConfiguration.current.locales[0]
    val languageCode = displayLocale.language
    val language = when (languageCode) {
        "ru" -> "Русский"
        "es" -> "Español"
        else -> "English"
    }
    val initialFocus = remember { FocusRequester() }
    val backupDateFormat = remember(section, displayLocale) { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, displayLocale) }
    val rows = when (section) {
        "root" -> listOf("homeOptions", "appearanceGroup", "mediaGroup", "controller", "localeGroup", "systemGroup", "updates")
        "appearanceGroup" -> listOf("appearance", "art", "grafana")
        "mediaGroup" -> listOf("libraryGroup", "nowPlaying", "downloads", "movies")
        "libraryGroup" -> (if (activity.legacyRecoveryPending) listOf("legacyRecovery") else emptyList()) +
            listOf("addGames", "folders", "portableCopy", "covers", "ndsEmulator", "androidGames", "groupVersions", "libraryTab", "appsTab")
        "localeGroup" -> listOf("language", "clock")
        "systemGroup" -> listOf("batteryStatus", "network", "torrServer", "torrServeInstall", "revancedManager", "defaultHome", "shield", "remote")
        "portableCopy" -> listOf("copyAction", "portablePreview")
        else -> emptyList()
    }
    val focusedRow = returnRow.takeIf { it in rows } ?: rows.firstOrNull()
    fun rowFocus(id: String) = if (id == focusedRow) Modifier.focusRequester(initialFocus) else Modifier
    // Each section starts at its own top; requesting the remembered row brings
    // it into view instead of carrying an unrelated panel's scroll offset.
    val scrollState = key(section) { rememberScrollState() }
    Column(modifier.background(Ink).border(1.dp, Color(0xFF424244)).padding(horizontal = 22.dp, vertical = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (section != "root") {
                val backLabel = tr(R.string.back)
                Action("‹", icon = true, compact = true, modifier = Modifier.semantics { contentDescription = backLabel }, onClick = onBack)
                Spacer(Modifier.width(12.dp))
            }
            Text(tr(settingsTitle(section)).uppercase(), modifier = Modifier.weight(1f), color = White, fontSize = 23.sp,
                fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            val closeLabel = tr(R.string.close)
            Action("×", icon = true, compact = true, modifier = Modifier.semantics { contentDescription = closeLabel }, onClick = onClose)
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.weight(1f).verticalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (section == "root") {
                listOf(
                    "homeOptions" to R.string.sections_create_hint,
                    "appearanceGroup" to R.string.settings_appearance_summary,
                    "mediaGroup" to R.string.settings_media_summary,
                    "controller" to R.string.settings_controls_summary,
                    "localeGroup" to R.string.settings_locale_summary,
                    "systemGroup" to R.string.settings_system_summary,
                    "updates" to R.string.updates_summary
                ).forEach { (route, summary) -> key(route) {
                    SettingRow(tr(settingsTitle(route)), tr(summary), rowFocus(route), onClick = { onSection(route) })
                } }
            } else if (section == "appearanceGroup") {
                SettingRow(tr(R.string.settings_cards_title), modifier = rowFocus("appearance"), onClick = { onSection("appearance") })
                SettingRow(tr(R.string.art_title), modifier = rowFocus("art"), onClick = { onSection("art") })
                SettingRow(tr(R.string.grafana_settings_title), modifier = rowFocus("grafana"), onClick = { onSection("grafana") })
            } else if (section == "mediaGroup") {
                SettingRow(tr(R.string.downloads_library_group), tr(R.string.downloads_library_hint), rowFocus("libraryGroup"), onClick = { onSection("libraryGroup") })
                SettingRow(tr(R.string.media_player), tr(R.string.media_settings_summary), rowFocus("nowPlaying"), onClick = { onSection("nowPlaying") })
                SettingRow(tr(R.string.torr_download_channel), modifier = rowFocus("downloads"), onClick = { onSection("downloads") })
                SettingRow(tr(R.string.movies_title), modifier = rowFocus("movies"), onClick = { activity.startActivity(Intent(activity, MovieLibraryActivity::class.java)) })
                MediaAutoCoverSetting()
            } else if (section == "libraryGroup") {
                if (activity.legacyRecoveryPending) SettingRow(tr(R.string.legacy_recovery_title), tr(R.string.legacy_recovery_summary),
                    rowFocus("legacyRecovery"), onClick = { activity.retryLegacyRecovery() })
                SettingRow(tr(R.string.add_games), modifier = rowFocus("addGames"), onClick = { activity.chooseFolder("all") })
                SettingRow(tr(R.string.folders), modifier = rowFocus("folders"), onClick = { activity.manageFolders() })
                SettingRow(tr(R.string.portable_copy_title), tr(R.string.portable_copy_summary), rowFocus("portableCopy"), onClick = { onSection("portableCopy") })
                SettingRow(tr(R.string.covers_title), tr(R.string.covers_summary), rowFocus("covers"), onClick = { onSection("covers") })
                NintendoDsEmulatorSetting(rowFocus("ndsEmulator"))
                AndroidGamesVisibilitySetting(rowFocus("androidGames"))
                GroupVersionsSetting(rowFocus("groupVersions"))
                LibraryVisibilitySetting(rowFocus("libraryTab"))
                AppsVisibilitySetting(rowFocus("appsTab"))
            } else if (section == "localeGroup") {
                SettingRow(tr(R.string.language), language, rowFocus("language"), onClick = { onSection("language") })
                SettingRow(tr(R.string.clock_title), modifier = rowFocus("clock"), onClick = { onSection("clock") })
            } else if (section == "systemGroup") {
                val batteryVisible = rememberBatteryVisible()
                SettingRow(tr(R.string.battery_visibility), tr(if (batteryVisible) R.string.enabled else R.string.disabled), rowFocus("batteryStatus"), onClick = { BatteryPreferences.visible(activity, !batteryVisible) })
                SettingRow(tr(R.string.network_settings), modifier = rowFocus("network"), onClick = { activity.openNetworkSettings() })
                TorrServerStatusSetting(rowFocus("torrServer"))
                TorrServeInstallSetting(activity, rowFocus("torrServeInstall"))
                ReVancedInstallSetting(activity, rowFocus("revancedManager"))
                DefaultHomeSetting(activity, rowFocus("defaultHome"))
                SettingRow(tr(R.string.shield_settings), modifier = rowFocus("shield"), onClick = { activity.openSettings() })
                SettingRow(tr(R.string.remote_autostart), tr(if (remoteAuto) R.string.enabled else R.string.disabled), rowFocus("remote"), onClick = {
                    remoteAuto = !remoteAuto
                    prefs.edit().putBoolean("remoteAutostart", remoteAuto).apply()
                    if (remoteAuto) RemoteStartup.start(activity)
                })
            } else if (section == "updates") {
                UpdateSettings(activity, initialFocus)
            } else if (section == "downloads") {
                TorrDownloadUi(activity, initialFocus)
            } else if (section == "nowPlaying") {
                MediaPlayerSettings(activity, mediaPlayback, initialFocus)
            } else if (section == "portableCopy") {
                Text(tr(R.string.portable_copy_unavailable_restore), color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                SettingRow(tr(if (activity.portableCopyBusy) R.string.portable_copy_cancel else R.string.portable_copy_choose),
                    modifier = rowFocus("copyAction"), onClick = {
                        if (activity.portableCopyBusy) activity.cancelPortableCopy() else activity.choosePortableCopyFolder()
                    })
                if (activity.portableCopyMessage.isNotBlank()) Text(activity.portableCopyMessage, color = White, fontSize = 13.sp, lineHeight = 19.sp)
                SettingRow(tr(R.string.portable_preview_title), tr(R.string.portable_preview_summary), rowFocus("portablePreview"),
                    onClick = { if (!activity.portableCopyBusy) onSection("portablePreview") })
            } else if (section == "portablePreview") {
                Text(tr(R.string.portable_preview_hint), color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                SettingRow(tr(if (activity.portablePreviewBusy) R.string.portable_preview_cancel else R.string.portable_preview_folder),
                    modifier = Modifier.focusRequester(initialFocus), onClick = {
                        if (activity.portablePreviewBusy) activity.cancelPortablePreview() else activity.choosePortablePreviewFolder()
                    })
                if (activity.portablePreviewMessage.isNotBlank()) Text(activity.portablePreviewMessage, color = White, fontSize = 13.sp, lineHeight = 19.sp)
                if (activity.portableDecisionGroups.isNotEmpty()) {
                    Text(tr(R.string.portable_decision_hint), color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                    activity.portableDecisionGroups.forEach { group ->
                        val label = when (group) {
                            PortableRestoreDecisionGroup.GAMES_AND_COVERS -> R.string.portable_decision_games
                            PortableRestoreDecisionGroup.HOME -> R.string.portable_decision_home
                            PortableRestoreDecisionGroup.SETTINGS -> R.string.portable_decision_settings
                        }
                        val value = when (activity.portableDecisionChoices[group]) {
                            PortableRestoreChoice.KEEP_CURRENT -> R.string.portable_decision_keep
                            PortableRestoreChoice.USE_BACKUP -> R.string.portable_decision_backup
                            null -> R.string.portable_decision_choose
                        }
                        key(group) { SettingRow(tr(label), tr(value), onClick = { activity.choosePortableDecision(group) }) }
                    }
                    Text(tr(R.string.portable_decision_remaining, activity.portableDecisionRemaining), color = Muted, fontSize = 13.sp)
                }
                if (activity.portableDecisionRootBlocks > 0) Text(tr(R.string.portable_decision_root_blocked), color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                if (activity.portableRestoreAvailable) SettingRow(tr(R.string.portable_restore_action), tr(R.string.portable_restore_summary),
                    onClick = { activity.confirmPortableRestore() })
                activity.portablePreviewTitles.forEach { title -> Text(title, color = White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (activity.portablePreviewExtra > 0) Text(tr(R.string.portable_preview_more, activity.portablePreviewExtra), color = Muted, fontSize = 13.sp)
                activity.portableBackups.forEachIndexed { index, candidate ->
                    val label = candidate.modified?.let { date -> tr(R.string.portable_preview_dated, index + 1, backupDateFormat.format(java.util.Date(date))) }
                        ?: tr(R.string.portable_preview_number, index + 1)
                    key(candidate.documentId) { SettingRow(label, modifier = Modifier, onClick = { activity.inspectPortableCandidate(candidate) }) }
                }
            } else if (section == "covers") {
                ManagedCoverSettings(activity, games, initialFocus)
            } else if (section == "art") {
                ArtSettings(initialFocus)
            } else if (section == "grafana") {
                GrafanaSettings(initialFocus)
            } else if (section.startsWith("clock")) {
                ClockSettings(initialFocus, section, onSection)
            } else if (section == "homeOptions") {
                SectionSettings(initialFocus, apps, games)
                MediaVisibilitySetting()
                DownloadsVisibilitySetting()
            } else if (section == "appearance") {
                AppearanceSettings(initialFocus)
            } else if (section == "controller") {
                ControllerSettings(initialFocus)
            } else {
                Text(tr(R.string.language_hint), color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                Spacer(Modifier.height(12.dp))
                listOf("ru" to "Русский", "en" to "English", "es" to "Español").forEach { (code, name) ->
                    val current = code == languageCode
                    SettingRow(name, if (current) tr(R.string.current_language) else "",
                        modifier = if (current) Modifier.focusRequester(initialFocus) else Modifier,
                        isSelected = current, trailing = if (current) "✓" else "",
                        onClick = { activity.setLanguage(code) })
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (appVersion.isEmpty()) "GOSTEX" else "GOSTEX / $appVersion", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            ControlHints(back = if (section == "root") R.string.close else R.string.back)
        }
    }
    LaunchedEffect(section) { withFrameNanos { }; initialFocus.requestFocus() }
    LaunchedEffect(section, activity.portablePreviewFocusRevision) {
        if (section == "portablePreview" && activity.portablePreviewFocusRevision > 0) {
            withFrameNanos { }
            initialFocus.requestFocus()
        }
    }
}

@Composable internal fun SettingRow(title: String, value: String = "", modifier: Modifier = Modifier, isSelected: Boolean = false, trailing: String = "›", onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth().touchSurface(onClick = onClick).semantics { selected = isSelected }.onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Panel, focusedContainerColor = Lime),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Lime)))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = if (focused) Ink else White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2)
                if (value.isNotEmpty()) Text(value, color = if (focused) Ink else Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.width(12.dp))
            Text(trailing, color = if (focused) Ink else Muted, fontSize = 20.sp)
        }
    }
}
