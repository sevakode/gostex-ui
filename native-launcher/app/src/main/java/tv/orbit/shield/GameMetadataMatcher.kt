package tv.orbit.shield

import android.content.Context
import org.json.JSONArray
import java.text.Normalizer
import java.util.Locale

/** Conservative, offline metadata matching. A filename is never evidence for another platform. */
internal object GameMetadataMatcher {
    private var cachedGames: List<Game>? = null
    private var cachedIndex: MetadataIndex? = null

    @Synchronized
    fun find(context: Context, platform: String, filename: String, bundled: List<Game>): Game? {
        val index = if (cachedGames === bundled) cachedIndex!! else {
            val aliases = runCatching {
                val data = JSONArray(context.assets.open("metadata-aliases.json").bufferedReader().use { it.readText() })
                (0 until data.length()).map { i ->
                    val item = data.getJSONObject(i)
                    MetadataAlias(item.getString("platform"), item.getString("alias"), item.getString("title"))
                }
            }.getOrDefault(emptyList())
            MetadataIndex(bundled, aliases).also { cachedGames = bundled; cachedIndex = it }
        }
        return index.find(platform, filename)
    }
}

internal data class MetadataAlias(val platform: String, val alias: String, val title: String)

internal class MetadataIndex(games: List<Game>, aliases: List<MetadataAlias>) {
    private val exact = mutableMapOf<Pair<String, String>, MutableSet<Game>>()
    private val normalized = mutableMapOf<Pair<String, String>, MutableSet<Game>>()

    init {
        fun add(index: MutableMap<Pair<String, String>, MutableSet<Game>>, game: Game, key: String) {
            if (key.isNotBlank()) index.getOrPut(game.platform to key) { linkedSetOf() }.add(game)
        }
        games.forEach { game ->
            add(exact, game, metadataExactKey(game.title))
            add(normalized, game, metadataTitleKey(game.title))
            // These are previously identified files, not guesses about unrecognised hacks or prototypes.
            game.files.forEach { file -> add(exact, game, metadataExactKey(file.substringAfterLast('/'))) }
        }
        aliases.forEach { alias ->
            games.singleOrNull { it.platform == alias.platform && it.title == alias.title }?.let { game ->
                add(normalized, game, metadataTitleKey(alias.alias))
            }
        }
    }

    fun find(platform: String, filename: String): Game? {
        // Exact names retain distinctions between a known translation and the original release.
        exact[platform to metadataExactKey(filename)]?.let { return it.singleOrNull() }
        return normalized[platform to metadataTitleKey(filename)]?.singleOrNull()
    }
}

private val romExtensions = setOf("sfc", "smc", "zip", "7z", "rar", "nes", "unf", "unif", "fds", "gb", "gbc", "dmg", "gba", "mb", "md", "gen", "smd", "sms", "gg", "pce", "ngp", "ngc", "npc", "nds", "iso", "cso", "pbp", "elf", "z64", "n64", "v64", "cdi", "gdi", "chd", "cue", "bin", "exe", "rvz", "gcm", "wbfs", "gcz", "ciso", "wia", "dol", "cci", "cxi", "3ds", "3dsx", "app", "axf", "zcci", "zcxi", "z3dsx")
private val harmlessParenthesis = Regex("(?i)(?:U|E|J|USA|Europe|Japan|World|Canada|France|Germany|Spain|Italy|Australia|Korea|China|Taiwan|Brazil|Russia|United States|United Kingdom)(?:\\s*,\\s*(?:USA|Europe|Japan|Canada|Australia))*|(?:En|Es|Fr|De|It|Ja|Ko|Zh|Ru|Pt)(?:\\s*,\\s*(?:En|Es|Fr|De|It|Ja|Ko|Zh|Ru|Pt))*|(?:Rev\\.?\\s*\\d+(?:\\.\\d+)*|V\\d+(?:\\.\\d+)*)")
private val harmlessBracket = Regex("(?i)!|T[+-].+|[abo]\\d*")

internal fun metadataExactKey(value: String): String {
    val base = value.trim()
    val extension = base.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return (if (extension in romExtensions) base.substringBeforeLast('.') else base)
        .replace('_', ' ').trim().lowercase(Locale.ROOT)
}

internal fun metadataTitleKey(value: String): String {
    var title = metadataExactKey(value)
    // Strip only recognised suffix metadata. Preserve sequel numbers, subtitles, Hacks, Beta,
    // Prototype, Demo and unknown parenthetical text rather than attaching retail metadata to them.
    while (title.isNotEmpty()) {
        val before = title
        if (title.endsWith(']')) {
            val start = title.lastIndexOf('[')
            if (start >= 0 && harmlessBracket.matches(title.substring(start + 1, title.lastIndex)))
                title = title.substring(0, start).trim()
        } else if (title.endsWith(')')) {
            val start = title.lastIndexOf('(')
            if (start >= 0 && harmlessParenthesis.matches(title.substring(start + 1, title.lastIndex)))
                title = title.substring(0, start).trim()
        }
        if (before == title) break
    }
    title = title.replace(Regex(",\\s*(the|a|an)$"), "")
        .replace(Regex("^(the|a|an)\\s+"), "")
    title = Normalizer.normalize(title, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
    return title.replace(Regex("[^\\p{L}\\p{N}]+"), "")
}
