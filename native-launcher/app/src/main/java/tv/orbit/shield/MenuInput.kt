package tv.orbit.shield

import android.view.KeyEvent

/**
 * TV Surface invokes a click on confirm UP even without a corresponding DOWN on that Surface.
 * A menu opened during a long press must swallow that opening gesture's remaining events.
 * Keep the guard independent of composition so key presses do not rebuild the dialog window.
 */
internal class MenuConfirmKeyGate {
    private val pressed = mutableSetOf<Int>()

    fun consume(keyCode: Int, action: Int, repeatCount: Int, canceled: Boolean = false): Boolean {
        if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER &&
            keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER && keyCode != KeyEvent.KEYCODE_BUTTON_A) return false
        if (canceled) {
            pressed.remove(keyCode)
            return true
        }
        return when (action) {
            KeyEvent.ACTION_DOWN -> {
                if (repeatCount == 0) pressed.add(keyCode)
                keyCode !in pressed
            }
            KeyEvent.ACTION_UP -> !pressed.remove(keyCode)
            else -> false
        }
    }
}
