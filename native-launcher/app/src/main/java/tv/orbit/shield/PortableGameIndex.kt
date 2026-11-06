package tv.orbit.shield

import org.json.JSONArray
import org.json.JSONObject

/** A verified content file in the selected portable tree. IDs are local to the index;
 * paths are components under that tree, never absolute paths or Android URI grants. */
internal data class PortableIndexedFile(val id: String, val path: List<String>, val bytes: Long, val sha256: String)
internal data class PortableIndexedGame(
    val id: String, val title: String, val platform: String, val emulator: String,
    val component: String, val variants: List<String>, val genre: String, val year: String,
    val developer: String, val players: String, val description: String, val demo: Boolean,
    val manualCover: String?, val originalCover: String?, val wideCover: String?, val coverChoice: String,
    val baseCover: String = "", val scene: String = "", val coverSourceName: String = "",
    val coverSourceUrl: String = "", val coverAuthor: String = "",
)
internal data class PortableGameIndex(val files: List<PortableIndexedFile>, val games: List<PortableIndexedGame>)

/** Versioned game/file index only. Home sections, preferences and the transaction
 * envelope are separate work; decoding never opens files or changes app state.
 * Import must revalidate document identity/contents and resolve emulator components
 * against installed, allowed platforms instead of trusting strings in this index. */
internal object PortableGameIndexCodec {
    private const val VERSION = 2
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_FILES = 32768
    private const val MAX_GAMES = 8192
    private const val MAX_VARIANTS = 256
    private val digestPattern = Regex("[a-f0-9]{64}")
    private val coverChoices = setOf("auto", "manual", "original")

    private fun JSONObject.onlyKeys(expected: Set<String>) {
        require(length() == expected.size)
        val fields = keys()
        while (fields.hasNext()) require(fields.next() in expected) { "Unknown index field" }
    }

    private fun token(value: String, limit: Int = 512): String {
        require(value.isNotBlank() && value.length <= limit && value == value.trim() && value.none(Char::isISOControl))
        return value
    }
    private fun text(value: String, limit: Int, nonempty: Boolean = false): String {
        require(value.length <= limit && (!nonempty || value.isNotBlank()) && value.none { it == '\u0000' })
        return value
    }
    private fun asset(value: String): String {
        if (value.isNotEmpty()) require(!value.startsWith('/') && ':' !in value &&
            PortablePath.fromComponents(value.split('/')) != null) { "Nonportable artwork reference" }
        return value
    }
    private fun sourceUrl(value: String): String {
        if (value.isNotEmpty()) {
            require(value.length <= 1000)
            val uri = java.net.URI(value)
            require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.query == null && uri.fragment == null) { "Unsafe cover source URL" }
        }
        return value
    }
    private fun JSONObject.string(name: String): String = opt(name) as? String ?: error("Expected string: $name")
    private fun JSONObject.optionalId(name: String): String? = when (val v = opt(name)) {
        null, JSONObject.NULL -> null
        is String -> token(v)
        else -> error("Expected optional file ID: $name")
    }
    private fun JSONArray.strings(max: Int): List<String> {
        require(length() <= max)
        return (0 until length()).map { opt(it) as? String ?: error("Expected string array") }
    }

    fun decode(serialized: String): PortableGameIndex {
        requirePortableJson(serialized, MAX_BYTES)
        val root = JSONObject(serialized)
        root.onlyKeys(setOf("version", "files", "games"))
        val version = root.opt("version")
        require(version == 1 || version == VERSION) { "Unsupported game index version" }
        val filesJson = root.getJSONArray("files")
        val gamesJson = root.getJSONArray("games")
        require(filesJson.length() <= MAX_FILES && gamesJson.length() <= MAX_GAMES)
        val fileIds = mutableSetOf<String>()
        val filePaths = mutableSetOf<List<String>>()
        val files = (0 until filesJson.length()).map { i ->
            val row = filesJson.getJSONObject(i)
            row.onlyKeys(setOf("id", "path", "bytes", "sha256"))
            val id = token(row.string("id"))
            require(fileIds.add(id)) { "Duplicate file ID" }
            val path = PortablePath.fromComponents(row.getJSONArray("path").strings(128))?.components
                ?: error("Invalid relative path")
            require(filePaths.add(path)) { "Duplicate portable path" }
            val rawSize = row.opt("bytes")
            require(rawSize is Int || rawSize is Long) { "Invalid file size" }
            val size = (rawSize as Number).toLong()
            val sha = row.string("sha256")
            require(size >= 0 && digestPattern.matches(sha)) { "Invalid file metadata" }
            PortableIndexedFile(id, path, size, sha)
        }
        val gameIds = mutableSetOf<String>()
        val games = (0 until gamesJson.length()).map { i ->
            val row = gamesJson.getJSONObject(i)
            val v1Keys = setOf("id", "title", "platform", "emulator", "component", "variants", "genre", "year",
                "developer", "players", "description", "demo", "manualCover", "originalCover", "wideCover", "coverChoice")
            row.onlyKeys(if (version == 1) v1Keys else v1Keys + setOf("baseCover", "scene", "coverSourceName", "coverSourceUrl", "coverAuthor"))
            val id = token(row.string("id"))
            require(gameIds.add(id)) { "Duplicate game ID" }
            val variants = row.getJSONArray("variants").strings(MAX_VARIANTS).map { token(it) }
            require(variants.isNotEmpty() && variants.distinct().size == variants.size && variants.all(fileIds::contains)) {
                "Invalid game file references"
            }
            fun cover(name: String) = row.optionalId(name).also { require(it == null || it in fileIds) }
            val manual = cover("manualCover")
            val original = cover("originalCover")
            val wide = cover("wideCover")
            val choice = row.string("coverChoice")
            require(choice in coverChoices)
            val demo = row.opt("demo") as? Boolean ?: error("Expected demo flag")
            PortableIndexedGame(id, text(row.string("title"), 1024, true), token(row.string("platform"), 200),
                text(row.string("emulator"), 512), text(row.string("component"), 512), variants,
                text(row.string("genre"), 1024), text(row.string("year"), 128), text(row.string("developer"), 1024),
                text(row.string("players"), 128), text(row.string("description"), 128 * 1024), demo,
                manual, original, wide, choice,
                if (version == 1) "" else asset(row.string("baseCover")),
                if (version == 1) "" else asset(row.string("scene")),
                if (version == 1) "" else text(row.string("coverSourceName"), 100),
                if (version == 1) "" else sourceUrl(row.string("coverSourceUrl")),
                if (version == 1) "" else text(row.string("coverAuthor"), 120))
        }
        return PortableGameIndex(files, games)
    }

    fun encode(index: PortableGameIndex): String {
        require(index.files.size <= MAX_FILES && index.games.size <= MAX_GAMES)
        // Bound work before building large JSON arrays, including encoded/escaped bytes.
        var serializedBytes = 128L
        fun account(row: JSONObject) {
            serializedBytes += row.toString().toByteArray(Charsets.UTF_8).size + 1L
            require(serializedBytes <= MAX_BYTES) { "Game index too large" }
        }
        val files = JSONArray()
        index.files.forEach { file ->
            token(file.id)
            require(PortablePath.fromComponents(file.path) != null && file.bytes >= 0 && digestPattern.matches(file.sha256))
            val row = JSONObject().put("id", file.id).put("path", JSONArray(file.path))
                .put("bytes", file.bytes).put("sha256", file.sha256)
            account(row); files.put(row)
        }
        val games = JSONArray()
        index.games.forEach { game ->
            token(game.id); text(game.title, 1024, true); token(game.platform, 200)
            text(game.emulator, 512); text(game.component, 512); text(game.genre, 1024); text(game.year, 128)
            text(game.developer, 1024); text(game.players, 128); text(game.description, 128 * 1024)
            asset(game.baseCover); asset(game.scene); text(game.coverSourceName, 100)
            sourceUrl(game.coverSourceUrl); text(game.coverAuthor, 120)
            require(game.variants.size in 1..MAX_VARIANTS && game.coverChoice in coverChoices)
            game.variants.forEach { token(it) }
            listOfNotNull(game.manualCover, game.originalCover, game.wideCover).forEach { token(it) }
            val row = JSONObject().put("id", game.id).put("title", game.title)
            .put("platform", game.platform).put("emulator", game.emulator).put("component", game.component)
            .put("variants", JSONArray(game.variants)).put("genre", game.genre).put("year", game.year)
            .put("developer", game.developer).put("players", game.players).put("description", game.description)
            .put("demo", game.demo).put("manualCover", game.manualCover ?: JSONObject.NULL)
            .put("originalCover", game.originalCover ?: JSONObject.NULL).put("wideCover", game.wideCover ?: JSONObject.NULL)
            .put("coverChoice", game.coverChoice)
            .put("baseCover", game.baseCover).put("scene", game.scene).put("coverSourceName", game.coverSourceName)
            .put("coverSourceUrl", game.coverSourceUrl).put("coverAuthor", game.coverAuthor)
            account(row); games.put(row)
        }
        val result = JSONObject().put("version", VERSION).put("files", files).put("games", games).toString()
        decode(result) // Refuse to create an index that the reader would reject.
        return result
    }
}
