package tv.orbit.shield

import android.view.KeyEvent

/** Only the Home feed consumes shoulders; modal panels and running apps retain their own input. */
internal fun isHomeShelfKey(event: KeyEvent): Boolean =
    event.keyCode == KeyEvent.KEYCODE_BUTTON_L1 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R1

/**
 * A physical press advances once. The caller consumes all matching events while Home is active,
 * including repeats and key-up, so holding a shoulder cannot race through shelves or leak a click.
 */
internal fun homeShelfStep(event: KeyEvent): Int? {
    if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0 || event.isCanceled) return null
    return when (event.keyCode) {
        KeyEvent.KEYCODE_BUTTON_L1 -> -1
        KeyEvent.KEYCODE_BUTTON_R1 -> 1
        else -> null
    }
}
