package tv.orbit.shield

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Docs
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Source adapter for a previously granted external-storage tree. */
internal class PortableSafPlanningSource(private val resolver: ContentResolver, private val tree: Uri) : PortablePlanningSource {
    private val source = PortableSafSource(resolver, tree)
    private val documents = PortableSafContent(resolver, tree)
    override val rootDocumentId: String = documents.rootId
    private fun uri(id: String) = Docs.buildDocumentUriUsingTree(tree, id)
    override fun locate(savedFile: String, checkActive: () -> Unit) = source.locate(savedFile, checkActive)
    override fun resolve(documentId: String, checkActive: () -> Unit) = PortableSourceLookup.locate(documents, documentId, checkActive)
    override fun open(documentId: String): InputStream = documents.openRead(documentId)
    override fun stamp(documentId: String): PortableSourceStamp {
        val projection = arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_SIZE, Docs.Document.COLUMN_LAST_MODIFIED)
        return resolver.query(uri(documentId), projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.getString(0) != documentId || cursor.isNull(1) || cursor.isNull(2))
                throw IOException("Missing source metadata")
            val result = PortableSourceStamp(cursor.getLong(1), cursor.getLong(2))
            if (cursor.moveToNext()) throw IOException("Ambiguous source metadata")
            result
        } ?: throw IOException("Source metadata unavailable")
    }
    override fun files(checkActive: () -> Unit): List<DiscImport.File> {
        val result = mutableListOf<DiscImport.File>()
        val visited = mutableSetOf(documents.rootId)
        var count = 0
        fun walk(parent: String, parts: List<String>) {
            checkActive(); require(parts.size <= 16) { "Source tree too deep" }
            val rows = documents.children(parent)
            require(rows.map { it.id }.distinct().size == rows.size) { "Ambiguous source directory" }
            for (row in rows) {
                checkActive(); require(++count <= 10000) { "Source tree too large" }
                val path = parts + row.name
                require(PortablePath.fromComponents(path) != null && row.id.isNotBlank())
                if (row.directory) {
                    require(visited.add(row.id) && documents.isChild(parent, row.id)) { "Invalid source directory" }
                    walk(row.id, path)
                } else result += DiscImport.File(path.joinToString("/"), row.id)
            }
        }
        walk(documents.rootId, emptyList())
        return result
    }
}

/** Managed images are private app files named by their validated content digest. */
internal class PortablePrivateCover(context: Context, key: String) : PortablePlanningCover {
    private val directory = File(context.filesDir, "covers").canonicalFile
    private val file = File(directory, (managedCoverDigest(key) ?: error("Invalid cover key")) + ".image").canonicalFile
    init { require(file.parentFile == directory) }
    override fun stamp(): PortableSourceStamp {
        if (!file.isFile) throw IOException("Managed cover unavailable")
        return PortableSourceStamp(file.length(), file.lastModified())
    }
    override fun open(): InputStream = file.inputStream()
}
