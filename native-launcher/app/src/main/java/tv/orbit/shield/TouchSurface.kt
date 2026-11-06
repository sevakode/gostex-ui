package tv.orbit.shield

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/** Adds pointer input to TV Material surfaces, whose click handler is D-pad oriented. */
@Composable
internal fun Modifier.touchSurface(enabled: Boolean = true, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier {
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    return if (!enabled) this else pointerInput(onLongClick != null) {
        detectTapGestures(onTap = { click() }, onLongPress = if (onLongClick != null) ({ longClick?.invoke() }) else null)
    }
}
