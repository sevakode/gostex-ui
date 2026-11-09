package tv.orbit.shield

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.util.UUID

internal class TorrDownloadPartialBatch(val prepared: Int, cause: Exception) : IOException(cause)
internal class TorrDownloadDestinationExists : IOException("Destination already exists")

internal object TorrDownloadDocuments {
    /** Delete only a recorded offline file, after explicit confirmation. */
    fun deletePrefetch(context: Context, id: String) {
        val job = TorrDownloadStore.beginDeletePrefetch(context, id) ?: throw IOException("Download unavailable")
        try {
            val tree = Uri.parse(job.tree)
            val document = Uri.parse(job.document)
            if (tree.authority != document.authority) throw IOException("Unexpected provider")
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val parent = job.parentId.ifBlank { rootId }
            if (job.grouped && android.os.Build.VERSION.SDK_INT >= 29 &&
                !DocumentsContract.isChildDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, rootId), DocumentsContract.buildDocumentUriUsingTree(tree, parent))) throw IOException("Folder moved")
            val documentId = DocumentsContract.getDocumentId(document)
            if (!portableSafDirectChild(context.contentResolver, tree, parent, documentId)) throw IOException("File moved")
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE)
            val matches = context.contentResolver.query(document, columns, null, null, null)?.use { c ->
                c.moveToFirst() && c.getString(0) == documentId && c.getString(1) == job.finalName && !c.isNull(2) && c.getLong(2) == job.total && !c.moveToNext()
            } ?: false
            if (!matches || !DocumentsContract.deleteDocument(context.contentResolver, document)) throw IOException("Cannot safely delete file")
            TorrDownloadStore.finishDeletePrefetch(context, id)
        } finally { TorrDownloadStore.endDiscard(id) }
    }
    /** Explicitly discard only a verified temporary document, never a completed file. */
    fun discard(context: Context, id: String) {
        val job = TorrDownloadStore.beginDiscard(context, id) ?: throw IOException("Download is still active or unavailable")
        try {
            verifyPartial(context, job)
            if (!DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(job.document))) throw IOException("Cannot delete temporary document")
            TorrDownloadStore.finishDiscard(context, id)
        } finally {
            TorrDownloadStore.endDiscard(id)
        }
    }
    @Synchronized fun enqueueAll(context: Context, tree: Uri, entry: TorrCatalogEntry, files: List<TorrCatalogFile>): List<TorrDownloadJob> {
        require(files.isNotEmpty() && files.size <= 100 && files.all { it in entry.files })
        require(files.map { it.id }.distinct().size == files.size)
        val paths = files.map { TorrDownloadPaths.components(it.path).joinToString("/").lowercase() }
        require(paths.distinct().size == paths.size)
        TorrDownloadStore.recover(context)
        require(TorrDownloadStore.jobs.value.size + files.size <= 100)
        // If a provider fails part way through, already recorded items stay paused;
        // no existing file is deleted and the user can resume them explicitly.
        // Publish disc descriptors after their tracks and playlists last. This
        // reduces the chance of a library scan seeing an incomplete set.
        val ordered = files.sortedBy {
            when (it.path.substringAfterLast('.', "").lowercase()) {
                "cue", "gdi", "mds" -> 1
                "m3u", "m3u8" -> 2
                else -> 0
            }
        }
        val prepared = mutableListOf<TorrDownloadJob>()
        for (file in ordered) {
            try { prepared += enqueue(context, tree, entry, file) }
            catch (error: Exception) {
                if (prepared.isNotEmpty()) throw TorrDownloadPartialBatch(prepared.size, error)
                throw error
            }
        }
        return prepared
    }

    /** Re-check ownership before every write, including after process recovery. */
    fun verifyPartial(context: Context, job: TorrDownloadJob) {
        val tree = Uri.parse(job.tree)
        val document = Uri.parse(job.document)
        if (document.authority != tree.authority) throw IOException("Unexpected destination provider")
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val parentId = job.parentId.ifBlank { rootId }
        if (job.grouped && android.os.Build.VERSION.SDK_INT >= 29 &&
            !DocumentsContract.isChildDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, rootId), DocumentsContract.buildDocumentUriUsingTree(tree, parentId))) {
            throw IOException("Download directory moved outside selected folder")
        }
        val documentId = DocumentsContract.getDocumentId(document)
        if (!portableSafDirectChild(context.contentResolver, tree, parentId, documentId)) throw IOException("Partial document was moved")
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val matches = context.contentResolver.query(document, projection, null, null, null)?.use { cursor ->
            cursor.moveToFirst() && cursor.getString(0) == documentId && cursor.getString(1) == ".gostex-${job.id}.partial" &&
                cursor.getString(2) != DocumentsContract.Document.MIME_TYPE_DIR && !cursor.moveToNext()
        } ?: false
        if (!matches) throw IOException("Partial document no longer belongs to this job")
    }

    /** Creates a new uniquely named partial document; never opens or replaces an existing file. */
    @Synchronized fun enqueue(context: Context, tree: Uri, entry: TorrCatalogEntry, file: TorrCatalogFile, prefetchMovieId: String = ""): TorrDownloadJob {
        require(file in entry.files && file.size >= 0 && tree.scheme == "content")
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        TorrDownloadStore.recover(context)
        val id = UUID.randomUUID().toString()
        val parts = TorrDownloadPaths.components(file.path)
        val base = parts.last()
        val finalName = base
        val parentId = TorrDownloadGroups.parent(context, tree, entry, parts)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val partialName = ".gostex-$id.partial"
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        fun listChildren(): List<Pair<String, String>> = context.contentResolver.query(children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (size >= 10000) throw IOException("Too many destination documents")
                    add((cursor.getString(0) ?: throw IOException("Missing document ID")) to (cursor.getString(1) ?: ""))
                }
            }
        } ?: throw IOException("Cannot inspect destination folder")
        val before = listChildren()
        if (before.map { it.first }.distinct().size != before.size) throw IOException("Ambiguous destination")
        if (before.any { it.second == partialName || it.second.equals(finalName, ignoreCase = true) }) throw TorrDownloadDestinationExists()
        val document = DocumentsContract.createDocument(context.contentResolver, parent, "application/octet-stream", partialName)
            ?: throw IOException("Could not create a temporary download")
        var safeToDelete = false
        try {
            if (document.authority != tree.authority) throw IOException("Unexpected destination provider")
            val documentId = DocumentsContract.getDocumentId(document)
            if (before.any { it.first == documentId }) throw IOException("Provider returned an existing document")
            if (!portableSafDirectChild(context.contentResolver, tree, parentId, documentId)) throw IOException("Destination is outside selected folder")
            val matches = listChildren().filter { it.second == partialName }
            if (matches.size != 1 || matches.single().first != documentId) throw IOException("Ambiguous temporary document")
            safeToDelete = true
            // Check early that the provider can safely seek before committing a job.
            TorrSafTarget.open(context.contentResolver, document).use { if (it.size() != 0L) throw IOException("New document is not empty") }
            val job = TorrDownloadJob(id, entry.hash, file.id, file.size, base, tree.toString(), document.toString(), finalName,
                parentId = parentId, grouped = true, prefetchMovieId = prefetchMovieId)
            TorrDownloadStore.add(context, job)
            return job
        } catch (error: Exception) {
            // Only delete after proving this call created this exact direct child.
            if (safeToDelete) runCatching { DocumentsContract.deleteDocument(context.contentResolver, document) }
            throw error
        }
    }

    /** Recover a matching completed file in this torrent's managed folder without changing it. */
    @Synchronized fun recoverCompleted(context: Context, tree: Uri, entry: TorrCatalogEntry, file: TorrCatalogFile, movieId: String): TorrDownloadJob {
        require(file in entry.files && file.size > 0 && movieId.isNotBlank() && tree.scheme == "content")
        TorrDownloadStore.recover(context)
        val parts = TorrDownloadPaths.components(file.path)
        val parentId = TorrDownloadGroups.parent(context, tree, entry, parts)
        val parent = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val matches = context.contentResolver.query(parent, columns, null, null, null)?.use { cursor ->
            buildList<String> {
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == parts.last() && !cursor.isNull(2) && cursor.getLong(2) == file.size &&
                        cursor.getString(3) != DocumentsContract.Document.MIME_TYPE_DIR) add(cursor.getString(0) ?: throw IOException("Missing document ID"))
                    if (size > 1) throw IOException("Ambiguous completed file")
                }
            }
        } ?: throw IOException("Cannot inspect completed file")
        val documentId = matches.singleOrNull() ?: throw IOException("Completed file unavailable")
        if (!portableSafDirectChild(context.contentResolver, tree, parentId, documentId)) throw IOException("File is outside selected folder")
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        context.contentResolver.openAssetFileDescriptor(document, "r")?.use { } ?: throw IOException("Cannot read completed file")
        val job = TorrDownloadJob(UUID.randomUUID().toString(), entry.hash, file.id, file.size, parts.last(), tree.toString(),
            document.toString(), parts.last(), offset = file.size, state = "done", parentId = parentId, grouped = true, prefetchMovieId = movieId)
        TorrDownloadStore.add(context, job)
        return job
    }
}
