package tv.orbit.shield

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.*

internal object MediaPlayerPreferences {
    const val KEY = "status.mediaPlayerVisible"
    const val PAUSED_KEY = "status.mediaPlayerShowPaused"
    fun visible(context: Context) = context.getSharedPreferences("orbit", 0).readBooleanOrDefault(KEY, true)
    fun visible(context: Context, value: Boolean) {
        context.getSharedPreferences("orbit", 0).edit().putBoolean(KEY, value).apply()
    }
    fun options(context: Context) = MediaPlayerOptions(visible(context),
        context.getSharedPreferences("orbit", 0).readBooleanOrDefault(PAUSED_KEY, false))
    fun showPaused(context: Context, value: Boolean) {
        context.getSharedPreferences("orbit", 0).edit().putBoolean(PAUSED_KEY, value).apply()
    }
}

internal data class MediaPlayerOptions(val visible: Boolean = true, val showPaused: Boolean = false)

@Composable internal fun rememberMediaPlayerOptions(): MediaPlayerOptions {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("orbit", 0) }
    var options by remember(context) { mutableStateOf(MediaPlayerPreferences.options(context)) }
    DisposableEffect(context, prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == MediaPlayerPreferences.KEY || key == MediaPlayerPreferences.PAUSED_KEY)
                options = MediaPlayerPreferences.options(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        options = MediaPlayerPreferences.options(context)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return options
}

/** Only the current app icon is decoded; no artwork downloads, progress timer or animated marquee. */
@Composable private fun MediaAppIcon(packageName: String, modifier: Modifier) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val icon = remember(context, packageName, configuration) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, 64, 64)
            drawable.draw(android.graphics.Canvas(bitmap))
            bitmap.asImageBitmap()
        }.getOrNull()
    }
    if (icon != null) Image(icon, null, modifier)
    else CompactGlyph(CompactIcon.Music, modifier, Color(0xFFCCFF00))
}

@Composable internal fun MediaPlayerButton(session: NowPlayingSession, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val status = tr(if (session.isPlaying) R.string.media_playing else R.string.media_paused)
    val title = session.title.ifBlank { session.appName }
    val subtitle = listOf(session.appName, session.artist).filter { it.isNotBlank() }.distinct().joinToString(" · ")
    Surface(onClick = onClick, modifier = modifier.size(40.dp).touchSurface(onClick = onClick).semantics { contentDescription = "$status · $title · $subtitle" },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF242428), focusedContainerColor = Color(0xFF38383D)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color(0xFFCCFF00))))) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            MediaAppIcon(session.packageName, Modifier.size(26.dp))
            Box(Modifier.align(Alignment.BottomEnd).padding(3.dp).size(12.dp)
                .background(Color(0xFF141416), CircleShape), contentAlignment = Alignment.Center) {
                CompactGlyph(if (session.isPlaying) CompactIcon.Play else CompactIcon.Pause, Modifier.size(9.dp),
                    if (session.isPlaying) Color(0xFFCCFF00) else Color(0xFFADADB0))
            }
        }
    }
}

/** Compact transport panel, separate from settings. Session callbacks update text without moving focus. */
@Composable internal fun MediaPlayerPopover(activity: MainActivity, state: MediaPlaybackState, onDismiss: () -> Unit) {
    val session = state.session
    val currentDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(session == null) { if (session == null) currentDismiss() }
    if (session == null) return
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val initialFocus = remember { FocusRequester() }
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .18f)).safeDrawingPadding()
            .pointerInput(Unit) { detectTapGestures { currentDismiss() } }) {
            Column(Modifier.align(Alignment.TopEnd).padding(top = 66.dp, end = 36.dp).widthIn(max = 320.dp).fillMaxWidth()
                .heightIn(max = 360.dp).background(Color(0xFF17171B), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF48484D), RoundedCornerShape(8.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MediaAppIcon(session.packageName, Modifier.size(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(session.appName, color = Color(0xFFF5F5F5), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(tr(if (session.isPlaying) R.string.media_playing else R.string.media_paused),
                            color = Color(0xFFADADB0), fontSize = 11.sp)
                    }
                    CompactIconButton(CompactIcon.Close, tr(R.string.close), onClick = onDismiss)
                }
                MediaTrackDetails(session)
                MediaTransportControls(activity, session, initialFocus, onAppOpened = onDismiss)
            }
        }
        // Playback state, track changes and artwork never steal the currently focused control.
        LaunchedEffect(session.canPlayPause) {
            withFrameNanos { }
            runCatching { initialFocus.requestFocus() }
        }
    }
}

@Composable private fun MediaTrackDetails(session: NowPlayingSession) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(session.title.ifBlank { session.appName }, color = Color(0xFFF5F5F5), fontSize = 18.sp,
            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (session.artist.isNotBlank()) Text(session.artist, color = Color(0xFFADADB0), fontSize = 13.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun MediaTransportControls(
    activity: MainActivity, session: NowPlayingSession, initialFocus: FocusRequester, onAppOpened: () -> Unit = {},
) {
    fun perform(action: () -> Boolean) {
        if (!action()) activity.notice = activity.getString(R.string.media_action_failed)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CompactIconButton(CompactIcon.Previous, tr(R.string.media_previous), enabled = session.canSkipPrevious,
            onClick = { perform { session.skipPrevious() } })
        CompactIconButton(if (session.isPlaying) CompactIcon.Pause else CompactIcon.Play,
            tr(if (session.isPlaying) R.string.media_pause else R.string.media_resume),
            modifier = if (session.canPlayPause) Modifier.focusRequester(initialFocus) else Modifier,
            primary = true, enabled = session.canPlayPause, onClick = { perform { session.togglePlayback() } })
        CompactIconButton(CompactIcon.Next, tr(R.string.media_next), enabled = session.canSkipNext,
            onClick = { perform { session.skipNext() } })
        Spacer(Modifier.weight(1f))
        CompactIconButton(CompactIcon.Open, tr(R.string.media_open_app, session.appName),
            modifier = if (!session.canPlayPause) Modifier.focusRequester(initialFocus) else Modifier,
            onClick = { if (session.openApp(activity)) onAppOpened() else activity.notice = activity.getString(R.string.media_action_failed) })
    }
}

@Composable internal fun MediaPlayerSettings(activity: MainActivity, state: MediaPlaybackState, initialFocus: FocusRequester) {
    val options = rememberMediaPlayerOptions()
    val session = state.session
    if (session != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MediaAppIcon(session.packageName, Modifier.size(32.dp))
            Column(Modifier.weight(1f)) {
                Text(session.appName, color = Color(0xFFCCFF00), fontSize = 13.sp)
                Text(tr(if (session.isPlaying) R.string.media_playing else R.string.media_paused), color = Color(0xFFADADB0), fontSize = 12.sp)
            }
        }
        MediaTrackDetails(session)
        MediaTransportControls(activity, session, initialFocus)
        Spacer(Modifier.height(8.dp))
    } else {
        Text(tr(if (state.accessGranted) R.string.media_empty else R.string.media_access_hint),
            color = Color(0xFFADADB0), fontSize = 13.sp, lineHeight = 19.sp)
    }
    SettingRow(tr(R.string.media_visibility), tr(if (options.visible) R.string.enabled else R.string.disabled),
        modifier = if (session == null) Modifier.focusRequester(initialFocus) else Modifier,
        onClick = { MediaPlayerPreferences.visible(activity, !options.visible) })
    SettingRow(tr(R.string.media_show_paused), tr(if (options.showPaused) R.string.enabled else R.string.disabled),
        onClick = { MediaPlayerPreferences.showPaused(activity, !options.showPaused) })
    SettingRow(tr(R.string.media_access), tr(if (state.accessGranted) R.string.media_access_granted else R.string.media_access_required), onClick = {
        if (!MediaAccess.openSettings(activity)) activity.notice = activity.getString(R.string.media_settings_unavailable)
    })
    Text(tr(R.string.media_hint), color = Color(0xFFADADB0), fontSize = 12.sp, lineHeight = 17.sp)
    if (MediaPictureInPicture.isSupported(activity)) {
        SettingRow(tr(R.string.media_pip), tr(R.string.media_pip_settings), onClick = {
            if (!MediaPictureInPicture.openSettings(activity)) activity.notice = activity.getString(R.string.media_pip_unavailable)
        })
        Text(tr(R.string.media_pip_hint), color = Color(0xFFADADB0), fontSize = 12.sp, lineHeight = 17.sp)
    }
}
