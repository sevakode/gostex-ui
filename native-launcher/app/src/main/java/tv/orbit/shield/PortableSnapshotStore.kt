package tv.orbit.shield

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import org.json.JSONObject

internal data class PortableCommittedSnapshot(val snapshot: PortableSnapshot, val commitDocumentId: String)

/** Append-only generations: the small commit document is created only after the state
 * document is written and read back successfully. Invalid/partial records are never
 * accepted. Existing documents are never overwritten or deleted. Run off the UI thread.
 * This stores state only; it does not prove that referenced game files exist or match. */
internal object PortableSnapshotStore {
    private const val MAX_STATE = 40 * 1024 * 1024
    private const val MAX_COMMIT = 4096
    private val commitName = Regex("gostex-commit-([a-f0-9]{32})\\.json")
    private val digestPattern = Regex("[a-f0-9]{64}")
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun read(directory: PortableStagingDirectory, id: String, limit: Int, checkActive: () -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        directory.openRead(id).use { input ->
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count == -1) break
                if (count <= 0 || count > limit - output.size()) throw IOException("Document exceeds limit or cannot be read")
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }
    private fun text(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
    private fun exactKeys(obj: JSONObject, keys: Set<String>) {
        require(obj.length() == keys.size)
        val iterator = obj.keys()
        while (iterator.hasNext()) require(iterator.next() in keys)
    }

    fun write(directory: PortableStagingDirectory, snapshot: PortableSnapshot, checkActive: () -> Unit = {},
              beforeCommit: () -> Unit = {}): PortableStagedFile {
        val content = PortableSnapshotCodec.encode(snapshot).toByteArray(Charsets.UTF_8)
        require(content.size <= MAX_STATE)
        val hex = snapshot.id.replace("-", "")
        val stateName = "gostex-state-$hex.json"
        val commitName = "gostex-commit-$hex.json"
        // Reject retries of a generation instead of touching a partially written one.
        val before = directory.children()
        require(before.none { it.name == stateName || it.name == commitName }) { "Generation already exists" }
        val protected = before.map { it.id }.toSet()
        val state = portableStageFile(directory, { ByteArrayInputStream(content) }, MAX_STATE.toLong(), protected,
            checkActive, newName = { stateName })
        val marker = JSONObject().put("version", 1).put("id", snapshot.id).put("state", state.name)
            .put("bytes", state.receipt.bytes).put("sha256", state.receipt.sha256).toString().toByteArray(Charsets.UTF_8)
        require(marker.size <= MAX_COMMIT)
        checkActive(); beforeCommit(); checkActive()
        return portableStageFile(directory, { ByteArrayInputStream(marker) }, MAX_COMMIT.toLong(),
            protected + state.documentId, checkActive, newName = { commitName })
    }

    /** Caller chooses a record; incomplete or ambiguous records throw and do not mutate anything. */
    fun readCommitted(directory: PortableStagingDirectory, commitId: String, checkActive: () -> Unit = {}): PortableCommittedSnapshot {
        val children = directory.children()
        require(children.map { it.id }.distinct().size == children.size)
        val commit = children.singleOrNull { it.id == commitId } ?: error("Missing commit")
        val match = commitName.matchEntire(commit.name) ?: error("Invalid commit name")
        require(children.count { it.name == commit.name } == 1 && directory.isChild(commit.id))
        val markerText = text(read(directory, commit.id, MAX_COMMIT, checkActive))
        requirePortableJson(markerText, MAX_COMMIT)
        val marker = JSONObject(markerText)
        exactKeys(marker, setOf("version", "id", "state", "bytes", "sha256"))
        require(marker.opt("version") == 1)
        val id = marker.opt("id") as? String ?: error("Invalid generation")
        require(id.replace("-", "") == match.groupValues[1])
        val expectedName = "gostex-state-${match.groupValues[1]}.json"
        require(marker.opt("state") == expectedName)
        val length = marker.opt("bytes")
        require(length is Int || length is Long)
        val expectedLength = (length as Number).toLong()
        require(expectedLength in 0..MAX_STATE.toLong())
        val expectedHash = marker.opt("sha256") as? String ?: error("Invalid digest")
        require(digestPattern.matches(expectedHash))
        val state = children.singleOrNull { it.name == expectedName } ?: error("Missing or ambiguous state")
        require(state.id != commit.id && directory.isChild(state.id))
        val bytes = read(directory, state.id, MAX_STATE, checkActive)
        require(bytes.size.toLong() == expectedLength && digest(bytes) == expectedHash) { "Damaged state document" }
        val snapshot = PortableSnapshotCodec.decode(text(bytes))
        require(snapshot.id == id)
        return PortableCommittedSnapshot(snapshot, commit.id)
    }
}
