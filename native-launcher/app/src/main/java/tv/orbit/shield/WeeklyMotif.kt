package tv.orbit.shield

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/** Original pixel props. Each is placed inside its own world's full composition. */
internal object WeeklyMotif {
    private class PixelBrush(val canvas: Canvas) {
        val paint = Paint().apply { isAntiAlias = false; isDither = false }

        fun color(rgb: Int, alpha: Int = 255) {
            paint.color = rgb or (0xFF shl 24)
            paint.alpha = alpha
            paint.style = Paint.Style.FILL
        }

        fun rect(x: Float, y: Float, width: Float, height: Float) =
            canvas.drawRect(x, y, x + width, y + height, paint)

        fun polygon(vararg points: Float) {
            val path = Path().apply {
                moveTo(points[0], points[1])
                for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1])
                close()
            }
            canvas.drawPath(path, paint)
        }
    }

    fun drawSky(canvas: Canvas, theme: ArtTheme) {
        if (theme != ArtTheme.COMICS) return
        with(PixelBrush(canvas)) {
            // A restrained searchlight and the unmistakable wing/ear/tail silhouette.
            color(0xEDDCA8, 13)
            polygon(264f, 141f, 237f, 36f, 284f, 36f)
            color(0xEDDCA8, 8)
            canvas.drawOval(232f, 15f, 290f, 59f, paint)
            color(0xEDDCA8, 75)
            canvas.drawOval(238f, 21f, 284f, 53f, paint)
            color(0xF3E4B7, 70)
            canvas.drawOval(241f, 24f, 281f, 50f, paint)
            color(0x111A2C, 245)
            polygon(243f, 31f, 249f, 34f, 255f, 34f,
                257f, 31f, 257f, 26f, 260f, 30f, 262f, 30f,
                265f, 26f, 265f, 31f, 267f, 34f, 273f, 34f,
                279f, 31f, 277f, 40f, 272f, 38f, 269f, 43f,
                265f, 40f, 261f, 47f, 257f, 40f, 253f, 43f,
                250f, 38f, 245f, 40f)
        }
    }

    /** x/y refer to a common 52 × 46 pixel prop frame, not a shared background. */
    fun drawReference(canvas: Canvas, theme: ArtTheme, x: Float, y: Float, scale: Float = 1f) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(scale, scale)
        canvas.translate(-240f, -106f)
        with(PixelBrush(canvas)) {
            when (theme) {
                ArtTheme.GAMES -> tetris(this)
                ArtTheme.MEDIA -> policeBox(this)
                ArtTheme.COMICS -> searchlight(this)
                ArtTheme.BOOKS -> rose(this)
                ArtTheme.HISTORY -> lunarModule(this)
            }
        }
        canvas.restore()
    }

    private fun tetris(b: PixelBrush) = with(b) {
        // Each coloured group contains four squares. The open gaps retain their identity.
        fun piece(rgb: Int, vararg cells: Int) {
            for (i in cells.indices step 2) {
                val x = 244f + cells[i] * 4f
                val y = 115f + cells[i + 1] * 4f
                color(rgb)
                rect(x, y, 4f, 4f)
                color(0xFFFFFF, 72)
                rect(x, y, 4f, 1f)
                rect(x, y + 1f, 1f, 2f)
                color(0x08151E, 110)
                rect(x + 3f, y + 1f, 1f, 3f)
                rect(x + 1f, y + 3f, 2f, 1f)
            }
        }
        piece(0xD29A4B, 0, 5, 0, 6, 0, 7, 1, 7) // L
        piece(0xA46ECD, 2, 6, 3, 6, 4, 6, 3, 7) // T
        piece(0xD9C86C, 5, 6, 6, 6, 5, 7, 6, 7) // O
        piece(0x56BDC7, 7, 4, 7, 5, 7, 6, 7, 7) // I
        piece(0x86B774, 2, 4, 3, 4, 1, 5, 2, 5) // S
        piece(0xD17A86, 4, 3, 5, 3, 5, 4, 6, 4) // Z
        piece(0x718FCB, 1, 0, 1, 1, 2, 1, 3, 1) // J, suspended above the sculpture
        color(0xD1C39A, 95)
        rect(243f, 148f, 36f, 1f)
    }

    private fun policeBox(b: PixelBrush) = with(b) {
        // Stepped roof, roof lamp, white six-pane windows and notice panel identify the TARDIS.
        color(0x081824)
        rect(249f, 145f, 27f, 4f)
        color(0x214F78)
        rect(251f, 118f, 23f, 28f)
        color(0x3D78A7)
        rect(251f, 118f, 2f, 28f)
        rect(261f, 120f, 2f, 26f)
        color(0x123650)
        rect(270f, 119f, 4f, 27f)
        rect(254f, 136f, 6f, 8f)
        rect(264f, 136f, 5f, 8f)
        color(0x3D78A7)
        rect(249f, 115f, 26f, 3f)
        rect(252f, 113f, 20f, 2f)
        rect(257f, 111f, 10f, 2f)
        color(0x081F34)
        rect(250f, 117f, 24f, 4f)
        color(0xD5DEDD)
        rect(253f, 118f, 18f, 1f)
        color(0xDDE8D7)
        for (door in 0..1) for (row in 0..2) for (col in 0..1) {
            rect(254f + door * 10 + col * 3, 123f + row * 3, 2f, 2f)
        }
        color(0xD7DECD)
        rect(254f, 133f, 5f, 5f)
        color(0x687C84)
        rect(255f, 134f, 3f, 1f)
        rect(255f, 136f, 2f, 1f)
        color(0xD6BA7B)
        rect(263f, 135f, 1f, 2f)
        color(0x1A4161)
        rect(259f, 106f, 6f, 6f)
        color(0xE1E7CD)
        rect(260f, 107f, 4f, 4f)
        color(0xBCD9D0, 24)
        rect(258f, 105f, 8f, 7f)
        color(0x44789B)
        rect(259f, 105f, 6f, 1f)
    }

    private fun searchlight(b: PixelBrush) = with(b) {
        color(0x748493)
        polygon(258f, 143f, 262f, 137f, 270f, 139f, 268f, 146f)
        color(0xC9C8AF)
        polygon(259f, 142f, 262f, 137f, 266f, 138f, 263f, 144f)
        color(0x283849)
        rect(262f, 145f, 3f, 3f)
        rect(257f, 148f, 15f, 2f)
    }

    private fun rose(b: PixelBrush) = with(b) {
        // A little asteroid-like rock and a protected red rose, recalling The Little Prince.
        color(0x756F72)
        polygon(246f, 145f, 252f, 139f, 270f, 138f, 280f, 145f,
            277f, 151f, 252f, 152f)
        color(0xA49B8B)
        polygon(250f, 144f, 256f, 140f, 269f, 140f, 276f, 144f)
        color(0x434B59)
        rect(251f, 148f, 5f, 2f)
        rect(272f, 146f, 4f, 2f)
        // The stepped glass bell is transparent; no opaque white blob over the flower.
        color(0xB3DDE0, 31)
        polygon(252f, 141f, 252f, 124f, 255f, 119f, 259f, 117f,
            267f, 117f, 271f, 120f, 274f, 125f, 274f, 141f)
        color(0xB6D9D9, 190)
        rect(251f, 125f, 1f, 16f)
        rect(252f, 121f, 1f, 4f)
        rect(253f, 119f, 3f, 2f)
        rect(256f, 117f, 4f, 1f)
        rect(260f, 116f, 7f, 1f)
        rect(267f, 117f, 4f, 1f)
        rect(271f, 119f, 2f, 3f)
        rect(273f, 122f, 1f, 4f)
        rect(274f, 126f, 1f, 15f)
        color(0xE0EBDE, 210)
        rect(254f, 123f, 1f, 8f)
        rect(255f, 120f, 2f, 3f)
        color(0xC1D4CC, 120)
        rect(251f, 141f, 24f, 1f)
        color(0x729C71)
        rect(262f, 128f, 1f, 13f)
        polygon(263f, 136f, 267f, 132f, 269f, 132f, 268f, 135f)
        color(0x9BBD79)
        polygon(262f, 133f, 258f, 129f, 256f, 130f, 259f, 133f)
        color(0x9C3654)
        rect(258f, 123f, 8f, 5f)
        rect(260f, 121f, 5f, 8f)
        color(0xE5747B)
        rect(258f, 123f, 3f, 2f)
        rect(261f, 121f, 3f, 2f)
        color(0xC84E65)
        rect(263f, 124f, 4f, 3f)
        color(0xF2A091)
        rect(261f, 124f, 2f, 2f)
    }

    private fun lunarModule(b: PixelBrush) = with(b) {
        // Apollo's faceted ascent cabin, gold descent stage and splayed landing struts.
        color(0xB9B8AB)
        polygon(254f, 132f, 256f, 133f, 246f, 148f, 244f, 148f)
        polygon(275f, 132f, 277f, 132f, 287f, 148f, 285f, 148f)
        polygon(262f, 137f, 264f, 137f, 258f, 150f, 256f, 150f)
        color(0x7F8A8C)
        rect(240f, 148f, 10f, 2f)
        rect(282f, 148f, 10f, 2f)
        rect(253f, 150f, 9f, 2f)
        color(0xB59455)
        polygon(252f, 130f, 258f, 127f, 274f, 128f, 278f, 131f,
            276f, 139f, 254f, 139f)
        color(0xDCC589)
        polygon(253f, 131f, 263f, 131f, 262f, 138f, 254f, 137f)
        color(0x78664A)
        rect(266f, 131f, 10f, 7f)
        color(0xBBBDBA)
        polygon(256f, 125f, 256f, 117f, 261f, 113f, 270f, 114f,
            274f, 120f, 273f, 128f, 260f, 128f)
        color(0xE1E1D2)
        polygon(257f, 118f, 261f, 114f, 266f, 115f, 266f, 126f,
            260f, 126f, 257f, 123f)
        color(0x122934)
        polygon(257f, 118f, 262f, 118f, 262f, 123f)
        polygon(267f, 118f, 272f, 120f, 268f, 124f)
        color(0x4E6370)
        rect(261f, 125f, 4f, 4f)
        color(0xD1CBB6)
        rect(263f, 107f, 1f, 7f)
        rect(260f, 107f, 7f, 1f)
        rect(274f, 113f, 1f, 7f)
        canvas.drawCircle(275f, 112f, 2f, paint)
        // Front ladder and its rungs make the small craft readable at television distance.
        color(0xC4C0A2)
        rect(260f, 132f, 1f, 14f)
        rect(264f, 132f, 1f, 14f)
        for (y in 133..145 step 3) rect(260f, y.toFloat(), 5f, 1f)
    }
}
