package tv.orbit.shield

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

internal data class PortableContentEntry(val id: String, val name: String, val directory: Boolean)
internal interface PortableContentTree {
    val rootId: String
    fun children(parentId: String): List<PortableContentEntry>
    fun isChild(parentId: String, childId: String): Boolean
    fun openRead(id: String): InputStream
}
internal enum class PortableContentProblem { MISSING, AMBIGUOUS, WRONG_TYPE, OUTSIDE_TREE, CORRUPT, UNAVAILABLE }
internal data class PortableContentIssue(val fileId: String, val problem: PortableContentProblem)
internal data class PortableContentVerification(val verified: Map<String, String>, val issues: List<PortableContentIssue>)
private class ContentFailure(val problem: PortableContentProblem) : Exception()

/** Resolves only exact components under the selected root, then hashes each indexed
 * file in bounded memory. Run off the UI thread. The caller supplies cancellation
 * (normally coroutine ensureActive); no paths, documents or live state are changed.
 * Provider data can change afterwards, so consumers must revalidate at use time. */
internal object PortableContentVerifier {
    fun verify(tree: PortableContentTree, index: PortableGameIndex, checkActive: () -> Unit = {},
               onProgress: (fileId: String, read: Long, expected: Long) -> Unit = { _, _, _ -> }): PortableContentVerification {
        // Reuse the strict index validator even when supplied by an in-memory caller.
        PortableGameIndexCodec.encode(index)
        require(tree.rootId.isNotBlank())
        val verified = linkedMapOf<String, String>()
        val issues = mutableListOf<PortableContentIssue>()
        val buffer = ByteArray(64 * 1024)
        for (file in index.files) {
            checkActive()
            try {
                val path = PortablePath.fromComponents(file.path) ?: error("Invalid path")
                var parent = tree.rootId
                val visited = mutableSetOf(parent)
                path.components.forEachIndexed { position, name ->
                    checkActive()
                    val rows = tree.children(parent)
                    if (rows.size > 10000 || rows.map { it.id }.distinct().size != rows.size)
                        throw ContentFailure(PortableContentProblem.AMBIGUOUS)
                    val matches = rows.filter { it.name == name }
                    if (matches.isEmpty()) throw ContentFailure(PortableContentProblem.MISSING)
                    if (matches.size != 1) throw ContentFailure(PortableContentProblem.AMBIGUOUS)
                    val entry = matches.single()
                    if (entry.id.isBlank() || !visited.add(entry.id) || !tree.isChild(parent, entry.id))
                        throw ContentFailure(PortableContentProblem.OUTSIDE_TREE)
                    if (entry.directory != (position < path.components.lastIndex))
                        throw ContentFailure(PortableContentProblem.WRONG_TYPE)
                    parent = entry.id
                }
                val hash = MessageDigest.getInstance("SHA-256")
                var size = 0L
                tree.openRead(parent).use { stream ->
                    while (true) {
                        checkActive()
                        val count = stream.read(buffer)
                        if (count == -1) break
                        if (count <= 0) throw IOException("File read did not progress")
                        if (count.toLong() > file.bytes - size) throw ContentFailure(PortableContentProblem.CORRUPT)
                        size += count
                        hash.update(buffer, 0, count)
                        onProgress(file.id, size, file.bytes)
                    }
                }
                val actual = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                if (size != file.bytes || actual != file.sha256) throw ContentFailure(PortableContentProblem.CORRUPT)
                verified[file.id] = parent
            } catch (e: ContentFailure) {
                issues += PortableContentIssue(file.id, e.problem)
            } catch (_: IOException) {
                issues += PortableContentIssue(file.id, PortableContentProblem.UNAVAILABLE)
            } catch (_: SecurityException) {
                issues += PortableContentIssue(file.id, PortableContentProblem.UNAVAILABLE)
            }
        }
        return PortableContentVerification(verified.toMap(), issues.toList())
    }
}
