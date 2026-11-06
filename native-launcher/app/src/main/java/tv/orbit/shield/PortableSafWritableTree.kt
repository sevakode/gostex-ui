package tv.orbit.shield

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract as Docs
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Writable view of an already granted tree; creation relies on the provider's
 * documented create-new contract. It never requests access or deletes documents. */
internal class PortableSafWritableTree(private val resolver: ContentResolver, private val tree: Uri) : PortableWritableTree {
    init { require(tree.scheme == "content" && !tree.authority.isNullOrBlank() && Docs.isTreeUri(tree)) }
    override val rootId = Docs.getTreeDocumentId(tree)
    private fun uri(id: String) = Docs.buildDocumentUriUsingTree(tree, id)
    override fun children(parentId: String): List<PortableWritableEntry> {
        val columns = arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME, Docs.Document.COLUMN_MIME_TYPE)
        return resolver.query(Docs.buildChildDocumentsUriUsingTree(tree, parentId), columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (size >= 10000) throw IOException("Too many destination documents")
                    val id = cursor.getString(0) ?: throw IOException("Missing document ID")
                    val name = cursor.getString(1) ?: throw IOException("Missing document name")
                    val mime = cursor.getString(2) ?: throw IOException("Missing document type")
                    add(PortableWritableEntry(id, name, mime == Docs.Document.MIME_TYPE_DIR))
                }
            }
        } ?: throw IOException("Cannot list destination")
    }
    override fun create(parentId: String, name: String, directory: Boolean): PortableWritableEntry {
        val mime = if (directory) Docs.Document.MIME_TYPE_DIR else "application/octet-stream"
        val created = Docs.createDocument(resolver, uri(parentId), mime, name) ?: throw IOException("Cannot create document")
        if (created.authority != tree.authority) throw IOException("Unexpected document provider")
        val id = Docs.getDocumentId(created)
        return children(parentId).singleOrNull { it.id == id } ?: throw IOException("Created document is not a unique child")
    }
    override fun isChild(parentId: String, childId: String) = portableSafDirectChild(resolver, tree, parentId, childId)
    override fun openWrite(id: String): OutputStream = resolver.openOutputStream(uri(id), "w") ?: throw IOException("Cannot write document")
    override fun openRead(id: String): InputStream = resolver.openInputStream(uri(id)) ?: throw IOException("Cannot read document")
}
