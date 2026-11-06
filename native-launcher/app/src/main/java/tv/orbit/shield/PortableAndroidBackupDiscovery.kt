package tv.orbit.shield

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract as Docs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class PortableBackupCandidate(val documentId: String, val name: String, val modified: Long?)

/** Lists possible committed records without reading ROMs or trusting their contents.
 * A chosen candidate must still pass the full snapshot/content verifier. */
internal suspend fun discoverPortableBackups(resolver: ContentResolver, tree: Uri): List<PortableBackupCandidate> = withContext(Dispatchers.IO) {
    require(tree.scheme == "content" && Docs.isTreeUri(tree))
    val scope = currentCoroutineContext()
    val rootId = Docs.getTreeDocumentId(tree)
    val uri = Docs.buildChildDocumentsUriUsingTree(tree, rootId)
    val columns = arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME,
        Docs.Document.COLUMN_MIME_TYPE, Docs.Document.COLUMN_LAST_MODIFIED)
    val namePattern = Regex("gostex-commit-[a-f0-9]{32}\\.json")
    val found = resolver.query(uri, columns, null, null, null)?.use { cursor ->
        val result = mutableListOf<PortableBackupCandidate>()
        val seenIds = mutableSetOf<String>()
        val seenNames = mutableSetOf<String>()
        var count = 0
        while (cursor.moveToNext()) {
            scope.ensureActive()
            check(++count <= 4096) { "Too many folder entries" }
            val id = cursor.getString(0) ?: error("Missing document ID")
            val name = cursor.getString(1) ?: error("Missing document name")
            check(seenIds.add(id)) { "Ambiguous document ID" }
            if (!namePattern.matches(name)) continue
            check(seenNames.add(name) && result.size < 256) { "Ambiguous or excessive backups" }
            val mime = cursor.getString(2) ?: error("Missing document type")
            check(mime != Docs.Document.MIME_TYPE_DIR && portableSafDirectChild(resolver, tree, rootId, id)) { "Invalid backup document" }
            val modified = if (cursor.isNull(3)) null else cursor.getLong(3).takeIf { it > 0 }
            result += PortableBackupCandidate(id, name, modified)
        }
        result
    } ?: error("Cannot list folder")
    found.sortedWith(compareByDescending<PortableBackupCandidate> { it.modified ?: 0L }.thenBy { it.name })
}
