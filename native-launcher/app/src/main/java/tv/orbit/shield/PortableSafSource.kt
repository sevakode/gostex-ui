package tv.orbit.shield

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract as Docs

/** Locates an existing LibraryImport file under its already granted source tree.
 * Only the platform external-storage provider is supported; no permission is acquired. */
internal class PortableSafSource(private val resolver: ContentResolver, tree: Uri) {
    private val sourceTree = tree
    private val authority = "com.android.externalstorage.documents"
    init { require(Docs.isTreeUri(tree) && tree.scheme == "content" && tree.authority == authority) }
    private val documents = PortableSafContent(resolver, tree)

    fun locate(savedFile: String, checkActive: () -> Unit = {}): PortableSourceFile {
        val documentId = if (savedFile.startsWith("/")) {
            PortableExternalDocumentIds.primaryDocumentId(savedFile)
        } else {
            val uri = Uri.parse(savedFile)
            require(uri.scheme == "content" && uri.authority == authority)
            if (Docs.isTreeUri(uri)) require(Docs.getTreeDocumentId(uri) == Docs.getTreeDocumentId(sourceTree))
            Docs.getDocumentId(uri)
        }
        return PortableSourceLookup.locate(documents, documentId, checkActive)
    }
}
