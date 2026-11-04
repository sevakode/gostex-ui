package tv.orbit.shield

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.min
import kotlin.math.roundToInt

/** Classic Snake is the artwork here: chunky bodies, little meals and generous black space.
 * Construct once on the worker. Drawing uses fixed paths and primitive math, with no bitmaps,
 * random numbers, temporary collections or per-frame shape objects. */
internal class SnakeActRenderer(seed: Long) {
    val plan = SnakeActPlan.create(seed)
    private val paint = Paint().apply { isAntiAlias = false }

    fun draw(canvas: Canvas, bounds: RectF, elapsedSeconds: Double, alpha: Int = 255) {
        val opacity = alpha.coerceIn(0, 255)
        if (opacity == 0 || bounds.width() <= 0f || bounds.height() <= 0f) return
        val save = canvas.save()
        canvas.clipRect(bounds)
        paint.color = Color.BLACK; paint.alpha = opacity
        canvas.drawRect(bounds, paint)
        for (track in plan.snakes) {
            val region = track.region
            val cell = min(bounds.width() * region.width / track.columns,
                bounds.height() * region.height / track.rows).toFloat()
            val left = bounds.left + (bounds.width() * (region.left + region.width / 2)).toFloat() - cell * track.columns / 2
            val top = bounds.top + (bounds.height() * (region.top + region.height / 2)).toFloat() - cell * track.rows / 2
            val head = track.headDistance(elapsedSeconds)
            val length = track.bodyLength(elapsedSeconds)

            // One food pellet per snake. It is replaced only when the head reaches it.
            val food = track.path[track.foodStep(elapsedSeconds)]
            val foodX = left + (food % track.columns + .5f) * cell
            val foodY = top + (food / track.columns + .5f) * cell
            paint.color = track.color; paint.alpha = (opacity * .82).roundToInt()
            val pellet = cell * .19f
            canvas.drawRect(foodX - pellet, foodY - pellet, foodX + pellet, foodY + pellet, paint)

            for (segment in length - 1 downTo 0) {
                val wrapped = ((head - segment) % track.path.size + track.path.size) % track.path.size
                val before = wrapped.toInt()
                val after = (before + 1) % track.path.size
                val fraction = wrapped - before
                val a = track.path[before]; val b = track.path[after]
                val ax = a % track.columns; val ay = a / track.columns
                val dx = b % track.columns - ax; val dy = b / track.columns - ay
                val x = left + ((ax + dx * fraction + .5) * cell).toFloat()
                val y = top + ((ay + dy * fraction + .5) * cell).toFloat()
                paint.color = track.color
                paint.alpha = (opacity * (.30 + .70 * (1.0 - segment.toDouble() / length))).roundToInt()
                val half = cell * .455f
                canvas.drawRect(x - half, y - half, x + half, y + half, paint)
                if (segment == 0) {
                    // Directional eyes keep the reference legible even in a paused thumbnail.
                    paint.color = Color.BLACK; paint.alpha = opacity
                    val eye = cell * .065f
                    val forwardX = x + dx * cell * .20f
                    val forwardY = y + dy * cell * .20f
                    val sideX = -dy * cell * .18f; val sideY = dx * cell * .18f
                    canvas.drawRect(forwardX + sideX - eye, forwardY + sideY - eye,
                        forwardX + sideX + eye, forwardY + sideY + eye, paint)
                    canvas.drawRect(forwardX - sideX - eye, forwardY - sideY - eye,
                        forwardX - sideX + eye, forwardY - sideY + eye, paint)
                }
            }
        }
        canvas.restoreToCount(save)
    }

    /** One-time thumbnail preparation, never called by draw(). */
    fun renderSnapshot(width: Int = 640, height: Int = 360, elapsedSeconds: Double = 8.0): Bitmap {
        require(width > 0 && height > 0)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            draw(Canvas(it), RectF(0f, 0f, width.toFloat(), height.toFloat()), elapsedSeconds)
        }
    }
}
