package tv.orbit.shield

import android.content.SharedPreferences
import org.json.JSONArray

private fun portablePreferenceText(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val ch = value[index++]
        if (ch.isHighSurrogate()) {
            if (index >= value.length || !value[index++].isLowSurrogate()) return false
        } else if (ch.isLowSurrogate()) return false
    }
    return true
}

/** Provides one transactional slot for an explicitly selected subset of preferences.
 * Unselected preferences are never removed. This does not itself prevent other code
 * from reading or editing preferences during recovery; the caller must gate all access. */
internal class PortableAndroidPreferencesTransactionStorage(
    private val files: PortableTransactionStorage,
    private val preferences: SharedPreferences,
    selectedKeys: Set<String>,
    private val slot: String = "orbit-prefs",
) : PortableTransactionStorage {
    private val keys = selectedKeys.toSortedSet()
    private val maxBytes = 24 * 1024 * 1024
    companion object {
        /** Call only after verifying the enclosing journal checksum and slot identity. */
        fun keysFromSnapshot(text: String): Set<String> {
            requirePortableJson(text, 24 * 1024 * 1024)
            val root = JSONArray(text)
            require(root.length() == 3 && root.opt(0) == 1)
            val array = root.opt(1) as? JSONArray ?: error("Invalid preference keys")
            require(array.length() in 1..16384)
            val keys = (0 until array.length()).map { array.opt(it) as? String ?: error("Invalid preference key") }
            require(keys.all { it.isNotBlank() && it.length <= 1024 && it.none(Char::isISOControl) && portablePreferenceText(it) })
            require(keys == keys.distinct().sorted()) { "Invalid preference key order" }
            return keys.toSet()
        }
    }
    init {
        require(slot.matches(Regex("[a-z][a-z0-9._-]{0,79}")))
        require(keys.size in 1..16384 && keys.all { it.isNotBlank() && it.length <= 1024 && it.none(Char::isISOControl) && portablePreferenceText(it) })
    }
    private fun encode(values: Map<String, *>): String {
        val entries = JSONArray()
        keys.forEach { key ->
            if (values.containsKey(key)) {
                val value = values[key] ?: error("Invalid preference")
                val row = JSONArray().put(key)
                when (value) {
                    is String -> { require(portablePreferenceText(value)); row.put("s").put(value) }
                    is Boolean -> row.put("b").put(value)
                    is Int -> row.put("i").put(value.toString())
                    is Long -> row.put("l").put(value.toString())
                    is Float -> { require(value.isFinite()); row.put("f").put(value.toRawBits().toString()) }
                    is Set<*> -> {
                        require(value.size <= 8192)
                        val list = value.map { (it as? String ?: error("Invalid preference set")).also { text -> require(portablePreferenceText(text)) } }.sorted()
                        row.put("ss").put(JSONArray(list))
                    }
                    else -> error("Unsupported preference")
                }
                entries.put(row)
            }
        }
        return JSONArray().put(1).put(JSONArray(keys.toList())).put(entries).toString().also { requirePortableJson(it, maxBytes) }
    }
    internal fun snapshotFromValues(values: Map<String, *>): String = encode(values)
    private fun decode(text: String): Map<String, Any> {
        requirePortableJson(text, maxBytes)
        val root = JSONArray(text)
        require(keysFromSnapshot(text) == keys) { "Preference selection changed" }
        val rows = root.opt(2) as? JSONArray ?: error("Invalid preference snapshot")
        require(rows.length() <= keys.size)
        val result = linkedMapOf<String, Any>()
        for (i in 0 until rows.length()) {
            val row = rows.opt(i) as? JSONArray ?: error("Invalid preference entry")
            require(row.length() == 3)
            val key = row.opt(0) as? String ?: error("Invalid preference key")
            require(key in keys && key !in result) { "Unexpected preference key" }
            val type = row.opt(1) as? String ?: error("Invalid preference type")
            val value = row.opt(2) ?: error("Invalid preference value")
            result[key] = when (type) {
                "s" -> value as? String ?: error("Invalid preference string")
                "b" -> value as? Boolean ?: error("Invalid preference boolean")
                "i" -> (value as? String)?.toIntOrNull() ?: error("Invalid preference integer")
                "l" -> (value as? String)?.toLongOrNull() ?: error("Invalid preference long")
                "f" -> Float.fromBits((value as? String)?.toIntOrNull() ?: error("Invalid preference float"))
                    .also { require(it.isFinite()) }
                "ss" -> {
                    val array = value as? JSONArray ?: error("Invalid preference set")
                    require(array.length() <= 8192)
                    val list = (0 until array.length()).map { array.opt(it) as? String ?: error("Invalid set entry") }
                    require(list.distinct().size == list.size)
                    list.toSet()
                }
                else -> error("Unsupported preference type")
            }
        }
        // Require one canonical representation for transaction comparisons and recovery.
        require(encode(result) == text) { "Noncanonical preference snapshot" }
        return result
    }
    internal fun decodeSnapshot(text: String): Map<String, Any> = decode(text)
    override fun read(slot: String): String? = if (slot == this.slot) encode(preferences.all) else files.read(slot)
    override fun write(slot: String, value: String?) {
        if (slot != this.slot) { files.write(slot, value); return }
        val values = decode(requireNotNull(value))
        val edit = preferences.edit()
        keys.forEach { key ->
            when (val item = values[key]) {
                null -> edit.remove(key)
                is String -> edit.putString(key, item)
                is Boolean -> edit.putBoolean(key, item)
                is Int -> edit.putInt(key, item)
                is Long -> edit.putLong(key, item)
                is Float -> edit.putFloat(key, item)
                is Set<*> -> edit.putStringSet(key, item.map { it as String }.toSet())
                else -> error("Unsupported preference")
            }
        }
        check(edit.commit()) { "Preference commit failed" }
        check(read(slot) == value) { "Preference readback failed" }
    }
    override fun readJournal() = files.readJournal()
    override fun writeJournal(value: String) = files.writeJournal(value)
    override fun clearJournal() = files.clearJournal()
}
