package tv.orbit.shield

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Docs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Stages immutable images only. Commit must later revalidate the whole portable copy
 * and live state under an exclusion gate; this result is not a lock or a grant. */
internal object PortableAndroidRestoreStaging {
    suspend fun stage(context: Context, tree: Uri, commitId: String, expectedRestore: PortableRestorePlan,
                      expectedLive: PortableLiveLibrary, expectedRoots: List<LibraryRootRecord>,
                      decisions: Map<PortableRestoreConflict, PortableRestoreChoice>,
                      onCover: (Int, Int) -> Unit = { _, _ -> }): PortableRestoreMergeResult = withContext(Dispatchers.IO) {
        require(tree.scheme == "content" && Docs.isTreeUri(tree))
        require(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }) {
            "Persistent read permission required"
        }
        require(expectedRestore.roots.all { it.treeUri == tree.toString() }) { "Restore tree changed" }
        val scope = currentCoroutineContext()
        fun currentRoots() = LibraryImport.roots(context).map { LibraryRootRecord(it.platform, it.uri, it.source) }
        scope.ensureActive()
        check(PortableLiveCapture.capture(context) == expectedLive && currentRoots() == expectedRoots) { "Live state changed" }
        val selected = resolvePortableRestore(expectedLive, expectedRoots, expectedRestore, decisions)
        check(selected.ready) { "Unresolved restore conflicts" }
        val referenced = expectedRestore.covers.values.flatMap { listOf(it.manual, it.original, it.wide) }
            .filter(String::isNotEmpty).map { managedCoverDigest(it) ?: error("Invalid cover reference") }.toSet()
        val available = expectedRestore.coverFiles.map { it.digest }
        require(available.distinct().size == available.size && available.toSet() == referenced) { "Incomplete cover index" }
        val selectedKeys = selected.covers.values.flatMap { listOf(it.manual, it.original, it.wide) }
            .filter(String::isNotEmpty).toSet()
        val required = selectedKeys.intersect(referenced.map(::managedCoverKey).toSet())
        ManagedCoverStore.stagePortable(context, PortableSafContent(context.contentResolver, tree), expectedRestore.coverFiles, required, onCover)
        scope.ensureActive()
        check(PortableLiveCapture.capture(context) == expectedLive && currentRoots() == expectedRoots) { "Live state changed" }
        val committed = PortableSnapshotStore.readCommitted(PortableSafStaging(context.contentResolver, tree), commitId) { scope.ensureActive() }
        check(committed.snapshot == expectedRestore.snapshot) { "Portable metadata changed" }
        selected
    }
}
