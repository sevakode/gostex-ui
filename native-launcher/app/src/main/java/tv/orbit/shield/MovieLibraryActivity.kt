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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Film metadata and user-owned sources; streaming is delegated to an installed player. */
class MovieLibraryActivity : ComponentActivity() {
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
    private var readFailed = false
    private var requestGeneration = 0L
    private var pendingFileId: String? = null
    private var pendingTorrentHash: String? = null
    private var pendingTorrentTitle: String = ""

    private val torrentPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = pendingFileId.also { pendingFileId = null }
        val uri = result.data?.data
        if (id != null && uri != null) {
            val flags = result.data?.flags ?: 0
            val accepted = runCatching {
                contentResolver.takePersistableUriPermission(uri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val movie = saved.first { it.id == id }
                val source = MovieSource(uri.lastPathSegment?.takeLast(120) ?: "Torrent", uri.toString())
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
        refresh()
        pendingTorrentHash = (savedInstanceState?.getString(EXTRA_TORRENT_HASH) ?: intent.getStringExtra(EXTRA_TORRENT_HASH))
            ?.takeIf { it.matches(Regex("(?i)[0-9a-f]{40}")) }?.lowercase(java.util.Locale.ROOT)
        pendingTorrentTitle = (savedInstanceState?.getString(EXTRA_TORRENT_TITLE) ?: intent.getStringExtra(EXTRA_TORRENT_TITLE)).orEmpty().take(256)
        intent.getStringExtra(EXTRA_MOVIE_ID)?.let { id ->
            saved.firstOrNull { it.id == id }?.let { movie ->
                openMovie(MovieCatalogItem(movie.id, movie.title, movie.year, movie.poster, durationSeconds = movie.durationSeconds, mediaType = movie.mediaType))
            }
        }
        setContent { MovieScreen() }
        if (savedInstanceState == null && pendingTorrentHash != null) {
            TorrentArtworkPolicy.query(pendingTorrentTitle).takeIf { it.length >= 3 }?.let(::searchFor)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(EXTRA_TORRENT_HASH, pendingTorrentHash)
        outState.putString(EXTRA_TORRENT_TITLE, pendingTorrentTitle)
        super.onSaveInstanceState(outState)
    }

    companion object {
        internal const val EXTRA_MOVIE_ID = "movie_id"
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
        val generation = ++requestGeneration
        page = "detail"; selected = item; sources = emptyList(); error = ""
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

    private fun addLink() = input(R.string.movies_add_link, R.string.movies_link_hint) { value ->
        val source = value.trim()
        if (!MovieLibraryStore.validSource(source)) { toast(R.string.movies_source_failed); return@input }
        val movie = saveSelected()
        if (movie == null || !runCatching {
            MovieLibraryStore.save(this, movie.copy(sources = movie.sources.filterNot { it.uri == source } + MovieSource(getString(R.string.movies_add_link), source)))
        }.getOrDefault(false)) toast(R.string.movies_source_failed) else refresh()
    }

    private fun addFile() {
        val movie = saveSelected() ?: return
        pendingFileId = movie.id
        runCatching { torrentPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/x-bittorrent", "application/octet-stream"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }) }.onFailure { pendingFileId = null; toast(R.string.movies_file_error) }
    }

    private fun addAddon() = input(R.string.movies_add_addon, R.string.movies_addon_hint) { value ->
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

    private fun findSources() {
        val movie = selected ?: return
        val active = addons.filter { it.enabled }
        val generation = ++requestGeneration
        busy = true; progressMessage = ""; error = ""; sources = emptyList()
        lifecycleScope.launch {
            val fetched = withContext(Dispatchers.IO) {
                active.flatMap { entry -> runCatching { MovieAddonClient.streams(entry.addon, movie.id) }.getOrDefault(emptyList()) }
            }
            if (generation != requestGeneration || selected?.id != movie.id || isFinishing) return@launch
            sources = fetched; busy = false
        }
    }

    private fun play(source: MovieSource) {
        if (!MovieLibraryStore.validSource(source.uri)) { toast(R.string.movies_source_failed); return }
        val uri = Uri.parse(source.uri)
        val isTorrent = uri.scheme.equals("torrserver", true) || uri.scheme.equals("magnet", true) || uri.scheme.equals("content", true) ||
            ((uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && uri.path?.endsWith(".torrent", ignoreCase = true) == true)
        if (isTorrent) { prepareTorrent(uri); return }
        val intent = if (uri.scheme.equals("acestream", true)) Intent(Intent.ACTION_VIEW, uri)
            else Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*")
        startMovieIntent(intent)
    }

    private fun startMovieIntent(intent: Intent) {
        runCatching {
            startActivity(intent)
            selected?.id?.let { id -> getSharedPreferences("orbit", MODE_PRIVATE).edit().putLong("played:movie:$id", System.currentTimeMillis()).apply() }
        }.onFailure { toast(R.string.movies_player_missing) }
    }

    private fun prepareTorrent(uri: Uri) {
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
                busy = false; error = ""
                if (entry.files.isEmpty()) { toast(R.string.movies_metadata_unavailable); return@launch }
                if (videos.isEmpty()) { toast(R.string.movies_no_video); return@launch }
                fun open(file: TorrCatalogFile) {
                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(TorrPlayback.url(entry.hash, file.id)), "video/*")
                    startMovieIntent(intent)
                }
                if (videos.size == 1) open(videos.single())
                else AlertDialog.Builder(this@MovieLibraryActivity).setTitle(R.string.movies_choose_video)
                    .setItems(videos.map { it.path }.toTypedArray()) { _, index -> open(videos[index]) }
                    .setNegativeButton(R.string.movies_cancel, null).show()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == requestGeneration && !isFinishing && !isDestroyed) {
                    busy = false; error = getString(if (failure is TorrMetadataTimeout) R.string.movies_torrent_timeout else R.string.movies_torrent_error)
                }
            }
        }
    }

    private fun chooseStream(stream: MovieStreamOption) {
        val movie = saveSelected() ?: return
        val label = listOf(stream.name, stream.description).filter { it.isNotBlank() }.joinToString(" · ").take(256)
        val source = MovieSource(label, stream.uri, stream.fileIndex, stream.sizeBytes)
        val updated = movie.copy(sources = movie.sources.filterNot { it.uri == source.uri && it.fileIndex == source.fileIndex } + source)
        if (runCatching { MovieLibraryStore.save(this, updated) }.getOrDefault(false)) { refresh(); play(source) }
        else toast(R.string.movies_source_failed)
    }

    @Composable private fun MovieScreen() {
        Column(Modifier.fillMaxSize().background(Color(0xFF0A0A0A)).padding(horizontal = 36.dp, vertical = 22.dp)) {
            Text(tr(R.string.media_tab_title), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MovieButton(tr(R.string.movies_saved)) { requestGeneration++; page = "saved"; busy = false; error = "" }
                MovieButton(tr(R.string.movies_search)) { search() }
                MovieButton(tr(R.string.movies_addons)) { requestGeneration++; page = "addons"; busy = false; error = "" }
                MovieButton(tr(R.string.back)) { if (page == "saved") finish() else { requestGeneration++; page = "saved"; busy = false; error = "" } }
            }
            if (busy) Text(progressMessage.ifBlank { tr(R.string.movies_searching) }, color = Color(0xFFCCFF00), modifier = Modifier.padding(vertical = 8.dp))
            if (error.isNotBlank()) Text(error, color = Color(0xFFFFA69A), modifier = Modifier.padding(vertical = 8.dp))
            Spacer(Modifier.height(10.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
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
                            SettingRow(entry.addon.name, tr(if (entry.enabled) R.string.movies_disable else R.string.movies_enable), onClick = {
                                if (!readFailed && runCatching { MovieAddonPreferences.save(this@MovieLibraryActivity, entry.copy(enabled = !entry.enabled)) }.getOrDefault(false)) refresh()
                                else toast(R.string.movies_source_failed)
                            })
                        }
                    }
                    "detail" -> {
                        val movie = selected
                        if (movie != null) {
                            val stored = saved.firstOrNull { it.id == movie.id }
                            item { Text(movie.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                            item { Text(listOf(movie.year, movie.description).filter { it.isNotBlank() }.joinToString(" · "), color = Color.LightGray, fontSize = 14.sp) }
                            if (pendingTorrentHash != null) item { SettingRow(tr(R.string.movies_attach_torrent), pendingTorrentTitle, onClick = { attachPendingTorrent() }) }
                            item { SettingRow(tr(if (stored == null) R.string.movies_save else R.string.movies_remove), onClick = {
                                if (stored == null) saveSelected() else if (!readFailed && runCatching { MovieLibraryStore.remove(this@MovieLibraryActivity, stored.id) }.getOrDefault(false)) refresh()
                                else toast(R.string.movies_corrupt)
                            }) }
                            item { SettingRow(tr(R.string.movies_add_link), onClick = { addLink() }) }
                            item { SettingRow(tr(R.string.movies_add_file), onClick = { addFile() }) }
                            if (movie.mediaType == "movie") item { SettingRow(tr(R.string.movies_sources), onClick = { findSources() }) }
                            item { Text(tr(R.string.movies_torrent_hint), color = Color.LightGray, fontSize = 13.sp) }
                            stored?.sources?.let { savedSources ->
                                items(savedSources, key = { "saved:${it.uri}:${it.fileIndex}" }) { source ->
                                    SettingRow(source.label.ifBlank { tr(R.string.movies_play) }, tr(R.string.movies_play), onClick = { play(source) })
                                    val estimate = source.sizeBytes?.let { size -> movie.durationSeconds?.let { MoviePlaybackEstimator.estimate(size, it, null) } }
                                    if (estimate != null) Text(tr(R.string.movies_estimate_average, String.format(java.util.Locale.ROOT, "%.1f", estimate.averageBitsPerSecond / 1_000_000)), color = Color.LightGray)
                                }
                            }
                            if (!busy && sources.isEmpty() && stored?.sources.isNullOrEmpty() && (movie.mediaType == "series" || addons.isEmpty()))
                                item { Text(tr(if (movie.mediaType == "series") R.string.movies_no_series_sources else R.string.movies_no_sources), color = Color.LightGray) }
                            items(sources) { stream ->
                                SettingRow(stream.name.ifBlank { tr(R.string.movies_play) }, stream.description, onClick = { chooseStream(stream) })
                                val estimate = stream.sizeBytes?.let { size -> movie.durationSeconds?.let { MoviePlaybackEstimator.estimate(size, it, null) } }
                                if (estimate != null) Text(tr(R.string.movies_estimate_average, String.format(java.util.Locale.ROOT, "%.1f", estimate.averageBitsPerSecond / 1_000_000)), color = Color.LightGray)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun MovieButton(label: String, onClick: () -> Unit) {
    androidx.tv.material3.Button(onClick = onClick) { Text(label) }
}
