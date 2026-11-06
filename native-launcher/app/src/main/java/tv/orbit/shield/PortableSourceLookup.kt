package tv.orbit.shield

/** A source file under one explicitly granted external-storage tree. The document ID
 * is only meaningful on the current device and must not be stored as portable identity. */
internal data class PortableSourceFile(val path: List<String>, val documentId: String)

internal object PortableExternalDocumentIds {
    private data class Id(val volume: String, val components: List<String>)
    private fun parse(id: String): Id {
        val separator = id.indexOf(':')
        require(separator > 0)
        val volume = id.substring(0, separator)
        require(volume.none { it == '/' || it == '\\' || it.isISOControl() })
        val remainder = id.substring(separator + 1)
        val parts = if (remainder.isEmpty()) emptyList() else remainder.split('/')
        if (parts.isNotEmpty()) require(PortablePath.fromComponents(parts) != null)
        return Id(volume, parts)
    }

    fun primaryDocumentId(path: String): String {
        val prefix = "/storage/emulated/0/"
        require(path.startsWith(prefix)) { "Unsupported primary storage path" }
        val parts = path.removePrefix(prefix).split('/')
        require(PortablePath.fromComponents(parts) != null)
        return "primary:" + parts.joinToString("/")
    }

    fun relative(rootId: String, fileId: String): PortablePath {
        val root = parse(rootId)
        val file = parse(fileId)
        require(root.volume == file.volume) { "Different volume" }
        return portableRelativePath(root.components, file.components) ?: error("File outside selected root")
    }
}

/** Verify every path component against the live provider and its granted tree.
 * Unsupported or ambiguous sources fail instead of guessing by name. */
internal object PortableSourceLookup {
    fun locate(tree: PortableContentTree, documentId: String, checkActive: () -> Unit = {}): PortableSourceFile {
        val path = PortableExternalDocumentIds.relative(tree.rootId, documentId)
        var parent = tree.rootId
        val visited = mutableSetOf(parent)
        path.components.forEachIndexed { index, name ->
            checkActive()
            val rows = tree.children(parent)
            require(rows.size <= 10000 && rows.map { it.id }.distinct().size == rows.size) { "Ambiguous directory" }
            val matches = rows.filter { it.name == name }
            require(matches.size == 1) { "Missing or ambiguous file" }
            val child = matches.single()
            require(child.id.isNotBlank() && visited.add(child.id) && tree.isChild(parent, child.id)) { "Unverified document" }
            require(child.directory == (index < path.components.lastIndex)) { "Unexpected document type" }
            parent = child.id
        }
        require(parent == documentId) { "Document identity changed" }
        return PortableSourceFile(path.components, parent)
    }
}
