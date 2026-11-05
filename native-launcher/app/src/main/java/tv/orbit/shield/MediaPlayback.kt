package tv.orbit.shield

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

internal data class MediaPlaybackState(
    val accessGranted: Boolean = false,
    val session: NowPlayingSession? = null,
)

internal data class NowPlayingSession(
    val controller: MediaController,
    val packageName: String,
    val appName: String,
    val title: String,
    val artist: String,
    val playbackState: Int,
    val actions: Long,
) {
    val isPlaying: Boolean get() = mediaPlaybackPriority(playbackState) == 0
    val canPlayPause: Boolean get() = supports(if (isPlaying) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY) ||
        supports(PlaybackState.ACTION_PLAY_PAUSE)
    val canSkipPrevious: Boolean get() = supports(PlaybackState.ACTION_SKIP_TO_PREVIOUS)
    val canSkipNext: Boolean get() = supports(PlaybackState.ACTION_SKIP_TO_NEXT)
    private fun supports(action: Long) = actions and action != 0L

    fun togglePlayback(): Boolean {
        if (!canPlayPause) return false
        return runCatching {
            if (supports(if (isPlaying) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY)) {
                if (isPlaying) controller.transportControls.pause() else controller.transportControls.play()
                true
            } else {
                val down = controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                val up = controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                down && up
            }
        }.getOrDefault(false)
    }
    fun skipPrevious(): Boolean = canSkipPrevious && runCatching { controller.transportControls.skipToPrevious() }.isSuccess
    fun skipNext(): Boolean = canSkipNext && runCatching { controller.transportControls.skipToNext() }.isSuccess

    fun openApp(context: Context): Boolean {
        val pending = runCatching { controller.sessionActivity }.getOrNull()
        if (pending != null && runCatching { pending.send() }.isSuccess) return true
        return runCatching {
            val intent = context.packageManager.getLeanbackLaunchIntentForPackage(packageName)
                ?: context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return false
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }
}

/** The system's session order breaks ties. Idle/paused sessions are hidden by default. */
internal fun mediaPlaybackPriority(state: Int, showPaused: Boolean = false): Int? = when (state) {
    PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
    PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
    PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
    PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> 0
    PlaybackState.STATE_PAUSED -> if (showPaused) 1 else null
    else -> null
}

internal object MediaAccess {
    fun component(context: Context) = ComponentName(context, MediaNotificationListenerService::class.java)
    fun isGranted(context: Context): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(component(context)) == true
    }.getOrDefault(false)

    fun openSettings(context: Context): Boolean {
        // Detail settings are not implemented on every Android TV firmware.
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString())
        for (intent in listOf(detail, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
        }
        return false
    }
}

/** Only connection state is observed. Notification contents are never read or retained. */
class MediaNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() { MediaAccessEvents.changed() }
    override fun onListenerDisconnected() { MediaAccessEvents.changed() }
    override fun onDestroy() { MediaAccessEvents.changed(); super.onDestroy() }
}

private object MediaAccessEvents {
    private val listeners = linkedSetOf<() -> Unit>()
    fun add(listener: () -> Unit) { listeners.add(listener) }
    fun remove(listener: () -> Unit) { listeners.remove(listener) }
    fun changed() { listeners.toList().forEach { it() } }
}

/** One observer per visible launcher. No timers, position polling, artwork downloads, or playback service. */
@Composable internal fun rememberMediaPlayback(
    activity: MainActivity,
    enabled: Boolean,
    showPaused: Boolean = false,
): MediaPlaybackState {
    val context = activity.applicationContext
    var state by remember(context) { mutableStateOf(MediaPlaybackState(MediaAccess.isGranted(context))) }
    DisposableEffect(activity, enabled, showPaused) {
        val playback = MediaPlaybackObserver(context, enabled, showPaused) { state = it }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) playback.start()
            if (event == Lifecycle.Event.ON_STOP) playback.stop()
        }
        activity.lifecycle.addObserver(lifecycleObserver)
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) playback.start()
        onDispose { activity.lifecycle.removeObserver(lifecycleObserver); playback.stop() }
    }
    return state
}

/** Platform boundary is kept separate so callback disposal and permission loss can be verified. */
internal class MediaPlaybackObserver(
    private val context: Context,
    private val enabled: Boolean,
    private val showPaused: Boolean = false,
    private val onChanged: (MediaPlaybackState) -> Unit,
) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var sessionsChanged: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var accessGranted = false
    private val sessions = linkedMapOf<MediaSession.Token, ObservedSession>()
    private var order = emptyList<MediaSession.Token>()
    private val appNames = mutableMapOf<String, String>()
    private val accessChanged: () -> Unit = { if (running) refreshAccess() }

    fun start() {
        if (running) return
        running = true
        MediaAccessEvents.add(accessChanged)
        refreshAccess()
    }

    fun stop() {
        if (!running) return
        running = false
        MediaAccessEvents.remove(accessChanged)
        clearSessions()
        appNames.clear()
        handler.removeCallbacksAndMessages(null)
        onChanged(MediaPlaybackState(accessGranted))
    }

    private fun refreshAccess() {
        accessGranted = MediaAccess.isGranted(context)
        if (!enabled || !accessGranted || manager == null) {
            clearSessions()
            onChanged(MediaPlaybackState(accessGranted))
            return
        }
        try {
            if (sessionsChanged == null) {
                // A new listener identity also rejects queued callbacks from a previous visible lifecycle.
                val listener = object : MediaSessionManager.OnActiveSessionsChangedListener {
                    override fun onActiveSessionsChanged(controllers: List<MediaController>?) {
                        if (running && sessionsChanged === this) reconcile(controllers.orEmpty())
                    }
                }
                sessionsChanged = listener
                manager.addOnActiveSessionsChangedListener(listener, MediaAccess.component(context), handler)
            }
            reconcile(manager.getActiveSessions(MediaAccess.component(context)))
        } catch (_: SecurityException) {
            clearSessions()
            accessGranted = false
            onChanged(MediaPlaybackState())
        } catch (_: RuntimeException) {
            clearSessions()
            onChanged(MediaPlaybackState(accessGranted))
        }
    }

    private fun clearSessions() {
        val listener = sessionsChanged
        sessionsChanged = null
        if (listener != null) runCatching { manager?.removeOnActiveSessionsChangedListener(listener) }
        sessions.values.forEach { runCatching { it.controller.unregisterCallback(it.callback) } }
        sessions.clear()
        order = emptyList()
    }

    private fun reconcile(controllers: List<MediaController>) {
        val tokens = controllers.map { it.sessionToken }.toSet()
        sessions.keys.filter { it !in tokens }.forEach { token ->
            sessions.remove(token)?.let { runCatching { it.controller.unregisterCallback(it.callback) } }
        }
        for (controller in controllers) {
            val token = controller.sessionToken
            val existing = sessions[token]
            val observed = existing ?: ObservedSession(controller).also { sessions[token] = it }
            try {
                if (existing == null) controller.registerCallback(observed.callback, handler)
                // A fresh platform session list is also a new snapshot: apps may miss a final callback.
                observed.playback = observed.controller.playbackState
                observed.metadata = observed.controller.metadata
            } catch (_: RuntimeException) {
                runCatching { observed.controller.unregisterCallback(observed.callback) }
                sessions.remove(token)
            }
        }
        order = controllers.map { it.sessionToken }.distinct()
        publish()
    }

    private fun publish() {
        if (!running) return
        val chosen = order.mapNotNull { sessions[it] }
            .filter { mediaPlaybackPriority(it.playback?.state ?: PlaybackState.STATE_NONE, showPaused) != null }
            .minByOrNull { mediaPlaybackPriority(it.playback?.state ?: PlaybackState.STATE_NONE, showPaused) ?: Int.MAX_VALUE }
        val session = chosen?.let {
            val packageName = it.controller.packageName
            val appName = appNames.getOrPut(packageName) {
                runCatching {
                    @Suppress("DEPRECATION")
                    context.packageManager.getApplicationInfo(packageName, 0).loadLabel(context.packageManager).toString()
                }.getOrDefault(packageName)
            }
            val metadata = it.metadata
            NowPlayingSession(it.controller, packageName, appName,
                metadataText(metadata, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE),
                metadataText(metadata, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                    MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_AUTHOR),
                it.playback?.state ?: PlaybackState.STATE_NONE, it.playback?.actions ?: 0L)
        }
        onChanged(MediaPlaybackState(accessGranted, session))
    }

    private inner class ObservedSession(val controller: MediaController) {
        var playback: PlaybackState? = null
        var metadata: MediaMetadata? = null
        private fun isCurrent() = running && sessions[controller.sessionToken] === this
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (isCurrent()) { playback = state; publish() }
            }
            override fun onMetadataChanged(value: MediaMetadata?) {
                if (isCurrent()) { metadata = value; publish() }
            }
            override fun onSessionDestroyed() {
                if (!isCurrent()) return
                sessions.remove(controller.sessionToken)
                order = order.filter { it != controller.sessionToken }
                runCatching { controller.unregisterCallback(this) }
                publish()
            }
        }
    }
}

private fun metadataText(metadata: MediaMetadata?, vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
    runCatching { metadata?.getText(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.take(512) }.getOrNull()
}.orEmpty()
