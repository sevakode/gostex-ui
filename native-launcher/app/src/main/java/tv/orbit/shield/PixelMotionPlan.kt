package tv.orbit.shield

import kotlin.math.hypot
import kotlin.random.Random

/** These families are geometry, separate from the persisted reveal-style preference. */
internal enum class PixelMotionKind { SNAKES, BLOCKS, WEAVE }

/** A coarse image tile. Offsets are in grid cells relative to its final [x], [y].
 * A renderer can use offset*(1-phase) + arc*4*phase*(1-phase); both end at zero.
 * [pathIndex] and [step] address a snake; they are -1 in the other motion families. */
internal data class PixelMotionTile(
    val x: Int,
    val y: Int,
    val start: Double,
    val duration: Double,
    val sourceX: Double = 0.0,
    val sourceY: Double = 0.0,
    val arcX: Double = 0.0,
    val arcY: Double = 0.0,
    val pathIndex: Int = -1,
    val step: Int = -1,
) {
    /** Allocation-free eased progress. It never oscillates, and finishes before scene progress 1. */
    fun phase(progress: Double): Double {
        if (progress.isNaN()) return 0.0
        val t = ((progress - start) / duration).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}

/** Prepared once off the UI thread. [tiles] are row-major and [paths] contain tile indices.
 * Snakes cover four disjoint rectangles continuously; blocks settle in coherent clusters;
 * weave moves complete ribbons. No renderer needs to generate random numbers per frame. */
internal class PixelMotionPlan private constructor(
    val kind: PixelMotionKind,
    val columns: Int,
    val rows: Int,
    val tiles: List<PixelMotionTile>,
    val paths: List<IntArray>,
) {
    companion object {
        fun create(kind: PixelMotionKind, seed: Long, columns: Int = 32, rows: Int = 18): PixelMotionPlan {
            // Technical bound: the picture is a coarse mosaic, never a particle-sized allocation.
            require(columns in 2..256 && rows in 2..256)
            val random = Random(seed)
            val tiles = arrayOfNulls<PixelMotionTile>(columns * rows)
            val paths = mutableListOf<IntArray>()
            when (kind) {
                PixelMotionKind.SNAKES -> {
                    val splitX = split(columns, random)
                    val splitY = split(rows, random)
                    val regions = listOf(
                        Region(0, 0, splitX, splitY),
                        Region(splitX, 0, columns - splitX, splitY),
                        Region(0, splitY, splitX, rows - splitY),
                        Region(splitX, splitY, columns - splitX, rows - splitY),
                    )
                    // Spiral turns, horizontal ribbons and vertical ribbons change the route
                    // itself. Reflections and reversal add variety without discontinuous jumps.
                    val routeOffset = random.nextInt(3)
                    regions.forEachIndexed { index, region ->
                        val path = route(region, columns, (index + routeOffset) % 3,
                            random.nextBoolean(), random.nextBoolean(), random.nextBoolean())
                        paths += path
                        val delay = index * .018
                        path.forEachIndexed { step, cell ->
                            tiles[cell] = PixelMotionTile(cell % columns, cell / columns,
                                delay + .72 * step / (path.size - 1).coerceAtLeast(1), .12,
                                pathIndex = index, step = step)
                        }
                    }
                }
                PixelMotionKind.BLOCKS -> {
                    val blockWidth = if (random.nextBoolean()) 4 else 6
                    val blockHeight = if (random.nextBoolean()) 3 else 4
                    val focusX = random.nextDouble() * (columns - 1)
                    val focusY = random.nextDouble() * (rows - 1)
                    val distance = hypot(columns.toDouble(), rows.toDouble())
                    for (top in 0 until rows step blockHeight) {
                        for (left in 0 until columns step blockWidth) {
                            val w = minOf(blockWidth, columns - left)
                            val h = minOf(blockHeight, rows - top)
                            val start = .02 + .62 * hypot(left + w / 2.0 - focusX,
                                top + h / 2.0 - focusY) / distance
                            val duration = .25 + random.nextDouble() * .04
                            // All pixels of a block travel as one intact piece of the painting.
                            val dx = (random.nextDouble() * 2.0 - 1.0) * minOf(columns * .2, 6.0)
                            val dy = (random.nextDouble() * 2.0 - 1.0) * minOf(rows * .2, 4.0)
                            val arcX = (random.nextDouble() * 2.0 - 1.0) * .6
                            val arcY = (random.nextDouble() * 2.0 - 1.0) * .9
                            for (y in top until top + h) for (x in left until left + w) {
                                tiles[y * columns + x] = PixelMotionTile(x, y, start, duration,
                                    dx, dy, arcX, arcY)
                            }
                        }
                    }
                }
                PixelMotionKind.WEAVE -> {
                    val horizontal = random.nextBoolean()
                    val bandWidth = if (random.nextBoolean()) 2 else 3
                    val across = if (horizontal) rows else columns
                    val along = if (horizontal) columns else rows
                    val bandCount = (across + bandWidth - 1) / bandWidth
                    val reverseOrder = random.nextBoolean()
                    val direction = if (random.nextBoolean()) 1.0 else -1.0
                    for (band in 0 until bandCount) {
                        val order = if (reverseOrder) bandCount - 1 - band else band
                        val start = .04 + .53 * order / (bandCount - 1).coerceAtLeast(1)
                        val sign = direction * if (band % 2 == 0) 1.0 else -1.0
                        val travel = sign * minOf(along * .28, 8.0)
                        // Alternating ribbons enter from opposing sides; a low broad bow
                        // separates the interlacing pieces before they settle into the image.
                        val bow = sign * .45
                        for (cross in band * bandWidth until minOf((band + 1) * bandWidth, across)) {
                            for (position in 0 until along) {
                                val x = if (horizontal) position else cross
                                val y = if (horizontal) cross else position
                                tiles[y * columns + x] = PixelMotionTile(x, y, start, .29,
                                    if (horizontal) travel else 0.0, if (horizontal) 0.0 else travel,
                                    if (horizontal) 0.0 else bow, if (horizontal) bow else 0.0)
                            }
                        }
                    }
                }
            }
            return PixelMotionPlan(kind, columns, rows, tiles.map { requireNotNull(it) }, paths.toList())
        }

        private data class Region(val x: Int, val y: Int, val width: Int, val height: Int)

        private fun split(size: Int, random: Random): Int {
            val offset = size / 6
            return (size / 2 + random.nextInt(-offset, offset + 1)).coerceIn(1, size - 1)
        }

        private fun route(region: Region, columns: Int, shape: Int,
                          mirrorX: Boolean, mirrorY: Boolean, reverse: Boolean): IntArray {
            val cells = ArrayList<Int>(region.width * region.height)
            fun add(x: Int, y: Int) {
                val dx = if (mirrorX) region.width - 1 - x else x
                val dy = if (mirrorY) region.height - 1 - y else y
                cells += (region.y + dy) * columns + region.x + dx
            }
            when (shape) {
                0 -> {
                    var left = 0; var top = 0
                    var right = region.width - 1; var bottom = region.height - 1
                    while (left <= right && top <= bottom) {
                        for (x in left..right) add(x, top)
                        top++
                        for (y in top..bottom) add(right, y)
                        right--
                        if (top <= bottom) {
                            for (x in right downTo left) add(x, bottom)
                            bottom--
                        }
                        if (left <= right) {
                            for (y in bottom downTo top) add(left, y)
                            left++
                        }
                    }
                }
                1 -> for (y in 0 until region.height) {
                    if (y % 2 == 0) for (x in 0 until region.width) add(x, y)
                    else for (x in region.width - 1 downTo 0) add(x, y)
                }
                else -> for (x in 0 until region.width) {
                    if (x % 2 == 0) for (y in 0 until region.height) add(x, y)
                    else for (y in region.height - 1 downTo 0) add(x, y)
                }
            }
            if (reverse) cells.reverse()
            return cells.toIntArray()
        }
    }
}
