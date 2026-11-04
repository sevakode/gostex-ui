package tv.orbit.shield

/** Path-based SAF representation used by the installed DuckStation 12010 FileHelper.
 * Intent.data still carries the real content URI, so Android grants read access to it.
 * The native bootPath is a path, not the percent-encoded content URI: relative CUE/M3U
 * names must remain siblings when DuckStation combines them with the boot filename.
 */
internal object DuckStationPath {
    fun externalDocument(authority: String, treeId: String?, documentId: String): String {
        require(authority == "com.android.externalstorage.documents")
        if (treeId != null) {
            val prefix = if (treeId.endsWith(':')) treeId else treeId.trimEnd('/') + "/"
            require(documentId == treeId || documentId.startsWith(prefix))
        }
        fun part(kind: String, id: String): String {
            val volume = id.substringBefore(':')
            val path = id.substringAfter(':', "")
            require(':' in id && volume.isNotEmpty() && '/' !in volume)
            return "/$kind@$volume" + if (path.isEmpty()) "" else "/$path"
        }
        return "/android_content/$authority" + (treeId?.let { part("tree", it) } ?: "") + part("document", documentId)
    }
}
