package tv.orbit.shield

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.tv.material3.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class LauncherNotice(val id: Long, val message: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

/** Identity belongs to an occurrence, so repeating the same error gives it a fresh timeout. */
internal class LauncherNoticeState {
    private var nextId = 0L
    var current by mutableStateOf<LauncherNotice?>(null)
        private set

    fun show(message: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        current = message.takeIf(String::isNotBlank)?.let { LauncherNotice(++nextId, it, actionLabel, action) }
    }

    /** An old timeout or click must never clear a newer message. */
    fun dismiss(id: Long): Boolean {
        if (current?.id != id) return false
        current = null
        return true
    }
}

/**
 * A transient TV notice above the hint strip. It never requests focus on arrival. Its only
 * coroutine is a cancellable timeout, paused while focused, obscured, or in the background.
 */
@Composable internal fun LauncherNoticeHost(
    state: LauncherNoticeState,
    lifecycleOwner: LifecycleOwner,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    onRestoreFocus: () -> Unit = {},
) {
    // Keep this scope alive through removal of the notice, to restore focus after the next frame.
    val scope = rememberCoroutineScope()
    val restoreFocus by rememberUpdatedState(onRestoreFocus)
    val notice = state.current ?: return
    val accessibility = LocalAccessibilityManager.current
    val timeout = accessibility?.calculateRecommendedTimeoutMillis(
        originalTimeoutMillis = 8_000L,
        containsIcons = true,
        containsText = true,
        containsControls = true,
    ) ?: 8_000L
    var remaining by remember(notice.id, timeout) { mutableLongStateOf(timeout.coerceAtLeast(0L)) }
    // Replacing the text does not recreate the close button or revoke its current focus.
    var actionFocused by remember { mutableStateOf(false) }
    var closeFocused by remember { mutableStateOf(false) }
    val focused = actionFocused || closeFocused
    var started by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { owner, _ ->
            started = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        started = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(notice.id, timeout, focused, started, active) {
        if (focused || !started || !active) return@LaunchedEffect
        val begin = SystemClock.elapsedRealtime()
        try {
            delay(remaining)
            state.dismiss(notice.id)
        } finally {
            remaining = (remaining - (SystemClock.elapsedRealtime() - begin)).coerceAtLeast(0L)
        }
    }
    fun close() {
        val shouldRestore = focused
        if (state.dismiss(notice.id) && shouldRestore) scope.launch {
            withFrameNanos { }
            restoreFocus()
        }
    }
    BackHandler(enabled = focused && active, onBack = ::close)
    val closeLabel = tr(R.string.close)
    Box(modifier.fillMaxWidth().padding(horizontal = 36.dp).padding(bottom = 62.dp),
        contentAlignment = Alignment.Center) {
        Row(Modifier.widthIn(max = 600.dp).clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF29292D)).padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(notice.message, modifier = Modifier.weight(1f, fill = false),
                color = Color(0xFFF5F5F5), fontSize = 13.sp, lineHeight = 18.sp,
                maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (notice.actionLabel != null && notice.action != null) {
                val perform = { val action = notice.action; close(); action.invoke() }
                Surface(onClick = perform, modifier = Modifier.touchSurface(onClick = perform)
                    .onFocusChanged { actionFocused = it.hasFocus },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF424A22), focusedContainerColor = Color(0xFFCCFF00)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)) {
                    Text(notice.actionLabel, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = if (actionFocused) Color.Black else Color.White, fontSize = 13.sp)
                }
            }
            Surface(onClick = ::close, modifier = Modifier.size(32.dp).touchSurface(onClick = ::close)
                .onFocusChanged { closeFocused = it.hasFocus }
                .semantics { contentDescription = closeLabel },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
                colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent,
                    focusedContainerColor = Color(0xFF45454A)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color(0xFFCCFF00))))) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("×", color = Color(0xFFF5F5F5), fontSize = 20.sp)
                }
            }
        }
    }
}
