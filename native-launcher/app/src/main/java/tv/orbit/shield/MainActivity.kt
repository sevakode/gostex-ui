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

class MainActivity : ComponentActivity() {
    internal val updates: LauncherUpdates by lazy { LauncherUpdates(this) }
    private val diagnosticExport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) updates.exportDiagnostics(uri)
    }
    internal fun chooseDiagnosticExport() { diagnosticExport.launch("gostex-diagnostics.zip") }
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(Language.context(base))
    }
    fun setLanguage(code: String) {
        if (code !in setOf("ru", "en", "es")) return
        val changed = code != resources.configuration.locales[0].language
        if (getSharedPreferences("orbit", MODE_PRIVATE).edit().putString("language", code).commit() && changed) recreate()
    }
    private var pending: Pair<Game, Int>? = null
    internal val notices = LauncherNoticeState()
    var notice: String
        get() = notices.current?.message.orEmpty()
        set(value) { notices.show(value) }
    var resumeVersion by mutableIntStateOf(0)
    var homeVersion by mutableIntStateOf(0)
    var library by mutableStateOf<List<Game>>(emptyList())
    var scanning by mutableStateOf(false)
    internal var managedCovers by mutableStateOf<Map<String, ManagedCover>>(emptyMap())
    var coverEditorId by mutableStateOf<String?>(null)
    var coversBusy by mutableStateOf(false)
    var coversStatus by mutableStateOf("")
    private var pendingCoverId: String? = null
    private var coversLoop: Job? = null
    private var bundled = emptyList<Game>()
    private val importScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var importPlatform = ""
    private var importSource = LibrarySource.FILES
    private var importHintRoot: LibraryImport.Root? = null
    private var refreshPending = false
    private var libraryVisible = false
    private var importJob: Job? = null
    override fun onResume() { super.onResume(); updates.onResume(); libraryVisible = true; resumeVersion++; refreshLibrary(); startCoverSync() }
    override fun onPause() { AndroidDiagnostics.event("launcher.pause"); super.onPause() }
    override fun onStop() {
        updates.onStop()
        libraryVisible = false
        coversLoop?.cancel()
        refreshPending = false
        importJob?.cancel()
        super.onStop()
    }
    override fun onDestroy() { updates.close(); importScope.cancel(); super.onDestroy() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AndroidDiagnostics.event("launcher.intent", if (intent.hasCategory(Intent.CATEGORY_HOME)) "home" else "other")
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homeVersion++
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
        val id = pendingCoverId
        pendingCoverId = null
        if (uri != null && id != null) importCoverImage(id, uri)
    }
    private fun importCoverImage(id: String, uri: Uri) {
        importScope.launch {
            coversBusy = true
            try {
                managedCovers = withContext(Dispatchers.IO) { ManagedCoverStore.importManual(this@MainActivity, id, uri) }
                ManagedCoverPreferences.choice(this@MainActivity, id, CoverChoice.MANUAL)
                coversStatus = getString(R.string.cover_saved)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { notice = getString(R.string.cover_error, e.localizedMessage.orEmpty()) }
            finally { coversBusy = false }
            syncCovers(false)
        }
    }
    internal suspend fun applyOnlineCover(id: String, bytes: ByteArray, image: OnlineCoverImage) {
        managedCovers = withContext(Dispatchers.IO) { ManagedCoverStore.importOnline(this@MainActivity,id,bytes,image) }
        ManagedCoverPreferences.choice(this,id,CoverChoice.MANUAL)
        coversStatus = getString(R.string.cover_saved)
        syncCovers(false)
    }
    fun chooseSharedCover(id: String) {
        if (coversBusy) return
        val folder = ManagedCoverPreferences.read(this).folder
        if (folder.isEmpty()) { chooseCoversFolder(); return }
        importScope.launch {
            coversBusy = true
            try {
                val images = withContext(Dispatchers.IO) { ManagedCoverStore.images(this@MainActivity, folder) }
                if (images.isEmpty()) notice = getString(R.string.covers_no_images)
                else android.app.AlertDialog.Builder(this@MainActivity).setTitle(getString(R.string.cover_choose_shared))
                    .setItems(images.map { it.first }.toTypedArray()) { _, index -> importCoverImage(id, images[index].second) }
                    .setNegativeButton(getString(R.string.cancel), null).show()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { notice = getString(R.string.cover_error, e.localizedMessage.orEmpty()) }
            finally { coversBusy = false }
        }
    }
    fun chooseCover(id: String) {
        if (coversBusy) return
        pendingCoverId = id
        coverPicker.launch(arrayOf("image/png", "image/jpeg", "image/webp"))
    }
    private val coversFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val old = ManagedCoverPreferences.read(this).folder
            ManagedCoverPreferences.folder(this, uri.toString())
            if (old.isNotEmpty() && old != uri.toString() && LibraryImport.roots(this).none { it.uri == old }) {
                runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            }
            syncCovers(true)
            startCoverSync()
        } catch (e: Exception) { notice = getString(R.string.cover_error, e.localizedMessage.orEmpty()) }
    }
    fun chooseCoversFolder() { coversFolderPicker.launch(ManagedCoverPreferences.read(this).folder.takeIf { it.isNotEmpty() }?.let(Uri::parse)) }
    fun disconnectCoversFolder() {
        val old = ManagedCoverPreferences.read(this).folder
        ManagedCoverPreferences.folder(this, "")
        coversLoop?.cancel()
        if (old.isNotEmpty() && LibraryImport.roots(this).none { it.uri == old }) runCatching {
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
    fun syncCovers(manual: Boolean) { importScope.launch { performCoverSync(manual) } }
    private suspend fun performCoverSync(manual: Boolean) {
        if (coversBusy) return
        val options = ManagedCoverPreferences.read(this)
        if (options.folder.isEmpty()) return
        coversBusy = true
        try {
            val currentGames = library.toList()
            val result = withContext(Dispatchers.IO) {
                ManagedCoverStore.sync(this@MainActivity, currentGames, options.folder, manual || options.automatic, forceExport = manual)
            }
            managedCovers = result.entries
            coversStatus = getString(R.string.covers_sync_result, result.applied, result.rejected)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            coversStatus = getString(R.string.cover_error, e.localizedMessage.orEmpty())
            if (manual) notice = coversStatus
        } finally { coversBusy = false }
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
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
        gameFilePicker.launch(arrayOf("*/*"))
    }
    private fun chooseLibraryFolder(platform: String, source: LibrarySource) {
        importPlatform = platform
        importSource = source
        val existing = LibraryImport.roots(this).lastOrNull { it.platform == platform && it.source == source }?.uri
        val initial = existing?.let { Uri.parse(it) } ?: bundled.firstOrNull { it.platform == platform && it.tree.isNotBlank() }?.tree?.let {
            DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", it)
        }
        folderPicker.launch(initial?.let { DocumentsContract.buildDocumentUriUsingTree(it, DocumentsContract.getTreeDocumentId(it)) })
    }
    fun manageFolders() {
        val roots = LibraryImport.roots(this)
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
                    LibraryImport.removeRoot(this, roots[index])
                    refreshLibrary()
                }.setNegativeButton(getString(R.string.cancel), null).show()
        }.setNegativeButton(getString(R.string.close), null).show()
    }
    fun refreshLibrary() {
        if (!libraryVisible) { refreshPending = true; return }
        if (scanning) { refreshPending = true; return }
        refreshPending = false
        val roots = LibraryImport.roots(this)
        val hintRoot = importHintRoot
        scanning = true
        // Enter try/finally before onStop can cancel the queued Main dispatcher work.
        // Otherwise cancellation before coroutine start leaves scanning=true forever.
        importJob = importScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = withContext(Dispatchers.IO) {
                    val previous = LibraryImport.read(this@MainActivity)
                    val errors = mutableListOf<String>()
                    val found = roots.flatMap { root ->
                        val scanned = try { LibraryImport.scan(this@MainActivity, root, bundled, showHints = root == hintRoot) { warning ->
                            errors += "${PlatformRegistry.title(this@MainActivity, root.platform)}: $warning"
                        } }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) {
                            errors += "${PlatformRegistry.title(this@MainActivity, root.platform)}: ${e.localizedMessage}"
                            null
                        }
                        libraryRootSnapshot(root.uri, scanned, previous, root.platform)
                    }.let(::mergeImportedVariants)
                    currentCoroutineContext().ensureActive()
                    LibraryImport.write(this@MainActivity, found)
                    found to errors
                }
                library = result.first
                if (importHintRoot == hintRoot) importHintRoot = null
                if (result.second.isNotEmpty()) notice = result.second.joinToString("; ")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { notice = getString(R.string.refresh_error, e.localizedMessage.orEmpty()) }
            finally {
                scanning = false
                if (refreshPending && libraryVisible && !isDestroyed) { refreshPending = false; refreshLibrary() }
            }
        }
    }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
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
        updates // Initialize bounded diagnostics and the preference listener once for this Activity.
        importPlatform = savedInstanceState?.getString("importPlatform").orEmpty()
        importSource = LibrarySource.read(savedInstanceState?.getString("importSource"), importPlatform)
        pendingCoverId = savedInstanceState?.getString("pendingCoverId")
        managedCovers = ManagedCoverStore.read(this)
        RemoteStartup.start(this)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        bundled = loadGames()
        LibraryImport.migrateGrantedCatalogRoots(this, bundled)
        val rootKeys = LibraryImport.roots(this).map { it.uri to it.platform }.toSet()
        library = LibraryImport.read(this).filter { (it.tree to it.platform) in rootKeys }
        ArtSchedule.install(this)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Lime, surface = Ink, onSurface = White)) { ControllerProvider { Orbit(this, library.filterNot { it.demo }) } } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("importPlatform", importPlatform)
        outState.putString("importSource", importSource.value)
        outState.putString("pendingCoverId", pendingCoverId)
        super.onSaveInstanceState(outState)
    }

    private fun loadGames(): List<Game> {
        return LibraryImport.decode(assets.open("catalog.json").bufferedReader().use { it.readText() })
    }

    fun launchGame(game: Game, variant: Int = 0) {
        AndroidDiagnostics.event("launch.game")
        try {
            val spec = PlatformRegistry.resolve(game.platform, LibraryPreferences.read(this).ndsEmulator)
            if (spec == null) { notice = getString(R.string.platform_unknown); return }
            PlatformRegistry.unavailableReason(this, spec)?.let { notice = it; return }
            val raw = game.files.getOrNull(variant)
            if (raw.isNullOrEmpty()) { notice = getString(R.string.game_file_missing); return }
            val uri = if (raw.startsWith("content:")) Uri.parse(raw) else if (raw.startsWith("/storage/emulated/0/Android/data/com.flycast.emulator/files/")) Uri.parse(raw) else {
                val tree = if (game.tree.startsWith("content:")) Uri.parse(game.tree) else DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", game.tree)
                val granted = contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
                if (!granted) {
                    pending = game to variant
                    notice = getString(R.string.grant_folder, game.tree.removePrefix("primary:"))
                    picker.launch(DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)))
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
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() },
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
    val visibleTabs = remember(libraryOptions.tabVisible, appsOptions.tabVisible) {
        buildList {
            add("home")
            if (libraryOptions.tabVisible) add("library")
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
    val mediaOptions = rememberMediaPlayerOptions()
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
    val homeOptions = rememberHomeOptions()
    val clockOptions = rememberClockOptions()
    val clockInstant = rememberClockInstant(clockOptions.visible && clockOptions.zones.isNotEmpty() && detail == null)
    val played = remember(games, activity.resumeVersion) {
        games.associate { it.id to runCatching { prefs.getLong("played:${it.id}", 0L) }.getOrDefault(0L) }
    }
    val homeGames = remember(games, favorites, played, homeOptions.order) {
        orderedHomeGames(games, favorites, played, homeOptions.order)
    }
    val shelfContents = remember(sectionOptions, homeGames, apps, played) {
        homeShelfContents(sectionOptions, homeGames, apps, played)
    }
    val chosen = games.firstOrNull { it.id == chosenId } ?: homeGames.firstOrNull()
    val locale = LocalConfiguration.current.locales[0].toLanguageTag()
    val platformLabels = remember(locale) { PlatformRegistry.all.associate { it.id to activity.getString(it.titleRes) } }
    val sortedGames = remember(games) { games.sortedBy { it.title.lowercase(java.util.Locale.ROOT) } }
    val scopedGames = remember(sortedGames, platform) {
        sortedGames.filter { platform == "all" || it.platform == platform }
    }
    val searchIndex = remember(scopedGames, platformLabels) { GameSearchIndex(scopedGames, platformLabels) }
    val filtered = remember(homeGames, tab, platform, searchIndex, searchQuery) {
        if (tab == "home") homeGames.filter { platform == "all" || it.platform == platform }
        else searchIndex.filter(searchQuery)
    }
    LaunchedEffect(tab, visibleTabs) {
        if (tab !in visibleTabs) {
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

    Box(Modifier.fillMaxSize().background(Ink).onPreviewKeyEvent { event ->
        if (tab == "home" && detail == null && !settingsOpen && !mediaOpen && !searchOpen && tileMenu == null && membershipItem == null && favoriteOrderItem == null && isHomeShelfKey(event.nativeKeyEvent)) {
            homeShelfStep(event.nativeKeyEvent)?.let(feedState::moveBy)
            true
        } else false
    }) {
        if (detail != null) {
            val g = detail!!
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
                if (g.files.size > 1) {
                    Text(tr(R.string.variant, variant + 1, g.files.size), color = Muted, fontSize = 12.sp)
                    Text(g.files[variant].substringAfterLast('/'), color = White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(540.dp))
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Action(tr(R.string.play), primary = true, modifier = Modifier.focusRequester(firstFocus), onClick = { activity.launchGame(g, variant) })
                    Action(tr(R.string.cover_manage), onClick = { activity.editGameCover(g.id) })
                    Action(if (g.id in favorites) tr(R.string.favorite_on) else tr(R.string.favorite_off), onClick = { toggle(g) })
                    if (g.files.size > 1) Action(tr(R.string.other_version), onClick = { variant = (variant + 1) % g.files.size })
                }
                Spacer(Modifier.height(22.dp))
                ControlHints(R.string.control_select, R.string.back)
            }
            LaunchedEffect(g.id) { firstFocus.requestFocus() }
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 36.dp, end = 36.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.gostex_logo), contentDescription = "GOSTEX", modifier = Modifier.width(100.dp).height(34.dp))
                    Spacer(Modifier.width(24.dp))
                    visibleTabs.forEach { label ->
                        Action(tabTitle(label), compact = true, active = tab == label, onClick = { tab = label; platform = "all" })
                        Spacer(Modifier.width(6.dp))
                    }
                    Row(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        HeaderClock(clockOptions, clockInstant)
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
                        onEmptySettings = { settingsInitialSection = "homeOptions"; settingsReturnFocus = null; settingsOpen = true })
                } else if (tab == "apps") {
                    AppsScreen(apps, ready = loadedApps != null, favorites = favoriteRefs,
                        selectedPackage = selectedAppPackage, onSelect = { selectedAppPackage = it },
                        scroll = appsScroll, selectedFocus = firstFocus,
                        inputEnabled = !settingsOpen && !mediaOpen && tileMenu == null && membershipItem == null && favoriteOrderItem == null,
                        onOpen = activity::openApp, onMenu = ::appMenu, modifier = Modifier.weight(1f))
                } else {
                    Row(Modifier.padding(start = 36.dp, end = 36.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tabTitle(tab).uppercase(), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = White)
                        Spacer(Modifier.width(16.dp)); Text(gameCount(filtered.size), color = Muted, fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        if (searchQuery.isNotBlank()) {
                            Text(searchQuery, Modifier.widthIn(max = 180.dp).padding(end = 12.dp), color = Lime,
                                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            CompactIconButton(CompactIcon.Clear, tr(R.string.clear_search), onClick = { applySearch("") })
                            Spacer(Modifier.width(6.dp))
                        }
                        CompactIconButton(CompactIcon.Search, tr(R.string.search_games), active = searchQuery.isNotBlank(), modifier = Modifier.focusRequester(searchFocus),
                            onClick = { searchReturnTarget = ""; searchOpen = true })
                        Spacer(Modifier.width(6.dp))
                        CompactIconButton(CompactIcon.Add, tr(if (activity.scanning) R.string.scanning else R.string.add_games), enabled = !activity.scanning,
                            onClick = { activity.chooseFolder(platform) })
                        Spacer(Modifier.width(6.dp))
                        CompactIconButton(CompactIcon.Folder, tr(R.string.folders), onClick = { activity.manageFolders() })
                    }
                    if (games.isNotEmpty() || searchQuery.isNotBlank()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(3.dp)) {
                                item(key = "all") { Action(tr(R.string.all), compact = true, active = platform == "all", onClick = { platform = "all" }) }
                                items(PlatformRegistry.all, key = { it.id }) { p ->
                                    Action(tr(p.titleRes), compact = true, active = platform == p.id, onClick = { platform = p.id })
                                }
                            }
                        }
                        if (tab == "library") {
                            val selectedPlatform = PlatformRegistry.resolve(platform, libraryOptions.ndsEmulator)
                            val importHint = if (selectedPlatform == null) tr(R.string.platform_import_hint) else {
                                val formats = selectedPlatform.extensions.sorted().joinToString(" · ") { it.uppercase(java.util.Locale.ROOT) }
                                tr(R.string.platform_formats, selectedPlatform.emulator, formats) +
                                    (selectedPlatform.hintRes?.let { "\n" + tr(it) } ?: "")
                            }
                            Text(importHint, Modifier.padding(start = 36.dp, end = 36.dp, bottom = 6.dp), color = Muted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (filtered.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (searchQuery.isNotBlank()) Text(tr(R.string.search_empty), Modifier.padding(horizontal = 36.dp), color = Muted, fontSize = 18.sp)
                        else Column(Modifier.widthIn(max = 660.dp).padding(horizontal = 36.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(tr(R.string.library_empty_title), color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text(tr(R.string.library_import_steps, tr(R.string.add_games)), color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
                            Action(if (activity.scanning) tr(R.string.scanning) else tr(R.string.add_games), modifier = Modifier.focusRequester(addGamesFocus),
                                onClick = { if (!activity.scanning) activity.chooseFolder(if (games.isEmpty()) "all" else platform) })
                            Text(tr(R.string.library_optional_hint), color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                    } else LazyVerticalGrid(columns = GridCells.Fixed(LocalTileSize.current.columns), state = libraryScroll, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(start = TileLayout.screenInset + TileLayout.focusInset, end = TileLayout.screenInset + TileLayout.focusInset, top = 8.dp, bottom = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(TileLayout.gap), verticalArrangement = Arrangement.spacedBy(TileLayout.gap)) {
                        items(filtered, key = { it.id }) { g ->
                            val cardFocus = remember(g.id) { FocusRequester() }
                            Poster(g, Modifier.fillMaxWidth()
                                .then(if (g.id == chosen?.id || (chosen !in filtered && g == filtered.first())) Modifier.focusRequester(firstFocus) else Modifier)
                                .focusRequester(cardFocus), g.id in favorites, onFocus = { selectGame(g) },
                                onClick = { openGame(g, cardFocus) }, onLongClick = { gameMenu(g, cardFocus) })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 8.dp)) {
                    ControlHints(R.string.control_select, R.string.back, showHomeNavigation = tab == "home", showHoldMenu = true)
                    Spacer(Modifier.weight(1f)); Text("GOSTEX / SHIELD", color = Lime, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                }
            }
            LaunchedEffect(tab, platform, homeOptions.order, searchQuery, searchOpen, filtered.isEmpty()) {
                if (!settingsOpen && !mediaOpen && !searchOpen && tileMenu == null && membershipItem == null && favoriteOrderItem == null && tab == "library") {
                    if (searchReturnTarget == "button" || filtered.isEmpty()) {
                        withFrameNanos { }
                        runCatching { if (filtered.isEmpty() && searchQuery.isBlank()) addGamesFocus.requestFocus() else searchFocus.requestFocus() }
                    } else if (filtered.isNotEmpty()) {
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
        val itemKey = when (target) {
            is TileMenuTarget.GameItem -> homeGameKey(target.game.id)
            is TileMenuTarget.AppItem -> homeAppKey(target.app.packageName)
        }
        val currentSection = sectionOptions.sections.firstOrNull {
            tab == "home" && it.id == tileReturnAnchor?.shelf && it.kind == HomeSectionKind.CUSTOM
        }
        TileContextMenu(target, isFavorite = itemKey in favoriteRefs,
            currentSectionTitle = currentSection?.let { sectionTitle(it) },
            onPlay = { dismissTileMenu(); menuGame?.let { activity.launchGame(it, 0) } },
            onDetails = { tileMenu = null; detail = menuGame; variant = 0 },
            onCover = { dismissTileMenu(); menuGame?.let { activity.editGameCover(it.id) } },
            onToggleFavorite = { SectionPreferences.toggleFavorite(activity, itemKey); dismissTileMenu() },
            onOpenApp = { dismissTileMenu(); menuApp?.let(activity::openApp) },
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
        FavoriteOrderDialog(itemKey, apps, games, onDismiss = { favoriteOrderItem = null; tileRestore++ })
    }
    if (settingsOpen) SettingsDrawer(activity, apps, games, mediaPlayback, settingsInitialSection, onClose = { settingsOpen = false })
    if (mediaOpen) MediaPlayerPopover(activity, mediaPlayback, onDismiss = { mediaOpen = false })
    activity.coverEditorId?.let { id -> games.firstOrNull { it.id == id }?.let { game ->
        GameCoverEditor(activity, game, onClose = { activity.coverEditorId = null })
    } }
    if (searchOpen) GameSearchDrawer(searchQuery, searchIndex,
        scopeLabel = listOf(tabTitle(tab), platformTitle(platform).takeIf { platform != "all" }).filterNotNull().joinToString(" / "),
        onApply = ::applySearch, onClose = { searchReturnTarget = "button"; searchOpen = false })

}

@Composable private fun GameSearchDrawer(
    query: String, index: GameSearchIndex, scopeLabel: String, onApply: (String) -> Unit, onClose: () -> Unit
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Keep editing reads inside the dialog composition. Updating the outer Dialog on every
        // selection/composition change resets window parameters and can restart older TV IMEs.
        val state = rememberTextFieldState(initialText = query)
        val inputFocus = remember { FocusRequester() }
        val resultsFocus = remember { FocusRequester() }
        var inputFocused by remember { mutableStateOf(false) }
        val draft = state.text.toString()
        val resultCount = remember(index, draft) { index.filter(draft).size }
        val keyboard = LocalSoftwareKeyboardController.current
        val submit = { keyboard?.hide(); onApply(draft) }
        val close = { keyboard?.hide(); onClose() }
        val dialogView = LocalView.current
        val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
        LaunchedEffect(dialogWindow) { dialogWindow?.setDimAmount(0f) }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f)).imePadding()) {
            Column(Modifier.align(Alignment.CenterEnd).width(440.dp).fillMaxHeight().background(Ink)
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
            modifier = Modifier.fillMaxWidth().aspectRatio(TileLayout.aspectRatio).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Panel, focusedContainerColor = Panel),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
            border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(1.dp, Color(0xFF424244))), focusedBorder = Border(BorderStroke(2.dp, Lime)))
        ) {
            Box(Modifier.fillMaxSize()) {
                CoverArtwork(game.cover.ifEmpty { game.scene }, Modifier.fillMaxSize(), game.id)
                if (game.cover.isEmpty() && game.scene.isEmpty() && LocalManagedCovers.current[game.id]?.let { it.manual.isNotEmpty() || it.original.isNotEmpty() || it.wide.isNotEmpty() } != true) {
                    Box(Modifier.fillMaxSize().background(Panel), contentAlignment = Alignment.Center) {
                        Text(game.title, Modifier.padding(12.dp), color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
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

private fun settingsParent(section: String): String = when {
    section == "clock" || section == "language" -> "localeGroup"
    section.startsWith("clock/") -> clockParent(section)
    section in setOf("appearance", "art", "grafana") -> "appearanceGroup"
    section == "covers" || section == "nowPlaying" -> "mediaGroup"
    else -> "root"
}

private fun settingsTitle(section: String): Int = when {
    section.startsWith("clock") -> clockSectionTitleRes(section)
    else -> when (section) {
        "covers" -> R.string.covers_title
        "nowPlaying" -> R.string.media_player
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
        savedSection.startsWith("clock/") || savedSection in setOf("root", "appearanceGroup", "mediaGroup", "controller", "localeGroup", "systemGroup", "language", "art", "grafana", "homeOptions", "appearance", "clock", "covers", "nowPlaying", "updates") -> savedSection
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
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f * (1f - slide.value)))) {
            SettingsPanel(activity, apps, games, mediaPlayback, close, back, section, returnRow, navigate, Modifier.align(Alignment.CenterEnd).width(380.dp)
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
    var remoteAuto by remember { mutableStateOf(prefs.readBooleanOrDefault("remoteAutostart", true)) }
    val languageCode = LocalConfiguration.current.locales[0].language
    val language = when (languageCode) {
        "ru" -> "Русский"
        "es" -> "Español"
        else -> "English"
    }
    val initialFocus = remember { FocusRequester() }
    val rows = when (section) {
        "root" -> listOf("homeOptions", "appearanceGroup", "mediaGroup", "controller", "localeGroup", "systemGroup", "updates")
        "appearanceGroup" -> listOf("appearance", "art", "grafana")
        "mediaGroup" -> listOf("addGames", "folders", "covers", "nowPlaying", "ndsEmulator", "libraryTab", "appsTab")
        "localeGroup" -> listOf("language", "clock")
        "systemGroup" -> listOf("networkStatus", "network", "shield", "remote")
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
                SettingRow(tr(R.string.add_games), modifier = rowFocus("addGames"), onClick = { activity.chooseFolder("all") })
                SettingRow(tr(R.string.folders), modifier = rowFocus("folders"), onClick = { activity.manageFolders() })
                SettingRow(tr(R.string.covers_title), tr(R.string.covers_summary), rowFocus("covers"), onClick = { onSection("covers") })
                SettingRow(tr(R.string.media_player), tr(R.string.media_settings_summary), rowFocus("nowPlaying"), onClick = { onSection("nowPlaying") })
                NintendoDsEmulatorSetting(rowFocus("ndsEmulator"))
                LibraryVisibilitySetting(rowFocus("libraryTab"))
                AppsVisibilitySetting(rowFocus("appsTab"))
            } else if (section == "localeGroup") {
                SettingRow(tr(R.string.language), language, rowFocus("language"), onClick = { onSection("language") })
                SettingRow(tr(R.string.clock_title), modifier = rowFocus("clock"), onClick = { onSection("clock") })
            } else if (section == "systemGroup") {
                val networkVisible = rememberNetworkVisible()
                SettingRow(tr(R.string.network_status_visibility), tr(if (networkVisible) R.string.enabled else R.string.disabled), rowFocus("networkStatus"), onClick = { NetworkPreferences.visible(activity, !networkVisible) })
                SettingRow(tr(R.string.network_settings), modifier = rowFocus("network"), onClick = { activity.openNetworkSettings() })
                SettingRow(tr(R.string.shield_settings), modifier = rowFocus("shield"), onClick = { activity.openSettings() })
                SettingRow(tr(R.string.remote_autostart), tr(if (remoteAuto) R.string.enabled else R.string.disabled), rowFocus("remote"), onClick = {
                    remoteAuto = !remoteAuto
                    prefs.edit().putBoolean("remoteAutostart", remoteAuto).apply()
                    if (remoteAuto) RemoteStartup.start(activity)
                })
            } else if (section == "updates") {
                UpdateSettings(activity, initialFocus)
            } else if (section == "nowPlaying") {
                MediaPlayerSettings(activity, mediaPlayback, initialFocus)
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
}

@Composable internal fun SettingRow(title: String, value: String = "", modifier: Modifier = Modifier, isSelected: Boolean = false, trailing: String = "›", onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth().semantics { selected = isSelected }.onFocusChanged { focused = it.isFocused },
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
