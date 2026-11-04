package dev.gostex.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.gostex.core.ContentKind
import kotlin.math.ceil
import kotlin.math.floor

/** Brandbook design tokens, independent of stored user preferences. */
object GostexBrand {
    val Ink = Color(0xFF0A0A0A)
    val Card = Color(0xFF141417)
    val Panel = Color(0xFF1F1F23)
    val Muted = Color(0xFF9E9EA4)
    val Lime = Color(0xFFCCFF00)
    val White = Color(0xFFF5F5F6)
    val Line = Color(0xFF2E2E33)
}

// Exact paths and fill rules from app/src/main/res/drawable/gostex_logo.xml.
// No text/font approximation: the split x and original letterforms remain intact.
private val gostexLogo: ImageVector by lazy {
    ImageVector.Builder("GOSTEX", 100.dp, 34.dp, 100f, 34f).apply {
        addPath(
            pathData = PathParser().parsePathString("M10.52998,7.04799 C11.77954,7.04799 12.85696,7.32745 13.76183,7.88644 C14.66663,8.44540 15.32359,9.18727 15.73293,10.11168 L15.73293,7.43501 L19.77260,7.43501 L19.77260,23.14006 C19.77260,24.60199 19.42802,25.83835 18.73859,26.84882 C18.07072,27.85924 17.12265,28.61163 15.89461,29.10614 C14.68813,29.62210 13.26613,29.88020 11.62876,29.88020 C10.12065,29.88020 8.82798,29.67593 7.75076,29.26744 C6.67356,28.85898 5.80109,28.27835 5.13321,27.52591 C4.48688,26.79497 4.03439,25.94574 3.77586,24.97827 L8.04173,24.68793 C8.25717,25.33289 8.62344,25.83827 9.14048,26.20377 C9.67909,26.56918 10.50852,26.75185 11.62876,26.75185 C12.89986,26.75185 13.88023,26.46181 14.56966,25.88136 C15.25909,25.32236 15.60367,24.44069 15.60367,23.23676 L15.60367,21.04381 C15.19431,21.88225 14.53738,22.55969 13.63255,23.07568 C12.72771,23.57014 11.71503,23.81743 10.59475,23.81743 C9.17279,23.81743 7.90158,23.47330 6.78125,22.78532 C5.68249,22.07587 4.82062,21.09756 4.19582,19.85063 C3.57107,18.58221 3.25871,17.12016 3.25871,15.46477 C3.25873,13.80937 3.56048,12.35812 4.16371,11.11119 C4.78848,9.84285 5.65016,8.85390 6.74886,8.14444 C7.84762,7.41350 9.10806,7.04800 10.52998,7.04799 Z M11.56425,10.27275 C10.29311,10.27275 9.30193,10.74593 8.59097,11.69189 C7.90158,12.61635 7.55696,13.86333 7.55696,15.43271 C7.55696,17.00211 7.91240,18.25992 8.62336,19.20586 C9.35585,20.13026 10.33625,20.59238 11.56425,20.59238 C12.79226,20.59238 13.77239,20.14084 14.50488,19.23790 C15.23737,18.31345 15.61449,17.04510 15.63606,15.43271 C15.65759,14.35776 15.49602,13.44388 15.15129,12.69142 C14.82812,11.91750 14.35417,11.32629 13.72945,10.91783 C13.12621,10.48785 12.40443,10.27280 11.56425,10.27275 Z").toNodes(),
            fill = SolidColor(GostexBrand.Lime), pathFillType = PathFillType.EvenOdd
        )
        addPath(
            pathData = PathParser().parsePathString("M97.24126,27.65683 L92.68469,27.65683 L88.80676,21.78745 L84.86404,27.65683 L80.37187,27.65683 L86.35052,19.20749 L91.31747,19.20749 L97.24126,27.65683 Z").toNodes(),
            fill = SolidColor(GostexBrand.Lime), pathFillType = PathFillType.NonZero
        )
        addPath(
            pathData = PathParser().parsePathString("M29.86691,7.04799 C31.56891,7.04800 33.05545,7.42433 34.32655,8.17680 C35.59766,8.90775 36.57802,9.95042 37.26746,11.30486 C37.97841,12.63780 38.33385,14.21806 38.33385,16.04544 C38.33385,17.85135 37.97841,19.43159 37.26746,20.78604 C36.57802,22.14043 35.59763,23.19371 34.32655,23.94617 C33.05545,24.67712 31.56891,25.04262 29.86691,25.04265 C28.16489,25.04265 26.66756,24.67715 25.37485,23.94617 C24.10376,23.19371 23.11256,22.14047 22.40158,20.78604 C21.71217,19.43159 21.36760,17.85135 21.36760,16.04544 C21.36760,14.21806 21.71217,12.63780 22.40158,11.30486 C23.11256,9.95042 24.10376,8.90775 25.37485,8.17680 C26.66753,7.42433 28.16489,7.04799 29.86691,7.04799 Z M29.86691,10.40176 C28.53116,10.40176 27.49687,10.90715 26.76434,11.91762 C26.03186,12.90656 25.66556,14.28259 25.66556,16.04544 C25.66556,17.82977 26.03189,19.21642 26.76434,20.20537 C27.49687,21.19431 28.53116,21.68884 29.86691,21.68884 C31.20264,21.68884 32.22583,21.19431 32.93680,20.20537 C33.66933,19.21642 34.03558,17.82982 34.03558,16.04544 C34.03558,14.28252 33.66933,12.90656 32.93680,11.91762 C32.22583,10.90715 31.20264,10.40181 29.86691,10.40176 Z").toNodes(),
            fill = SolidColor(GostexBrand.Lime), pathFillType = PathFillType.EvenOdd
        )
        addPath(
            pathData = PathParser().parsePathString("M46.63194,7.04799 C48.18320,7.04800 49.48676,7.30608 50.54227,7.82207 C51.59814,8.31654 52.41664,9.00456 52.99812,9.88600 C53.60170,10.74593 53.97855,11.73479 54.12946,12.85275 L49.96040,13.04611 C49.85294,12.46570 49.65900,11.96051 49.37892,11.53056 C49.09885,11.10059 48.72162,10.77809 48.24759,10.56311 C47.77356,10.32663 47.21342,10.20837 46.56717,10.20837 C45.59778,10.20842 44.86502,10.40184 44.36966,10.78882 C43.87430,11.17575 43.62624,11.69174 43.62624,12.33669 C43.62624,12.78815 43.73445,13.16447 43.94974,13.46548 C44.18675,13.76645 44.56361,14.01375 45.08069,14.20723 C45.59778,14.37921 46.28709,14.54047 47.14902,14.69098 C48.95862,14.97046 50.38069,15.33594 51.41486,15.78741 C52.47037,16.21742 53.21379,16.77635 53.64474,17.46432 C54.09706,18.13078 54.32339,18.93701 54.32339,19.88295 C54.32339,20.97943 54.01094,21.91467 53.38602,22.68863 C52.78282,23.46259 51.92090,24.05374 50.80061,24.46220 C49.70204,24.84918 48.40915,25.04265 46.92268,25.04265 C45.22092,25.04262 43.78818,24.79561 42.62483,24.30118 C41.48283,23.80670 40.59961,23.11865 39.97479,22.23724 C39.35004,21.35576 38.99432,20.35587 38.90813,19.23790 L43.14154,19.04451 C43.29243,19.94748 43.66968,20.64630 44.27287,21.14076 C44.87607,21.63524 45.77000,21.88251 46.95508,21.88251 C47.92445,21.88251 48.67856,21.73181 49.21735,21.43085 C49.77750,21.10834 50.05757,20.61382 50.05757,19.94733 C50.05757,19.56039 49.96078,19.23782 49.76683,18.97987 C49.57288,18.72191 49.20630,18.49623 48.66788,18.30276 C48.12946,18.10925 47.33231,17.91583 46.27643,17.72232 C44.50989,17.42136 43.12021,17.05586 42.10776,16.62589 C41.09503,16.17442 40.37334,15.61542 39.94241,14.94897 C39.53308,14.28252 39.32838,13.45464 39.32838,12.46570 C39.32838,10.85327 39.94249,9.55249 41.17038,8.56355 C42.42021,7.55316 44.24048,7.04799 46.63194,7.04799 Z").toNodes(),
            fill = SolidColor(GostexBrand.Lime), pathFillType = PathFillType.NonZero
        )
        addPath(
            pathData = PathParser().parsePathString("M74.43817,7.04799 C76.07555,7.04800 77.50830,7.41354 78.73640,8.14444 C79.98587,8.87543 80.94496,9.92889 81.61256,11.30486 C82.28055,12.68080 82.61472,14.33627 82.61472,16.27115 L82.61472,17.20630 L70.36630,17.20630 C70.45242,18.71123 70.86205,19.85074 71.59442,20.62469 C72.34851,21.37717 73.33963,21.75349 74.56773,21.75349 C75.49406,21.75349 76.25882,21.54922 76.86203,21.14076 C77.48695,20.71078 77.91754,20.11938 78.15454,19.36691 L82.38800,19.62492 C81.91435,21.30180 80.97697,22.62399 79.57700,23.59145 C78.19800,24.55890 76.51718,25.04265 74.53535,25.04265 C72.81187,25.04265 71.31435,24.67712 70.04318,23.94617 C68.77200,23.19371 67.79156,22.14043 67.10223,20.78604 C66.41293,19.43159 66.06845,17.85135 66.06845,16.04544 C66.06845,14.23958 66.41293,12.67009 67.10223,11.33717 C67.79156,9.98277 68.76132,8.92923 70.01077,8.17680 C71.28195,7.42430 72.75775,7.04799 74.43817,7.04799 Z M74.43817,10.30507 C73.33963,10.30507 72.42397,10.65984 71.69159,11.36923 C70.98056,12.07869 70.53854,13.09999 70.36630,14.43294 L78.34888,14.43294 C78.26239,13.03557 77.86380,12.00366 77.15315,11.33717 C76.44211,10.64923 75.53712,10.30507 74.43817,10.30507 Z").toNodes(),
            fill = SolidColor(GostexBrand.Lime), pathFillType = PathFillType.EvenOdd
        )
        addPath(
            pathData = PathParser().parsePathString("M61.37965,7.43501 L65.93623,7.43501 L65.93623,10.62749 L61.37965,10.62749 L61.37965,19.33487 C61.37965,20.13026 61.55190,20.68927 61.89674,21.01175 C62.24121,21.31271 62.78000,21.46318 63.51239,21.46318 L65.93623,21.46318 L65.93623,24.65587 L62.25226,24.65587 C60.52840,24.65587 59.25722,24.25790 58.43872,23.46243 C57.64157,22.66696 57.24300,21.41998 57.24300,19.72162 L57.24300,10.62749 L54.52841,10.62749 L54.52841,7.43501 L57.24300,7.43501 L57.24300,3.40387 L61.37965,3.40387 L61.37965,7.43501 Z").toNodes(),
            fill = SolidColor(GostexBrand.Lime), pathFillType = PathFillType.NonZero
        )
        addPath(
            pathData = PathParser().parsePathString("M88.80676,10.80457 L92.68469,4.93518 L97.24126,4.93518 L91.31747,13.38426 L86.35052,13.38426 L80.37187,4.93518 L84.86404,4.93518 L88.80676,10.80457 Z").toNodes(),
            fill = SolidColor(GostexBrand.Lime), pathFillType = PathFillType.NonZero
        )
    }.build()
}

@Composable
fun GostexWordmark(modifier: Modifier = Modifier) {
    Image(imageVector = gostexLogo, contentDescription = "GOSTEX", modifier = modifier)
}

/** Static brandbook grid: no animation clock, random allocation, or background work. */
@Composable
fun PixelGrid(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cell = 22.dp.toPx()
        val columns = ceil(size.width / cell).toInt()
        val rows = ceil(size.height / cell).toInt()
        val line = GostexBrand.White.copy(alpha = 0.025f)
        for (column in 0..columns) {
            drawRect(line, Offset(column * cell, 0f), Size(1f, size.height))
        }
        for (row in 0..rows) {
            drawRect(line, Offset(0f, row * cell), Size(size.width, 1f))
        }
        for (row in 0 until rows) for (column in 0 until columns) {
            val seed = (column * 73 + row * 37) % 47
            if (seed == 5 || seed == 19) {
                val block = if (seed == 5) cell - 2f else cell * 0.22f
                drawRect(GostexBrand.Lime.copy(alpha = if (seed == 5) 0.045f else 0.11f),
                    Offset(column * cell + 1f, row * cell + 1f), Size(block, block))
            }
        }
    }
}

private val terminalPixels = listOf(
    "1111111111111111",
    "1000000000000001",
    "1111111111111111",
    "1000000000000001",
    "1010000000000001",
    "1001000000000001",
    "1000100000000001",
    "1001000000000001",
    "1010000111100001",
    "1000000000000001",
    "1000000000000001",
    "1111111111111111"
)
private val gamepadPixels = listOf(
    "0000111111110000",
    "0011111111111100",
    "0111111111111110",
    "0111011111110110",
    "0110001111101010",
    "0111011111110110",
    "0111111111111110",
    "1111111111111111",
    "1111110000111111",
    "1111100000011111",
    "1111000000001111",
    "0110000000000110"
)
private val mediaPixels = listOf(
    "1111111111111111",
    "1000000000000001",
    "1000001000000001",
    "1000001100000001",
    "1000001110000001",
    "1000001111000001",
    "1000001110000001",
    "1000001100000001",
    "1000001000000001",
    "1000000000000001",
    "1111111111111111",
    "0001100000011000"
)

/** A content-kind glyph, never a substitute landscape or franchise illustration. */
@Composable
fun PixelAppGlyph(kind: ContentKind, modifier: Modifier = Modifier, color: Color = GostexBrand.Lime) {
    val pixels = when (kind) {
        ContentKind.GAME -> gamepadPixels
        ContentKind.APP -> terminalPixels
        ContentKind.MEDIA -> mediaPixels
    }
    Canvas(modifier.size(64.dp, 48.dp)) {
        val unit = floor(minOf(size.width / 16, size.height / 12)).coerceAtLeast(1f)
        val origin = Offset((size.width - unit * 16) / 2, (size.height - unit * 12) / 2)
        pixels.forEachIndexed { row, cells -> cells.forEachIndexed { column, cell ->
            if (cell == '1') drawRect(color, origin + Offset(column * unit, row * unit), Size(unit, unit))
        } }
    }
}

/** Eight-dp square steps make focus visible without a rounded card outline. */
fun Modifier.pixelFocus(focused: Boolean): Modifier = drawWithContent {
    drawContent()
    if (focused) {
        val step = 8.dp.toPx()
        val thickness = 2.dp.toPx()
        val color = GostexBrand.Lime
        fun bracket(right: Boolean, bottom: Boolean) {
            fun block(x: Float, y: Float, width: Float, height: Float) {
                drawRect(color, Offset(if (right) size.width - x - width else x,
                    if (bottom) size.height - y - height else y), Size(width, height))
            }
            block(step, 0f, step * 2, thickness)
            block(step - thickness, thickness, thickness, step - thickness)
            block(0f, step, step, thickness)
            block(0f, step, thickness, step * 2)
        }
        bracket(right = false, bottom = false)
        bracket(right = true, bottom = false)
        bracket(right = false, bottom = true)
        bracket(right = true, bottom = true)
    }
}

private val digitPixels = mapOf(
    '0' to listOf("111", "101", "101", "101", "111"),
    '1' to listOf("010", "110", "010", "010", "111"),
    '2' to listOf("111", "001", "111", "100", "111"),
    '3' to listOf("111", "001", "111", "001", "111"),
    '4' to listOf("101", "101", "111", "001", "001"),
    '5' to listOf("111", "100", "111", "001", "111"),
    '6' to listOf("111", "100", "111", "101", "111"),
    '7' to listOf("111", "001", "010", "010", "010"),
    '8' to listOf("111", "101", "111", "101", "111"),
    '9' to listOf("111", "101", "111", "001", "111"),
    ':' to listOf("000", "010", "000", "010", "000"),
    '/' to listOf("001", "001", "010", "100", "100"),
    '-' to listOf("000", "000", "111", "000", "000"),
    '.' to listOf("000", "000", "000", "000", "010")
)

/** Small numeric accents; body text remains real localized text with normal typography. */
@Composable
fun PixelDigits(value: String, modifier: Modifier = Modifier, color: Color = GostexBrand.Lime) {
    val columns = maxOf(1, value.length * 4 - 1)
    Canvas(modifier.size((columns * 3).dp, 15.dp).semantics { contentDescription = value }) {
        val unit = floor(minOf(size.width / columns, size.height / 5)).coerceAtLeast(1f)
        val origin = Offset((size.width - columns * unit) / 2, (size.height - 5 * unit) / 2)
        value.forEachIndexed { index, character ->
            digitPixels[character]?.forEachIndexed { row, cells -> cells.forEachIndexed { column, cell ->
                if (cell == '1') drawRect(color,
                    origin + Offset((index * 4 + column) * unit, row * unit), Size(unit, unit))
            } }
        }
    }
}
