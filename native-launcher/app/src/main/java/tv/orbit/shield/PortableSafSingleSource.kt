package tv.orbit.shield

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Docs
import java.io.IOException
import java.io.InputStream

/** A persistent document grant provides exactly one readable file, never its siblings. */
internal class PortableSafSingleSource(private val context: Context, private val document: Uri) : PortablePlanningSource {
    private val resolver: ContentResolver = context.contentResolver
    init {
        require(document.scheme == "content" && !Docs.isTreeUri(document) && Docs.isDocumentUri(context, document))
    }
    override val rootDocumentId: String = Docs.getDocumentId(document).also { require(it.isNotBlank()) }

    private data class Metadata(val name: String, val stamp: PortableSourceStamp)
    private fun metadata(): Metadata {
        require(resolver.persistedUriPermissions.any { it.uri == document && it.isReadPermission }) { "Persistent file grant required" }
        val columns = arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME,
            Docs.Document.COLUMN_MIME_TYPE, Docs.Document.COLUMN_SIZE, Docs.Document.COLUMN_LAST_MODIFIED)
        return resolver.query(document, columns, null, null, null)?.use { c ->
            if (!c.moveToFirst() || c.getString(0) != rootDocumentId || c.isNull(1) || c.isNull(2) || c.isNull(3) || c.isNull(4))
                throw IOException("File metadata unavailable")
            val name = c.getString(1)
            require(c.getString(2) != Docs.Document.MIME_TYPE_DIR && PortablePath.fromComponents(listOf(name)) != null)
            val result = Metadata(name, PortableSourceStamp(c.getLong(3), c.getLong(4)))
            if (c.moveToNext()) throw IOException("Ambiguous file metadata")
            result
        } ?: throw IOException("File metadata unavailable")
    }
    override fun locate(savedFile: String, checkActive: () -> Unit): PortableSourceFile {
        checkActive()
        require(savedFile == document.toString()) { "File is outside its explicit grant" }
        return resolve(rootDocumentId, checkActive)
    }
    override fun resolve(documentId: String, checkActive: () -> Unit): PortableSourceFile {
        checkActive(); require(documentId == rootDocumentId)
        return PortableSourceFile(listOf(metadata().name), documentId)
    }
    override fun stamp(documentId: String): PortableSourceStamp {
        require(documentId == rootDocumentId)
        return metadata().stamp
    }
    override fun open(documentId: String): InputStream {
        require(documentId == rootDocumentId)
        metadata()
        return resolver.openInputStream(document) ?: throw IOException("Cannot open file")
    }
    override fun files(checkActive: () -> Unit): List<DiscImport.File> =
        throw IllegalArgumentException("A single file grant cannot include companion files")
}
