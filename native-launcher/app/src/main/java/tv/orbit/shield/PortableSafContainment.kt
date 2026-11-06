package tv.orbit.shield

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract as Docs
import java.io.IOException

/** Check direct membership and tree-granted access, including on API 28 where the
 * public isChildDocument helper is unavailable. This relies on a conforming
 * DocumentsProvider and its tree-grant enforcement; it cannot defeat a dishonest
 * provider or a concurrent move after the check. Run off the UI thread. */
internal fun portableSafDirectChild(resolver: ContentResolver, tree: Uri, parentId: String, childId: String): Boolean {
    if (parentId.isBlank() || childId.isBlank() || parentId == childId) return false
    val columns = arrayOf(Docs.Document.COLUMN_DOCUMENT_ID)
    val children = Docs.buildChildDocumentsUriUsingTree(tree, parentId)
    val count = resolver.query(children, columns, null, null, null)?.use { cursor ->
        var rows = 0
        var matches = 0
        while (cursor.moveToNext()) {
            if (++rows > 10000) throw IOException("Too many documents")
            val id = cursor.getString(0) ?: throw IOException("Missing document ID")
            if (id == childId) matches++
        }
        matches
    } ?: throw IOException("Cannot list parent directory")
    if (count != 1) return false
    val child = Docs.buildDocumentUriUsingTree(tree, childId)
    val accessible = resolver.query(child, columns, null, null, null)?.use { cursor ->
        cursor.moveToFirst() && cursor.getString(0) == childId && !cursor.moveToNext()
    } ?: throw IOException("Cannot inspect child document")
    if (!accessible) return false
    return Build.VERSION.SDK_INT < 29 || Docs.isChildDocument(resolver, Docs.buildDocumentUriUsingTree(tree, parentId), child)
}
