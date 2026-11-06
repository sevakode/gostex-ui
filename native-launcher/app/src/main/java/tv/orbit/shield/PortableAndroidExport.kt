package tv.orbit.shield

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Docs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Creates a verified copy; restoration and physical media validation remain unfinished. */
internal object PortableAndroidExport {
    private val lock = Mutex()
    suspend fun export(context: Context, destination: Uri, onSourceVerification: (Int, Int) -> Unit = { _, _ -> },
                       onFile: (String, Int, Int) -> Unit = { _, _, _ -> }): PortableExportResult =
        lock.withLock {
            withContext(Dispatchers.IO) {
                val scope = currentCoroutineContext()
                val checkActive = { scope.ensureActive() }
                val live = PortableLiveCapture.capture(context, checkActive)
                PortableExportPreflight.validate(live.games.map { game ->
                    checkActive()
                    PortableExportItem(game.platform, game.files.map { file ->
                        Uri.parse(file).lastPathSegment ?: file.substringAfterLast('/')
                    })
                })
                val roots = LibraryImport.roots(context)
                val sources = live.games.map { it.tree }.distinct().associateWith { root ->
                    val uri = Uri.parse(root)
                    if (Docs.isTreeUri(uri)) PortableSafPlanningSource(context.contentResolver, uri)
                    else {
                        val games = live.games.filter { it.tree == root }
                        require(games.isNotEmpty() && games.all { game -> roots.any {
                            it.uri == root && it.platform == game.platform && it.source == LibrarySource.SINGLE_FILE
                        } }) { "Document was not added as a single file" }
                        PortableSafSingleSource(context, uri)
                    }
                }
                PortableExportWorkflow.export(live, {
                    check(LibraryImport.roots(context) == roots) { "Library sources changed" }
                    PortableLiveCapture.capture(context, checkActive)
                }, sources,
                    { key -> PortablePrivateCover(context, key) }, PortableSafWritableTree(context.contentResolver, destination),
                    PortableSafStaging(context.contentResolver, destination), checkActive, onSourceVerification, onFile)
            }
        }
}
