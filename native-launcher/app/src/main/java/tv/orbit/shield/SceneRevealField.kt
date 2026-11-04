package tv.orbit.shield

import kotlin.math.hypot
import kotlin.math.abs

/** Authored landmarks lead the reveal; a broad light field brings out illuminated surfaces first.
 * Computed once on the worker thread, with no moving particles or per-frame randomness. */
internal object SceneRevealField {
    /** Broad, image-derived underpainting. A blurred neighborhood suppresses tiny flecks;
     * no white outline is added, only the painting's own colors at restrained opacity. */
    fun underpainting(profile: SceneRevealProfile, colors: IntArray, width: Int, height: Int): DoubleArray {
        require(width > 1 && height > 1 && colors.size == width * height)
        val light = DoubleArray(colors.size) { i ->
            val c = colors[i]
            (((c shr 16) and 255) * .2126 + ((c shr 8) and 255) * .7152 + (c and 255) * .0722) / 255.0
        }
        val broad = DoubleArray(colors.size) { i ->
            val x = i % width; val y = i / width
            var sum = 0.0; var count = 0
            var lowest = 1.0; var highest = 0.0
            for (sy in (y - 2).coerceAtLeast(0)..(y + 2).coerceAtMost(height - 1)) {
                for (sx in (x - 2).coerceAtLeast(0)..(x + 2).coerceAtMost(width - 1)) {
                    val value = light[sy * width + sx]
                    sum += value; count++
                    lowest = minOf(lowest, value); highest = maxOf(highest, value)
                }
            }
            // Trim isolated extrema, including at image edges. A single star cannot
            // grow into a large early highlight; connected windows and surfaces remain.
            (sum - lowest - highest) / (count - 2)
        }
        return DoubleArray(colors.size) { i ->
            val x = i % width; val y = i / width
            when (profile.development) {
                SceneDevelopment.CONTOURS -> {
                    val horizontal = abs(broad[y * width + (x + 1).coerceAtMost(width - 1)] -
                        broad[y * width + (x - 1).coerceAtLeast(0)])
                    val vertical = abs(broad[(y + 1).coerceAtMost(height - 1) * width + x] -
                        broad[(y - 1).coerceAtLeast(0) * width + x])
                    .52 * smooth((hypot(horizontal, vertical) - .012) / .13)
                }
                SceneDevelopment.LIGHT -> .48 * smooth((broad[i] - .08) / .52)
            }
        }
    }

    /** Original-color underpainting develops first; color fills it without an erase/reflash.
     * Both stages only increase opacity. At progress=1 every pixel is fully visible. */
    fun coverage(progress: Double, start: Double, detail: Double, softness: Double): Double {
        val fill = smooth((progress - start * (1.0 - softness)) / softness)
        val early = smooth((progress - (start * .55 - .12).coerceAtLeast(0.0)) / (softness * 1.5))
        return fill + detail * early * (1.0 - fill)
    }

    private fun smooth(value: Double): Double {
        val t = value.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    fun create(profile: SceneRevealProfile, colors: IntArray, width: Int, height: Int): DoubleArray {
        require(width > 1 && height > 1 && colors.size == width * height)
        val luminance = DoubleArray(colors.size) { i ->
            val c = colors[i]
            (((c shr 16) and 255) * .2126 + ((c shr 8) and 255) * .7152 + (c and 255) * .0722) / 255.0
        }
        val field = DoubleArray(colors.size) { i ->
            val column = i % width; val row = i / width
            val x = column.toDouble() / (width - 1)
            val y = row.toDouble() / (height - 1)
            // Average a neighborhood so isolated bright pixels cannot become flashing specks.
            var light = 0.0; var samples = 0
            for (sy in (row - 2).coerceAtLeast(0)..(row + 2).coerceAtMost(height - 1)) {
                for (sx in (column - 2).coerceAtLeast(0)..(column + 2).coerceAtMost(width - 1)) {
                    light += luminance[sy * width + sx]; samples++
                }
            }
            profile.anchors.minOf { anchor ->
                anchor.delay + .3 * hypot((x - anchor.x) / anchor.radiusX, (y - anchor.y) / anchor.radiusY)
            } + .12 * (1.0 - light / samples)
        }
        val minimum = field.minOrNull() ?: 0.0
        val range = ((field.maxOrNull() ?: 1.0) - minimum).coerceAtLeast(.001)
        return DoubleArray(field.size) { ((field[it] - minimum) / range).coerceIn(0.0, 1.0) }
    }
}
