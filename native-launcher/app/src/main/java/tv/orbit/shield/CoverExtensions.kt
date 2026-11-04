package tv.orbit.shield

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.time.LocalDate

/** A generated composition supplements the original scan; it never replaces its source record. */
internal data class CoverExtension(
    val original: String,
    val image: String,
    val sourceName: String,
    val sourceUrl: String,
    val sourceSha256: String,
    val generationCredit: String,
    val generatedAt: String,
)

internal object CoverExtensionCatalog {
    private const val MAX_LENGTH = 512 * 1024
    private const val MAX_ENTRIES = 512

    fun isImagePath(path: String): Boolean = path.length in 1..240 &&
        path.startsWith("media/") && path.matches(Regex("[A-Za-z0-9_./-]+")) &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
        path.substringAfterLast('.').lowercase(java.util.Locale.ROOT) in setOf("png", "jpg", "jpeg", "webp")

    fun parse(text: String): Map<String, CoverExtension> {
        if (text.length > MAX_LENGTH) return emptyMap()
        return runCatching {
            val root = JSONObject(text)
            if (root.opt("version") != 1) return@runCatching emptyMap()
            val items = root.optJSONObject("extensions") ?: return@runCatching emptyMap()
            if (items.length() > MAX_ENTRIES) return@runCatching emptyMap()
            buildMap {
                val keys = items.keys()
                while (keys.hasNext()) {
                    val original = keys.next()
                    val item = items.optJSONObject(original) ?: continue
                    val image = item.opt("image") as? String ?: continue
                    val sourceName = item.opt("sourceName") as? String ?: continue
                    val sourceUrl = item.opt("sourceUrl") as? String ?: continue
                    val sourceSha256 = item.opt("sourceSha256") as? String ?: continue
                    val generationCredit = item.opt("generationCredit") as? String ?: continue
                    val generatedAt = item.opt("generatedAt") as? String ?: continue
                    if (!isImagePath(original) || !isImagePath(image) || image == original) continue
                    if (sourceName.isBlank() || sourceName.length > 100 || generationCredit.isBlank() || generationCredit.length > 512) continue
                    if (sourceUrl.length > 2048 || !runCatching {
                        val uri = URI(sourceUrl)
                        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
                    }.getOrDefault(false)) continue
                    if (!sourceSha256.matches(Regex("[a-f0-9]{64}"))) continue
                    if (!runCatching { LocalDate.parse(generatedAt); true }.getOrDefault(false)) continue
                    put(original, CoverExtension(original, image, sourceName, sourceUrl, sourceSha256, generationCredit, generatedAt))
                }
            }
        }.getOrDefault(emptyMap())
    }
}

/** One small immutable index per process. No file I/O or JSON parsing on the Compose thread. */
internal object CoverExtensions {
    private val lock = Mutex()
    private var catalog: Map<String, CoverExtension>? = null

    suspend fun find(context: Context, original: String): CoverExtension? = withContext(Dispatchers.IO) {
        if (!CoverExtensionCatalog.isImagePath(original)) return@withContext null
        lock.withLock {
            val index = catalog ?: runCatching {
                context.assets.open("cover-extensions.json").bufferedReader().use { CoverExtensionCatalog.parse(it.readText()) }
            }.getOrDefault(emptyMap()).also { catalog = it }
            index[original]
        }
    }
}

internal data class ResolvedCoverAsset<T>(val value: T?, val generated: Boolean)

/** Decoding failures and missing catalogue entries both return to the same original cover. */
internal suspend fun <T> resolveCoverAsset(
    original: String,
    useGenerated: Boolean,
    extension: suspend (String) -> CoverExtension?,
    load: suspend (String) -> T?,
): ResolvedCoverAsset<T> {
    if (useGenerated) {
        extension(original)?.let { candidate ->
            load(candidate.image)?.let { return ResolvedCoverAsset(it, true) }
        }
    }
    return ResolvedCoverAsset(load(original), false)
}
