package tv.orbit.shield

import org.json.JSONArray
import org.json.JSONObject

internal data class LibraryRootRecord(val platform: String, val uri: String, val source: LibrarySource)

/** Legacy installations use an array. A portable root requires an object envelope,
 * so older clients reject the document instead of treating the root as a normal scan. */
internal object LibraryRootCodec {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private fun bounded(value: String) {
        require(value.length <= MAX_BYTES && value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
    }
    private fun records(array: JSONArray, strict: Boolean): List<LibraryRootRecord> {
        require(array.length() <= 32768)
        val seen = mutableSetOf<Pair<String, String>>()
        return (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            if (strict) {
                require(row.length() == 3)
                val fields = row.keys(); while (fields.hasNext()) require(fields.next() in setOf("platform", "uri", "source"))
            }
            val platform = (row.opt("platform") as? String)?.takeIf { it.isNotBlank() } ?: error("Invalid platform")
            val uri = (row.opt("uri") as? String)?.takeIf { it.isNotBlank() } ?: error("Invalid URI")
            val raw = row.optString("source")
            val source = LibrarySource.read(raw, platform)
            if (strict) {
                require(raw in LibrarySource.entries.map { it.value } && source.value == raw)
                require(platform.length <= 512 && uri.length <= 8192 && seen.add(platform to uri))
            } else require(source != LibrarySource.PORTABLE_SNAPSHOT) { "Portable roots require a versioned document" }
            LibraryRootRecord(platform, uri, source)
        }
    }
    fun decode(serialized: String): List<LibraryRootRecord> {
        bounded(serialized)
        return if (serialized.trimStart().startsWith('[')) records(JSONArray(serialized), false) else {
            val root = JSONObject(serialized)
            require(root.length() == 2 && root.opt("version") == 2 && root.has("roots"))
            records(root.getJSONArray("roots"), true)
        }
    }
    /** Refuse to migrate an unknown legacy extension or source without preserving it. */
    fun requireLosslessLegacy(serialized: String) {
        bounded(serialized)
        if (!serialized.trimStart().startsWith('[')) { decode(serialized); return }
        val rows = JSONArray(serialized)
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val fields = row.keys(); while (fields.hasNext()) require(fields.next() in setOf("platform", "uri", "source"))
            if (row.has("source")) {
                val value = row.opt("source") as? String ?: error("Invalid legacy source")
                val platform = row.opt("platform") as? String ?: error("Invalid platform")
                require(value in LibrarySource.entries.map { it.value } && LibrarySource.read(value, platform).value == value && value != LibrarySource.PORTABLE_SNAPSHOT.value)
            }
        }
    }
    fun encode(roots: List<LibraryRootRecord>): String {
        val rows = JSONArray()
        roots.forEach { rows.put(JSONObject().put("platform", it.platform).put("uri", it.uri).put("source", it.source.value)) }
        val text = if (roots.any { it.source == LibrarySource.PORTABLE_SNAPSHOT })
            JSONObject().put("version", 2).put("roots", rows).toString() else rows.toString()
        bounded(text)
        decode(text)
        return text
    }
}
