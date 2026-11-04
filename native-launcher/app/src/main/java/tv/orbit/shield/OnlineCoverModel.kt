package tv.orbit.shield

import java.net.URI
import java.net.URLDecoder
import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject

internal enum class CoverProvider(val id: String, val label: String) {
    STEAMGRIDDB("steamgriddb", "SteamGridDB"), LIBRETRO("libretro", "Libretro")
}
internal fun coverProviders(raw: String?): List<CoverProvider> {
    if (raw == "off") return emptyList()
    val parsed = raw?.split(',')?.mapNotNull { id -> CoverProvider.entries.firstOrNull { it.id == id } }?.distinct().orEmpty()
    return parsed.ifEmpty { CoverProvider.entries.toList() }
}
private val coverKeyPattern = Regex("[a-fA-F0-9]{32}")
private val coverSuffixPattern = Regex("(?i)\\((?:USA|Europe|Eur|Japan|World|Russia|Rus|En|Es|Ru|Fr|De|It|Ja|Rev[ .0-9]+)(?:[, ]+(?:En|Es|Ru|Fr|De|It|Ja))*\\)|\\[T[+-][^]]*]")
private val coverSpacePattern = Regex("\\s+")
private val coverMarksPattern = Regex("\\p{M}+")
private val coverSymbolsPattern = Regex("[^\\p{L}\\p{N}]")
internal fun coverApiKeyValid(value: String) = value.isEmpty() || coverKeyPattern.matches(value)
internal fun coverSearchTitle(value: String): String = value.substringBeforeLast('.', value)
    .let { if (value.substringAfterLast('.').lowercase(Locale.ROOT) in setOf("nds","iso","cso","zip","7z","nes","sfc","smc","gba","gb","gbc","chd","cue","z64","n64","v64","gdi","pbp","rvz","gcm","wbfs","cci","cxi","3ds","3dsx","zcci","zcxi","z3dsx")) it else value }
    .replace(coverSuffixPattern, " ").replace('_', ' ').replace(coverSpacePattern, " ").trim().take(160)
internal fun coverTitleKey(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
    .replace(coverMarksPattern, "").lowercase(Locale.ROOT).replace(coverSymbolsPattern, "")
internal fun coverImageUrl(value: String): Boolean = runCatching {
    val u = URI(value)
    u.scheme == "https" && u.userInfo == null && u.port in setOf(-1,443) && u.fragment == null &&
        (u.host == "thumbnails.libretro.com" || u.host == "cdn2.steamgriddb.com" || u.host == "cdn.steamgriddb.com" ||
            u.host == "staticcdn.steamgriddb.com" || (u.host == "s3.amazonaws.com" && u.path.startsWith("/steamgriddb/")))
}.getOrDefault(false)
internal data class OnlineCoverGame(val id: Int, val title: String, val year: String)
internal data class OnlineCoverImage(val provider: CoverProvider, val title: String, val url: String, val preview: String,
    val width: Int = 0, val height: Int = 0, val author: String = "", val sourceUrl: String = url)
internal object OnlineCoverParser {
    fun games(text: String): List<OnlineCoverGame> {
        val root = JSONObject(text); require(root.optBoolean("success"))
        val rows = root.getJSONArray("data")
        return (0 until minOf(rows.length(), 12)).mapNotNull { i ->
            val o = rows.getJSONObject(i); val id = o.optInt("id")
            val name = o.optString("name").take(200)
            if (id <= 0 || name.isBlank()) null else OnlineCoverGame(id, name, o.optLong("release_date").takeIf { it > 0 }?.let {
                java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneOffset.UTC).year.toString()
            }.orEmpty())
        }
    }
    fun images(text: String, title: String): List<OnlineCoverImage> {
        val root = JSONObject(text); require(root.optBoolean("success"))
        val rows = root.getJSONArray("data")
        return (0 until minOf(rows.length(), 100)).mapNotNull { i ->
            val o = rows.getJSONObject(i); val url = o.optString("url"); val thumb = o.optString("thumb")
            val w = o.optInt("width"); val h = o.optInt("height")
            if (!coverImageUrl(url) || !coverImageUrl(thumb) || w !in 32..8192 || h !in 32..8192 ||
                o.optBoolean("nsfw") || o.optBoolean("humor") || o.optBoolean("epilepsy") ||
                o.optString("mime") !in setOf("image/png","image/jpeg","image/webp")) null
            else OnlineCoverImage(CoverProvider.STEAMGRIDDB,title,url,thumb,w,h,
                o.optJSONObject("author")?.optString("name").orEmpty().take(120),
                "https://www.steamgriddb.com/grid/" + o.optInt("id"))
        }.distinctBy { it.url }.sortedBy { if (it.width > it.height) 0 else 1 }.take(16)
    }
    fun libretroName(href: String): String? = runCatching {
        val name = URLDecoder.decode(href.replace("+", "%2B"), "UTF-8")
        name.takeIf { it.endsWith(".png", true) && it.length in 5..240 && '/' !in it && '\\' !in it &&
            it.none { c -> c.code < 32 } && it !in setOf(".","..") }
    }.getOrNull()
    fun libretroMatches(names: List<String>, query: String, checkpoint: () -> Unit = {}): List<String> {
        val key = coverTitleKey(coverSearchTitle(query))
        if (key.isBlank()) return emptyList()
        return names.asSequence().filter { checkpoint(); coverTitleKey(coverSearchTitle(it.removeSuffix(".png"))) == key }.distinct().take(16).toList()
    }
}

/** Platform identifiers are persisted metadata, not localized labels or game-specific branches. */
internal val libretroSystems = mapOf(
    "SNES" to "Nintendo - Super Nintendo Entertainment System", "PSP" to "Sony - PlayStation Portable",
    "Nintendo 64" to "Nintendo - Nintendo 64", "Dreamcast" to "Sega - Dreamcast", "NES" to "Nintendo - Nintendo Entertainment System",
    "Game Boy" to "Nintendo - Game Boy", "Game Boy Color" to "Nintendo - Game Boy Color", "Game Boy Advance" to "Nintendo - Game Boy Advance",
    "Mega Drive" to "Sega - Mega Drive - Genesis", "Master System" to "Sega - Master System - Mark III", "PC Engine" to "NEC - PC Engine - TurboGrafx 16",
    "Neo Geo Pocket" to "SNK - Neo Geo Pocket", "Nintendo DS" to "Nintendo - Nintendo DS", "PlayStation" to "Sony - PlayStation",
    "GameCube" to "Nintendo - GameCube", "Wii" to "Nintendo - Wii"
)
