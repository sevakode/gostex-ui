package tv.orbit.shield

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Independent, reproducible worlds. Render once per picture; never animate on this canvas. */
internal object WeeklyLandscape {
    private const val W = 320f
    private const val H = 180f

    fun render(seed: Long, width: Int = 320, height: Int = 180, world: ArtWorld = ArtWorld.TETROMINO): Bitmap {
        require(width > 0 && height > 0) { "Artwork dimensions must be positive" }
        if (world == ArtWorld.SNAKE) return SnakeActRenderer(seed).renderSnapshot(width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(width / W, height / H)
        val scene = World(canvas, Random(seed))
        when (world) {
            ArtWorld.HYRULE -> scene.hyrule()
            ArtWorld.TATOOINE -> scene.tatooine()
            ArtWorld.GOTHAM -> scene.gotham()
            ArtWorld.TETROMINO -> scene.arcade()
            ArtWorld.LONDON -> scene.london()
            ArtWorld.B612 -> scene.asteroid()
            ArtWorld.TRANQUILITY -> scene.tranquility()
            ArtWorld.SNAKE -> error("Snake snapshots are rendered independently")
        }
        return bitmap
    }

    private class World(val canvas: Canvas, val random: Random) {
        private val paint = Paint().apply { isAntiAlias = false; isDither = false }

        private fun color(rgb: Int, alpha: Int = 255) {
            paint.color = rgb or (0xFF shl 24)
            paint.alpha = alpha
            paint.style = Paint.Style.FILL
        }
        private fun rect(x: Float, y: Float, w: Float, h: Float, rgb: Int, alpha: Int = 255) {
            color(rgb, alpha)
            canvas.drawRect(x, y, x + w, y + h, paint)
        }
        private fun poly(rgb: Int, vararg p: Float, alpha: Int = 255) {
            val path = Path().apply {
                moveTo(p[0], p[1])
                for (i in 2 until p.size step 2) lineTo(p[i], p[i + 1])
                close()
            }
            color(rgb, alpha)
            canvas.drawPath(path, paint)
        }
        private fun oval(x: Float, y: Float, w: Float, h: Float, rgb: Int, alpha: Int = 255) {
            color(rgb, alpha)
            canvas.drawOval(x, y, x + w, y + h, paint)
        }
        private fun line(x: Float, y: Float, x2: Float, y2: Float, rgb: Int, alpha: Int = 255) {
            color(rgb, alpha)
            paint.strokeWidth = 1f
            canvas.drawLine(x, y, x2, y2, paint)
        }
        /** Shade only inside the original disk; no second overlapping planet outside its rim. */
        private fun diskShadow(x: Int, y: Int, diameter: Int, offset: Float, tint: Int, alpha: Int = 255) {
            val r = diameter / 2f
            for (py in 0 until diameter) for (px in 0 until diameter) {
                val dx = px + .5f - r
                val dy = py + .5f - r
                if (dx * dx + dy * dy <= r * r &&
                    (dx - offset) * (dx - offset) + dy * dy <= r * r) {
                    rect((x + px).toFloat(), (y + py).toFloat(), 1f, 1f, tint, alpha)
                }
            }
        }
        private fun gradient(top: Int, bottom: Int, from: Int = 0, until: Int = 180) {
            for (y in from until until) rect(0f, y.toFloat(), W, 1f,
                blend(top, bottom, (y - from).toFloat() / (until - from)))
        }
        private fun stars(count: Int, maxY: Int, tint: Int, opacity: Int = 130) {
            repeat(count) {
                rect(random.nextInt(5, 315).toFloat(), random.nextInt(4, maxY).toFloat(),
                    1f, 1f, tint, random.nextInt(opacity / 3, opacity))
            }
        }
        private fun cloud(x: Float, y: Float, length: Float, tint: Int, alpha: Int = 30) {
            rect(x + 8, y, length - 17, 2f, tint, alpha / 2)
            rect(x, y + 2, length, 3f, tint, alpha)
            rect(x + 5, y + 5, length - 11, 2f, tint, alpha / 2)
        }
        private fun windows(x: Float, y: Float, w: Float, h: Float, stepX: Int, stepY: Int,
                            tint: Int, alpha: Int = 180) {
            for (wy in y.toInt() until (y + h).toInt() step stepY) {
                for (wx in x.toInt() until (x + w).toInt() step stepX) {
                    if (random.nextInt(100) < 62) rect(wx.toFloat(), wy.toFloat(), 1f, 2f,
                        tint, random.nextInt((alpha / 2).coerceAtLeast(1), alpha))
                }
            }
        }

        /** Hyrule's forest sanctuary: a distant castle, ruined arches and the Master Sword. */
        fun hyrule() {
            val leaf = if (random.nextBoolean()) 0x365942 else 0x405E3B
            gradient(0x537979, 0xD2BD8F, 0, 116)
            oval(85f, 59f, 16f, 16f, 0xF2DEAD)
            poly(0x718D87, 41f, 101f, 76f, 78f, 104f, 87f, 137f, 66f,
                165f, 88f, 199f, 76f, 222f, 111f, 222f, 139f, 41f, 139f)
            poly(0x567767, 25f, 117f, 81f, 101f, 115f, 107f, 150f, 95f,
                184f, 105f, 218f, 96f, 253f, 126f, 253f, 150f, 25f, 150f)
            // Castle silhouette occupies the same distant opening as the prepared painting.
            rect(125f, 72f, 31f, 13f, 0x536B79)
            for ((x, top) in listOf(126f to 65f, 136f to 57f, 148f to 63f)) {
                rect(x, top, 5f, 20f, 0x4E6375)
                poly(0x465D70, x - 2, top, x + 2.5f, top - 9, x + 7, top)
                rect(x + 2, top + 4, 1f, 3f, 0xC1B38F, 150)
            }
            rect(139f, 51f, 1f, 8f, 0x425D70)
            poly(0xAF936E, 140f, 52f, 147f, 54f, 140f, 56f)
            // An old viaduct spans the valley; open arches expose the distant green below.
            rect(92f, 102f, 93f, 4f, 0x9DAB8C)
            for (x in 96..181 step 13) rect(x.toFloat(), 106f, 4f, 12f, 0x8C9D81)
            gradient(0x405B41, 0x142D2C, 127, 180)
            poly(0x708069, 101f, 180f, 139f, 145f, 176f, 127f, 218f, 126f,
                247f, 180f)
            // Right temple wall has an open pointed arch, rather than an unrelated landscape prop.
            rect(201f, 27f, 119f, 108f, 0x576552)
            rect(207f, 33f, 113f, 93f, 0x66715B)
            poly(0x354C43, 225f, 114f, 225f, 68f, 239f, 46f, 253f, 68f,
                253f, 114f)
            poly(0x839074, 218f, 114f, 218f, 66f, 239f, 36f, 260f, 66f,
                260f, 114f, 253f, 114f, 253f, 68f, 239f, 46f, 225f, 68f, 225f, 114f)
            for (y in 38..125 step 10) {
                line(204f, y.toFloat(), 218f, y.toFloat(), 0x354C43, 110)
                line(261f, y.toFloat(), 320f, y.toFloat(), 0x354C43, 100)
            }
            for (x in listOf(202, 269, 304)) {
                rect(x.toFloat(), 27f, 5f, 109f, 0x879178, 130)
                rect(x + 5f, 27f, 3f, 109f, 0x273F36, 120)
            }
            fun triangle(x: Float, y: Float, size: Float, tint: Int) {
                poly(tint, x, y + size, x + size / 2, y, x + size, y + size)
            }
            triangle(234f, 37f, 8f, 0xCAB876)
            triangle(230f, 45f, 8f, 0xCAB876)
            triangle(238f, 45f, 8f, 0xCAB876)
            // Broken left columns and a large tree frame the castle, with no star-like particles.
            rect(66f, 82f, 9f, 58f, 0x707B64)
            rect(63f, 80f, 16f, 5f, 0x8E957B)
            rect(72f, 86f, 2f, 52f, 0xA2A78B, 115)
            rect(176f, 66f, 9f, 65f, 0x7C866B)
            poly(0x7C866B, 174f, 69f, 185f, 52f, 199f, 48f, 199f, 54f, 188f, 59f, 182f, 71f)
            poly(0x243C31, 0f, 0f, 39f, 0f, 35f, 55f, 27f, 111f, 45f, 157f,
                14f, 165f, 0f, 145f)
            poly(0x3E4B35, 14f, 0f, 27f, 0f, 23f, 54f, 17f, 107f,
                29f, 147f, 20f, 143f, 10f, 108f)
            poly(0x2A4130, 24f, 40f, 58f, 18f, 109f, 9f, 166f, 0f, 166f, 9f,
                110f, 20f, 61f, 30f, 24f, 55f)
            repeat(74) {
                val x = random.nextInt(0, 218).toFloat()
                val y = random.nextInt(0, 40).toFloat()
                val h = random.nextInt(4, 12).toFloat()
                rect(x, y, random.nextInt(5, 16).toFloat(), h, leaf)
                rect(x + 2, y, 4f, 2f, 0x84945D, 100)
            }
            // Steps, stone pedestal and a recognizable blue-violet crossguard.
            for (step in 0..3) {
                rect(180f - step * 8, 139f + step * 7, 81f + step * 16, 7f, blend(0x869071, 0x3D5142, step / 4f))
                rect(180f - step * 8, 139f + step * 7, 81f + step * 16, 1f, 0xB1B28D, 130)
            }
            poly(0x929B7D, 201f, 123f, 236f, 123f, 244f, 138f, 194f, 138f)
            rect(195f, 138f, 48f, 6f, 0x586C54)
            triangle(215f, 128f, 5f, 0x465D4B)
            triangle(212.5f, 133f, 5f, 0x465D4B)
            triangle(217.5f, 133f, 5f, 0x465D4B)
            poly(0xDDE4CB, 218f, 91f, 222f, 91f, 222f, 121f, 220f, 127f, 218f, 121f)
            rect(221f, 94f, 1f, 26f, 0xA4BFB8)
            rect(218f, 79f, 4f, 12f, 0x656092)
            rect(217f, 77f, 6f, 3f, 0x8174AA)
            poly(0x7465A0, 218f, 90f, 213f, 86f, 206f, 91f, 205f, 96f,
                213f, 91f, 219f, 95f, 221f, 95f, 227f, 91f, 235f, 96f,
                234f, 91f, 227f, 86f, 222f, 90f)
            poly(0xC7B77A, 217f, 92f, 220f, 96f, 223f, 92f, 220f, 90f)
            // Low foliage anchors the sanctuary and leaves the bottom caption region subdued.
            for (i in 0..50) {
                val x = random.nextInt(0, 320).toFloat()
                val y = random.nextInt(160, 180).toFloat()
                rect(x, y, random.nextInt(4, 14).toFloat(), 3f, leaf, 170)
            }
        }

        /** Tatooine: twin suns above dry mesas, domed homes and two droids on a terrace. */
        fun tatooine() {
            val sand = if (random.nextBoolean()) 0xB17F5C else 0xB68760
            gradient(0x78516F, 0xE2A56F, 0, 102)
            oval(192f, 47f, 22f, 22f, 0xF4CE83)
            oval(222f, 30f, 13f, 13f, 0xEEC283)
            cloud(174f, 61f, 54f, 0xA06A70, 95)
            // The two irregular mesas and dry valley belong to a desert world.
            poly(0x855D63, 0f, 89f, 12f, 76f, 26f, 74f, 34f, 49f, 54f, 47f,
                59f, 54f, 80f, 54f, 89f, 70f, 102f, 74f, 116f, 91f, 144f, 100f,
                0f, 115f)
            poly(0xA27465, 0f, 100f, 43f, 84f, 67f, 89f, 104f, 88f, 124f, 101f,
                170f, 88f, 205f, 95f, 239f, 86f, 278f, 99f, 320f, 91f, 320f, 180f, 0f, 180f)
            poly(sand, 0f, 127f, 42f, 116f, 95f, 119f, 139f, 103f, 193f, 109f,
                243f, 101f, 293f, 118f, 320f, 113f, 320f, 180f, 0f, 180f)
            for (i in 0..15) {
                val x = random.nextInt(100, 290).toFloat()
                val y = random.nextInt(95, 137).toFloat()
                line(x, y, x + random.nextInt(9, 30), y - 2, 0xD9AE7A, 65)
            }
            // A low sail barge silhouette supplies a distant reference without taking over the scene.
            poly(0x8D6865, 245f, 86f, 259f, 86f, 256f, 90f, 248f, 90f)
            line(252f, 82f, 252f, 87f, 0x8D6865)
            poly(0xA87968, 246f, 82f, 259f, 81f, 254f, 85f)
            fun dome(x: Float, base: Float, w: Float, h: Float, tint: Int) {
                oval(x, base - h, w, h * 1.4f, tint)
                rect(x, base - h * .3f, w, h * .5f, tint)
                rect(x + w - 4, base - h * .3f, 4f, h * .5f, 0x76565A, 90)
                oval(x + w * .36f, base - h * .31f, w * .23f, h * .48f, 0x60484E)
                rect(x + w * .36f, base - h * .06f, w * .23f, h * .25f, 0x60484E)
                rect(x + w * .4f, base - h * .17f, w * .13f, h * .28f, 0xD59D5A, 150)
            }
            dome(84f, 111f, 30f, 17f, 0xC09773)
            dome(124f, 110f, 26f, 15f, 0xBD906B)
            dome(44f, 111f, 40f, 24f, 0xC29D79)
            dome(-8f, 115f, 49f, 37f, 0xD0AE82)
            dome(17f, 144f, 56f, 31f, 0xB48E6C)
            dome(83f, 145f, 51f, 26f, 0xC29A72)
            // Moisture vaporators: a repeated piece of the settlement, not an arbitrary skyline.
            for ((px, py, height) in listOf(Triple(20f, 81f, 38f), Triple(77f, 109f, 27f), Triple(147f, 121f, 31f))) {
                rect(px, py - height, 2f, height, 0x6F6764)
                rect(px - 3, py - height + 10, 8f, 11f, 0xAAA08A)
                rect(px - 5, py - height + 14, 12f, 2f, 0x6D6866)
                rect(px - 1, py - height - 4, 4f, 5f, 0xB6AA91)
            }
            // Terrace and canopy frame the right-hand droids, matching their image anchors.
            poly(0x866454, 167f, 180f, 212f, 137f, 320f, 128f, 320f, 180f)
            line(212f, 137f, 320f, 128f, 0xD7AB77, 160)
            rect(307f, 19f, 13f, 114f, 0xA68363)
            rect(302f, 24f, 4f, 106f, 0x725350)
            poly(0x754F4B, 257f, 18f, 281f, 21f, 306f, 5f, 320f, 0f, 320f, 17f,
                281f, 30f, 258f, 22f)
            rect(258f, 20f, 2f, 103f, 0x6E5452)
            line(260f, 22f, 301f, 30f, 0x3E3B42, 130)
            poly(0x614C48, 237f, 134f, 257f, 131f, 289f, 145f, 277f, 150f)
            // R2-D2's cylindrical white/blue body and three feet.
            oval(235f, 101f, 14f, 12f, 0x778FA2)
            rect(235f, 107f, 14f, 23f, 0xD0C8AD)
            rect(240f, 103f, 4f, 6f, 0x385473)
            rect(245f, 105f, 2f, 2f, 0x293C51)
            rect(235f, 111f, 14f, 2f, 0x405D7A)
            rect(240f, 117f, 7f, 3f, 0x3F5A77)
            rect(240f, 123f, 4f, 5f, 0x697E89)
            rect(232f, 114f, 4f, 18f, 0xB7B6A6)
            rect(249f, 114f, 4f, 18f, 0xA9AEA4)
            rect(230f, 131f, 8f, 4f, 0xD3C9AE)
            rect(247f, 131f, 8f, 4f, 0xD3C9AE)
            rect(240f, 129f, 4f, 7f, 0xBDB59F)
            // C-3PO: gold humanoid silhouette, ringed eyes and slim articulated limbs.
            oval(269f, 76f, 8f, 11f, 0xC4A464)
            rect(269f, 80f, 8f, 6f, 0xB99557)
            rect(270f, 80f, 2f, 2f, 0x6D614B)
            rect(274f, 80f, 2f, 2f, 0x6D614B)
            rect(272f, 87f, 3f, 3f, 0x9B804E)
            poly(0xB69B60, 266f, 90f, 279f, 90f, 279f, 104f, 275f, 108f, 269f, 107f, 266f, 103f)
            rect(270f, 95f, 7f, 6f, 0x8D784E)
            rect(268f, 108f, 9f, 6f, 0x947B4D)
            poly(0xB69B60, 265f, 91f, 267f, 93f, 262f, 103f, 265f, 111f,
                262f, 112f, 258f, 103f)
            poly(0xC2A76B, 279f, 91f, 282f, 94f, 286f, 104f, 279f, 111f,
                277f, 109f, 282f, 103f)
            rect(268f, 114f, 4f, 20f, 0xB29A65)
            rect(275f, 114f, 4f, 20f, 0x9A8559)
            rect(267f, 122f, 6f, 2f, 0x776948)
            rect(274f, 122f, 6f, 2f, 0x776948)
            rect(266f, 134f, 7f, 3f, 0xB59D69)
            rect(275f, 134f, 7f, 3f, 0xA38E60)
            poly(0x4F4446, 0f, 163f, 55f, 153f, 96f, 165f, 147f, 159f,
                170f, 180f, 0f, 180f, alpha = 160)
        }

        /** A geometric, impossible arcade city: the architecture itself is made of tetrominoes. */
        fun arcade() {
            val variant = random.nextBoolean()
            val sky = if (variant) 0x100C25 else 0x061726
            val haze = if (variant) 0x332343 else 0x173C45
            val mint = if (variant) 0x79C3BB else 0x81B9D1
            gradient(sky, haze, 0, 110)
            gradient(0x121B2C, 0x070D19, 110, 180)
            stars(75, 90, 0xB4ADC7, 95)
            // A broken orbital ring and tiny moons supply scale around the suspended pieces.
            oval(133f, 19f, 85f, 59f, mint, 11)
            oval(135f, 20f, 81f, 56f, sky, 235)
            oval(72f, 29f, 11f, 11f, 0xA6A0BA, 100)
            rect(72f, 33f, 8f, 2f, sky, 100)
            // The receding floor is restrained, with the brightest detail away from captions.
            for (x in -130..470 step 35) line(160f, 104f, x.toFloat(), 180f, mint, 28)
            for (y in listOf(111, 118, 128, 142, 164)) line(0f, y.toFloat(), W, y.toFloat(), mint, 25)
            line(0f, 108f, W, 108f, mint, 55)
            // Distant low-contrast blocks are an arcade skyline, not anonymous mountains.
            repeat(24) { i ->
                val x = i * 14f - 4f
                val h = random.nextInt(8, 36).toFloat()
                rect(x, 106 - h, 10f, h, blend(haze, 0x51657A, .22f))
                for (y in (107 - h).toInt()..103 step 6) rect(x + 2, y.toFloat(), 6f, 1f, mint, 22)
            }
            fun block(x: Float, y: Float, size: Float, rgb: Int) {
                val d = size * .25f
                poly(blend(rgb, 0x030B18, .45f), x + size, y, x + size + d, y - d,
                    x + size + d, y + size - d, x + size, y + size)
                poly(blend(rgb, 0xDBE9DB, .24f), x, y, x + d, y - d,
                    x + size + d, y - d, x + size, y)
                rect(x, y, size, size, rgb)
                rect(x + 1, y + 1, size - 2, 1f, 0xD7E4DD, 100)
                rect(x + 1, y + 2, 1f, size - 3, 0xD7E4DD, 65)
                rect(x + 2, y + size - 2, size - 3, 1f, 0x091528, 95)
                rect(x + size - 2, y + 2, 1f, size - 3, 0x091528, 100)
            }
            fun piece(x: Float, y: Float, size: Float, rgb: Int, vararg cells: Int) {
                for (i in cells.indices step 2) block(x + cells[i] * size, y + cells[i + 1] * size, size, rgb)
            }
            // All seven pieces retain exactly four squares and are separated enough to read.
            piece(32f, 88f, 14f, 0x527BA8, 0, 0, 0, 1, 1, 1, 2, 1)
            piece(101f, 106f, 12f, 0xB98F5A, 0, 0, 0, 1, 0, 2, 1, 2)
            piece(249f, 92f, 13f, 0xB2AB69, 0, 0, 1, 0, 0, 1, 1, 1)
            piece(208f, 118f, 11f, 0x639F8C, 1, 0, 2, 0, 0, 1, 1, 1)
            piece(282f, 47f, 10f, 0x659CA9, 0, 0, 0, 1, 0, 2, 0, 3)
            piece(66f, 54f, 9f, 0xB37286, 0, 0, 1, 0, 1, 1, 2, 1)
            oval(147f, 124f, 52f, 7f, 0x070B18, 150)
            piece(145f, 52f, 15f, 0x9C83B4, 0, 0, 1, 0, 2, 0, 1, 1)
            // Quiet vertical light shafts connect the centre piece to its future landing place.
            rect(147f, 88f, 43f, 38f, 0xA9A4C6, 5)
            rect(162f, 104f, 13f, 22f, 0xA9A4C6, 8)
            rect(153f, 135f, 39f, 1f, mint, 38)
        }

        /** London at midnight: cobbles, Victorian houses, a clock tower, and a blue police box. */
        fun london() {
            val window = if (random.nextBoolean()) 0xC8A16A else 0xCEAA84
            gradient(0x0B1428, 0x53616A, 0, 112)
            gradient(0x23303A, 0x0C1522, 112, 180)
            stars(28, 56, 0xC4C9CE, 100)
            oval(173f, 18f, 21f, 21f, 0xD5D9CA, 175)
            diskShadow(173, 18, 21, 7f, 0x19263A)
            repeat(6) { cloud(random.nextInt(45, 240).toFloat(), random.nextInt(30, 75).toFloat(),
                random.nextInt(28, 65).toFloat(), 0xB6BEBC, 18) }
            // The clock tower sits at the vanishing point, framed by two independent street walls.
            rect(158f, 65f, 18f, 49f, 0x444D55)
            rect(160f, 50f, 14f, 20f, 0x586064)
            poly(0x3C4852, 158f, 52f, 167f, 35f, 176f, 52f)
            rect(166f, 31f, 2f, 9f, 0x4E5A61)
            rect(157f, 69f, 20f, 2f, 0x81827A)
            oval(161f, 55f, 11f, 11f, window, 210)
            line(166f, 57f, 166f, 61f, 0x35434D)
            line(166f, 61f, 169f, 62f, 0x35434D)
            for (x in 160..174 step 4) rect(x.toFloat(), 74f, 1f, 36f, 0x8A887C, 80)
            for (i in 0..7) {
                val x = 110f + i * 8
                val h = random.nextInt(10, 24).toFloat()
                rect(x, 111 - h, 7f, h, 0x34434E)
                rect(x + 3, 108 - h, 2f, 4f, 0x34434E)
            }
            // Left terrace, strongly foreshortened roof and warm sash windows.
            poly(0x19212C, 0f, 34f, 56f, 49f, 122f, 92f, 122f, 124f, 0f, 168f)
            poly(0x101A29, 0f, 23f, 61f, 43f, 128f, 91f, 122f, 95f, 52f, 57f, 0f, 43f)
            rect(20f, 26f, 7f, 15f, 0x15202B)
            rect(47f, 36f, 6f, 13f, 0x15202B)
            for (i in 0..6) {
                val x = 5f + i * 17
                val top = 50f + i * 7
                val size = 10f - i * .8f
                for (row in 0..2) {
                    val y = top + row * (24f - i * 1.4f)
                    rect(x, y, size, 13f - i, 0x080F1B)
                    rect(x + 1, y + 1, size - 2, 10f - i, window, if ((i + row) % 3 == 0) 130 else 60)
                    line(x + size / 2, y, x + size / 2, y + 11 - i, 0x182330)
                    rect(x - 1, y + 12 - i, size + 2, 1f, 0x59606A, 140)
                }
                line(x + size + 3, top - 4, x + size + 3, 155 - i * 5f, 0x56606A, 75)
            }
            // Right brick frontage leaves the TARDIS prominent on its own pavement.
            poly(0x242932, 229f, 76f, 274f, 44f, 320f, 24f, 320f, 169f, 219f, 126f)
            poly(0x101B2A, 221f, 78f, 274f, 34f, 320f, 12f, 320f, 31f, 278f, 49f, 229f, 85f)
            for (i in 0..4) {
                val x = 232f + i * 19
                val top = 81f - i * 8
                rect(x, top, 7 + i.toFloat(), 11 + i.toFloat(), window, 80 + i * 12)
                rect(x, top + 27, 7 + i.toFloat(), 13 + i.toFloat(), window, 50)
                line(x + 4, top, x + 4, top + 13, 0x23303A)
                rect(x - 2, top - 2, 12f, 2f, 0x4C5056)
            }
            // Pavements and cobbles converge rather than borrowing the lake's horizontal ripples.
            poly(0x323D45, 0f, 158f, 129f, 115f, 154f, 114f, 89f, 180f, 0f, 180f)
            poly(0x3A4146, 195f, 114f, 223f, 118f, 320f, 163f, 320f, 180f, 269f, 180f)
            line(89f, 180f, 154f, 114f, 0x8B8F89, 100)
            line(269f, 180f, 195f, 114f, 0x8B8F89, 100)
            for (y in 118..178 step 4) {
                val spread = (y - 112) * 1.9f
                val step = 5 + (y - 112) / 7
                var x = (174 - spread).toInt()
                while (x < 174 + spread) {
                    rect(x.toFloat(), y.toFloat(), (step - 2).toFloat(), 1f, 0x7C8B90, 25)
                    x += step
                }
            }
            fun lamp(x: Float, y: Float, h: Float) {
                rect(x, y - h, 2f, h, 0x101925)
                rect(x - 2, y, 6f, 2f, 0x0A111C)
                rect(x - 3, y - h - 9, 8f, 10f, 0x121C27)
                rect(x - 1, y - h - 7, 4f, 6f, window, 235)
                poly(0x18202B, x - 5, y - h - 9, x + 1, y - h - 13, x + 6, y - h - 9)
                oval(x - 9, y - h - 12, 20f, 20f, window, 10)
            }
            lamp(101f, 134f, 35f)
            lamp(219f, 128f, 29f)
            // Long, subdued blue reflection; never animated or glittering.
            for (i in 0..7) rect(240f - i * .6f, 146f + i * 3, 24f + i, 1f, 0x6CABC0, 45 - i * 4)
            WeeklyMotif.drawReference(canvas, ArtTheme.MEDIA, 228f, 84f, 1.35f)
        }

        /** Gotham is all gothic/Art Deco towers, rooftop machinery and rain-dark masonry. */
        fun gotham() {
            val window = if (random.nextBoolean()) 0xB3A379 else 0xB6AD8E
            gradient(0x080F1C, 0x324552, 0, 125)
            gradient(0x16222D, 0x080F18, 125, 180)
            stars(17, 55, 0x9EAFBB, 65)
            repeat(12) { cloud(random.nextInt(-20, 300).toFloat(), random.nextInt(20, 86).toFloat(),
                random.nextInt(25, 70).toFloat(), 0x8497A0, 19) }
            WeeklyMotif.drawSky(canvas, ArtTheme.COMICS)
            // Far city is a stepped silhouette with mist between successive streets.
            for (layer in 0..1) {
                val tint = if (layer == 0) 0x2F404B else 0x233541
                var x = -5f
                while (x < W) {
                    val w = random.nextInt(8, 19).toFloat()
                    val h = random.nextInt(20, 48) + layer * 8
                    val top = 125f - h
                    rect(x, top, w, h.toFloat(), tint)
                    rect(x + w * .3f, top - 5, w * .4f, 5f, tint)
                    windows(x + 2, top + 4, w - 4, h - 8f, 4, 7, window, 90)
                    x += w + random.nextInt(1, 5)
                }
                rect(0f, 106f + layer * 9, W, 10f, 0x697C82, 10)
            }
            fun tower(x: Float, top: Float, w: Float, base: Float, tint: Int, spire: Boolean) {
                rect(x, top, w, base - top, tint)
                rect(x + 3, top - 7, w - 6, 7f, tint)
                rect(x + w * .3f, top - 13, w * .4f, 6f, tint)
                if (spire) poly(tint, x + w * .35f, top - 12, x + w * .5f, top - 29,
                    x + w * .65f, top - 12)
                rect(x, top + 1, w, 1f, 0x69787C, 100)
                for (xx in (x + 3).toInt() until (x + w - 1).toInt() step 6)
                    rect(xx.toFloat(), top + 3, 1f, base - top - 5, 0x64727A, 80)
                windows(x + 4, top + 5, w - 7, base - top - 9, 6, 6, window, 190)
                rect(x + w - 4, top, 4f, base - top, 0x06111D, 170)
            }
            tower(23f, 47f, 31f, 149f, 0x152633, true)
            tower(69f, 73f, 27f, 141f, 0x182B38, false)
            tower(115f, 58f, 26f, 145f, 0x142733, true)
            tower(168f, 82f, 26f, 143f, 0x172B37, false)
            tower(289f, 65f, 33f, 154f, 0x152631, true)
            // Gothic cathedral roof and buttresses in the mid-ground.
            poly(0x11212D, 194f, 123f, 215f, 99f, 237f, 123f, 237f, 147f, 194f, 147f)
            rect(197f, 104f, 6f, 43f, 0x11212D)
            rect(229f, 104f, 6f, 43f, 0x11212D)
            poly(0x11212D, 195f, 104f, 200f, 93f, 205f, 104f)
            poly(0x11212D, 227f, 104f, 232f, 93f, 237f, 104f)
            oval(209f, 116f, 12f, 12f, window, 80)
            line(215f, 116f, 215f, 128f, 0x11212D)
            line(209f, 122f, 221f, 122f, 0x11212D)
            // Foreground rooftop provides the caption-safe dark base, with wet coping stones.
            poly(0x101D29, 0f, 154f, 109f, 141f, 206f, 143f, 252f, 137f, 320f, 148f,
                320f, 180f, 0f, 180f)
            line(0f, 154f, 109f, 141f, 0x77858A, 80)
            line(109f, 141f, 206f, 143f, 0x77858A, 70)
            line(206f, 143f, 252f, 137f, 0x77858A, 85)
            for (x in 0..320 step 24) line(x.toFloat(), 180f, 180f + (x - 180) * .7f, 145f, 0x5C707A, 20)
            for (y in listOf(152, 163, 178)) line(0f, y.toFloat(), W, y.toFloat(), 0x6C7A83, 13)
            for (i in 0..22) {
                val x = random.nextInt(155, 313).toFloat()
                val y = random.nextInt(148, 177).toFloat()
                rect(x, y, random.nextInt(3, 11).toFloat(), 1f, window, 20)
            }
            WeeklyMotif.drawReference(canvas, ArtTheme.COMICS, 240f, 98f, 1f)
            // A watchful cape and pointed cowl belong to this rooftop, not a floating emblem.
            poly(0x070F19, 193f, 130f, 198f, 111f, 207f, 110f, 214f, 132f,
                207f, 129f, 203f, 133f, 198f, 129f)
            rect(199f, 104f, 7f, 8f, 0x08111B)
            poly(0x08111B, 199f, 106f, 199f, 100f, 202f, 104f, 204f, 104f, 206f, 100f, 206f, 107f)
            rect(197f, 132f, 18f, 3f, 0x475763)
            rect(200f, 135f, 13f, 9f, 0x1C2E3B)
        }

        /** The Little Prince's tiny world floats in open space: rose, volcanoes and scarf. */
        fun asteroid() {
            val warm = if (random.nextBoolean()) 0xBD936F else 0xA98A7D
            gradient(0x10142D, 0x2C2947)
            // A broad dusty galactic arch is static and soft; no starburst or particle glare.
            for (x in 0..319 step 2) {
                val y = 36f + 17f * sin(x * .011f)
                rect(x.toFloat(), y, 2f, 14f, 0x9E7E9A, 8)
                rect(x.toFloat(), y + 4, 2f, 5f, 0xC19DAB, 6)
            }
            stars(125, 154, 0xD2C7AC, 150)
            oval(67f, 37f, 22f, 22f, 0xD1A478, 200)
            diskShadow(67, 37, 22, 5f, 0x302841)
            oval(36f, 100f, 9f, 7f, 0x8B7F8D, 110)
            // The asteroid has a lit upper rim, cratered hemisphere, and a substantial dark underside.
            oval(137f, 117f, 161f, 52f, 0x403B49)
            poly(0x5E5157, 137f, 140f, 143f, 130f, 161f, 122f, 191f, 117f,
                224f, 113f, 258f, 120f, 284f, 130f, 299f, 141f, 284f, 155f,
                253f, 166f, 212f, 172f, 174f, 163f, 150f, 152f)
            poly(warm, 140f, 136f, 160f, 124f, 192f, 119f, 223f, 116f,
                254f, 121f, 279f, 130f, 292f, 140f, 260f, 143f, 229f, 137f,
                202f, 144f, 174f, 137f)
            poly(0x77645F, 146f, 140f, 173f, 139f, 203f, 148f, 230f, 141f,
                259f, 147f, 290f, 142f, 274f, 156f, 239f, 164f, 204f, 165f, 173f, 158f)
            repeat(29) {
                val angle = random.nextFloat() * 6.28f
                val radius = random.nextFloat()
                val x = 217f + cos(angle) * 65 * radius
                val y = 143f + sin(angle) * 16 * radius
                oval(x, y, random.nextInt(2, 8).toFloat(), 2f, 0x3E3C4A, 70)
            }
            // Three little volcanoes, with one barely smoking.
            fun volcano(x: Float, y: Float, scale: Float) {
                poly(0x8C786B, x - 9 * scale, y, x - 4 * scale, y - 11 * scale,
                    x + 3 * scale, y - 11 * scale, x + 10 * scale, y)
                poly(0x5F5656, x + 1 * scale, y - 11 * scale, x + 3 * scale, y - 11 * scale,
                    x + 10 * scale, y, x + 5 * scale, y)
                oval(x - 4 * scale, y - 12 * scale, 7 * scale, 3 * scale, 0x3F3B49)
            }
            volcano(265f, 131f, 1.1f)
            volcano(171f, 131f, .75f)
            volcano(258f, 144f, .65f)
            cloud(259f, 108f, 22f, 0xCDC0B7, 30)
            WeeklyMotif.drawReference(canvas, ArtTheme.BOOKS, 196f, 71f, 1.45f)
            // Small traveller, warm hair, green coat and a scarf stretched into the space breeze.
            rect(184f, 115f, 3f, 13f, 0x7D977A)
            rect(188f, 115f, 3f, 13f, 0x627D6E)
            rect(183f, 126f, 4f, 2f, 0x494956)
            rect(188f, 126f, 4f, 2f, 0x494956)
            poly(0x7EA284, 183f, 110f, 190f, 110f, 194f, 120f, 181f, 120f)
            rect(184f, 103f, 6f, 7f, 0xDBBD90)
            poly(0xD6B268, 182f, 104f, 184f, 100f, 186f, 102f, 188f, 99f,
                191f, 102f, 191f, 105f, 187f, 104f, 184f, 106f)
            poly(0xD7B779, 184f, 110f, 187f, 110f, 177f, 107f, 164f, 109f,
                156f, 104f, 166f, 106f, 178f, 104f, 187f, 108f)
        }

        /** Apollo on the Moon: vacuum-black sky, Earthrise, mineral ground and long shadows. */
        fun tranquility() {
            gradient(0x050C17, 0x132133, 0, 107)
            stars(59, 84, 0xBBC4D0, 100)
            // Earth is the only blue object in the scene and has visible continents/cloud bands.
            oval(69f, 22f, 37f, 37f, 0x4886A6)
            oval(70f, 23f, 34f, 34f, 0x447E98)
            poly(0xA5B8A2, 76f, 26f, 86f, 25f, 89f, 29f, 85f, 32f,
                87f, 35f, 82f, 38f, 80f, 35f, 75f, 34f)
            poly(0xA0AE93, 86f, 39f, 93f, 38f, 96f, 43f, 91f, 51f, 88f, 49f, 88f, 44f)
            rect(77f, 29f, 18f, 2f, 0xDBDFD4, 160)
            rect(72f, 37f, 12f, 2f, 0xDBDFD4, 190)
            rect(86f, 47f, 15f, 2f, 0xDBDFD4, 150)
            diskShadow(69, 22, 37, 14f, 0x112334, 160)
            poly(0x5C6670, 0f, 106f, 18f, 99f, 31f, 98f, 47f, 90f, 64f, 96f,
                91f, 91f, 110f, 96f, 135f, 102f, 159f, 96f, 178f, 100f,
                201f, 91f, 224f, 95f, 251f, 92f, 281f, 101f, 307f, 96f, 320f, 102f,
                320f, 180f, 0f, 180f)
            poly(0x73777A, 0f, 114f, 37f, 108f, 69f, 109f, 99f, 102f,
                129f, 112f, 167f, 110f, 205f, 103f, 243f, 110f, 282f, 106f,
                320f, 117f, 320f, 180f, 0f, 180f)
            poly(0x565F67, 0f, 143f, 32f, 138f, 57f, 141f, 95f, 130f,
                128f, 133f, 162f, 128f, 198f, 136f, 230f, 131f, 263f, 140f,
                320f, 130f, 320f, 180f, 0f, 180f)
            fun crater(x: Float, y: Float, w: Float, h: Float) {
                oval(x - 1, y - 1, w + 2, h + 2, 0x9C9E9C, 100)
                oval(x, y, w, h, 0x414C59)
                oval(x + w * .18f, y + h * .5f, w * .77f, h * .4f, 0x737C81)
            }
            crater(25f, 116f, 42f, 10f)
            crater(121f, 111f, 18f, 5f)
            crater(280f, 119f, 28f, 7f)
            crater(265f, 156f, 48f, 14f)
            repeat(180) {
                val x = random.nextInt(0, 320).toFloat()
                val y = random.nextInt(108, 180).toFloat()
                rect(x, y, if (y > 140) 2f else 1f, 1f,
                    if (it % 2 == 0) 0xCCD0C7 else 0x293643, 35)
            }
            // Long hard vacuum shadows anchor the lander and astronaut to the regolith.
            poly(0x273440, 223f, 142f, 271f, 143f, 320f, 165f, 310f, 171f, 268f, 156f)
            WeeklyMotif.drawReference(canvas, ArtTheme.HISTORY, 204f, 76f, 1.55f)
            // Astronaut and flag are a historical visual reference, without fabricated lettering.
            poly(0x2B3845, 182f, 145f, 189f, 145f, 215f, 156f, 208f, 157f)
            rect(181f, 126f, 7f, 12f, 0xD0D0C3)
            rect(179f, 128f, 3f, 9f, 0x9FA9AA)
            rect(179f, 123f, 10f, 7f, 0xD3D3C7)
            rect(182f, 124f, 6f, 4f, 0x817452)
            rect(182f, 138f, 2f, 8f, 0xC4CAC1)
            rect(187f, 137f, 2f, 9f, 0xAFB9B5)
            rect(189f, 129f, 7f, 2f, 0xC5CCC3)
            rect(198f, 113f, 1f, 34f, 0xBDC3BC)
            rect(199f, 114f, 16f, 10f, 0xBCB7A9)
            for (y in 115..123 step 2) rect(199f, y.toFloat(), 16f, 1f, 0x9B7270)
            rect(199f, 114f, 7f, 5f, 0x5C7386)
            // Footprints run from the ladder towards the traveller and foreground.
            for (i in 0..12) {
                val x = 227f - i * 4.2f
                val y = 146f + sin(i * .5f) * 4 + i * .9f
                rect(x, y, 2f, 1f, 0x354451, 180)
                rect(x - 1, y + 2, 2f, 1f, 0xA4ABA5, 100)
            }
            // Caption-safe shade is part of the lunar foreground, with no black panel drawn here.
            poly(0x374553, 0f, 151f, 34f, 148f, 67f, 155f, 111f, 150f,
                151f, 167f, 192f, 174f, 212f, 180f, 0f, 180f, alpha = 190)
        }
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun channel(x: Int, y: Int) = (x + (y - x) * f).toInt()
        return Color.rgb(channel(Color.red(a), Color.red(b)), channel(Color.green(a), Color.green(b)),
            channel(Color.blue(a), Color.blue(b)))
    }
}
