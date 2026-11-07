package tv.orbit.shield

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import kotlinx.coroutines.*
import kotlin.math.*

/** Prepared pixel choreography, calm reveals and still paintings share one bounded renderer. */
internal class PixelArtView(context: Context) : View(context) {
    private val cols = 160
    private val rows = 90
    private val effectSequence = ArtEffectSequence(kotlin.random.Random.nextLong())
    private val prefs = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)
    private val programCursor = ArtProgramCursor(prefs)
    private var options = ArtPreferences.read(context)
    private var sceneOptions = options
    private var catalog: List<ArtEntry>? = null
    private var scene = programCursor.nextScene()
    private var seed = ArtSchedule.ensure(context)
    private var artwork: Artwork? = null
    private var artworkPending = false
    private var loading: Job? = null
    private var generation = 0L
    private var running = false
    private var elapsedMs = 0.0
    private var lastFrame = 0L
    private var nextWeekCheck = 0L
    private val paint = Paint()
    private val full = RectF()
    private val captionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val captionTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val creditTypeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val shadePaint = Paint()
    private var shadeWidth = 0
    private var shadeHeight = 0
    private var captionTitle = ""
    private var captionReference = ""
    private var captionCredit = ""

    private data class Artwork(
        val entry: ArtEntry, val image: Bitmap, val mosaic: Bitmap, val revealed: Bitmap,
        val colors: IntArray, val revealColors: IntArray, val starts: DoubleArray, val details: DoubleArray,
        val focusLevels: List<Bitmap>, val style: RevealStyle,
        val motion: PixelMotionPlan?, val motionImage: Bitmap?, val motionColors: IntArray,
        val snakeAct: SnakeActRenderer?, val retainedGrid: Bitmap?,
    )
    private val isAct get() = artwork?.snakeAct != null
    private val still get() = artwork?.style == RevealStyle.STILL ||
        (artwork == null && sceneOptions.revealStyle == RevealStyle.STILL)
    private val revealEndMs get() = if (still) 0.0 else (if (isAct) 24000.0 else 14000.0) * sceneOptions.speed.durationScale
    private val softenMs get() = 3200.0 * sceneOptions.speed.durationScale
    private val sharpenMs get() = if (still || isAct) 0.0 else 2200.0
    private val explicitlyStill get() = sceneOptions.revealStyle == RevealStyle.STILL
    private val fadeMs get() = if (explicitlyStill) 0.0 else 2800.0
    private val fadeStart get() = revealEndMs + sharpenMs + if (isAct) 0.0 else sceneOptions.holdSeconds * 1000.0
    private val cycleMs get() = fadeStart + fadeMs
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith(ArtPreferences.KEY_PREFIX)) refreshOptions()
    }
    private val runner = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            if (now >= nextWeekCheck) { seed = ArtSchedule.ensure(context); nextWeekCheck = now + 60000L }
            if (artwork != null) elapsedMs += (now - lastFrame).coerceAtLeast(0L).toDouble()
            lastFrame = now
            if (elapsedMs >= cycleMs && loading?.isActive != true) {
                scene++
                // Static mode swaps prepared paintings directly, never through a black frame.
                if (!explicitlyStill) artwork = null
                elapsedMs = 0.0
                loadArtwork()
            }
            invalidate()
            val holding = !isAct && elapsedMs >= revealEndMs + sharpenMs && elapsedMs < fadeStart
            postDelayed(this, if (holding) 1000L else 50L)
        }
    }
    init { contentDescription = context.getString(R.string.art_title) }

    private fun refreshOptions() {
        val updated = ArtPreferences.read(context)
        if (updated == options) return
        val previous = options
        options = updated
        // Caption visibility changes without disturbing the picture. Other choices start a fresh scene.
        if (updated.copy(captionMode = previous.captionMode) != previous) {
            generation++; loading?.cancel(); loading = null
            artworkPending = true
            scene = programCursor.nextScene()
            if (updated.revealStyle != RevealStyle.STILL) artwork = null
            elapsedMs = 0.0
            if (running) loadArtwork()
        }
        invalidate()
    }

    private fun loadArtwork() {
        if (loading?.isActive == true) return
        artworkPending = true
        sceneOptions = options
        val requestedOptions = sceneOptions
        val requestedScene = scene
        val requestedSeed = seed
        val request = ++generation
        loading = CoroutineScope(Dispatchers.Main).launch {
            val prepared = withContext(Dispatchers.IO) {
                val entries = catalog ?: ArtCatalog.load(context).also { catalog = it }
                val paintings = entries.filterNot { it.direction.isInterlude }
                val acts = entries.filter { it.direction.isInterlude }
                val includeActs = requestedOptions.revealStyle == RevealStyle.MIXED && requestedOptions.interludes && acts.isNotEmpty()
                val position = ArtProgramPosition.at(requestedScene, includeActs)
                val cycleLength = ArtCatalog.playlist(paintings, requestedOptions.priorities).size
                val playlist = ArtCatalog.playlist(paintings, requestedOptions.priorities, position.painting / cycleLength)
                var entry = position.interlude?.let { acts[(it % acts.size).toInt()] }
                    ?: playlist[(position.painting % playlist.size).toInt()]
                val snakeAct = if (entry.direction.kind == ArtActKind.SNAKES)
                    SnakeActRenderer(requestedSeed + requestedScene + entry.id.hashCode()) else null
                val treatment = if (snakeAct == null) effectSequence.at(requestedOptions.revealStyle, requestedScene, entry.direction)
                    else ArtTreatment()
                var image = entry.asset?.let { path ->
                    runCatching {
                        context.assets.open(path).use { BitmapFactory.decodeStream(it, null,
                            BitmapFactory.Options().apply { inSampleSize = 2 }) }
                    }.getOrNull()
                }
                if (snakeAct != null) image = snakeAct.renderSnapshot()
                if (image == null) {
                    // Keep the actual world even if its asset cannot be decoded. Categories
                    // control frequency only: a Zelda picture must not turn into Tetris.
                    entry = paintings.firstOrNull { it.world == entry.world && it.asset == null }
                        ?: entry.copy(asset = null)
                    image = WeeklyLandscape.render(requestedSeed + entry.id.hashCode(), 640, 360, entry.world)
                }
                val picture = requireNotNull(image)
                val mosaic = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
                val scale = max(cols.toFloat() / picture.width, rows.toFloat() / picture.height)
                val w = picture.width * scale; val h = picture.height * scale
                Canvas(mosaic).drawBitmap(picture, null,
                    RectF((cols-w)/2, (rows-h)/2, (cols+w)/2, (rows+h)/2), Paint(Paint.FILTER_BITMAP_FLAG))
                val colors = IntArray(cols * rows)
                mosaic.getPixels(colors, 0, cols, 0, 0, cols, rows)
                val style = treatment.style
                val motionKind = when (style) {
                    RevealStyle.BLOCKS -> PixelMotionKind.BLOCKS
                    RevealStyle.WEAVE -> PixelMotionKind.WEAVE
                    else -> null
                }
                // Geometry and sampled colors are prepared off the UI thread. Movement uses
                // continuous paths and a fixed coarse grid, never a random particle emitter.
                val motion = motionKind?.let { PixelMotionPlan.create(it, requestedSeed + requestedScene + entry.id.hashCode(),
                    treatment.columns, treatment.rows) }
                val motionImage = motion?.let { Bitmap.createScaledBitmap(mosaic, it.columns, it.rows, true) }
                val motionColors = motion?.let { plan -> IntArray(plan.columns * plan.rows).also {
                    requireNotNull(motionImage).getPixels(it, 0, plan.columns, 0, 0, plan.columns, plan.rows)
                } } ?: IntArray(0)
                val starts = if (snakeAct != null) DoubleArray(0) else if (style == RevealStyle.SCENE)
                    SceneRevealField.create(entry.reveal, colors, cols, rows)
                else revealField(style, requestedSeed + requestedScene + entry.id.hashCode())
                val details = if (snakeAct == null && style == RevealStyle.SCENE)
                    SceneRevealField.underpainting(entry.reveal, colors, cols, rows) else DoubleArray(0)
                val levels = if (style == RevealStyle.FOCUS) (0..2).map { step ->
                    (treatment.columns * (1 shl step)).coerceAtMost(cols) to
                        (treatment.rows * (1 shl step)).coerceAtMost(rows)
                }.distinct().map { (w, h) ->
                    Bitmap.createScaledBitmap(mosaic, w, h, true)
                } + mosaic else emptyList()
                val retainedGrid = if (treatment.retainPixelGrid) motionImage ?:
                    Bitmap.createScaledBitmap(mosaic, treatment.columns, treatment.rows, true) else null
                Artwork(entry, picture, mosaic, Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888),
                    colors, IntArray(cols * rows), starts, details, levels, style, motion, motionImage, motionColors,
                    snakeAct, retainedGrid)

            }
            if (!running || request != generation) return@launch
            artwork = prepared
            programCursor.shown(requestedScene)
            artworkPending = false
            captionTitle = prepared.entry.title(context)
            captionReference = "${context.getString(prepared.entry.theme.titleRes)} · ${prepared.entry.reference(context)}"
            captionCredit = prepared.entry.credit(context)
            contentDescription = "$captionTitle. $captionReference. $captionCredit"
            elapsedMs = 0.0; lastFrame = SystemClock.uptimeMillis()
            // Leave the slow holding timer immediately when a fresh scene is ready.
            removeCallbacks(runner)
            post(runner)
            invalidate()
        }
    }

    fun start() {
        if (running) return
        refreshOptions()
        running = true; lastFrame = SystemClock.uptimeMillis()
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        if (artwork == null || artworkPending) loadArtwork()
        post(runner)
    }
    fun stop() {
        running = false; removeCallbacks(runner)
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        generation++; loading?.cancel(); loading = null
    }
    override fun onDetachedFromWindow() { stop(); artwork = null; super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(4, 6, 10))
        val art = artwork ?: return
        val cell = min(width / cols.toFloat(), height / rows.toFloat())
        val ox = (width - cols * cell) / 2f; val oy = (height - rows * cell) / 2f
        full.set(ox, oy, ox + cols * cell, oy + rows * cell)
        val opacity = if (elapsedMs <= fadeStart || fadeMs == 0.0) 1.0
            else 1.0 - smooth((elapsedMs - fadeStart) / fadeMs)
        val alpha = (255 * opacity).roundToInt()
        paint.color = Color.WHITE; paint.isFilterBitmap = false; paint.alpha = alpha
        if (art.snakeAct != null) {
            val appear = smooth(elapsedMs / 1800.0)
            art.snakeAct.draw(canvas, full, elapsedMs / 1000.0 / sceneOptions.speed.durationScale,
                (alpha * appear).roundToInt())
        } else if (still) {
            paint.isFilterBitmap = true
            canvas.drawBitmap(art.image, null, full, paint)
        } else if (elapsedMs >= revealEndMs) {
            val sharpen = smooth((elapsedMs - revealEndMs) / sharpenMs)
            if (sharpen < 1.0) canvas.drawBitmap(art.motionImage ?: art.mosaic, null, full, paint)
            paint.isFilterBitmap = art.retainedGrid == null; paint.alpha = (alpha * sharpen).roundToInt()
            canvas.drawBitmap(art.retainedGrid ?: art.image, null, full, paint)
        } else if (art.motion != null) drawMotion(canvas, art, alpha)
        else if (art.style == RevealStyle.FOCUS) drawFocus(canvas, art, alpha)
        else drawReveal(canvas, art, alpha)
        drawCaption(canvas, opacity)
    }

    private fun smooth(value: Double): Double {
        val t = value.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /** A continuous ink-distance field or a contiguous Hilbert mosaic path. */
    private fun revealField(style: RevealStyle, sceneSeed: Long): DoubleArray {
        if (style != RevealStyle.INK && style != RevealStyle.TILES) return DoubleArray(0)
        val random = kotlin.random.Random(sceneSeed)
        val ax = .22 + random.nextDouble() * .2
        val ay = .25 + random.nextDouble() * .45
        val bx = .62 + random.nextDouble() * .2
        val by = .25 + random.nextDouble() * .45
        val phase = random.nextDouble() * PI * 2
        val field = DoubleArray(cols * rows) { index ->
            val x = (index % cols).toDouble() / (cols - 1)
            val y = (index / cols).toDouble() / (rows - 1)
            if (style == RevealStyle.TILES) {
                // Sixteen by nine panels: adjacent sections open along a folded path,
                // with long overlapping dissolves rather than a flashing checkerboard.
                hilbertIndex(index % cols / 10, index / cols / 10 + 3).toDouble()
            } else {
                min(hypot((x - ax) * 1.5, y - ay), hypot((x - bx) * 1.5, y - by) + .12) +
                    .055 * sin(x * 11 + phase) * cos(y * 9 - phase) +
                    .025 * sin(x * 20 + y * 13 + phase)
            }
        }
        val min = field.minOrNull() ?: 0.0
        val range = ((field.maxOrNull() ?: 1.0) - min).coerceAtLeast(.001)
        // Unitless starts; the timing setting is applied in the renderer.
        return DoubleArray(field.size) { (field[it] - min) / range }
    }

    private fun hilbertIndex(column: Int, row: Int): Int {
        var x = column; var y = row; var distance = 0; var size = 8
        while (size > 0) {
            val rx = if (x and size != 0) 1 else 0
            val ry = if (y and size != 0) 1 else 0
            distance += size * size * ((3 * rx) xor ry)
            if (ry == 0) {
                if (rx == 1) { x = size - 1 - x; y = size - 1 - y }
                val previousX = x; x = y; y = previousX
            }
            size /= 2
        }
        return distance
    }

    private fun drawReveal(canvas: Canvas, art: Artwork, alpha: Int) {
        if (art.style == RevealStyle.SCENE) {
            // A subdued establishing silhouette keeps the whole world readable while
            // its landmarks develop. It only gains opacity, never pulses or flickers.
            paint.alpha = (alpha * .14 * smooth(elapsedMs / softenMs)).roundToInt()
            canvas.drawBitmap(art.mosaic, null, full, paint)
        }
        var changed = false
        val spreadMs = revealEndMs - softenMs
        for (index in art.colors.indices) {
            val coverage = if (art.style == RevealStyle.SCENE)
                SceneRevealField.coverage(elapsedMs / revealEndMs, art.starts[index], art.details[index], softenMs / revealEndMs)
            else smooth((elapsedMs - art.starts[index] * spreadMs) / softenMs)
            val color = (art.colors[index] and 0x00ffffff) or ((coverage * 255).roundToInt() shl 24)
            if (art.revealColors[index] != color) {
                art.revealColors[index] = color
                changed = true
            }
        }
        if (changed) art.revealed.setPixels(art.revealColors, 0, cols, 0, 0, cols, rows)
        paint.alpha = alpha
        canvas.drawBitmap(art.revealed, null, full, paint)
    }

    private fun drawMotion(canvas: Canvas, art: Artwork, alpha: Int) {
        val plan = art.motion ?: return
        val progress = (elapsedMs / revealEndMs).coerceIn(0.0, 1.0)
        val cellWidth = full.width() / plan.columns
        val cellHeight = full.height() / plan.rows
        val saved = canvas.save()
        canvas.clipRect(full)
        // A very faint color map anchors the world while its moving pieces arrive.
        paint.alpha = (alpha * .06 * smooth(progress / .2)).roundToInt()
        art.motionImage?.let { canvas.drawBitmap(it, null, full, paint) }
        for (index in plan.tiles.indices) {
            val tile = plan.tiles[index]
            val phase = tile.phase(progress)
            if (phase <= 0.0) continue
            val offset = 1.0 - phase
            val bow = 4.0 * phase * offset
            val x = full.left + ((tile.x + tile.sourceX * offset + tile.arcX * bow) * cellWidth).toFloat()
            val y = full.top + ((tile.y + tile.sourceY * offset + tile.arcY * bow) * cellHeight).toFloat()
            paint.color = art.motionColors[index]
            paint.alpha = (alpha * smooth((progress - tile.start) / .1)).roundToInt()
            canvas.drawRect(x, y, x + cellWidth, y + cellHeight, paint)
        }
        canvas.restoreToCount(saved)
    }

    private fun drawFocus(canvas: Canvas, art: Artwork, alpha: Int) {
        // A picture develops from large color planes into fine pixel detail. No travel,
        // rotation, pulsing, shimmer or artificial white highlights.
        val progress = (elapsedMs / revealEndMs).coerceIn(0.0, 1.0)
        val visibility = smooth(progress / .22)
        val stage = (progress * (art.focusLevels.size - 1)).coerceAtMost(art.focusLevels.size - 1.0)
        val index = floor(stage).toInt()
        paint.alpha = (alpha * visibility).roundToInt()
        canvas.drawBitmap(art.focusLevels[index], null, full, paint)
        if (index < art.focusLevels.lastIndex) {
            paint.alpha = (alpha * visibility * smooth(stage - index)).roundToInt()
            canvas.drawBitmap(art.focusLevels[index + 1], null, full, paint)
        }
    }

    private fun drawCaption(canvas: Canvas, opacity: Double) {
        if (options.captionMode == CaptionMode.HIDDEN) return
        val visibility = (if (still) 1.0 else if (isAct) smooth((elapsedMs - 1200) / 1800)
            else smooth((elapsedMs - revealEndMs + 700) / 1800)) * opacity
        if (visibility <= 0) return
        val unit = width / 1920f
        val margin = 64f * unit
        val bottom = height - 48f * unit
        if (shadeWidth != width || shadeHeight != height) {
            shadeWidth = width; shadeHeight = height
            shadePaint.shader = LinearGradient(0f, height-240f*unit, 0f, height.toFloat(),
                Color.TRANSPARENT, Color.argb(155,0,0,0), Shader.TileMode.CLAMP)
        }
        shadePaint.alpha = (255 * visibility).toInt()
        canvas.drawRect(0f, height-240f*unit, width.toFloat(), height.toFloat(), shadePaint)
        captionPaint.color = Color.WHITE
        captionPaint.setShadowLayer(4f * unit, 0f, unit, Color.BLACK)
        val details = options.captionMode == CaptionMode.DETAILS
        captionPaint.typeface = captionTypeface
        captionPaint.textSize = 34f * unit; captionPaint.alpha = (255 * visibility).toInt()
        val titleY = bottom - (if (details) 66f else 36f) * unit
        canvas.drawText(TextUtils.ellipsize(captionTitle, captionPaint, width * .8f, TextUtils.TruncateAt.END).toString(), margin, titleY, captionPaint)
        captionPaint.typeface = creditTypeface
        captionPaint.textSize = 24f * unit; captionPaint.alpha = (235 * visibility).toInt()
        if (details) canvas.drawText(TextUtils.ellipsize(captionReference, captionPaint, width * .8f, TextUtils.TruncateAt.END).toString(), margin, bottom - 32f*unit, captionPaint)
        captionPaint.alpha = (190 * visibility).toInt(); captionPaint.textSize = 21f * unit
        canvas.drawText(captionCredit, margin, bottom, captionPaint)
    }
}
