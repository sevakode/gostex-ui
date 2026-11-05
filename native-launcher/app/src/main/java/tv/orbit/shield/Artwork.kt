package tv.orbit.shield

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max

internal fun openCoverImage(context: Context, path: String): java.io.InputStream =
    ManagedCoverStore.imageFile(context, path)?.inputStream() ?: context.assets.open(path)

private data class CachedArtwork(val path: String, val bitmap: Bitmap?, val ambient: Bitmap?)

/** Sources and their tiny, prepared backdrops share the same 32 MiB budget. */
private object ArtCache {
    private val lock = Mutex()
    private val entries = object : LruCache<String, CachedArtwork>(32 * 1024) {
        override fun sizeOf(key: String, value: CachedArtwork): Int =
            max(1, ((value.bitmap?.allocationByteCount ?: 0) + (value.ambient?.allocationByteCount ?: 0) + 1023) / 1024)
    }

    fun peek(path: String): CachedArtwork? = entries.get(path)

    suspend fun load(context: Context, path: String): CachedArtwork = withContext(Dispatchers.IO) {
        // Serialize cache misses so hero and card requests cannot decode the same source twice.
        lock.withLock {
            entries.get(path)?.let { return@withLock it }
            currentCoroutineContext().ensureActive()
            val source = if (path.isBlank()) null else runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                openCoverImage(context, path).use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > 2048 ||
                    bounds.outWidth.toLong() * bounds.outHeight / sample / sample > 2_097_152L) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                openCoverImage(context, path).use { BitmapFactory.decodeStream(it, null, options) }
            }.getOrNull()
            // A failed asset stays a neutral placeholder; never reuse another game's art.
            val entry = CachedArtwork(path, source, source?.let(::makeAmbient))
            entries.put(path, entry)
            entry
        }
    }

    /** Small CPU blur, once per decoded asset. Works on Android 11 without live blur or animation. */
    private fun makeAmbient(source: Bitmap): Bitmap {
        val side = 48
        val reduced = Bitmap.createScaledBitmap(source, side, side, true)
        var pixels = IntArray(side * side)
        var scratch = IntArray(pixels.size)
        reduced.getPixels(pixels, 0, side, 0, 0, side, side)
        if (reduced !== source) reduced.recycle()
        val radius = 4
        val diameter = radius * 2 + 1
        repeat(3) {
            for (y in 0 until side) for (x in 0 until side) {
                var red = 0; var green = 0; var blue = 0
                for (offset in -radius..radius) {
                    val color = pixels[y * side + (x + offset).coerceIn(0, side - 1)]
                    red += color shr 16 and 255; green += color shr 8 and 255; blue += color and 255
                }
                scratch[y * side + x] = (255 shl 24) or ((red / diameter) shl 16) or ((green / diameter) shl 8) or (blue / diameter)
            }
            val swap = pixels; pixels = scratch; scratch = swap
            for (y in 0 until side) for (x in 0 until side) {
                var red = 0; var green = 0; var blue = 0
                for (offset in -radius..radius) {
                    val color = pixels[(y + offset).coerceIn(0, side - 1) * side + x]
                    red += color shr 16 and 255; green += color shr 8 and 255; blue += color and 255
                }
                scratch[y * side + x] = (255 shl 24) or ((red / diameter) shl 16) or ((green / diameter) shl 8) or (blue / diameter)
            }
            val swapBack = pixels; pixels = scratch; scratch = swapBack
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    }
}

@Composable private fun rememberArtwork(path: String): CachedArtwork? {
    val context = LocalContext.current.applicationContext
    val entry by produceState(ArtCache.peek(path), context, path) {
        value = ArtCache.load(context, path)
    }
    // produceState keeps its previous value during a key change. Do not flash that previous cover.
    return entry?.takeIf { it.path == path }
}

@Composable internal fun Artwork(path: String, modifier: Modifier = Modifier, crop: Boolean = true) {
    rememberArtwork(path)?.bitmap?.let { bitmap ->
        Image(bitmap.asImageBitmap(), null, modifier, contentScale = if (crop) ContentScale.Crop else ContentScale.Fit)
    }
}

private data class CoverRenderResult(
    val requestPath: String,
    val requestedGenerated: Boolean,
    val requestedWide: String,
    val fallback: String,
    val artwork: ResolvedCoverAsset<CachedArtwork>,
)

@Composable private fun rememberCoverArtwork(path: String, useGenerated: Boolean, wide: String = "", fallback: String = ""): ResolvedCoverAsset<CachedArtwork>? {
    val context = LocalContext.current.applicationContext
    val result by produceState<CoverRenderResult?>(null, context, path, useGenerated, wide, fallback) {
        val custom = if (useGenerated && wide.isNotEmpty()) ArtCache.load(context, wide).takeIf { it.bitmap != null } else null
        val resolved = if (custom != null) ResolvedCoverAsset(custom, true) else resolveCoverAsset(path, useGenerated,
            extension = { CoverExtensions.find(context, it) },
            load = { ArtCache.load(context, it).takeIf { asset -> asset.bitmap != null } })
        val safe = if (resolved.value == null && fallback.isNotEmpty() && fallback != path)
            ResolvedCoverAsset(ArtCache.load(context, fallback).takeIf { it.bitmap != null }, false) else resolved
        value = CoverRenderResult(path, useGenerated, wide, fallback, safe)
    }
    return result?.takeIf { it.requestPath == path && it.requestedGenerated == useGenerated && it.requestedWide == wide && it.fallback == fallback }?.artwork
}

/** Wide cards may use authored extensions; portrait views always preserve the original scan. */
@Composable internal fun CoverArtwork(path: String, modifier: Modifier = Modifier, gameId: String? = null, fallbackTitle: String = "") {
    val choice = LocalManagedCoverOptions.current.choices[gameId] ?: CoverChoice.AUTO
    val selected = selectManagedCover(path, LocalManagedCovers.current[gameId], choice)
    val style = LocalCoverStyle.current
    BoxWithConstraints(modifier.clipToBounds().background(Color(0xFF1F1F21)), contentAlignment = Alignment.Center) {
        val useGenerated = style == CoverStyle.GENERATED && choice != CoverChoice.ORIGINAL && constraints.hasBoundedWidth &&
            constraints.hasBoundedHeight && constraints.maxHeight > 0 &&
            constraints.maxWidth.toFloat() / constraints.maxHeight >= 1.3f
        val resolved = rememberCoverArtwork(selected.original, useGenerated, selected.wide, path)
        val entry = resolved?.value
        if ((style == CoverStyle.AMBIENT || style == CoverStyle.GENERATED) && resolved?.generated != true) entry?.ambient?.let { ambient ->
            Image(ambient.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, alpha = .58f)
        }
        entry?.bitmap?.let { bitmap ->
            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(),
                contentScale = if (style == CoverStyle.CROP || resolved.generated) ContentScale.Crop else ContentScale.Fit)
        }
        if (entry == null && fallbackTitle.isNotBlank() &&
            (resolved != null || (selected.original.isEmpty() && selected.wide.isEmpty()))) {
            Text(fallbackTitle, Modifier.padding(12.dp), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
