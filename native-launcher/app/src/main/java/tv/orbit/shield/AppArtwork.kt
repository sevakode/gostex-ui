package tv.orbit.shield

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class TvApp(
    val name: String,
    val packageName: String,
    val activityName: String = "",
    val versionCode: Long = 0L,
    val lastUpdateTime: Long = 0L,
    val isGame: Boolean = false
)

/** Call off the UI thread. An app with both launchers keeps its TV activity and artwork. */
@Suppress("DEPRECATION")
internal fun loadTvApps(context: Context): List<TvApp> {
    val pm = context.packageManager
    return listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
        .flatMap { category -> pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0) }
        .filter { it.activityInfo.exported && it.activityInfo.enabled && it.activityInfo.applicationInfo.enabled }
        .filter { it.activityInfo.packageName != context.packageName }
        .distinctBy { it.activityInfo.packageName }
        .map { resolved ->
            val info = resolved.activityInfo
            val installed = runCatching { pm.getPackageInfo(info.packageName, 0) }.getOrNull()
            TvApp(
                name = resolved.loadLabel(pm).toString(),
                packageName = info.packageName,
                activityName = info.name,
                versionCode = installed?.longVersionCode ?: 0L,
                lastUpdateTime = installed?.lastUpdateTime ?: 0L,
                isGame = info.applicationInfo.category == android.content.pm.ApplicationInfo.CATEGORY_GAME ||
                    (info.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_IS_GAME) != 0
            )
        }
        .sortedBy { it.name.lowercase(Locale.ROOT) }
}

private const val BANNER_WIDTH = 640
private const val BANNER_HEIGHT = 360
private val AppPanel = Color(0xFF1F1F21)
private val AppLime = Color(0xFFCCFF00)
private val AppWhite = Color(0xFFF5F5F5)

/** Bounded final rasters only: no retained Activity, Drawable, PackageManager or full-sized source. */
private object AppArtworkCache {
    private val images = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun key(context: Context, app: TvApp): String {
        val config = context.resources.configuration
        return listOf(
            app.packageName, app.activityName, app.versionCode, app.lastUpdateTime,
            config.densityDpi, config.uiMode, config.locales.toLanguageTags()
        ).joinToString("|")
    }

    fun get(key: String): Bitmap? = images.get(key)

    fun load(context: Context, app: TvApp, key: String): Bitmap? {
        images.get(key)?.let { return it }
        return runCatching {
            val pm = context.packageManager
            val activity = app.activityName.takeIf(String::isNotBlank)?.let { name ->
                runCatching { pm.getActivityInfo(ComponentName(app.packageName, name), 0) }.getOrNull()
            }
            val application = activity?.applicationInfo ?: pm.getApplicationInfo(app.packageName, 0)
            // Prefer the selected TV activity's banner. The app can also have a different phone icon.
            val banner = activity?.takeIf { it.banner != 0 }?.let {
                runCatching { pm.getDrawable(app.packageName, it.banner, application) }.getOrNull()
            } ?: runCatching { application.loadBanner(pm) }.getOrNull()
            val drawable = banner ?: runCatching { activity?.loadIcon(pm) ?: application.loadIcon(pm) }
                .getOrElse { pm.defaultActivityIcon }
            renderAppArtwork(drawable, isBanner = banner != null).also { images.put(key, it) }
        }.getOrNull()
    }
}

/** Render every source with its own aspect ratio; a square icon is never stretched into a banner. */
private fun renderAppArtwork(drawable: Drawable, isBanner: Boolean): Bitmap {
    val sourceWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: 256
    val sourceHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: 256
    val source = drawable.mutate()
    val previousBounds = Rect(source.bounds)
    val bitmap = Bitmap.createBitmap(BANNER_WIDTH, BANNER_HEIGHT, Bitmap.Config.ARGB_8888)
    try {
        // Sampling is a tiny one-time raster, including vector/adaptive icons. It never runs in draw.
        val sample = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val sampleCanvas = Canvas(sample)
        fitDrawable(source, sampleCanvas, sourceWidth, sourceHeight, 0f, 0f, 32f, 32f)
        val color = sampleColor(sample)
        sample.recycle()

        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, BANNER_WIDTH.toFloat(), BANNER_HEIGHT.toFloat(),
                mixColor(color, 0xFF101114.toInt(), .52f),
                mixColor(color, 0xFF101114.toInt(), .88f), Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, BANNER_WIDTH.toFloat(), BANNER_HEIGHT.toFloat(), paint)
        if (isBanner) {
            fitDrawable(source, canvas, sourceWidth, sourceHeight, 0f, 0f,
                BANNER_WIDTH.toFloat(), BANNER_HEIGHT.toFloat())
        } else {
            val side = BANNER_HEIGHT * .56f
            fitDrawable(source, canvas, sourceWidth, sourceHeight,
                (BANNER_WIDTH - side) / 2f, (BANNER_HEIGHT - side) / 2f, side, side)
        }
    } finally {
        source.bounds = previousBounds
    }
    return bitmap
}

private fun fitDrawable(
    drawable: Drawable, canvas: Canvas, sourceWidth: Int, sourceHeight: Int,
    left: Float, top: Float, width: Float, height: Float
) {
    val scale = min(width / sourceWidth, height / sourceHeight)
    val drawnWidth = max(1, (sourceWidth * scale).roundToInt())
    val drawnHeight = max(1, (sourceHeight * scale).roundToInt())
    val x = (left + (width - drawnWidth) / 2f).roundToInt()
    val y = (top + (height - drawnHeight) / 2f).roundToInt()
    drawable.setBounds(x, y, x + drawnWidth, y + drawnHeight)
    drawable.draw(canvas)
}

private fun sampleColor(bitmap: Bitmap): Int {
    // A small weighted histogram avoids muddy gray backgrounds from multicolored marks.
    val weights = IntArray(512)
    val red = IntArray(512)
    val green = IntArray(512)
    val blue = IntArray(512)
    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
        val color = bitmap.getPixel(x, y)
        if (AndroidColor.alpha(color) < 160) continue
        val r = AndroidColor.red(color)
        val g = AndroidColor.green(color)
        val b = AndroidColor.blue(color)
        val high = max(r, max(g, b))
        val low = min(r, min(g, b))
        if (high < 30 || low > 235) continue
        val key = ((r shr 5) shl 6) or ((g shr 5) shl 3) or (b shr 5)
        val weight = 1 + (high - low) / 64
        weights[key] += weight
        red[key] += r * weight
        green[key] += g * weight
        blue[key] += b * weight
    }
    val key = weights.indices.maxByOrNull { weights[it] } ?: return 0xFF353841.toInt()
    val weight = weights[key]
    if (weight == 0) return 0xFF353841.toInt()
    return AndroidColor.rgb(red[key] / weight, green[key] / weight, blue[key] / weight)
}

private fun mixColor(color: Int, base: Int, amount: Float): Int = AndroidColor.rgb(
    (AndroidColor.red(color) * (1f - amount) + AndroidColor.red(base) * amount).roundToInt(),
    (AndroidColor.green(color) * (1f - amount) + AndroidColor.green(base) * amount).roundToInt(),
    (AndroidColor.blue(color) * (1f - amount) + AndroidColor.blue(base) * amount).roundToInt()
)

@Composable
internal fun AppTile(
    app: TvApp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocus: () -> Unit = {},
    favorite: Boolean = false
) {
    val context = LocalContext.current
    val cacheKey = AppArtworkCache.key(context, app)
    val banner by produceState(AppArtworkCache.get(cacheKey), cacheKey) {
        value = withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            AppArtworkCache.load(context, app, cacheKey)
        }
    }
    val image = remember(banner) { banner?.asImageBitmap() }
    var focused by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(TileLayout.aspectRatio)
                .touchSurface(onLongClick = onLongClick, onClick = onClick)
                .semantics { contentDescription = app.name }
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocus()
                },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = AppPanel, focusedContainerColor = AppPanel),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, Color(0xFF424244))),
                focusedBorder = Border(BorderStroke(2.dp, AppLime))
            )
        ) {
            Box(Modifier.fillMaxSize()) {
                image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                if (favorite) FavoriteBadge(Modifier.align(Alignment.TopEnd).padding(7.dp))
            }
        }
        Spacer(Modifier.height(TileLayout.titleGap))
        Text(app.name, color = if (focused) AppLime else AppWhite, fontSize = TileLayout.titleSize,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
