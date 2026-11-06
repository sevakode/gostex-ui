package tv.orbit.shield

import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal data class PortableWritableEntry(val id: String, val name: String, val directory: Boolean)
internal interface PortableWritableTree {
    val rootId: String
    fun children(parentId: String): List<PortableWritableEntry>
    fun create(parentId: String, name: String, directory: Boolean): PortableWritableEntry
    fun isChild(parentId: String, childId: String): Boolean
    fun openWrite(id: String): OutputStream
    fun openRead(id: String): InputStream
}
internal data class PortableCopyInput(val id: String, val path: List<String>, val expectedBytes: Long,
                                      val openSource: () -> InputStream)
internal data class PortableCopiedContent(val id: String, val path: List<String>, val documentId: String,
                                          val receipt: PortableCopyReceipt)
internal data class PortableGeneratedContent(val generationId: String, val generationName: String,
                                             val files: List<PortableCopiedContent>)

/** Copies into a fresh generation only, retaining paths needed by CUE/M3U.
 * A failed/cancelled copy leaves an uncommitted generation; it never deletes or
 * overwrites. Callers must serialize writes, protect source IDs for the same provider,
 * and check source identity/stability before and after every copy. A successful
 * result is not a committed backup until its manifest is separately published. */
internal object PortableGenerationWriter {
    fun copy(tree: PortableWritableTree, inputs: List<PortableCopyInput>, protectedDocumentIds: Set<String>,
             checkSource: (PortableCopyInput) -> Unit, checkActive: () -> Unit = {},
             newGenerationName: () -> String = { "gostex-content-${UUID.randomUUID().toString().replace("-", "")}" },
             onFile: (String, Int, Int) -> Unit = { _, _, _ -> }): PortableGeneratedContent {
        require(tree.rootId.isNotBlank() && inputs.size <= 32768)
        val ids = mutableSetOf<String>()
        val paths = mutableSetOf<List<String>>()
        val validated = inputs.map { input ->
            require(input.id.isNotBlank() && ids.add(input.id) && input.expectedBytes >= 0)
            val path = PortablePath.fromComponents(input.path) ?: error("Invalid destination path")
            require(paths.add(path.components)) { "Duplicate destination path" }
            input to path.components
        }
        for (path in paths) for (i in 1 until path.size)
            require(path.take(i) !in paths) { "File conflicts with directory" }
        val name = newGenerationName()
        require(name.matches(Regex("gostex-content-[a-f0-9]{32}")))
        val createdIds = mutableSetOf(tree.rootId)
        fun fresh(parent: String, childName: String, directory: Boolean): PortableWritableEntry {
            checkActive()
            val before = tree.children(parent)
            check(before.map { it.id }.distinct().size == before.size && before.none { it.name == childName }) { "Destination collision" }
            val result = tree.create(parent, childName, directory)
            check(result.id.isNotBlank() && result.id !in protectedDocumentIds && result.id !in createdIds &&
                before.none { it.id == result.id } && result.name == childName && result.directory == directory) { "Unexpected new document" }
            check(tree.isChild(parent, result.id)) { "Document outside destination" }
            val matching = tree.children(parent).filter { it.name == childName }
            check(matching.size == 1 && matching.single() == result) { "Ambiguous destination" }
            createdIds += result.id
            return result
        }
        val generation = fresh(tree.rootId, name, true)
        val directories = mutableMapOf<List<String>, String>(emptyList<String>() to generation.id)
        val copied = mutableListOf<PortableCopiedContent>()
        for ((index, pair) in validated.withIndex()) {
            val (input, path) = pair
            checkActive(); onFile(input.id, index, validated.size); checkSource(input)
            var parent = generation.id
            for (i in 1 until path.size) {
                val prefix = path.take(i)
                parent = directories[prefix] ?: fresh(parent, path[i - 1], true).id.also { directories[prefix] = it }
            }
            val file = fresh(parent, path.last(), false)
            val destination = object : PortableCopyDestination {
                override fun openNew() = tree.openWrite(file.id)
                override fun openForVerification() = tree.openRead(file.id)
            }
            val receipt = portableVerifiedCopy(input.openSource, destination, input.expectedBytes, checkActive)
            check(receipt.bytes == input.expectedBytes) { "Source length changed" }
            checkSource(input)
            copied += PortableCopiedContent(input.id, listOf(name) + path, file.id, receipt)
        }
        return PortableGeneratedContent(generation.id, name, copied.toList())
    }
}
