package tv.orbit.shield

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Strict, side-effect-free representation of SectionPreferences' version-1 document.
 * It preserves IDs, ordering, visibility and app references even if apps are absent.
 * It does not write preferences or infer that a referenced game or app exists. */
internal object PortableHomeStateCodec {
    private const val VERSION = 1
    private const val MAX_BYTES = 8 * 1024 * 1024
    // Keep these bounds aligned with SectionPreferences; reject rather than truncate.
    private const val MAX_SECTIONS = 64
    private const val MAX_ITEMS = 4096
    private const val MAX_FAVORITES = 8192
    private const val MAX_KEY = 512
    private const val MAX_NAME_CODEPOINTS = 64
    private val idPattern = Regex("[A-Za-z0-9:_-]{1,80}")

    private fun JSONObject.onlyKeys(allowed: Set<String>) {
        val iterator = keys()
        while (iterator.hasNext()) require(iterator.next() in allowed) { "Unknown home state field" }
    }
    private fun name(value: String): String {
        require(value.isNotBlank() && value.none(Char::isISOControl) &&
            value == value.trim().replace(Regex("\\s+"), " ") &&
            value.codePointCount(0, value.length) <= MAX_NAME_CODEPOINTS) { "Invalid section name" }
        return value
    }
    private fun item(value: String): String {
        require(value.length <= MAX_KEY && value.none(Char::isISOControl))
        val ref = when {
            value.startsWith("game:") -> value.removePrefix("game:")
            value.startsWith("app:") -> value.removePrefix("app:")
            else -> error("Invalid home reference")
        }
        require(ref.isNotBlank() && ref == ref.trim())
        return value
    }
    private fun items(values: List<String>, max: Int): List<String> {
        require(values.size <= max && values.distinct().size == values.size)
        return values.map(::item)
    }
    private fun JSONArray.strings(max: Int): List<String> {
        require(length() <= max)
        return items((0 until length()).map { opt(it) as? String ?: error("Invalid home reference") }, max)
    }

    fun decode(serialized: String): HomeSectionsOptions {
        requirePortableJson(serialized, MAX_BYTES)
        val root = JSONObject(serialized)
        root.onlyKeys(setOf("version", "sections", "favorites"))
        require(root.opt("version") == VERSION) { "Unsupported home state version" }
        val rows = root.getJSONArray("sections")
        require(rows.length() <= MAX_SECTIONS)
        val ids = mutableSetOf<String>()
        val builtinKinds = mutableSetOf<HomeSectionKind>()
        val sections = (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val id = row.opt("id") as? String ?: error("Missing section ID")
            require(idPattern.matches(id) && ids.add(id)) { "Invalid or duplicate section ID" }
            val rawKind = row.opt("kind") as? String ?: error("Missing section kind")
            val kind = HomeSectionKind.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == rawKind }
                ?: error("Unsupported section kind")
            require(kind == HomeSectionKind.CUSTOM || builtinKinds.add(kind)) { "Duplicate built-in section" }
            val visible = row.opt("visible") as? Boolean ?: error("Missing section visibility")
            if (kind == HomeSectionKind.CUSTOM) {
                row.onlyKeys(setOf("id", "kind", "name", "items", "visible"))
                HomeSection(id, kind, name(row.opt("name") as? String ?: error("Missing section name")),
                    row.getJSONArray("items").strings(MAX_ITEMS), visible)
            } else {
                row.onlyKeys(setOf("id", "kind", "visible"))
                HomeSection(id, kind, visible = visible)
            }
        }
        return HomeSectionsOptions(sections, root.getJSONArray("favorites").strings(MAX_FAVORITES))
    }

    fun encode(options: HomeSectionsOptions): String {
        require(options.sections.size <= MAX_SECTIONS)
        val rows = JSONArray()
        var encodedBytes = 128L
        options.sections.forEach { section ->
            require(idPattern.matches(section.id))
            val row = JSONObject().put("id", section.id).put("kind", section.kind.name.lowercase(Locale.ROOT))
                .put("visible", section.visible)
            if (section.kind == HomeSectionKind.CUSTOM) {
                row.put("name", name(section.name)).put("items", JSONArray(items(section.items, MAX_ITEMS)))
            } else require(section.name.isEmpty() && section.items.isEmpty())
            encodedBytes += row.toString().toByteArray(Charsets.UTF_8).size + 1L
            require(encodedBytes <= MAX_BYTES)
            rows.put(row)
        }
        val favorites = JSONArray(items(options.favorites, MAX_FAVORITES))
        encodedBytes += favorites.toString().toByteArray(Charsets.UTF_8).size
        require(encodedBytes <= MAX_BYTES)
        val result = JSONObject().put("version", VERSION).put("sections", rows).put("favorites", favorites).toString()
        decode(result) // Protect callers from producing lossy/invalid snapshots.
        return result
    }
}
