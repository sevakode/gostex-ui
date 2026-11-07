package tv.orbit.shield

import android.content.Context
import android.content.Intent

internal object TorrDownloadActions {
    /** Called from visible UI only: Android restricts starting data transfers in background. */
    fun start(context: Context, id: String) = startMany(context, listOf(id))
    fun startMany(context: Context, ids: List<String>) {
        TorrDownloadStore.queue(context, ids)
        try {
            context.startForegroundService(Intent(context, TorrDownloadService::class.java).setAction(TorrDownloadService.ACTION_START))
        } catch (error: Exception) {
            ids.forEach { id -> TorrDownloadStore.update(context, id) { if (it.state == "queued") it.copy(state = "paused") else it } }
            throw error
        }
    }
    fun pause(context: Context, id: String) {
        val job = TorrDownloadStore.jobs.value.firstOrNull { it.id == id } ?: return
        if (job.state == "queued") {
            val updated = TorrDownloadStore.update(context, id) { if (it.state == "queued") it.copy(state = "paused") else it }
            if (updated.state == "running") {
                context.startService(Intent(context, TorrDownloadService::class.java).setAction(TorrDownloadService.ACTION_PAUSE).putExtra("id", id))
            }
        } else if (job.state == "running") {
            context.startService(Intent(context, TorrDownloadService::class.java).setAction(TorrDownloadService.ACTION_PAUSE).putExtra("id", id))
        }
    }
}
