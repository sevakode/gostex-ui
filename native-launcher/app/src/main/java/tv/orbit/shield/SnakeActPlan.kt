package tv.orbit.shield

import kotlin.math.floor
import kotlin.math.min
import kotlin.random.Random

/** A standalone Snake composition. It never references a painting or a reveal effect. */
internal class SnakeActPlan private constructor(val snakes: List<SnakeTrack>) {
    companion object {
        fun create(seed: Long): SnakeActPlan {
            val random = Random(seed)
            val mirror = random.nextBoolean()
            val accents = if (random.nextBoolean()) listOf(0xff70aaa3.toInt(), 0xff9985ba.toInt())
                else listOf(0xff9a9c70.toInt(), 0xff729aae.toInt())
            val layout = listOf(
                // One large protagonist, with two smaller independent boards and deliberate gaps.
                SnakeRegion(.06, .28, .60, .55),
                SnakeRegion(.70, .09, .24, .22),
                SnakeRegion(.73, .62, .21, .19),
            )
            val sizes = listOf(32 to 16, 28 to 14, 24 to 12)
            val colors = listOf(0xffccff00.toInt()) + accents
            return SnakeActPlan(layout.mapIndexed { index, original ->
                val region = if (mirror) original.copy(left = 1.0 - original.left - original.width) else original
                val (columns, rows) = sizes[index]
                val path = loop(columns, rows, random.nextBoolean(), random.nextBoolean(), random.nextBoolean())
                SnakeTrack(region, columns, rows, path, colors[index],
                    initialStep = random.nextInt(path.size),
                    cellsPerSecond = listOf(4.8, 6.1, 5.3)[index],
                    baseLength = listOf(44, 32, 28)[index],
                    foodStride = listOf(23, 19, 17)[index],
                    growthLimit = 12)
            })
        }

        /** Hamiltonian loop, reserving the first column for the return journey. Even rows
         * guarantee a cardinal edge from the last cell back to the first cell. */
        private fun loop(columns: Int, rows: Int, transpose: Boolean,
                         mirrorX: Boolean, reverse: Boolean): IntArray {
            val w = if (transpose) rows else columns
            val h = if (transpose) columns else rows
            require(w >= 2 && h >= 2 && h % 2 == 0)
            val path = ArrayList<Int>(columns * rows)
            fun add(x: Int, y: Int) {
                val px = if (transpose) y else x
                val py = if (transpose) x else y
                path += py * columns + if (mirrorX) columns - 1 - px else px
            }
            for (x in 0 until w) add(x, 0)
            for (y in 1 until h) {
                if (y % 2 == 1) for (x in w - 1 downTo 1) add(x, y)
                else for (x in 1 until w) add(x, y)
            }
            for (y in h - 1 downTo 1) add(0, y)
            if (reverse) path.reverse()
            return path.toIntArray()
        }
    }
}

internal data class SnakeRegion(val left: Double, val top: Double, val width: Double, val height: Double)

internal class SnakeTrack(
    val region: SnakeRegion,
    val columns: Int,
    val rows: Int,
    val path: IntArray,
    val color: Int,
    val initialStep: Int,
    val cellsPerSecond: Double,
    val baseLength: Int,
    val foodStride: Int,
    val growthLimit: Int,
) {
    // Both head and food repeat together. Wrapping time prevents integer overflow during
    // long sessions without making the head teleport or relocating food mid-approach.
    private val periodSteps = path.size / gcd(path.size, foodStride) * foodStride
    val periodSeconds = periodSteps / cellsPerSecond

    fun travel(elapsedSeconds: Double): Double =
        if (!elapsedSeconds.isFinite() || elapsedSeconds <= 0.0) 0.0
        else (elapsedSeconds % periodSeconds) * cellsPerSecond

    fun headDistance(elapsedSeconds: Double): Double = initialStep + travel(elapsedSeconds)

    fun foodStep(elapsedSeconds: Double): Int {
        val nextMeal = (floor(travel(elapsedSeconds) / foodStride) + 1.0) * foodStride
        return ((initialStep + nextMeal) % path.size).toInt()
    }

    fun bodyLength(elapsedSeconds: Double): Int {
        if (!elapsedSeconds.isFinite() || elapsedSeconds <= 0.0) return baseLength
        val meals = floor(elapsedSeconds * cellsPerSecond / foodStride)
        return baseLength + min(growthLimit.toDouble(), meals * 2.0).toInt()
    }

    private fun gcd(first: Int, second: Int): Int {
        var a = first; var b = second
        while (b != 0) { val rest = a % b; a = b; b = rest }
        return a
    }
}
