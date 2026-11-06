package tv.orbit.shield

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Docs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Internal, read-only preview using a newly granted tree; not a restore operation. */
internal suspend fun inspectPortableRestore(context: Context, tree: Uri, commitId: String,
                                           onProgress: (String, Long, Long) -> Unit = { _, _, _ -> }): PortableRestoreAssessment =
    withContext(Dispatchers.IO) {
        require(tree.scheme == "content" && Docs.isTreeUri(tree))
        val scope = currentCoroutineContext()
        PortableRestorePlanner.inspect(PortableSafStaging(context.contentResolver, tree), PortableSafContent(context.contentResolver, tree),
            commitId, tree.toString(), { id -> Docs.buildDocumentUriUsingTree(tree, id).toString() },
            { id -> PlatformRegistry.find(id)?.let { PortableTrustedPlatform(it.emulator, it.component) } },
            { scope.ensureActive() }, onProgress)
    }
