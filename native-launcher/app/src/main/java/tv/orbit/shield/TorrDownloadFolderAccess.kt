package tv.orbit.shield

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/** Permission metadata only; never opens, scans, moves or changes a download. */
internal object TorrDownloadFolderAccess {
    data class Identity(val authority: String, val documentId: String)
    data class Request(val id: String, val tree: String, val document: String)
    enum class Outcome { RESTORED, CANCELLED, DIFFERENT_FOLDER, UNAVAILABLE, STALE }
    data class Result(val outcome: Outcome, val grants: Set<Identity>?)

    fun identity(tree: String): Identity? = runCatching {
        val uri = Uri.parse(tree)
        require(uri.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(uri))
        // A grant for /tree/ROOT/document/CHILD need not cover ROOT or its siblings.
        require(uri.pathSegments.size == 2 && uri.pathSegments[0] == "tree")
        val authority = uri.authority?.takeIf { it.isNotBlank() } ?: return null
        val documentId = DocumentsContract.getTreeDocumentId(uri).takeIf { it.isNotBlank() } ?: return null
        Identity(authority, documentId)
    }.getOrNull()

    /** Null means the permission snapshot could not be read, not that every grant is missing. */
    fun snapshot(resolver: ContentResolver): Set<Identity>? = runCatching {
        resolver.persistedUriPermissions.asSequence()
            .filter { it.isReadPermission && it.isWritePermission }
            .mapNotNull { identity(it.uri.toString()) }.toSet()
    }.getOrNull()

    fun needsAccess(tree: String, grants: Set<Identity>?): Boolean =
        grants != null && identity(tree)?.let { it !in grants } == true

    /** The picker result can only regrant the recorded destination, never select a replacement. */
    fun restore(resolver: ContentResolver, request: Request?, current: Request?, selected: Uri?): Result {
        val expected = request?.let { identity(it.tree) }
        var outcome = when {
            request == null || request != current || expected == null -> Outcome.STALE
            selected == null -> Outcome.CANCELLED
            identity(selected.toString()) != expected -> Outcome.DIFFERENT_FOLDER
            else -> try {
                resolver.takePersistableUriPermission(selected,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                Outcome.RESTORED
            } catch (_: Exception) { Outcome.UNAVAILABLE }
        }
        val grants = snapshot(resolver)
        if (outcome == Outcome.RESTORED && (grants == null || expected !in grants)) outcome = Outcome.UNAVAILABLE
        return Result(outcome, grants)
    }
}
