package tv.orbit.shield

import org.json.JSONArray

/** Stable IDs only; unknown but valid package IDs survive an older app/export round trip. */
internal object AppCatalogPreferencesCodec {
    private const val MAX_ITEMS = 128
    private const val MAX_TEXT = 8192
    private val packageId = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")

    private fun checkedIds(ids: Collection<String>): List<String> {
        require(ids.size <= MAX_ITEMS) { "Too many catalog entries" }
        require(ids.all { it.length <= 255 && packageId.matches(it) }) { "Invalid catalog package" }
        return ids.distinct()
    }

    fun decodeOrder(serialized: String): List<String> {
        requirePortableJson(serialized, MAX_TEXT)
        val array = JSONArray(serialized)
        require(array.length() <= MAX_ITEMS) { "Too many catalog entries" }
        return checkedIds((0 until array.length()).map { array.opt(it) as? String ?: error("Invalid catalog entry") })
    }

    fun encodeOrder(ids: Collection<String>): String = JSONArray(checkedIds(ids)).toString().also {
        require(it.length <= MAX_TEXT) { "Catalog order is too large" }
    }

    fun checkedHidden(ids: Set<String>): Set<String> = checkedIds(ids).toSet()
}
