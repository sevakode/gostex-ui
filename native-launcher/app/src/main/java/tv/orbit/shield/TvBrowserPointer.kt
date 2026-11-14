package tv.orbit.shield

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import kotlin.math.roundToInt

internal class TvBrowserPointer(private val container: FrameLayout, private val web: WebView) {
    private val density = web.resources.displayMetrics.density
    private val cursor = CursorView()
    private var horizontal = .5f
    private var vertical = .5f
    private var pressedKey = KeyEvent.KEYCODE_UNKNOWN
    private var pressedAt = 0L

    fun show() {
        if (cursor.parent != null) return
        horizontal = .5f
        vertical = .5f
        container.addView(cursor, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
    }

    fun hide() {
        pressedKey = KeyEvent.KEYCODE_UNKNOWN
        pressedAt = 0L
        container.removeView(cursor)
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (cursor.parent == null) return false
        val key = event.keyCode
        val direction = when (key) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
            else -> false
        }
        if (direction) {
            if (event.action == KeyEvent.ACTION_DOWN) move(key, event.repeatCount)
            return true
        }
        return when (key) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                        pressedKey = key
                        pressedAt = event.downTime
                    }
                    KeyEvent.ACTION_UP -> if (pressedKey == key && pressedAt == event.downTime) {
                        pressedKey = KeyEvent.KEYCODE_UNKNOWN
                        pressedAt = 0L
                        if (!event.isCanceled) click()
                    }
                }
                true
            }
            else -> false
        }
    }

    private fun move(key: Int, repeatCount: Int) {
        val maxX = web.width - 1f
        val maxY = web.height - 1f
        if (maxX <= 0 || maxY <= 0) return
        val step = (16 + repeatCount.coerceAtMost(6) * 5) * density
        val x = horizontal * maxX + when (key) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -step
            KeyEvent.KEYCODE_DPAD_RIGHT -> step
            else -> 0f
        }
        val y = vertical * maxY + when (key) {
            KeyEvent.KEYCODE_DPAD_UP -> -step
            KeyEvent.KEYCODE_DPAD_DOWN -> step
            else -> 0f
        }
        val nextX = x.coerceIn(0f, maxX)
        val nextY = y.coerceIn(0f, maxY)
        horizontal = nextX / maxX
        vertical = nextY / maxY

        val scrollX = (x - nextX).roundToInt()
        val scrollY = (y - nextY).roundToInt()
        val dx = if (scrollX != 0 && web.canScrollHorizontally(scrollX)) scrollX else 0
        val dy = if (scrollY != 0 && web.canScrollVertically(scrollY)) scrollY else 0
        if (dx != 0 || dy != 0) web.scrollBy(dx, dy)
        cursor.invalidate()
    }

    private fun click() {
        if (!web.isShown || web.width < 1 || web.height < 1) return
        val time = SystemClock.uptimeMillis()
        val x = horizontal * (web.width - 1f)
        val y = vertical * (web.height - 1f)
        dispatchTouch(MotionEvent.ACTION_DOWN, time, x, y)
        dispatchTouch(MotionEvent.ACTION_UP, time, x, y)
    }

    private fun dispatchTouch(action: Int, downTime: Long, x: Float, y: Float) {
        val touch = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        try {
            touch.source = InputDevice.SOURCE_TOUCHSCREEN
            web.dispatchTouchEvent(touch)
        } finally {
            touch.recycle()
        }
    }

    private inner class CursorView : View(container.context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            if (web.width < 1 || web.height < 1) return
            val x = web.left + horizontal * (web.width - 1f)
            val y = web.top + vertical * (web.height - 1f)
            paint.color = Color.BLACK
            canvas.drawCircle(x, y, 11 * density, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(x, y, 8 * density, paint)
            paint.color = Color.BLACK
            canvas.drawCircle(x, y, 4 * density, paint)
        }
    }
}
