package tv.orbit.shield

import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

internal data class PortableSnapshot(
    val id: String,
    val index: PortableGameIndex,
    val home: HomeSectionsOptions,
    val settings: Map<String, Any>,
)

/** One self-contained state generation. Checksums detect damage, not malicious edits.
 * This codec validates the entire document before returning, but does not verify ROM
 * files, acquire permissions, commit a generation or mutate any live state. */
internal object PortableSnapshotCodec {
    private const val MAX_ENVELOPE_BYTES = 40 * 1024 * 1024
    private val uuid = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private val sha = Regex("[a-f0-9]{64}")
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun JSONObject.exactKeys(keys: Set<String>) {
        require(length() == keys.size)
        val iterator = keys()
        while (iterator.hasNext()) require(iterator.next() in keys) { "Unknown snapshot field" }
    }
    fun newId(): String = UUID.randomUUID().toString()
    private fun part(value: String): JSONObject = JSONObject().put("content", value).put("sha256", digest(value))
    private fun readPart(root: JSONObject, key: String): String {
        val part = root.opt(key) as? JSONObject ?: error("Missing snapshot part")
        part.exactKeys(setOf("content", "sha256"))
        val value = part.opt("content") as? String ?: error("Invalid snapshot part")
        val expected = part.opt("sha256") as? String ?: error("Missing snapshot digest")
        require(sha.matches(expected) && digest(value) == expected) { "Damaged snapshot part" }
        return value
    }
    fun encode(snapshot: PortableSnapshot): String {
        require(uuid.matches(snapshot.id)) { "Invalid snapshot ID" }
        val root = JSONObject().put("version", 1).put("id", snapshot.id)
            .put("index", part(PortableGameIndexCodec.encode(snapshot.index)))
            .put("home", part(PortableHomeStateCodec.encode(snapshot.home)))
            .put("settings", part(PortableSettingsCodec.encode(snapshot.settings)))
        return root.toString().also { requirePortableJson(it, MAX_ENVELOPE_BYTES) }
    }
    fun decode(serialized: String): PortableSnapshot {
        requirePortableJson(serialized, MAX_ENVELOPE_BYTES)
        val root = JSONObject(serialized)
        root.exactKeys(setOf("version", "id", "index", "home", "settings"))
        require(root.opt("version") == 1) { "Unsupported snapshot version" }
        val id = root.opt("id") as? String ?: error("Missing snapshot ID")
        require(uuid.matches(id)) { "Invalid snapshot ID" }
        // Verify all bytes before parsing and exposing any section.
        val index = readPart(root, "index")
        val home = readPart(root, "home")
        val settings = readPart(root, "settings")
        return PortableSnapshot(id, PortableGameIndexCodec.decode(index), PortableHomeStateCodec.decode(home),
            PortableSettingsCodec.decode(settings))
    }
}
