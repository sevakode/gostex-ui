package tv.orbit.shield

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/** Permission metadata only; never opens, scans, moves or changes a download. */
internal object TorrDownloadFolderAccess {
    data class Identity(val authority: String, val documentId: String)
    data class Request(val id: String, val tree: String, val document: String, val state: String, val error: String)
    enum class Outcome { RESTORED, CANCELLED, DIFFERENT_FOLDER, UNAVAILABLE, STALE }
    data class Grants(val read: Set<Identity>, val readWrite: Set<Identity>)
    data class Result(val outcome: Outcome, val grants: Set<Identity>?, val readGrants: Set<Identity>? = null)

    fun identity(tree: String): Identity? = runCatching {
        val uri = Uri.parse(tree)
        require(uri.scheme == ContentResolver.SCHEME_CONTENT && uri.query == null && uri.fragment == null &&
            DocumentsContract.isTreeUri(uri))
        // A grant for /tree/ROOT/document/CHILD need not cover ROOT or its siblings.
        require(uri.pathSegments.size == 2 && uri.pathSegments[0] == "tree")
        val authority = uri.authority?.takeIf { it.isNotBlank() } ?: return null
        val documentId = DocumentsContract.getTreeDocumentId(uri).takeIf { it.isNotBlank() } ?: return null
        Identity(authority, documentId)
    }.getOrNull()

    /** Null means the permission snapshot could not be read, not that every grant is missing. */
    fun snapshot(resolver: ContentResolver): Set<Identity>? = snapshotGrants(resolver)?.readWrite

    fun snapshotGrants(resolver: ContentResolver): Grants? = runCatching {
        val read = mutableSetOf<Identity>()
        val readWrite = mutableSetOf<Identity>()
        for (grant in resolver.persistedUriPermissions) {
            if (!grant.isReadPermission) continue
            val tree = identity(grant.uri.toString()) ?: continue
            read += tree
            if (grant.isWritePermission) readWrite += tree
        }
        Grants(read, readWrite)
    }.getOrNull()

    fun needsAccess(tree: String, grants: Set<Identity>?): Boolean =
        grants != null && identity(tree)?.let { it !in grants } == true

    fun canRestore(state: String, error: String): Boolean = when (state) {
        "done" -> true
        "paused" -> error != "video_invalid"
        "error" -> error == "storage" || error == "finalize" || error == "finalizing"
        else -> false
    }

    fun needsRestore(state: String, error: String, tree: String, grants: Set<Identity>?,
                     readGrants: Set<Identity>? = null): Boolean =
        canRestore(state, error) && needsAccess(tree, if (state == "done") readGrants else grants)

    /** The picker result can only regrant the recorded destination, never select a replacement. */
    fun restore(resolver: ContentResolver, request: Request?, current: Request?, selected: Uri?): Result {
        val expected = request?.let { identity(it.tree) }
        val completed = request?.state == "done"
        var outcome = when {
            request == null || request != current || expected == null ||
                !canRestore(request.state, request.error) -> Outcome.STALE
            selected == null -> Outcome.CANCELLED
            identity(selected.toString()) != expected -> Outcome.DIFFERENT_FOLDER
            else -> try {
                val flags = if (completed) Intent.FLAG_GRANT_READ_URI_PERMISSION else
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                resolver.takePersistableUriPermission(selected, flags)
                Outcome.RESTORED
            } catch (_: Exception) { Outcome.UNAVAILABLE }
        }
        val snapshot = snapshotGrants(resolver)
        val grants = if (completed) snapshot?.read else snapshot?.readWrite
        if (outcome == Outcome.RESTORED && (grants == null || expected !in grants)) outcome = Outcome.UNAVAILABLE
        return Result(outcome, snapshot?.readWrite, snapshot?.read)
    }
}
