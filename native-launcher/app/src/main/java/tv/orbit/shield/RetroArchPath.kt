package tv.orbit.shield

import java.io.File

/** Only ExternalStorageProvider IDs have this volume-relative filesystem meaning. */
internal object RetroArchPath {
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    fun externalDocument(authority: String?, documentId: String, treeId: String?, volumes: Map<String, String>): String? {
        if (authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val document = splitId(documentId) ?: return null
        if (document.second.isEmpty()) return null
        if (treeId != null) {
            val tree = splitId(treeId) ?: return null
            if (!tree.first.equals(document.first, ignoreCase = true)) return null
            if (tree.second.isNotEmpty() && document.second != tree.second && !document.second.startsWith(tree.second + "/")) return null
        }
        val root = volumes.entries.firstOrNull { it.key.equals(document.first, ignoreCase = true) }?.value ?: return null
        return insideRoot(File(root, document.second), root)
    }

    fun storedFile(path: String, volumes: Map<String, String>): String? {
        if (!File(path).isAbsolute || '\u0000' in path) return null
        return volumes.values.firstNotNullOfOrNull { insideRoot(File(path), it) }
    }

    private fun splitId(id: String): Pair<String, String>? {
        val split = id.indexOf(':')
        if (split <= 0 || '\u0000' in id || '\\' in id) return null
        val volume = id.substring(0, split)
        val relative = id.substring(split + 1).trimEnd('/')
        if ('/' in volume || relative.startsWith('/')) return null
        if (relative.isNotEmpty() && relative.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return volume to relative
    }

    private fun insideRoot(file: File, rootPath: String): String? = runCatching {
        val root = File(rootPath)
        if (!root.isAbsolute) return@runCatching null
        val base = root.canonicalFile.toPath()
        val candidate = file.canonicalFile.toPath()
        candidate.toString().takeIf { candidate != base && candidate.startsWith(base) }
    }.getOrNull()
}
