package tv.orbit.shield

import org.json.JSONObject

internal const val MANAGED_COVER_PREFIX = "managed-cover:"
internal fun coverDigestValid(value: String) = value.matches(Regex("[a-f0-9]{64}"))
internal fun managedCoverKey(digest: String) = MANAGED_COVER_PREFIX + digest
internal fun managedCoverDigest(key: String): String? = key.removePrefix(MANAGED_COVER_PREFIX)
    .takeIf { key.startsWith(MANAGED_COVER_PREFIX) && coverDigestValid(it) }

internal data class ManagedCover(val manual: String = "", val original: String = "", val wide: String = "",
    val sourceName: String = "", val sourceUrl: String = "", val author: String = "")
internal enum class CoverChoice(val id: String) {
    AUTO("auto"), MANUAL("manual"), ORIGINAL("original");
    companion object { fun read(value: String?) = entries.firstOrNull { it.id == value } ?: AUTO }
}
internal data class SelectedCover(val original: String, val wide: String = "")
internal fun selectManagedCover(bundled: String, entry: ManagedCover?, choice: CoverChoice): SelectedCover = when {
    entry == null || choice == CoverChoice.ORIGINAL -> SelectedCover(bundled)
    choice == CoverChoice.MANUAL && entry.manual.isNotEmpty() -> SelectedCover(entry.manual)
    else -> SelectedCover(entry.original.ifEmpty { bundled }, entry.wide)
}

internal data class CoverUpdate(val gameId: String, val original: String, val originalSha256: String,
    val wide: String, val wideSha256: String)

/** Files are resolved beneath a user-granted tree, never as arbitrary filesystem paths. */
internal object ManagedCoverManifest {
    const val MAX_BYTES = 512 * 1024
    const val MAX_ENTRIES = 512
    fun imagePath(value: String): Boolean = value.length in 1..200 &&
        value.split('/').size <= 4 && value.split('/').all {
            it.isNotEmpty() && it != "." && it != ".." && it.matches(Regex("[A-Za-z0-9_.-]+"))
        } && value.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp")

    fun parse(text: String): List<CoverUpdate> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val root = JSONObject(text)
        require(root.opt("version") == 1)
        val rows = root.getJSONArray("covers")
        require(rows.length() <= MAX_ENTRIES)
        val seen = mutableSetOf<String>()
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val id = row.getString("gameId")
            require(id.isNotBlank() && id.length <= 200 && seen.add(id))
            fun image(key: String): Pair<String, String> {
                if (!row.has(key)) return "" to ""
                val path = row.getString(key)
                val digest = row.getString(key + "Sha256")
                require(imagePath(path) && coverDigestValid(digest))
                return path to digest
            }
            val original = image("original")
            val wide = image("wide")
            require(original.first.isNotEmpty() || wide.first.isNotEmpty())
            CoverUpdate(id, original.first, original.second, wide.first, wide.second)
        }
    }

    fun decodeIndex(text: String): Map<String, ManagedCover> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val root = JSONObject(text)
        require(root.opt("version") == 1)
        val entries = root.getJSONObject("games")
        require(entries.length() <= MAX_ENTRIES)
        return buildMap {
            entries.keys().forEach { id ->
                val row = entries.getJSONObject(id)
                fun key(name: String) = row.optString(name).takeIf { managedCoverDigest(it) != null }.orEmpty()
                if (id.isNotBlank() && id.length <= 200) put(id, ManagedCover(key("manual"), key("original"), key("wide"), row.optString("sourceName").take(100),
                    row.optString("sourceUrl").take(1000), row.optString("author").take(120)))
            }
        }
    }

    fun encodeIndex(entries: Map<String, ManagedCover>): String {
        val rows = JSONObject()
        entries.toSortedMap().forEach { (id, entry) -> rows.put(id, JSONObject()
            .put("manual", entry.manual).put("original", entry.original).put("wide", entry.wide)
            .put("sourceName",entry.sourceName).put("sourceUrl",entry.sourceUrl).put("author",entry.author)) }
        return JSONObject().put("version", 1).put("games", rows).toString()
    }
}
