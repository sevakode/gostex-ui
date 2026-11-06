package tv.orbit.shield

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract as Docs
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** SAF implementation for the exact folder granted by the owner. This does not
 * request permissions, publish a manifest, delete partial files, or select a disk. */
internal class PortableSafStaging(private val resolver: ContentResolver, private val tree: Uri) : PortableStagingDirectory {
    init { require(Docs.isTreeUri(tree) && tree.scheme == "content" && !tree.authority.isNullOrBlank()) }
    private val root = Docs.buildDocumentUriUsingTree(tree, Docs.getTreeDocumentId(tree))
    private fun document(id: String) = Docs.buildDocumentUriUsingTree(tree, id)

    override fun children(): List<PortableStagingEntry> {
        val children = Docs.buildChildDocumentsUriUsingTree(tree, Docs.getDocumentId(root))
        val projection = arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME, Docs.Document.COLUMN_MIME_TYPE)
        return resolver.query(children, projection, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    check(size < 4096) { "Destination contains too many documents" }
                    add(PortableStagingEntry(cursor.getString(0) ?: throw IOException("Missing document ID"),
                        cursor.getString(1) ?: throw IOException("Missing document name"),
                        cursor.getString(2) ?: throw IOException("Missing MIME type")))
                }
            }
        } ?: throw IOException("Cannot list selected folder")
    }
    override fun create(name: String, mime: String): PortableStagingEntry {
        val uri = Docs.createDocument(resolver, root, mime, name) ?: throw IOException("Cannot create staging document")
        if (uri.authority != tree.authority) throw IOException("Unexpected document provider")
        val id = Docs.getDocumentId(uri)
        // The name and MIME must be read back from the directory; a provider may rename a file.
        return children().singleOrNull { it.id == id } ?: throw IOException("Created document is not a unique child")
    }
    override fun isChild(id: String): Boolean = portableSafDirectChild(resolver, tree, Docs.getDocumentId(root), id)
    override fun openWrite(id: String): OutputStream = resolver.openOutputStream(document(id), "w")
        ?: throw IOException("Cannot open staging document for writing")
    override fun openRead(id: String): InputStream = resolver.openInputStream(document(id))
        ?: throw IOException("Cannot read staging document")
}
