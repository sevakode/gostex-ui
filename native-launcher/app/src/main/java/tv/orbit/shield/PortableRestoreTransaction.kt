package tv.orbit.shield

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** A store must durably and atomically replace its whole value (including null), and its
 * read must report the last committed value after a process crash. Journal methods have
 * the same durability requirement. The caller must hold an exclusive gate respected by
 * every writer AND reader from before begin until recovery or commit is complete. The
 * protocol alone does not make SharedPreferences and files mutually atomic to readers. */
internal interface PortableTransactionStorage {
    fun read(slot: String): String?
    fun write(slot: String, value: String?)
    fun readJournal(): String?
    fun writeJournal(value: String)
    fun clearJournal()
}

internal data class PortableTransactionChange(val slot: String, val before: String?, val after: String?)
internal data class PortableTransactionRecord(val committed: Boolean, val changes: List<PortableTransactionChange>)

/** Journal is private state: never place its values in reports or error messages. */
internal object PortableRestoreTransaction {
    private const val MAX_BYTES = 48 * 1024 * 1024
    private val slotName = Regex("[a-z][a-z0-9._-]{0,79}")
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun valid(record: PortableTransactionRecord) {
        require(record.changes.isNotEmpty() && record.changes.size <= 32)
        require(record.changes.map { it.slot }.distinct().size == record.changes.size)
        record.changes.forEach { require(slotName.matches(it.slot) && it.before != it.after) }
    }
    private fun exact(obj: JSONObject, expected: Set<String>) {
        require(obj.length() == expected.size)
        val keys = obj.keys()
        while (keys.hasNext()) require(keys.next() in expected)
    }
    private fun putNullable(obj: JSONObject, key: String, value: String?) { obj.put(key, value ?: JSONObject.NULL) }
    private fun nullable(obj: JSONObject, key: String): String? {
        require(obj.has(key))
        val v = obj.get(key)
        return if (v === JSONObject.NULL) null else v as? String ?: error("Invalid journal value")
    }
    fun encode(record: PortableTransactionRecord): String {
        valid(record)
        val rows = JSONArray()
        record.changes.forEach { change ->
            val row = JSONObject().put("slot", change.slot)
            putNullable(row, "before", change.before); putNullable(row, "after", change.after)
            rows.put(row)
        }
        val body = JSONObject().put("version", 1).put("committed", record.committed).put("changes", rows).toString()
        val envelope = JSONObject().put("body", body).put("sha256", hash(body)).toString()
        require(envelope.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        return envelope
    }
    fun decode(text: String): PortableTransactionRecord {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val envelope = JSONObject(text)
        exact(envelope, setOf("body", "sha256"))
        val body = envelope.get("body") as? String ?: error("Invalid journal")
        require(envelope.opt("sha256") == hash(body)) { "Damaged journal" }
        val root = JSONObject(body)
        exact(root, setOf("version", "committed", "changes"))
        require(root.opt("version") == 1)
        val committed = root.get("committed") as? Boolean ?: error("Invalid journal")
        val rows = root.getJSONArray("changes")
        require(rows.length() in 1..32)
        val changes = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            exact(row, setOf("slot", "before", "after"))
            PortableTransactionChange(row.get("slot") as? String ?: error("Invalid slot"), nullable(row, "before"), nullable(row, "after"))
        }
        return PortableTransactionRecord(committed, changes).also(::valid)
    }
    fun begin(storage: PortableTransactionStorage, changes: List<PortableTransactionChange>) {
        val record = PortableTransactionRecord(false, changes.toList())
        val prepared = encode(record)
        check(storage.readJournal() == null) { "Recovery required" }
        check(record.changes.all { storage.read(it.slot) == it.before }) { "State changed before restore" }
        storage.writeJournal(prepared)
        record.changes.forEach {
            check(storage.read(it.slot) == it.before) { "State changed during restore" }
            storage.write(it.slot, it.after)
        }
        check(record.changes.all { storage.read(it.slot) == it.after }) { "State changed during restore" }
        storage.writeJournal(encode(record.copy(committed = true)))
        storage.clearJournal()
    }
    fun recover(storage: PortableTransactionStorage): Boolean {
        val serialized = storage.readJournal() ?: return false
        val record = decode(serialized)
        if (record.committed) {
            check(record.changes.all { storage.read(it.slot) == it.after }) { "Committed restore state changed" }
        } else {
            // Examine all stores before changing any: never overwrite an unknown generation.
            check(record.changes.all { storage.read(it.slot).let { value -> value == it.before || value == it.after } }) {
                "Restore recovery found changed state"
            }
            record.changes.asReversed().forEach {
                if (storage.read(it.slot) == it.after) storage.write(it.slot, it.before)
            }
            check(record.changes.all { storage.read(it.slot) == it.before }) { "Restore rollback incomplete" }
        }
        storage.clearJournal()
        return true
    }
}
