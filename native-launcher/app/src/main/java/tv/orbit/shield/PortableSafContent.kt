package tv.orbit.shield

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract as Docs
import java.io.IOException
import java.io.InputStream

/** Read-only SAF adapter for an already granted tree; no permission is requested here. */
internal class PortableSafContent(private val resolver: ContentResolver, private val tree: Uri) : PortableContentTree {
    init { require(tree.scheme == "content" && !tree.authority.isNullOrBlank() && Docs.isTreeUri(tree)) }
    override val rootId: String = Docs.getTreeDocumentId(tree)
    private fun uri(id: String): Uri = Docs.buildDocumentUriUsingTree(tree, id)
    override fun children(parentId: String): List<PortableContentEntry> {
        val childUri = Docs.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME, Docs.Document.COLUMN_MIME_TYPE)
        return resolver.query(childUri, columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (size >= 10000) throw IOException("Too many documents")
                    val id = cursor.getString(0) ?: throw IOException("Missing document ID")
                    val name = cursor.getString(1) ?: throw IOException("Missing document name")
                    val mime = cursor.getString(2) ?: throw IOException("Missing document type")
                    add(PortableContentEntry(id, name, mime == Docs.Document.MIME_TYPE_DIR))
                }
            }
        } ?: throw IOException("Unable to list directory")
    }
    override fun isChild(parentId: String, childId: String): Boolean =
        portableSafDirectChild(resolver, tree, parentId, childId)
    override fun openRead(id: String): InputStream = resolver.openInputStream(uri(id)) ?: throw IOException("Cannot open document")
}
