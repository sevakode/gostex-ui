package tv.orbit.shield

import android.app.AlertDialog
import android.view.KeyEvent

/** A long TV checklist must not require traversing its remaining items to reach Apply. */
internal fun AlertDialog.enableListButtonNavigation() {
    val list = listView ?: return // Text-entry dialogs retain their normal cursor and IME behavior.
    var returnPosition = 0
    var handledKey: Int? = null
    fun buttons() = listOfNotNull(
        getButton(AlertDialog.BUTTON_POSITIVE),
        getButton(AlertDialog.BUTTON_NEUTRAL),
        getButton(AlertDialog.BUTTON_NEGATIVE)
    ).filter { it.isShown && it.isEnabled }
    setOnKeyListener { _, keyCode, event ->
        if (event.action == KeyEvent.ACTION_UP && handledKey == keyCode) {
            handledKey = null
            true
        } else if (event.action != KeyEvent.ACTION_DOWN || event.isCanceled) false
        else if (handledKey == keyCode) true // Do not traverse buttons when a direction is held.
        else when {
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && list.hasFocus() -> {
                val position = list.selectedItemPosition.takeIf { it >= 0 } ?: list.firstVisiblePosition
                val moved = buttons().any { it.requestFocus() }
                if (moved) {
                    returnPosition = position
                    handledKey = keyCode
                }
                moved
            }
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT && buttons().any { it.hasFocus() } && list.count > 0 -> {
                val moved = list.requestFocus()
                if (moved) {
                    list.setSelection(returnPosition.coerceIn(0, list.count - 1))
                    handledKey = keyCode
                }
                moved
            }
            else -> false
        }
    }
    list.post {
        if (isShowing && list.count > 0) {
            list.requestFocus()
            if (list.selectedItemPosition < 0) list.setSelection(0)
        }
    }
}
