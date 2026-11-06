package tv.orbit.shield

import android.content.Context
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/** In-memory capture of the launcher's actual metadata, not a portable backup yet.
 * Game file locators and cover provenance may be private and must never be logged. */
internal data class PortableLiveLibrary(
    val games: List<Game>,
    val home: HomeSectionsOptions,
    val settings: Map<String, Any>,
    val covers: Map<String, ManagedCover>,
    val coverChoices: Map<String, String>,
)

internal object PortableLiveCapture {
    private const val MAX_LIBRARY_BYTES = 16 * 1024 * 1024
    private const val MAX_COVER_BYTES = 512 * 1024
    private const val HOME_KEY = "home.sections.v1"
    private const val CHOICE_PREFIX = "covers.choice."

    internal fun strictGames(text: String): List<Game> {
        requirePortableJson(text, MAX_LIBRARY_BYTES)
        val rows = JSONArray(text)
        require(rows.length() <= 8192)
        val required = setOf("id", "title", "platform", "emulator", "component")
        val optional = setOf("tree", "cover", "scene", "genre", "year", "developer", "players", "description")
        val allowed = required + optional + setOf("files", "demo")
        val ids = mutableSetOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.opt(i) as? JSONObject ?: error("Invalid game row")
            val fields = row.keys()
            while (fields.hasNext()) require(fields.next() in allowed) { "Unsupported game field" }
            required.forEach { require(row.opt(it) is String) { "Invalid game field" } }
            optional.forEach { if (row.has(it)) require(row.opt(it) is String) { "Invalid game field" } }
            if (row.has("demo")) require(row.opt("demo") is Boolean)
            val id = row.opt("id") as String
            require(id.isNotBlank() && ids.add(id)) { "Invalid or duplicate game ID" }
            val files = row.opt("files") as? JSONArray ?: error("Invalid game files")
            require(files.length() <= 256)
            for (j in 0 until files.length()) require(files.opt(j) is String)
        }
        return LibraryImport.decode(text)
    }

    /** Read the committed AtomicFile generation without invoking recovery or writing files. */
    private fun committedText(file: File, max: Int): String? {
        val backup = File(file.path + ".bak")
        val pending = File(file.path + ".new")
        val source = when {
            backup.exists() -> backup
            file.exists() -> file
            pending.exists() -> throw IOException("Only an unfinished source document exists")
            else -> return null
        }
        val bytes = source.inputStream().use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = stream.read(buffer)
                if (n == -1) break
                if (n <= 0 || n > max - output.size()) throw IOException("Source document is unreadable or too large")
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }

    internal fun strictCovers(text: String?): Map<String, ManagedCover> {
        if (text == null) return emptyMap()
        requirePortableJson(text, MAX_COVER_BYTES)
        val root = JSONObject(text)
        require(root.length() == 2 && root.opt("version") == 1 && root.has("games"))
        val games = root.opt("games") as? JSONObject ?: error("Invalid cover index")
        require(games.length() <= ManagedCoverManifest.MAX_ENTRIES)
        val entries = linkedMapOf<String, ManagedCover>()
        val ids = games.keys()
        val allowed = setOf("manual", "original", "wide", "sourceName", "sourceUrl", "author")
        while (ids.hasNext()) {
            val id = ids.next()
            require(id.isNotBlank() && id.length <= 200)
            val row = games.opt(id) as? JSONObject ?: error("Invalid cover row")
            val fields = row.keys()
            while (fields.hasNext()) require(fields.next() in allowed) { "Unsupported cover field" }
            fun field(name: String, max: Int): String {
                val value = if (row.has(name)) row.opt(name) as? String ?: error("Invalid cover value") else ""
                require(value.length <= max && '\u0000' !in value)
                return value
            }
            fun image(name: String): String = field(name, 128).also {
                require(it.isEmpty() || managedCoverDigest(it) != null) { "Invalid managed image" }
            }
            entries[id] = ManagedCover(image("manual"), image("original"), image("wide"),
                field("sourceName", 100), field("sourceUrl", 1000), field("author", 120))
        }
        return entries
    }

    private fun read(context: Context): PortableLiveLibrary {
        val prefs = context.getSharedPreferences("orbit", Context.MODE_PRIVATE).all
        val settings = PortableSettingsCodec.capture(prefs)
        val home = if (prefs.containsKey(HOME_KEY)) {
            PortableHomeStateCodec.decode(prefs[HOME_KEY] as? String ?: error("Invalid home state"))
        } else SectionPreferences.read(context).also { PortableHomeStateCodec.encode(it) }
        val choices = prefs.entries.filter { it.key.startsWith(CHOICE_PREFIX) }.associate { (key, raw) ->
            val id = key.removePrefix(CHOICE_PREFIX)
            val choice = raw as? String ?: error("Invalid cover choice")
            require(id.isNotBlank() && id.length <= 512 && choice in setOf("auto", "manual", "original"))
            id to choice
        }
        val gamesText = committedText(File(context.filesDir, "imported-games.json"), MAX_LIBRARY_BYTES)
        val games = gamesText?.let(::strictGames) ?: emptyList()
        val covers = strictCovers(committedText(File(context.filesDir, "covers/index.json"), MAX_COVER_BYTES))
        return PortableLiveLibrary(games, home, settings, covers, choices)
    }

    /** Two equal observations catch ordinary concurrent changes; callers still need a
     * shared mutation gate or explicit reconfirmation before final publication. */
    fun capture(context: Context, checkActive: () -> Unit = {}): PortableLiveLibrary {
        checkActive()
        val first = read(context)
        checkActive()
        val second = read(context)
        checkActive()
        check(first == second) { "Library changed while capturing" }
        return first
    }
}
