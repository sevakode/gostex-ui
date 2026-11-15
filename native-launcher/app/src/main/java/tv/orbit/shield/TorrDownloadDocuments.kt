package tv.orbit.shield

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.util.UUID

internal class TorrDownloadPartialBatch(val prepared: Int, cause: Exception) : IOException(cause)
internal class TorrDownloadDestinationExists : IOException("Destination already exists")
internal class HttpDownloadInvalidName : IOException("Invalid download file name")

internal fun validateHttpDownloadName(name: String): String {
    if (name.length > 512 || name.any { it.isISOControl() || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' }) {
        throw HttpDownloadInvalidName()
    }
    val cleaned = name.trim()
    if (cleaned.startsWith('.') || runCatching { TorrDownloadPaths.components(cleaned).size }.getOrNull() != 1) {
        throw HttpDownloadInvalidName()
    }
    return cleaned
}

/** The suggested name is editable; query strings and server headers never become file names. */
internal fun suggestHttpDownloadName(admission: HttpDownloadAdmissionResult): String = runCatching {
    val path = java.net.URI(admission.url.serialized).rawPath.orEmpty()
    validateHttpDownloadName(java.net.URLDecoder.decode(path.substringAfterLast('/').replace("+", "%2B"), "UTF-8"))
}.getOrDefault("download")

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
        TorrDownloadStore.requireActiveCapacity(context, files.size)
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
        prepareTree(context, tree)
        val id = UUID.randomUUID().toString()
        val parts = TorrDownloadPaths.components(file.path)
        val base = parts.last()
        val parentId = TorrDownloadGroups.parent(context, tree, entry, parts)
        return createPartial(context, tree, id, parentId, base) { document ->
            TorrDownloadJob(id, entry.hash, file.id, file.size, base, tree.toString(), document.toString(), base,
                parentId = parentId, grouped = true, prefetchMovieId = prefetchMovieId)
        }
    }

    /** Direct files use the same destination checks and durable queue as a torrent file. */
    @Synchronized fun enqueueHttp(context: Context, tree: Uri, admission: HttpDownloadAdmissionResult, name: String): TorrDownloadJob {
        require(tree.scheme == "content" && admission.size > 0 &&
            (admission.strongEtag == null || isStrongHttpEtag(admission.strongEtag)) &&
            (admission.session == null || admission.strongEtag != null))
        val finalName = validateHttpDownloadName(name)
        prepareTree(context, tree)
        TorrDownloadStore.requireHttpAdmission(context, tree.toString(), admission.resourceKey)
        val id = UUID.randomUUID().toString()
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val sessionBound = admission.session != null
        return createPartial(context, tree, id, parentId, finalName, admission.takeIf { sessionBound }) { document ->
            TorrDownloadJob(id, "", 0, admission.size, finalName, tree.toString(), document.toString(), finalName,
                parentId = parentId, http = HttpDownloadOrigin(admission.url.takeUnless { sessionBound }, admission.resourceKey,
                    admission.strongEtag, sessionBound = sessionBound))
        }
    }

    private fun prepareTree(context: Context, tree: Uri) {
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        TorrDownloadStore.recover(context)
    }

    private fun createPartial(context: Context, tree: Uri, id: String, parentId: String,
                                     finalName: String, admission: HttpDownloadAdmissionResult? = null,
                                     buildJob: (Uri) -> TorrDownloadJob): TorrDownloadJob {
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
            val job = buildJob(document)
            TorrDownloadStore.add(context, job, admission)
            return job
        } catch (error: Exception) {
            // Only delete after proving this call created this exact direct child.
            if (safeToDelete) runCatching { DocumentsContract.deleteDocument(context.contentResolver, document) }
            throw error
        }
    }

    /** Return only the owned completed content document; never expose the partial or its source link. */
    fun verifyCompletedHttp(context: Context, job: TorrDownloadJob): Uri {
        require(job.http != null && job.state == "done" && job.total > 0 && job.offset == job.total)
        val tree = Uri.parse(job.tree)
        val document = Uri.parse(job.document)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val documentId = DocumentsContract.getDocumentId(document)
        if (document.scheme != "content" || tree.scheme != "content" || document.authority != tree.authority ||
            job.parentId.ifBlank { rootId } != rootId || !portableSafDirectChild(context.contentResolver, tree, rootId, documentId)) {
            throw IOException("Downloaded file moved or unavailable")
        }
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val matches = context.contentResolver.query(document, projection, null, null, null)?.use { c ->
            c.moveToFirst() && c.getString(0) == documentId && c.getString(1) == job.finalName &&
                !c.isNull(2) && c.getLong(2) == job.total && c.getString(3) != DocumentsContract.Document.MIME_TYPE_DIR && !c.moveToNext()
        } ?: false
        if (!matches) throw IOException("Downloaded file no longer matches")
        when (SavedVideoIntegrity.check(context.contentResolver, document, job.finalName, job.total)) {
            SavedVideoIntegrity.Result.VALID -> Unit
            SavedVideoIntegrity.Result.INVALID -> throw IOException("Downloaded video has an invalid header")
            SavedVideoIntegrity.Result.UNAVAILABLE -> throw IOException("Downloaded video unavailable")
        }
        return document
    }

    /** Recover a matching completed file in this torrent's managed folder without changing it. */
    @Synchronized fun recoverCompleted(context: Context, tree: Uri, entry: TorrCatalogEntry, file: TorrCatalogFile, movieId: String): TorrDownloadJob {
        return recoverCompletedFiles(context, tree, entry, listOf(file), movieId).singleOrNull()
            ?: throw IOException("Completed file unavailable")
    }

    /** Directory listings live only for this pass; one slow/missing file must not hide its siblings. */
    @Synchronized fun recoverCompletedFiles(context: Context, tree: Uri, entry: TorrCatalogEntry,
                                           files: List<TorrCatalogFile>, movieId: String,
                                           checkActive: () -> Unit = {}): List<TorrDownloadJob> {
        require(files.size <= 100 && files.all { it in entry.files && it.size > 0 } && movieId.isNotBlank() && tree.scheme == "content")
        checkActive()
        TorrDownloadStore.recover(context)
        val existing = TorrDownloadStore.jobs.value.filter { it.tree == tree.toString() && it.hash.equals(entry.hash, true) }.map { it.index }.toSet()
        val paths = files.filter { it.id !in existing }.mapNotNull { file ->
            runCatching { file to TorrDownloadPaths.components(file.path) }.getOrNull()
        }.toMap()
        if (paths.isEmpty()) return emptyList()
        val parents = TorrDownloadGroups.existingParents(context, tree, entry, paths.values.toList(), checkActive)
        data class Child(val id: String, val name: String, val size: Long?, val mime: String)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val children = mutableMapOf<String, Result<List<Child>>>()
        fun list(parentId: String): List<Child> = children.getOrPut(parentId) {
            runCatching {
                val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
                context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            checkActive()
                            if (size >= 10000) throw IOException("Too many destination documents")
                            add(Child(cursor.getString(0) ?: throw IOException("Missing document ID"), cursor.getString(1) ?: "",
                                if (cursor.isNull(2)) null else cursor.getLong(2), cursor.getString(3) ?: ""))
                        }
                    }.also { list -> if (list.map { it.id }.distinct().size != list.size) throw IOException("Ambiguous document IDs") }
                } ?: throw IOException("Cannot inspect completed files")
            }
        }.getOrThrow()
        val recovered = mutableListOf<TorrDownloadJob>()
        for ((file, parts) in paths) {
            checkActive()
            // Another visible request may have recovered the same document while this pass waited for its lock.
            if (TorrDownloadStore.jobs.value.any { it.tree == tree.toString() && it.hash.equals(entry.hash, true) && it.index == file.id }) continue
            try {
                val parentId = parents[parts.dropLast(1)] ?: continue
                val child = list(parentId).singleOrNull { it.name == parts.last() && it.size == file.size && it.mime != DocumentsContract.Document.MIME_TYPE_DIR }
                    ?: continue
                if (child.id.isBlank() || child.id == parentId) continue
                val document = DocumentsContract.buildDocumentUriUsingTree(tree, child.id)
                // Direct membership was checked in the cached, unique parent listing. Verify tree access
                // and current identity without enumerating that parent again for every episode.
                val matches = context.contentResolver.query(document, columns, null, null, null)?.use { cursor ->
                    cursor.moveToFirst() && cursor.getString(0) == child.id && cursor.getString(1) == parts.last() &&
                        !cursor.isNull(2) && cursor.getLong(2) == file.size && cursor.getString(3) != DocumentsContract.Document.MIME_TYPE_DIR && !cursor.moveToNext()
                } ?: false
                if (!matches || android.os.Build.VERSION.SDK_INT >= 29 && !DocumentsContract.isChildDocument(context.contentResolver,
                        DocumentsContract.buildDocumentUriUsingTree(tree, parentId), document)) continue
                if (TorrPlayback.isVideo(parts.last())) {
                    val status = SavedVideoIntegrity.check(context.contentResolver, document, parts.last(), file.size, checkActive)
                    if (status != SavedVideoIntegrity.Result.VALID) continue
                } else {
                    context.contentResolver.openAssetFileDescriptor(document, "r")?.use { } ?: continue
                }
                checkActive()
                val job = TorrDownloadJob(UUID.randomUUID().toString(), entry.hash, file.id, file.size, parts.last(), tree.toString(),
                    document.toString(), parts.last(), offset = file.size, state = "done", parentId = parentId, grouped = true, prefetchMovieId = movieId)
                TorrDownloadStore.add(context, job)
                recovered += job
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Missing, inaccessible or concurrently registered documents are left untouched.
            }
        }
        return recovered
    }
}
