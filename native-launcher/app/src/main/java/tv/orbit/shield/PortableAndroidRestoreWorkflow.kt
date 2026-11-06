package tv.orbit.shield

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Invoke from the main thread with the foreground activity's actual drainer.
 * External media may
 * still change after the final hash: Android cannot lock a third-party provider. */
internal object PortableAndroidRestoreWorkflow {
    suspend fun execute(context: Context, tree: Uri, commitId: String, expected: PortableRestorePlan,
                        live: PortableLiveLibrary, roots: List<LibraryRootRecord>,
                        decisions: Map<PortableRestoreConflict, PortableRestoreChoice>,
                        drain: suspend () -> Unit,
                        onFile: (String, Long, Long) -> Unit = { _, _, _ -> }): PortableRestoreMergeResult =
        PortableMaintenance.runExclusive(context, drain) {
            PortableAndroidRestoreStaging.stage(context, tree, commitId, expected, live, roots, decisions)
            val refreshed = inspectPortableRestore(context, tree, commitId, onFile).plan ?: error("Portable content unavailable")
            check(refreshed == expected) { "Portable copy changed" }
            currentCoroutineContext().ensureActive()
            PortableMaintenance.sealForCommit()
            withContext(NonCancellable + Dispatchers.IO) {
                PortableAndroidRestoreApply.applyUnderExclusiveAccess(context, live, roots, expected, decisions) { refreshed }
            }
        }
}
