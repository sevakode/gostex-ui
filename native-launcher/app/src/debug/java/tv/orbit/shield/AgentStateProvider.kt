package tv.orbit.shield

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** On-demand local diagnostics for an explicitly ADB-authorized shell, not a network API. */
class AgentStateProvider : ContentProvider() {
    companion object {
        private var activity = WeakReference<MainActivity>(null)
        internal fun attach(value: MainActivity) { activity = WeakReference(value) }
        internal fun detach(value: MainActivity) { if (activity.get() === value) activity.clear() }
    }

    override fun onCreate() = true
    private fun checkCaller() {
        // Android shell UID; normal third-party applications cannot invoke this provider.
        if (Binder.getCallingUid() != 2000) throw SecurityException("ADB shell access required")
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        checkCaller()
        val path = uri.pathSegments
        require(path.size == 3 && path[0] == "state") { "Unknown endpoint" }
        val offset = path[1].toIntOrNull()?.coerceAtLeast(0) ?: 0
        val limit = path[2].toIntOrNull()?.coerceIn(0, 50) ?: 0
        val latch = CountDownLatch(1)
        var result = JSONObject().put("schema", 1).put("available", false)
        Handler(Looper.getMainLooper()).post {
            try {
                val screen = activity.get()
                if (screen != null) {
                    val games = screen.library
                    val items = JSONArray()
                    games.drop(offset).take(limit).forEach {
                        items.put(JSONObject().put("id", it.id).put("title", it.title).put("platform", it.platform))
                    }
                    result = JSONObject().put("schema", 1).put("available", true)
                        .put("scanning", screen.scanning).put("gameCount", games.size)
                        .put("metadataCount", screen.metadataCatalogSize())
                        .put("offset", offset).put("games", items)
                }
            } finally { latch.countDown() }
        }
        if (!latch.await(2, TimeUnit.SECONDS)) throw IllegalStateException("Launcher state timed out")
        return MatrixCursor(arrayOf("json")).apply { addRow(arrayOf(result.toString())) }
    }
    override fun getType(uri: Uri): String? { checkCaller(); return null }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Read only")
}
