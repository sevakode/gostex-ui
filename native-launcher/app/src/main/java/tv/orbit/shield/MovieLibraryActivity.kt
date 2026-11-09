package tv.orbit.shield

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Film metadata and user-owned sources; streaming is delegated to an installed player. */
class MovieLibraryActivity : ComponentActivity() {
    private val videoPlayer = VideoPlayerChooser(this)
    private var page by mutableStateOf("saved")
    private var saved by mutableStateOf<List<SavedMovie>>(emptyList())
    private var results by mutableStateOf<List<MovieCatalogItem>>(emptyList())
    private var selected by mutableStateOf<MovieCatalogItem?>(null)
    private var sources by mutableStateOf<List<MovieStreamOption>>(emptyList())
    private var addons by mutableStateOf<List<ConfiguredMovieAddon>>(emptyList())
    private var error by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var progressMessage by mutableStateOf("")
    private var searched by mutableStateOf(false)
    private var searchedSources by mutableStateOf(false)
    private var sourceEpisode by mutableStateOf<MovieEpisode?>(null)
    private var missingTorrentHash by mutableStateOf<String?>(null)
    private var readFailed = false
    private var requestGeneration = 0L
    private var sourceLookupJob: Job? = null
    private var bufferDialog: AlertDialog? = null
    private var lastPreparedMovie by mutableStateOf<String?>(null)
    private var lastPreparedHash by mutableStateOf<String?>(null)
    private var sourceRemovalVersion by mutableIntStateOf(0)
    private var pendingFileId: String? = null
    private var pendingRestoreHash: String? = null
    private var pendingTorrentHash: String? = null
    private var pendingTorrentTitle: String = ""

    private val torrentPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = pendingFileId.also { pendingFileId = null }
        val restoreHash = pendingRestoreHash.also { pendingRestoreHash = null }
        val uri = result.data?.data
        if (id != null && uri != null) {
            if (restoreHash != null) {
                restoreTorrentFile(id, restoreHash, uri)
                return@registerForActivityResult
            }
            val flags = result.data?.flags ?: 0
            val accepted = runCatching {
                contentResolver.takePersistableUriPermission(uri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val movie = saved.first { it.id == id }
                val source = MovieSource(uri.lastPathSegment?.substringAfterLast('/')?.takeLast(120) ?: "Torrent", uri.toString())
                check(MovieLibraryStore.save(this, movie.copy(sources = movie.sources.filterNot { it.uri == source.uri } + source)))
                refresh()
            }.isSuccess
            if (!accepted) toast(R.string.movies_file_error)
        }
    }

    override fun attachBaseContext(base: Context) { super.attachBaseContext(Language.context(base)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!PortableRestoreStartup.ready || PortableMaintenance.running) { finish(); return }
        runCatching { TorrDownloadStore.init(this) }
        refresh()
        pendingFileId = savedInstanceState?.getString(STATE_PENDING_FILE_ID)
        pendingRestoreHash = savedInstanceState?.getString(STATE_PENDING_RESTORE_HASH)?.takeIf { it.matches(Regex("(?i)[0-9a-f]{40}")) }
        lastPreparedMovie = savedInstanceState?.getString("buffer.movie")
        lastPreparedHash = savedInstanceState?.getString("buffer.hash")?.takeIf { it.matches(Regex("(?i)[0-9a-f]{40}")) }
        pendingTorrentHash = (savedInstanceState?.getString(EXTRA_TORRENT_HASH) ?: intent.getStringExtra(EXTRA_TORRENT_HASH))
            ?.takeIf { it.matches(Regex("(?i)[0-9a-f]{40}")) }?.lowercase(java.util.Locale.ROOT)
        pendingTorrentTitle = (savedInstanceState?.getString(EXTRA_TORRENT_TITLE) ?: intent.getStringExtra(EXTRA_TORRENT_TITLE)).orEmpty().take(256)
        intent.getStringExtra(EXTRA_MOVIE_ID)?.let { id ->
            saved.firstOrNull { it.id == id }?.let { movie ->
                openMovie(MovieCatalogItem(movie.id, movie.title, movie.year, movie.poster, durationSeconds = movie.durationSeconds, mediaType = movie.mediaType))
            }
        }
        setContent { MovieScreen(); videoPlayer.Render() }
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_PLAY_SINGLE, false)) {
            selected?.id?.let { id ->
                val sources = saved.firstOrNull { it.id == id }?.sources.orEmpty()
                val preferred = preferredAceSource(id)
                val aceSource = sources.singleOrNull { it.uri == preferred && canDefaultToAce(it) }
                if (aceSource != null) openTorrentInAce(aceSource) else sources.singleOrNull()?.let { play(it) }
            }
        }
        if (savedInstanceState == null && pendingTorrentHash != null) {
            TorrentArtworkPolicy.query(pendingTorrentTitle).takeIf { it.length >= 3 }?.let(::searchFor)
        }
    }

    override fun onStop() {
        bufferDialog?.dismiss()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PENDING_FILE_ID, pendingFileId)
        outState.putString(STATE_PENDING_RESTORE_HASH, pendingRestoreHash)
        outState.putString("buffer.movie", lastPreparedMovie)
        outState.putString("buffer.hash", lastPreparedHash)
        outState.putString(EXTRA_TORRENT_HASH, pendingTorrentHash)
        outState.putString(EXTRA_TORRENT_TITLE, pendingTorrentTitle)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val STATE_PENDING_FILE_ID = "pending_file_id"
        private const val STATE_PENDING_RESTORE_HASH = "pending_restore_hash"
        internal const val EXTRA_MOVIE_ID = "movie_id"
        internal const val EXTRA_PLAY_SINGLE = "play_single"
        internal const val EXTRA_TORRENT_HASH = "torrent_hash"
        internal const val EXTRA_TORRENT_TITLE = "torrent_title"
    }

    private fun toast(resource: Int) = Toast.makeText(this, resource, Toast.LENGTH_LONG).show()
    private fun refresh() {
        val library = runCatching { MovieLibraryStore.read(this) }
        val available = runCatching { MovieAddonPreferences.read(this) }
        readFailed = library.isFailure || available.isFailure
        if (library.isSuccess) saved = library.getOrThrow()
        if (available.isSuccess) addons = available.getOrThrow()
        if (readFailed) error = getString(R.string.movies_corrupt)
    }

    private fun input(title: Int, hint: Int, onSubmit: (String) -> Unit) {
        val field = EditText(this).apply { setSingleLine(true); setHint(hint) }
        AlertDialog.Builder(this).setTitle(title).setView(field)
            .setNegativeButton(R.string.movies_cancel, null)
            .setPositiveButton(R.string.movies_add) { _, _ -> onSubmit(field.text.toString()) }.show()
    }

    private fun search() = input(R.string.movies_search, R.string.movies_search, ::searchFor)

    private fun searchFor(query: String) {
        if (query.isBlank()) return
        stopSourceLookup()
        val generation = ++requestGeneration
        busy = true; progressMessage = ""; error = ""; searched = true; page = "results"
        lifecycleScope.launch {
            val fetched = withContext(Dispatchers.IO) { runCatching {
                val films = runCatching { MovieCatalogClient.search(query, type = "movie") }
                val series = runCatching { MovieCatalogClient.search(query, type = "series") }
                if (films.isFailure && series.isFailure) throw java.io.IOException("Catalog unavailable")
                val term = TorrentArtworkPolicy.normalized(query)
                (films.getOrDefault(emptyList()) + series.getOrDefault(emptyList())).distinctBy { it.mediaType to it.id }
                    .sortedBy { item ->
                        val title = TorrentArtworkPolicy.normalized(item.title)
                        when { title == term -> 0; title.startsWith(term) -> 1; term in title -> 2; else -> 3 }
                    }
            } }
            if (generation != requestGeneration || isFinishing) return@launch
            busy = false
            if (fetched.isSuccess) results = fetched.getOrThrow()
            else { results = emptyList(); error = getString(R.string.movies_source_failed) }
        }
    }

    private fun openMovie(item: MovieCatalogItem) {
        stopSourceLookup()
        val generation = ++requestGeneration
        page = "detail"; selected = item; sources = emptyList(); error = ""; searchedSources = false; sourceEpisode = null; missingTorrentHash = null
        if (!item.id.matches(Regex("tt[0-9]{1,16}"))) return
        lifecycleScope.launch {
            val details = withContext(Dispatchers.IO) { runCatching { MovieCatalogClient.details(item.id, type = item.mediaType) } }
            if (generation == requestGeneration && selected?.id == item.id && details.isSuccess) selected = details.getOrThrow()
        }
    }

    private fun saveSelected(): SavedMovie? {
        if (readFailed) { toast(R.string.movies_corrupt); return null }
        val movie = selected ?: return null
        val old = saved.firstOrNull { it.id == movie.id }
        val updated = SavedMovie(movie.id, movie.title, movie.year, movie.poster ?: old?.poster,
            old?.sources.orEmpty(), movie.durationSeconds ?: old?.durationSeconds, movie.mediaType)
        return if (runCatching { MovieLibraryStore.save(this, updated) }.getOrDefault(false)) {
            refresh(); updated
        } else { toast(R.string.movies_source_failed); null }
    }

    private fun attachPendingTorrent() {
        val hash = pendingTorrentHash ?: return
        val item = selected ?: return
        if (readFailed) { toast(R.string.movies_corrupt); return }
        val previous = saved.firstOrNull { it.id == item.id }
        val uri = "torrserver://$hash"
        val source = MovieSource(pendingTorrentTitle.ifBlank { getString(R.string.movies_torrent_source) }.take(256), uri)
        val updated = SavedMovie(item.id, item.title, item.year, item.poster ?: previous?.poster,
            previous?.sources.orEmpty().filterNot { it.uri.equals(uri, ignoreCase = true) } + source,
            item.durationSeconds ?: previous?.durationSeconds, item.mediaType)
        if (runCatching { MovieLibraryStore.save(this, updated) }.getOrDefault(false)) {
            pendingTorrentHash = null
            toast(R.string.movies_torrent_attached)
            finish()
        } else toast(R.string.movies_source_failed)
    }

    private fun localMediaEditor(initialTitle: String, initialType: String, editing: Boolean, onSave: (String, String) -> Boolean) {
        val title = EditText(this).apply {
            setSingleLine(true)
            setHint(R.string.movies_local_title)
            setText(initialTitle)
            filters = arrayOf(android.text.InputFilter.LengthFilter(256))
        }
        val movieId = android.view.View.generateViewId()
        val seriesId = android.view.View.generateViewId()
        val types = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.HORIZONTAL
            addView(android.widget.RadioButton(context).apply { id = movieId; setText(R.string.movies_type_movie) })
            addView(android.widget.RadioButton(context).apply { id = seriesId; setText(R.string.movies_type_series) })
            check(if (initialType == "series") seriesId else movieId)
        }
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(title); addView(types)
        }
        val dialog = AlertDialog.Builder(this).setTitle(if (editing) R.string.movies_local_edit else R.string.movies_local_create).setView(content)
            .setNegativeButton(R.string.movies_cancel, null).setPositiveButton(if (editing) R.string.movies_local_save else R.string.movies_add, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = title.text.toString().trim()
                if (readFailed) { toast(R.string.movies_corrupt); return@setOnClickListener }
                if (name.isBlank()) { toast(R.string.movies_local_title_required); return@setOnClickListener }
                val mediaType = if (types.checkedRadioButtonId == seriesId) "series" else "movie"
                if (onSave(name, mediaType)) dialog.dismiss() else toast(R.string.movies_source_failed)
            }
        }
        dialog.show()
    }

    private fun createLocalTorrentCard() {
        if (pendingTorrentHash == null || readFailed) return
        localMediaEditor(TorrentArtworkPolicy.query(pendingTorrentTitle), "movie", editing = false) { name, mediaType ->
            val hash = pendingTorrentHash ?: return@localMediaEditor false
            val label = TorrentArtworkPolicy.query(pendingTorrentTitle).ifBlank { getString(R.string.movies_torrent_source) }
            val movie = SavedMovie("local:${java.util.UUID.randomUUID()}", name, "", null,
                listOf(MovieSource(label.take(256), "torrserver://$hash")), mediaType = mediaType)
            val saved = runCatching { MovieLibraryStore.save(this, movie) }.getOrDefault(false)
            if (saved) { pendingTorrentHash = null; toast(R.string.movies_torrent_attached); finish() }
            saved
        }
    }

    private fun editLocalCard(movie: MovieCatalogItem) {
        if (readFailed || !movie.id.startsWith("local:")) return
        localMediaEditor(movie.title, movie.mediaType, editing = true) { name, mediaType ->
            val saved = runCatching { MovieLibraryStore.editLocal(this, movie.id, name, mediaType) }.getOrDefault(false)
            if (saved) { selected = movie.copy(title = name, mediaType = mediaType); refresh() }
            saved
        }
    }

    private fun addLink() = input(R.string.movies_add_link, R.string.movies_link_hint) { value ->
        val source = value.trim()
        if (!MovieLibraryStore.validSource(source)) { toast(R.string.movies_source_failed); return@input }
        val movie = saveSelected()
        if (movie == null || !runCatching {
            MovieLibraryStore.save(this, movie.copy(sources = movie.sources.filterNot { it.uri == source } + MovieSource(getString(R.string.movies_add_link), source)))
        }.getOrDefault(false)) toast(R.string.movies_source_failed) else refresh()
    }

    private fun addFile(restoreHash: String? = null) {
        val movie = saveSelected() ?: return
        pendingFileId = movie.id
        pendingRestoreHash = restoreHash
        runCatching { torrentPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/x-bittorrent", "application/octet-stream"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }) }.onFailure { pendingFileId = null; pendingRestoreHash = null; toast(R.string.movies_file_error) }
    }

    private fun restoreTorrentFile(movieId: String, hash: String, uri: Uri) {
        if (busy) return
        val generation = ++requestGeneration
        busy = true; progressMessage = getString(R.string.movies_restore_working); error = ""
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { TorrServerCatalog.restoreFromFile(it, hash) }
                        ?: throw java.io.IOException("Cannot open torrent")
                }
                if (generation != requestGeneration || selected?.id != movieId || isFinishing || isDestroyed) return@launch
                missingTorrentHash = null
                sourceRemovalVersion++
                toast(R.string.movies_restore_file_done)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == requestGeneration && selected?.id == movieId && !isFinishing && !isDestroyed)
                    error = getString(if (failure is TorrentIdentityMismatch) R.string.movies_restore_file_mismatch else R.string.movies_torrent_error)
            } finally { if (generation == requestGeneration) busy = false }
        }
    }

    private fun confirmRemoveSource(movieId: String, source: MovieSource) {
        AlertDialog.Builder(this)
            .setTitle(R.string.movies_remove_source)
            .setMessage(R.string.movies_remove_source_confirm)
            .setNegativeButton(R.string.movies_cancel, null)
            .setPositiveButton(R.string.movies_remove_source) { _, _ ->
                if (!readFailed && runCatching { MovieLibraryStore.removeSource(this, movieId, source) }.getOrDefault(false)) {
                    if (preferredAceSource(movieId) == source.uri)
                        getSharedPreferences("orbit", MODE_PRIVATE).edit().remove(preferredAceKey(movieId)).apply()
                    refresh()
                    sourceRemovalVersion++
                } else toast(R.string.movies_source_failed)
            }.show()
    }

    private fun confirmRemoveAddon(entry: ConfiguredMovieAddon) {
        AlertDialog.Builder(this)
            .setTitle(R.string.movies_remove_addon)
            .setMessage(getString(R.string.movies_remove_addon_confirm, entry.addon.name))
            .setNegativeButton(R.string.movies_cancel, null)
            .setPositiveButton(R.string.movies_remove_addon) { _, _ ->
                if (!readFailed && runCatching { MovieAddonPreferences.remove(this, entry.addon.manifestUrl) }.getOrDefault(false)) {
                    refresh()
                    sourceRemovalVersion++
                } else toast(R.string.movies_source_failed)
            }.show()
    }

    private fun addAddon() = input(R.string.movies_add_addon, R.string.movies_addon_hint) { value ->
        stopSourceLookup()
        val generation = ++requestGeneration
        busy = true; progressMessage = ""; error = ""
        lifecycleScope.launch {
            val inspected = withContext(Dispatchers.IO) { runCatching { MovieAddonClient.inspect(value) } }
            if (generation != requestGeneration || isFinishing) return@launch
            busy = false
            if (inspected.isSuccess && !readFailed && runCatching {
                    MovieAddonPreferences.save(this@MovieLibraryActivity, ConfiguredMovieAddon(inspected.getOrThrow(), true))
                }.getOrDefault(false)) refresh() else error = getString(R.string.movies_source_failed)
        }
    }

    private fun chooseEpisode() {
        val movie = selected ?: return
        stopSourceLookup()
        if (addons.none { it.enabled }) { error = getString(R.string.movies_no_active_addons); return }
        val generation = ++requestGeneration
        busy = true; progressMessage = getString(R.string.movies_loading_episodes); error = ""; sources = emptyList(); searchedSources = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { MovieCatalogClient.episodes(movie.id) } }
            if (generation != requestGeneration || selected?.id != movie.id || isFinishing || isDestroyed) return@launch
            busy = false
            val episodes = result.getOrElse { error = getString(R.string.movies_source_failed); return@launch }
            if (episodes.isEmpty()) { error = getString(R.string.movies_no_episodes); return@launch }
            val seasons = episodes.map { it.season }.distinct()
            AlertDialog.Builder(this@MovieLibraryActivity).setTitle(R.string.movies_choose_season)
                .setItems(seasons.map { getString(R.string.movies_season_number, it) }.toTypedArray()) { _, index ->
                    val seasonEpisodes = episodes.filter { it.season == seasons[index] }
                    AlertDialog.Builder(this@MovieLibraryActivity).setTitle(R.string.movies_choose_episode)
                        .setItems(seasonEpisodes.map { episodeLabel(it) }.toTypedArray()) { _, episodeIndex ->
                            findSources(seasonEpisodes[episodeIndex])
                        }.setNegativeButton(R.string.movies_cancel, null).show()
                }.setNegativeButton(R.string.movies_cancel, null).show()
        }
    }

    private fun episodeLabel(episode: MovieEpisode): String =
        listOf(getString(R.string.movies_episode_number, episode.episode), episode.title).filter { it.isNotBlank() }.joinToString(" · ")

    private fun stopSourceLookup() {
        sourceLookupJob?.cancel()
        sourceLookupJob = null
        requestGeneration++
        busy = false
    }

    private fun findSources(episode: MovieEpisode? = null) {
        val movie = selected ?: return
        stopSourceLookup()
        missingTorrentHash = null
        val active = addons.filter { it.enabled }
        if (active.isEmpty()) { error = getString(R.string.movies_no_active_addons); return }
        val generation = ++requestGeneration
        busy = true; progressMessage = ""; error = ""; sources = emptyList(); searchedSources = false; sourceEpisode = episode
        sourceLookupJob = lifecycleScope.launch {
            val fetched = withContext(Dispatchers.IO) {
                lookupAddons(active, onResult = { result ->
                    withContext(Dispatchers.Main.immediate) {
                        if (generation == requestGeneration && selected?.id == movie.id && !isFinishing && !isDestroyed) {
                            sources = sources + result.getOrDefault(emptyList())
                        }
                    }
                }) { entry -> MovieAddonClient.streams(entry.addon, episode?.id ?: movie.id, movie.mediaType) }
            }
            if (generation != requestGeneration || selected?.id != movie.id || isFinishing) return@launch
            busy = false; searchedSources = true
            if (fetched.all { it.isFailure }) error = getString(R.string.movies_source_failed)
            else {
                if (fetched.any { it.isFailure }) error = getString(R.string.movies_partial_sources)
            }
        }
    }

    private fun play(source: MovieSource, episode: MovieEpisode? = null, choosePlayer: Boolean = false) {
        if (busy && sourceLookupJob?.isActive != true) return
        if (sourceLookupJob?.isActive == true) stopSourceLookup()
        missingTorrentHash = null
        if (!MovieLibraryStore.validSource(source.uri)) { toast(R.string.movies_source_failed); return }
        val uri = Uri.parse(source.uri)
        val isTorrent = uri.scheme.equals("torrserver", true) || uri.scheme.equals("magnet", true) || uri.scheme.equals("content", true) ||
            ((uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && uri.path?.endsWith(".torrent", ignoreCase = true) == true)
        if (isTorrent) { prepareTorrent(uri, episode, choosePlayer, source.filenameHint); return }
        val intent = if (uri.scheme.equals("acestream", true)) Intent(Intent.ACTION_VIEW, uri)
            else Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*")
        startMovieIntent(intent, episode, choosePlayer)
    }

    private fun startMovieIntent(intent: Intent, episode: MovieEpisode? = null, choosePlayer: Boolean = false, onStarted: () -> Unit = {}, onReturned: () -> Unit = {}, onCompleted: (() -> Unit)? = null, playlist: VlcSeriesPlaylist? = null, playlistUnavailable: Boolean = false, resumeIdentity: String? = null, fromStart: Boolean = false, askResume: Boolean = true) {
        val id = selected?.id ?: return
        videoPlayer.open(intent, force = choosePlayer,
            onStarted = { MoviePlaybackReceipt.mark(this, id, episode?.id); onStarted() },
            onError = { toast(R.string.movies_player_missing) }, onReturned = onReturned, onCompleted = onCompleted, playlist = playlist, playlistUnavailable = playlistUnavailable, resumeIdentity = resumeIdentity, fromStart = fromStart, askResume = askResume)
    }

    private fun continueSavedEpisode(movieId: String, hash: String, fileId: Int) {
        if (selected?.id != movieId || !TorrPlaybackPreferences.autoNext(this)) return
        prepareTorrent(Uri.parse("torrserver://$hash"), null, false, null, afterFileId = fileId)
    }

    /** Open the retained document itself; Ace asks the user which file to play. */
    private fun preferredAceKey(movieId: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(movieId.toByteArray(Charsets.UTF_8))
        return "movies.ace_source." + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun preferredAceSource(movieId: String): String? =
        getSharedPreferences("orbit", MODE_PRIVATE).getString(preferredAceKey(movieId), null)

    private fun canDefaultToAce(source: MovieSource): Boolean =
        Uri.parse(source.uri).scheme.equals("content", ignoreCase = true) &&
            source.fileIndex == null && source.filenameHint == null

    private fun openTorrentInAce(source: MovieSource) {
        val uri = Uri.parse(source.uri)
        if (!uri.scheme.equals("content", ignoreCase = true)) return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/x-bittorrent")
            clipData = android.content.ClipData.newRawUri("torrent", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("org.acestream.EXTRA_SELECTED_PLAYER", "{\"type\":3}")
        }
        // Ace Stream also shipped its Android player under the older media package.
        for (packageName in listOf("org.acestream.node", "org.acestream.media")) {
            intent.component = android.content.ComponentName(packageName, "org.acestream.engine.ContentStartActivity")
            try {
                startActivity(intent)
                selected?.id?.let { MoviePlaybackReceipt.mark(this, it) }
                return
            } catch (_: android.content.ActivityNotFoundException) {
                // Try the other official package before suggesting installation.
            } catch (_: RuntimeException) {
                toast(R.string.movies_player_missing)
                return
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.movies_ace_install_title)
            .setMessage(R.string.movies_ace_install_message)
            .setNegativeButton(R.string.movies_cancel, null)
            .setPositiveButton(R.string.movies_ace_install_button) { _, _ ->
                val market = Uri.parse("market://details").buildUpon().appendQueryParameter("id", "org.acestream.node").build()
                val web = Uri.parse("https://play.google.com/store/apps/details").buildUpon().appendQueryParameter("id", "org.acestream.node").build()
                if (!runCatching { startActivity(Intent(Intent.ACTION_VIEW, market)) }.isSuccess &&
                    !runCatching { startActivity(Intent(Intent.ACTION_VIEW, web)) }.isSuccess)
                    toast(R.string.movies_player_missing)
            }.show()
    }

    private fun prepareTorrent(uri: Uri, episode: MovieEpisode?, choosePlayer: Boolean, filenameHint: String?, afterFileId: Int? = null) {
        if (busy) return
        val generation = ++requestGeneration
        busy = true; progressMessage = getString(R.string.movies_torrent_preparing); error = ""
        lifecycleScope.launch {
            try {
                val entry = withContext(Dispatchers.IO) {
                    val added = when {
                        uri.scheme.equals("torrserver", true) -> TorrServerCatalog.get(uri.host ?: throw java.io.IOException("Invalid torrent"))
                        uri.scheme.equals("magnet", true) -> TorrServerCatalog.addMagnet(uri.toString())
                        uri.scheme.equals("content", true) -> contentResolver.openInputStream(uri)?.use { TorrServerCatalog.upload(it) }
                            ?: throw java.io.IOException("Cannot open torrent")
                        else -> TorrServerCatalog.addTorrentUrl(uri.toString())
                    }
                    TorrServerCatalog.loadFiles(added.hash).also { require(it.hash.equals(added.hash, ignoreCase = true)) }
                }
                if (generation != requestGeneration || isFinishing || isDestroyed) return@launch
                val videos = entry.files.filter { TorrPlayback.isVideo(it.path) }
                if (entry.files.isEmpty()) { busy = false; toast(R.string.movies_metadata_unavailable); return@launch }
                if (videos.isEmpty()) { busy = false; toast(R.string.movies_no_video); return@launch }
                val paths = videos.map { it.path }
                val movieId = selected?.id
                val seriesId = selected?.takeIf { it.mediaType == "series" }?.id
                val canQueue = if (seriesId != null && TorrPlaybackPreferences.autoNext(this@MovieLibraryActivity) && orderedEpisodeFiles(paths)) {
                    try { withContext(Dispatchers.IO) { TorrServerCatalog.playlistMatchesVideos(entry, videos) } }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                } else false
                if (generation != requestGeneration || isFinishing || isDestroyed || selected?.id != movieId) return@launch
                busy = false; error = ""
                fun open(index: Int) {
                    var currentIndex = index
                    val prefetchJob = if (seriesId != null) lifecycleScope.launch {
                        try { TorrPrefetch.start(this@MovieLibraryActivity, seriesId, entry, videos, videos[index].id) {
                            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && selected?.id == movieId
                        } } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Playback remains available; the queue shows failed jobs. */ }
                    } else null
                    fun launchPlayer() {
                        lifecycleScope.launch {
                        val originalIndex = currentIndex
                        prefetchJob?.join()
                        if (isFinishing || isDestroyed || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) || selected?.id != movieId || currentIndex != originalIndex) return@launch
                        val file = videos[currentIndex]
                        val downloaded = TorrDownloadStore.jobs.value.firstOrNull { it.prefetchMovieId == seriesId && it.hash.equals(entry.hash, true) && it.index == file.id && it.state == "done" }
                        val offline = downloaded != null && withContext(Dispatchers.IO) { TorrPrefetch.playable(this@MovieLibraryActivity, downloaded) }
                        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(if (offline) Uri.parse(downloaded!!.document) else Uri.parse(TorrPlayback.url(entry.hash, file.id)), "video/*")
                            .addFlags(if (offline) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0)
                        val identified = episode?.takeIf { episodeFileMatchesHistory(paths, it.season, it.episode, currentIndex) }
                        // The server playlist was checked against this torrent's video IDs.
                        val playlist = if (!offline && seriesId != null && canQueue)
                            VlcSeriesPlaylist(entry.hash, file.id, file.path.substringAfterLast('/'), videos.map { it.id }.toSet(), videos.map { it.path.substringAfterLast('/') }.toSet()) { id ->
                                val playedIndex = videos.indexOfFirst { it.id == id }
                                if (playedIndex >= 0) {
                                    currentIndex = playedIndex
                                    TorrPlaybackPreferences.rememberFile(this@MovieLibraryActivity, seriesId, entry.hash, videos[playedIndex].path)
                                }
                            } else null
                        startMovieIntent(intent, identified, choosePlayer,
                            onStarted = {
                                if (seriesId != null) TorrPlaybackPreferences.rememberFile(this@MovieLibraryActivity, seriesId, entry.hash, file.path)
                            },
                            onReturned = {
                                if (!offline && movieId != null && selected?.id == movieId) {
                                    showTorrentBuffer(entry.hash, videos[currentIndex].id, startPreload = false, trackDownload = seriesId != null, downloadPreparation = prefetchJob, openPlayer = ::launchPlayer)
                                }
                            }, onCompleted = if (offline && seriesId != null) ({ continueSavedEpisode(seriesId, entry.hash, file.id) }) else null,
                            playlist = playlist, playlistUnavailable = !offline && seriesId != null && videos.size > 1 && playlist == null,
                            resumeIdentity = if (offline) TorrPlayback.url(entry.hash, file.id) else null, askResume = afterFileId == null)
                        }
                    }
                    val cached = TorrDownloadStore.jobs.value.firstOrNull { it.prefetchMovieId == seriesId && it.hash.equals(entry.hash, true) && it.index == videos[currentIndex].id && it.state == "done" }
                    if (cached == null) showTorrentBuffer(entry.hash, videos[currentIndex].id, trackDownload = seriesId != null, downloadPreparation = prefetchJob, openPlayer = ::launchPlayer)
                    else lifecycleScope.launch {
                        if (withContext(Dispatchers.IO) { TorrPrefetch.playable(this@MovieLibraryActivity, cached) }) launchPlayer()
                        else showTorrentBuffer(entry.hash, videos[currentIndex].id, trackDownload = seriesId != null, downloadPreparation = prefetchJob, openPlayer = ::launchPlayer)
                    }
                }
                if (afterFileId != null) {
                    if (!orderedEpisodeFiles(paths)) return@launch
                    val previous = videos.indexOfFirst { it.id == afterFileId }
                    if (previous >= 0 && previous + 1 < videos.size) open(previous + 1)
                } else if (videos.size == 1) open(0)
                else {
                    val lastFile = if (episode == null && filenameHint.isNullOrBlank() && seriesId != null)
                        TorrPlaybackPreferences.lastFileIndex(this@MovieLibraryActivity, seriesId, entry.hash, paths) else null
                    val suggested = suggestFilename(paths, filenameHint) ?: episode?.let { suggestEpisodeFile(paths, it.season, it.episode) } ?: lastFile
                    val labels = mediaFileLabels(videos).mapIndexed { index, label ->
                        val ready = TorrDownloadStore.jobs.value.any { it.prefetchMovieId == seriesId && it.hash.equals(entry.hash, true) && it.index == videos[index].id && it.state == "done" }
                        val decorated = if (ready) getString(R.string.prefetch_downloaded_label, label) else label
                        when {
                            index == lastFile -> getString(R.string.movies_last_played_file, decorated)
                            index == suggested -> getString(R.string.movies_suggested_file, decorated)
                            else -> decorated
                        }
                    }
                    val dialog = AlertDialog.Builder(this@MovieLibraryActivity).setTitle(R.string.movies_choose_video)
                        .setItems(labels.toTypedArray()) { _, index -> open(index) }
                        .setNegativeButton(R.string.movies_cancel, null).create()
                    dialog.show()
                    suggested?.let { dialog.listView?.apply { setSelection(it); requestFocus() } }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == requestGeneration && !isFinishing && !isDestroyed) {
                    missingTorrentHash = if (failure is TorrEntryMissing && uri.scheme.equals("torrserver", true)) uri.host else null
                    busy = false; error = getString(when (failure) {
                        is TorrMetadataTimeout -> R.string.movies_torrent_timeout
                        is TorrEntryMissing -> R.string.movies_torrent_missing
                        else -> R.string.movies_torrent_error
                    })
                }
            }
        }
    }

    private fun showTorrentBuffer(hash: String, fileId: Int, startPreload: Boolean = true, trackDownload: Boolean = false, downloadPreparation: Job? = null, openPlayer: () -> Unit) {
        val movieId = selected?.id
        bufferDialog?.dismiss()
        var dialog: AlertDialog? = null
        dialog = TorrPreparationDialog.show(this, hash, fileId, onOpen = {
            lastPreparedMovie = movieId
            lastPreparedHash = hash
            openPlayer()
        }, onDismiss = { if (bufferDialog === dialog) bufferDialog = null }, startPreload = startPreload, trackDownload = trackDownload, downloadPreparation = downloadPreparation)
        bufferDialog = dialog
    }

    /** An explicit snapshot of the last prepared torrent; it does not resume or start playback. */
    private fun showLastTorrentStatus(hash: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.movies_status_title)
            .setMessage(R.string.movies_status_loading)
            .setNegativeButton(R.string.movies_cancel, null)
            .setPositiveButton(R.string.movies_status_refresh, null)
            .create()
        var request: Job? = null
        bufferDialog?.dismiss()
        bufferDialog = dialog
        dialog.setOnDismissListener { request?.cancel(); if (bufferDialog === dialog) bufferDialog = null }
        dialog.show()
        fun refreshStatus() {
            if (request?.isActive == true) return
            dialog.setMessage(getString(R.string.movies_status_loading))
            request = lifecycleScope.launch {
                val result = runCatching { withContext(Dispatchers.IO) { TorrServerCatalog.status(hash) } }
                if (!isActive || !dialog.isShowing) return@launch
                val entry = result.getOrNull()
                if (entry == null) {
                    dialog.setMessage(getString(R.string.movies_status_unavailable))
                    return@launch
                }
                dialog.setMessage(torrentStatusText(this@MovieLibraryActivity, entry, true))
            }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { refreshStatus() }
        refreshStatus()
    }

    private fun restoreMissingTorrent(hash: String) {
        if (busy || hash != missingTorrentHash) return
        val movieId = selected?.id ?: return
        val generation = ++requestGeneration
        busy = true; progressMessage = getString(R.string.movies_restore_working); error = ""
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { TorrServerCatalog.restoreByHash(hash) }
                if (generation != requestGeneration || selected?.id != movieId || isFinishing || isDestroyed) return@launch
                missingTorrentHash = null
                sourceRemovalVersion++
                toast(R.string.movies_restore_sent)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (generation == requestGeneration && selected?.id == movieId && !isFinishing && !isDestroyed)
                    error = getString(R.string.movies_torrent_error)
            } finally {
                if (generation == requestGeneration) busy = false
            }
        }
    }

    private fun chooseStream(stream: MovieStreamOption, choosePlayer: Boolean = false) {
        if (busy && sourceLookupJob?.isActive != true) return
        if (stream.external) {
            if (sourceLookupJob?.isActive == true) stopSourceLookup()
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(stream.uri)).addCategory(Intent.CATEGORY_BROWSABLE)) }
                .onFailure { toast(R.string.movies_external_unavailable) }
            return
        }
        val movie = saveSelected() ?: return
        val label = listOf(stream.name, stream.description).filter { it.isNotBlank() }.joinToString(" · ").take(256)
        val source = MovieSource(label, stream.uri, stream.fileIndex, stream.sizeBytes, stream.filenameHint)
        if (movie.mediaType == "series") { play(source, sourceEpisode, choosePlayer); return }
        val updated = movie.copy(sources = movie.sources.filterNot { it.uri == source.uri && it.fileIndex == source.fileIndex } + source)
        if (runCatching { MovieLibraryStore.save(this, updated) }.getOrDefault(false)) { refresh(); play(source, choosePlayer = choosePlayer) }
        else toast(R.string.movies_source_failed)
    }

    @Composable private fun MovieScreen() {
        val downloads by TorrDownloadStore.jobs.collectAsState()
        val savedFocus = remember { FocusRequester() }
        val lastEpisode = rememberLastEpisode(selected?.takeIf { it.mediaType == "series" }?.id)
        LaunchedEffect(sourceRemovalVersion) {
            if (sourceRemovalVersion > 0) {
                withFrameNanos { }
                runCatching { savedFocus.requestFocus() }
            }
        }
        Column(Modifier.fillMaxSize().background(Color(0xFF0A0A0A)).padding(horizontal = 36.dp, vertical = 22.dp)) {
            Text(tr(R.string.media_tab_title), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MovieButton(tr(R.string.movies_saved), Modifier.focusRequester(savedFocus)) { stopSourceLookup(); requestGeneration++; page = "saved"; busy = false; error = "" }
                MovieButton(tr(R.string.movies_search)) { search() }
                MovieButton(tr(R.string.movies_addons)) { stopSourceLookup(); requestGeneration++; page = "addons"; busy = false; error = "" }
                MovieButton(tr(R.string.back)) { stopSourceLookup(); if (page == "saved") finish() else { requestGeneration++; page = "saved"; busy = false; error = "" } }
            }
            if (pendingTorrentHash != null && page in setOf("saved", "results")) {
                Text(tr(R.string.movies_attach_help), color = Color.LightGray, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            }
            if (busy) Text(progressMessage.ifBlank { tr(R.string.movies_searching) }, color = Color(0xFFCCFF00), modifier = Modifier.padding(vertical = 8.dp))
            if (error.isNotBlank()) Text(error, color = Color(0xFFFFA69A), modifier = Modifier.padding(vertical = 8.dp))
            Spacer(Modifier.height(10.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                if (pendingTorrentHash != null && page in setOf("saved", "results")) item(key = "create-local-torrent-card") {
                    SettingRow(tr(R.string.movies_local_create), tr(R.string.movies_local_hint), onClick = { createLocalTorrentCard() })
                }
                when (page) {
                    "saved" -> {
                        if (saved.isEmpty()) item { Text(tr(R.string.movies_empty), color = Color.LightGray) }
                        items(saved, key = { it.id }) { movie ->
                            SettingRow(movie.title, movie.year, onClick = {
                                openMovie(MovieCatalogItem(movie.id, movie.title, movie.year, movie.poster, durationSeconds = movie.durationSeconds, mediaType = movie.mediaType))
                            })
                        }
                    }
                    "results" -> {
                        if (!busy && searched && results.isEmpty()) item { Text(tr(R.string.movies_no_results), color = Color.LightGray) }
                        items(results, key = { "${it.mediaType}:${it.id}" }) { movie -> SettingRow(movie.title,
                            listOf(movie.year, tr(if (movie.mediaType == "series") R.string.movies_type_series else R.string.movies_type_movie)).filter { it.isNotBlank() }.joinToString(" · "), onClick = { openMovie(movie) }) }
                    }
                    "addons" -> {
                        item { Text(tr(R.string.movies_addon_privacy), color = Color.LightGray, fontSize = 13.sp) }
                        item { SettingRow(tr(R.string.movies_add_addon), onClick = { addAddon() }) }
                        items(addons, key = { it.addon.manifestUrl }) { entry ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SettingRow(entry.addon.name, tr(if (entry.enabled) R.string.movies_disable else R.string.movies_enable), modifier = Modifier.weight(1f), onClick = {
                                    if (!readFailed && runCatching { MovieAddonPreferences.save(this@MovieLibraryActivity, entry.copy(enabled = !entry.enabled)) }.getOrDefault(false)) refresh()
                                    else toast(R.string.movies_source_failed)
                                })
                                SettingRow(tr(R.string.movies_remove_addon), modifier = Modifier.widthIn(max = 180.dp), onClick = { confirmRemoveAddon(entry) })
                            }
                        }
                    }
                    "detail" -> {
                        val movie = selected
                        if (movie != null) {
                            val stored = saved.firstOrNull { it.id == movie.id }
                            item { Text(movie.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                            if (movie.id.startsWith("local:")) item {
                                SettingRow(tr(R.string.movies_local_edit), onClick = { editLocalCard(movie) })
                            }
                            val preparedHash = lastPreparedHash?.takeIf { lastPreparedMovie == movie.id }
                            if (preparedHash != null) item(key = "last-torrent-status") {
                                SettingRow(tr(R.string.movies_status_title), tr(R.string.movies_status_hint), onClick = { showLastTorrentStatus(preparedHash) })
                            }
                            item { Text(listOf(movie.year, movie.description).filter { it.isNotBlank() }.joinToString(" · "), color = Color.LightGray, fontSize = 14.sp) }
                            val offlineEpisodes = downloads.filter { it.prefetchMovieId == movie.id && it.state == "done" }
                            val partialEpisodes = downloads.filter { it.prefetchMovieId == movie.id && it.state != "done" }
                            if (movie.mediaType == "series" && offlineEpisodes.isEmpty() && partialEpisodes.isEmpty()) item {
                                val message = when {
                                    TorrPrefetch.count(this@MovieLibraryActivity) == 0 -> R.string.prefetch_status_disabled
                                    !TorrPrefetch.destinationAvailable(this@MovieLibraryActivity) -> R.string.prefetch_status_folder
                                    else -> R.string.prefetch_status_waiting
                                }
                                Text(tr(message), color = Color.LightGray, fontSize = 14.sp)
                            }
                            if (partialEpisodes.isNotEmpty()) item { Text(tr(R.string.prefetch_in_progress), color = Color.LightGray, fontSize = 14.sp) }
                            items(partialEpisodes, key = { "offline-partial:${it.id}" }) { job ->
                                val percent = if (job.total > 0) (job.offset.toDouble() * 100 / job.total).toInt().coerceIn(0, 100) else 0
                                val status = when (job.state) {
                                    "queued" -> R.string.torr_download_queued
                                    "running" -> R.string.torr_download_running
                                    "paused" -> R.string.torr_download_paused
                                    "finalizing" -> R.string.torr_download_finalizing
                                    else -> when (job.error) {
                                        "space" -> R.string.torr_download_low_space
                                        "space_unavailable" -> R.string.torr_download_space_unknown
                                        "storage" -> R.string.torr_download_storage_error
                                        "source_timeout" -> R.string.torr_download_source_timeout
                                        "source_connect" -> R.string.torr_download_source_connect
                                        "source_response" -> R.string.torr_download_source_response
                                        "source_changed" -> R.string.torr_download_source_changed
                                        else -> R.string.torr_download_error
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val downloadedSize = android.text.format.Formatter.formatShortFileSize(this@MovieLibraryActivity, job.offset)
                                    val totalSize = android.text.format.Formatter.formatShortFileSize(this@MovieLibraryActivity, job.total)
                                    SettingRow(job.name, "${tr(status)} · $downloadedSize / $totalSize · $percent%", modifier = Modifier.weight(1f), onClick = {
                                        runCatching {
                                            when (job.state) {
                                                "queued", "running" -> TorrDownloadActions.pause(this@MovieLibraryActivity, job.id)
                                                "paused", "error" -> TorrDownloadActions.start(this@MovieLibraryActivity, job.id)
                                            }
                                        }.onFailure { toast(R.string.torr_download_failed) }
                                    })
                                    SettingRow(tr(R.string.movies_play), modifier = Modifier.widthIn(max = 160.dp), onClick = {
                                        showTorrentBuffer(job.hash, job.index, trackDownload = true) {
                                            startMovieIntent(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(TorrPlayback.url(job.hash, job.index)), "video/*"))
                                        }
                                    })
                                    if (job.state == "paused" || job.state == "error") SettingRow(tr(R.string.prefetch_delete), modifier = Modifier.widthIn(max = 180.dp), onClick = {
                                        AlertDialog.Builder(this@MovieLibraryActivity).setTitle(R.string.prefetch_delete)
                                            .setMessage(getString(R.string.prefetch_delete_partial_confirm, job.name))
                                            .setPositiveButton(R.string.prefetch_delete) { _, _ ->
                                                lifecycleScope.launch {
                                                    val ok = withContext(Dispatchers.IO) { runCatching { TorrDownloadDocuments.discard(this@MovieLibraryActivity, job.id) }.isSuccess }
                                                    if (!ok) toast(R.string.prefetch_delete_failed)
                                                }
                                            }.setNegativeButton(R.string.cancel, null).show()
                                    })
                                }
                            }
                            if (offlineEpisodes.isNotEmpty()) item { Text(tr(R.string.prefetch_saved_episodes), color = Color.LightGray, fontSize = 14.sp) }
                            items(offlineEpisodes, key = { "offline:${it.id}" }) { job ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SettingRow(job.name, tr(R.string.prefetch_ready), modifier = Modifier.weight(1f), onClick = {
                                        lifecycleScope.launch {
                                            if (withContext(Dispatchers.IO) { TorrPrefetch.playable(this@MovieLibraryActivity, job) }) {
                                                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(job.document), "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                startMovieIntent(intent, onCompleted = { continueSavedEpisode(movie.id, job.hash, job.index) }, resumeIdentity = TorrPlayback.url(job.hash, job.index))
                                            } else toast(R.string.prefetch_missing)
                                        }
                                    })
                                    SettingRow(tr(R.string.prefetch_delete), modifier = Modifier.widthIn(max = 180.dp), onClick = {
                                        AlertDialog.Builder(this@MovieLibraryActivity).setTitle(R.string.prefetch_delete)
                                            .setMessage(getString(R.string.prefetch_delete_confirm, job.name))
                                            .setPositiveButton(R.string.prefetch_delete) { _, _ ->
                                                lifecycleScope.launch {
                                                    val ok = withContext(Dispatchers.IO) { runCatching { TorrDownloadDocuments.deletePrefetch(this@MovieLibraryActivity, job.id) }.isSuccess }
                                                    if (!ok) toast(R.string.prefetch_delete_failed)
                                                    else {
                                                        try { TorrPrefetch.refillAfterDelete(this@MovieLibraryActivity, job) { lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) } }
                                                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                                        catch (_: Exception) { /* Deletion succeeded; refill can be retried on playback. */ }
                                                    }
                                                }
                                            }.setNegativeButton(R.string.cancel, null).show()
                                    })
                                }
                            }
                            missingTorrentHash?.let { hash -> item(key = "restore-torrent") {
                                SettingRow(tr(R.string.movies_restore_torrent), tr(R.string.movies_restore_hint), onClick = { restoreMissingTorrent(hash) })
                            }; item(key = "restore-torrent-file") {
                                SettingRow(tr(R.string.movies_restore_file), tr(R.string.movies_restore_file_hint), onClick = { addFile(hash) })
                            } }
                            if (pendingTorrentHash != null) item { SettingRow(tr(R.string.movies_attach_torrent), pendingTorrentTitle, onClick = { attachPendingTorrent() }) }
                            item { SettingRow(tr(if (stored == null) R.string.movies_save else R.string.movies_remove), onClick = {
                                if (stored == null) saveSelected() else if (!readFailed && runCatching { MovieLibraryStore.remove(this@MovieLibraryActivity, stored.id) }.getOrDefault(false)) refresh()
                                else toast(R.string.movies_corrupt)
                            }) }
                            item { SettingRow(tr(R.string.movies_add_link), onClick = { addLink() }) }
                            item { SettingRow(tr(R.string.movies_add_file), onClick = { addFile() }) }
                            if (movie.id.matches(Regex("tt[0-9]{1,16}"))) item {
                                SettingRow(tr(if (movie.mediaType == "series") R.string.movies_find_episode_sources else R.string.movies_sources),
                                    onClick = { if (movie.mediaType == "series") chooseEpisode() else findSources() })
                            }
                            if (lastEpisode != null) item(key = "last-episode") {
                                SettingRow(tr(R.string.movies_last_episode), getString(R.string.movies_episode_selected, lastEpisode.season, episodeLabel(lastEpisode)),
                                    onClick = { findSources(lastEpisode) })
                            }
                            item { Text(tr(R.string.movies_torrent_hint), color = Color.LightGray, fontSize = 13.sp) }
                            stored?.sources?.let { savedSources ->
                                items(savedSources, key = { "saved:${it.uri}:${it.fileIndex}" }) { source ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        val sourceLabel = if (source.uri.startsWith("content:", ignoreCase = true) && source.label.startsWith("raw:/"))
                                            source.label.substringAfterLast('/').ifBlank { source.label }
                                        else source.label
                                        SettingRow(sourceLabel.ifBlank { tr(R.string.movies_play) }, tr(R.string.movies_play), modifier = Modifier.weight(1f), onClick = {
                                            if (canDefaultToAce(source) && preferredAceSource(movie.id) == source.uri) openTorrentInAce(source)
                                            else play(source)
                                        })
                                        SettingRow(tr(R.string.movies_source_options), modifier = Modifier.widthIn(max = 180.dp), onClick = {
                                            val canOpenInAce = Uri.parse(source.uri).scheme.equals("content", ignoreCase = true)
                                            val canPreferAce = canDefaultToAce(source)
                                            val isPreferredAce = canPreferAce && preferredAceSource(movie.id) == source.uri
                                            val options = buildList {
                                                add(getString(R.string.movies_choose_player))
                                                if (canOpenInAce) add(getString(R.string.movies_open_in_ace))
                                                if (canPreferAce) add(getString(if (isPreferredAce) R.string.movies_ace_clear_default else R.string.movies_ace_make_default))
                                                add(getString(R.string.movies_remove_source))
                                            }
                                            AlertDialog.Builder(this@MovieLibraryActivity).setTitle(R.string.movies_source_options)
                                                .setItems(options.toTypedArray()) { _, index ->
                                                    when {
                                                        index == 0 -> play(source, choosePlayer = true)
                                                        canOpenInAce && index == 1 -> openTorrentInAce(source)
                                                        canPreferAce && index == 2 -> {
                                                            val editor = getSharedPreferences("orbit", MODE_PRIVATE).edit()
                                                            if (isPreferredAce) editor.remove(preferredAceKey(movie.id))
                                                            else editor.putString(preferredAceKey(movie.id), source.uri)
                                                            editor.apply()
                                                        }
                                                        else -> confirmRemoveSource(movie.id, source)
                                                    }
                                                }.setNegativeButton(R.string.movies_cancel, null).show()
                                        })
                                    }
                                    val estimate = source.sizeBytes?.let { size -> movie.durationSeconds?.let { MoviePlaybackEstimator.estimate(size, it, null) } }
                                    if (estimate != null) Text(tr(R.string.movies_estimate_average, String.format(java.util.Locale.ROOT, "%.1f", estimate.averageBitsPerSecond / 1_000_000)), color = Color.LightGray)
                                }
                            }
                            if (!busy && !searchedSources && sources.isEmpty() && stored?.sources.isNullOrEmpty() && (movie.mediaType == "series" || addons.isEmpty()))
                                item { Text(tr(if (movie.mediaType == "series") R.string.movies_no_series_sources else R.string.movies_no_sources), color = Color.LightGray) }
                            sourceEpisode?.let { episode -> item { Text(getString(R.string.movies_episode_selected, episode.season, episodeLabel(episode)), color = Color.LightGray) } }
                            if (searchedSources && !busy && sources.isEmpty() && error.isBlank()) item { Text(tr(R.string.movies_search_empty), color = Color.LightGray) }
                            itemsIndexed(sources, key = { index, _ -> "found:$index" }) { _, stream ->
                                val hint = if (stream.external) listOf(tr(R.string.movies_external_open), stream.description).filter { it.isNotBlank() }.joinToString(" · ") else stream.description
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SettingRow(stream.name.ifBlank { tr(if (stream.external) R.string.movies_external_open else R.string.movies_play) }, hint, modifier = Modifier.weight(1f), onClick = { chooseStream(stream) })
                                    if (!stream.external) SettingRow(tr(R.string.movies_choose_player), modifier = Modifier.widthIn(max = 180.dp), onClick = { chooseStream(stream, choosePlayer = true) })
                                }
                                val estimate = stream.sizeBytes?.let { size -> movie.durationSeconds?.let { MoviePlaybackEstimator.estimate(size, it, null) } }
                                if (estimate != null) Text(tr(R.string.movies_estimate_average, String.format(java.util.Locale.ROOT, "%.1f", estimate.averageBitsPerSecond / 1_000_000)), color = Color.LightGray)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable private fun rememberLastEpisode(seriesId: String?): MovieEpisode? {
        val prefs = remember { getSharedPreferences("orbit", MODE_PRIVATE) }
        fun read(): MovieEpisode? = seriesId?.let { id ->
            runCatching { decodeEpisodeHistory(id, prefs.getString(episodeHistoryKey(id), null)) }.getOrNull()
        }
        var episode by remember(seriesId) { mutableStateOf(read()) }
        DisposableEffect(prefs, seriesId) {
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (seriesId != null && (key == null || key == episodeHistoryKey(seriesId))) episode = read()
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            episode = read()
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        return episode
    }
}

@Composable private fun MovieButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    androidx.tv.material3.Button(onClick = onClick, modifier = modifier) { Text(label) }
}
