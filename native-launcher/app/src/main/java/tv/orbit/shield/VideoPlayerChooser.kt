package tv.orbit.shield

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import java.security.MessageDigest

internal data class VlcSeriesPlaylist(val hash: String, val startId: Int, val startTitle: String, val ids: Set<Int>, val titles: Set<String>, val onFilePlayed: (Int) -> Unit)

internal class VideoPlayerChooser(private val activity: ComponentActivity) {
    private val prefs by lazy { activity.getSharedPreferences("orbit", Context.MODE_PRIVATE) }
    private val pendingKey = "video.pending." + activity.javaClass.simpleName
    private var request by mutableStateOf<Request?>(null)
    private var onPlayerReturned: (() -> Unit)? = null
    private var activePlaylist: VlcSeriesPlaylist? = null
    private var resumeSeek: Job? = null
    private var pausedCache: Job? = null
    private val failedPlaylistResumes = mutableSetOf<String>()
    private val result = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { response ->
        val expected = prefs.getString(pendingKey, null)
        prefs.edit().remove(pendingKey).apply()
        val data = response.data
        val uri = data?.getStringExtra("extra_uri")
        resumeSeek?.cancel(); resumeSeek = null
        pausedCache?.cancel(); pausedCache = null
        val playlist = activePlaylist.also { activePlaylist = null }
        val playedId = if (uri != null && playlist != null) runCatching {
            val value = Uri.parse(uri)
            val id = value.getQueryParameter("index")?.toIntOrNull()
            id?.takeIf { value.scheme == "http" && value.host == "127.0.0.1" && value.port == 8090 &&
                value.path?.startsWith("/stream") == true && value.getQueryParameter("link").equals(playlist.hash, true) && it in playlist.ids }
        }.getOrNull() else null
        if (playedId != null) playlist?.onFilePlayed?.invoke(playedId)
        val playedKey = if (playedId != null && playlist != null) digest(TorrPlayback.url(playlist.hash, playedId))
            else if (uri != null && expected != null && digest(uri) == expected) expected else null
        if (playedKey != null && data != null && data.hasExtra("extra_position")) {
            val position = data.getLongExtra("extra_position", 0L)
            val duration = data.getLongExtra("extra_duration", 0L)
            val editor = prefs.edit()
            if (duration > 0) editor.putLong("video.duration.$playedKey", duration)
            if (videoIsNearlyFinished(position, duration, TorrPlaybackPreferences.restartNearEnd(activity))) editor.remove("video.position.$playedKey")
            else if (position >= 10_000 && (duration <= 0 || position < duration)) editor.putLong("video.position.$playedKey", position)
            editor.apply()
        }
        val callback = onPlayerReturned.also { onPlayerReturned = null }
        if (!activity.isFinishing && !activity.isDestroyed) callback?.invoke()
    }
    private data class Player(val component: ComponentName, val label: String)
    private data class Request(val intent: Intent, val players: List<Player>, val onStarted: () -> Unit, val onError: () -> Unit, val onReturned: () -> Unit, val playlist: VlcSeriesPlaylist?, val playlistUnavailable: Boolean)

    fun open(intent: Intent, force: Boolean = false, onStarted: () -> Unit = {}, onError: () -> Unit, onReturned: () -> Unit = {}, playlist: VlcSeriesPlaylist? = null, playlistUnavailable: Boolean = false) {
        val players = activity.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { item ->
                val info = item.activityInfo ?: return@mapNotNull null
                if (!info.exported || !info.enabled || !info.applicationInfo.enabled) return@mapNotNull null
                Player(ComponentName(info.packageName, info.name), item.loadLabel(activity.packageManager).toString())
            }.distinctBy { it.component }.sortedBy { it.label.lowercase() }
        if (players.isEmpty()) { onError(); return }
        val next = Request(Intent(intent), players, onStarted, onError, onReturned, playlist, playlistUnavailable)
        val preferred = prefs.getString("video.default_player", null)
        val selected = players.firstOrNull { it.component.flattenToString() == preferred }
        if (!force && selected != null) launch(next, selected, null) else request = next
    }

    private fun launch(req: Request, player: Player, rememberDefault: Boolean?) {
        // VLC's StartActivity can demand broad file access even for network
        // streams. Its exported video activity can open a stream directly.
        val directVlc = ComponentName("org.videolan.vlc", "org.videolan.vlc.gui.video.VideoPlayerActivity")
        val isNetworkStream = req.intent.data?.scheme in setOf("http", "https")
        val canOpenDirectly = player.component.packageName == directVlc.packageName && isNetworkStream && runCatching {
            activity.packageManager.getActivityInfo(directVlc, 0).let { it.exported && it.enabled && it.applicationInfo.enabled }
        }.getOrDefault(false)
        val key = req.intent.dataString?.let(::digest)
        val savedPosition = key?.let { prefs.getLong("video.position.$it", 0L) } ?: 0L
        val knownDuration = key?.let { prefs.getLong("video.duration.$it", 0L) } ?: 0L
        val position = videoResumePosition(savedPosition, knownDuration, TorrPlaybackPreferences.restartNearEnd(activity))
        val wasFinished = savedPosition > 0 && position == 0L
        if (wasFinished && key != null) prefs.edit().remove("video.position.$key").apply()
        // Without notification access we cannot reliably seek the first playlist
        // item, so keep resume correct by playing that one file directly.
        val playlist = req.playlist?.takeIf {
            canOpenDirectly && TorrPlaybackPreferences.autoNext(activity) &&
                (position <= 0 || (MediaAccess.isGranted(activity) && key !in failedPlaylistResumes))
        }
        if (player.component.packageName == "org.videolan.vlc" && TorrPlaybackPreferences.autoNext(activity)) {
            val message = when {
                req.playlistUnavailable -> R.string.movies_next_not_ordered
                req.playlist != null && playlist == null && position > 0 && !MediaAccess.isGranted(activity) -> R.string.movies_next_needs_media_access
                else -> null
            }
            if (message != null) android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show()
        }
        val intent = Intent(req.intent).setComponent(if (canOpenDirectly) directVlc else player.component)
        if (playlist != null) {
            val url = "http://127.0.0.1:8090/stream/gostex.m3u?link=${playlist.hash}&index=${playlist.startId}&m3u=1"
            intent.setDataAndType(Uri.parse(url), "application/x-mpegURL")
        }
        if (player.component.packageName == "org.videolan.vlc" && key != null) {
            if ((position > 0 || wasFinished) && playlist == null) intent.putExtra("position", position)
            prefs.edit().putString(pendingKey, key).apply()
        } else prefs.edit().remove(pendingKey).apply()
        try {
            onPlayerReturned = req.onReturned
            activePlaylist = playlist
            val launchTime = SystemClock.elapsedRealtime()
            result.launch(intent)
            val stream = localTorrentStreamIdentity(req.intent.dataString)
            if (canOpenDirectly && stream != null && MediaAccess.isGranted(activity))
                keepPausedTorrent(stream, playlist, launchTime)
            if (playlist != null && savedPosition > 0 && MediaAccess.isGranted(activity))
                resumePlaylist(playlist, savedPosition, launchTime)
            else if (playlist == null && canOpenDirectly && savedPosition > 0 && knownDuration <= 0 && MediaAccess.isGranted(activity) && key != null)
                guardLegacySingle(req.intent.data, key, savedPosition, launchTime)
            if (rememberDefault == true) prefs.edit().putString("video.default_player", player.component.flattenToString()).apply()
            else if (rememberDefault == false && prefs.getString("video.default_player", null) == player.component.flattenToString())
                prefs.edit().remove("video.default_player").apply()
            req.onStarted()
        } catch (_: Exception) {
            onPlayerReturned = null
            resumeSeek?.cancel(); resumeSeek = null
            pausedCache?.cancel(); pausedCache = null
            activePlaylist = null
            prefs.edit().remove(pendingKey).apply()
            req.onError()
        }
    }

    /** Keep only the selected paused VLC stream alive, with a bounded idle budget. */
    private fun keepPausedTorrent(stream: Pair<String, Int>, playlist: VlcSeriesPlaylist?, launchTime: Long) {
        pausedCache?.cancel()
        pausedCache = activity.lifecycleScope.launch {
            val manager = activity.getSystemService(MediaSessionManager::class.java)
            var pausedAt = 0L
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val controller = runCatching { manager?.getActiveSessions(MediaAccess.component(activity)) }.getOrNull()
                    ?.firstOrNull { session ->
                        val state = session.playbackState
                        val title = session.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                        val mediaUri = session.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
                        val sameStream = localTorrentStreamIdentity(mediaUri) == stream || vlcTorrentTitleIdentity(title) == stream
                        val samePlaylist = playlist != null && torrentVideoTitleMatches(title, playlist.titles)
                        session.packageName == "org.videolan.vlc" && state != null &&
                            state.lastPositionUpdateTime >= launchTime && (sameStream || samePlaylist)
                    }
                // A playlist may already have advanced to another file. Only
                // checkpoint a single stream when VLC identifies it exactly.
                if (playlist == null && controller != null) {
                    val state = controller.playbackState
                    val title = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    val mediaUri = controller.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
                    val exact = localTorrentStreamIdentity(mediaUri) == stream || vlcTorrentTitleIdentity(title) == stream
                    if (exact && state != null && state.state in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED)) {
                        val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                        val checkpoint = videoCheckpoint(state.position, duration, TorrPlaybackPreferences.restartNearEnd(activity))
                        if (checkpoint != null) {
                            val key = digest(TorrPlayback.url(stream.first, stream.second))
                            val editor = prefs.edit()
                            if (duration > 0L) editor.putLong("video.duration.$key", duration)
                            if (checkpoint == 0L) editor.remove("video.position.$key")
                            else editor.putLong("video.position.$key", checkpoint)
                            editor.apply()
                        }
                    }
                }
                if (controller?.playbackState?.state == PlaybackState.STATE_PAUSED) {
                    if (pausedAt == 0L) pausedAt = now
                    if (now - pausedAt < 10 * 60_000L) {
                        runCatching { withContext(Dispatchers.IO) { TorrServerCatalog.loadFiles(stream.first, readTimeoutMs = 4000) } }
                    }
                } else pausedAt = 0L
                delay(15_000)
            }
        }
    }

    /** VLC does not apply the position extra to a playlist item. Seek once
     * the newly opened first item has an active, matching media session. */
    private fun resumePlaylist(playlist: VlcSeriesPlaylist, position: Long, launchTime: Long) {
        resumeSeek?.cancel()
        resumeSeek = activity.lifecycleScope.launch {
            val manager = activity.getSystemService(MediaSessionManager::class.java)
            repeat(50) {
                val controller = runCatching { manager?.getActiveSessions(MediaAccess.component(activity)) }.getOrNull()
                    ?.firstOrNull { session ->
                        val state = session.playbackState
                        session.packageName == "org.videolan.vlc" &&
                            torrentVideoTitleMatches(session.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE), setOf(playlist.startTitle)) &&
                            state?.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime >= launchTime &&
                            (state.actions and PlaybackState.ACTION_SEEK_TO) != 0L
                    }
                val duration = controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                val restartNearEnd = TorrPlaybackPreferences.restartNearEnd(activity)
                if (controller != null && duration > 0 && runCatching { controller.transportControls.seekTo(videoResumePosition(position, duration, restartNearEnd)) }.isSuccess) {
                    val key = digest(TorrPlayback.url(playlist.hash, playlist.startId))
                    val editor = prefs.edit().putLong("video.duration.$key", duration)
                    if (videoIsNearlyFinished(position, duration, restartNearEnd)) editor.remove("video.position.$key")
                    editor.apply()
                    return@launch
                }
                delay(300)
            }
            failedPlaylistResumes.add(digest(TorrPlayback.url(playlist.hash, playlist.startId)))
            android.widget.Toast.makeText(activity, R.string.movies_resume_retry_single, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Old saves have no duration. Only correct a directly opened torrent when
     * VLC reports the exact same stream and its duration is known. */
    private fun guardLegacySingle(uri: Uri?, key: String, position: Long, launchTime: Long) {
        val hash = uri?.getQueryParameter("link") ?: return
        val index = uri.getQueryParameter("index") ?: return
        resumeSeek?.cancel()
        resumeSeek = activity.lifecycleScope.launch {
            val manager = activity.getSystemService(MediaSessionManager::class.java)
            repeat(50) {
                val controller = runCatching { manager?.getActiveSessions(MediaAccess.component(activity)) }.getOrNull()
                    ?.firstOrNull { session ->
                        val state = session.playbackState
                        val title = session.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                        val sameStream = title?.let { runCatching {
                            Uri.parse(it).let { stream -> stream.getQueryParameter("link").equals(hash, true) && stream.getQueryParameter("index") == index }
                        }.getOrDefault(false) } ?: false
                        session.packageName == "org.videolan.vlc" && state?.state == PlaybackState.STATE_PLAYING &&
                            state.lastPositionUpdateTime >= launchTime && (state.actions and PlaybackState.ACTION_SEEK_TO) != 0L &&
                            sameStream
                    }
                val duration = controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                if (controller != null && duration > 0) {
                    val editor = prefs.edit().putLong("video.duration.$key", duration)
                    if (videoIsNearlyFinished(position, duration, TorrPlaybackPreferences.restartNearEnd(activity)) && runCatching { controller.transportControls.seekTo(0L) }.isSuccess)
                        editor.remove("video.position.$key")
                    editor.apply()
                    return@launch
                }
                delay(300)
            }
        }
    }

    @Composable fun Render() {
        val current = request ?: return
        var rememberDefault by remember(current) { mutableStateOf(false) }
        val focus = remember(current) { FocusRequester() }
        Dialog(onDismissRequest = { request = null }) {
            Column(Modifier.fillMaxWidth(0.85f).background(Color(0xFF161E24)).padding(20.dp)) {
                Text(activity.getString(R.string.video_choose_player), color = Color.White, fontSize = 22.sp)
                Spacer(Modifier.height(12.dp))
                SettingRow(activity.getString(R.string.video_remember_player), modifier = Modifier.focusRequester(focus),
                    isSelected = rememberDefault, trailing = if (rememberDefault) "☑" else "☐") { rememberDefault = !rememberDefault }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(current.players, key = { it.component.flattenToString() }) { player ->
                        val isDefault = prefs.getString("video.default_player", null) == player.component.flattenToString()
                        SettingRow(player.label, value = if (isDefault) activity.getString(R.string.video_current_default) else "", trailing = "▶") {
                            request = null
                            launch(current, player, rememberDefault)
                        }
                    }
                }
            }
        }
        LaunchedEffect(current) { withFrameNanos { }; runCatching { focus.requestFocus() } }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
