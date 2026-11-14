package tv.orbit.shield

import android.content.Context
import android.content.Intent

internal object TorrDownloadActions {
    /** Called from visible UI only: Android restricts starting data transfers in background. */
    fun start(context: Context, id: String) = startMany(context, listOf(id))
    fun startMany(context: Context, ids: List<String>, restartId: String? = null) {
        TorrDownloadStore.queue(context, ids, restartId)
        try { requestWorker(context) }
        catch (error: Exception) {
            ids.forEach { id -> TorrDownloadStore.update(context, id) { if (it.state == "queued") it.copy(state = "paused") else it } }
            throw error
        }
    }

    /** One cold-queue handoff from the focused launcher. The worker reads the current rows. */
    fun startRecovered(context: Context) {
        val ids = TorrDownloadStore.takeRecoveredQueue()
        if (ids.isEmpty()) return
        if (!TorrDownloadResumeOptions.read(context)) {
            TorrDownloadStore.pauseRecovered(context, ids)
            return
        }
        try { requestWorker(context) }
        catch (error: Exception) {
            TorrDownloadStore.pauseRecovered(context, ids)
            throw error
        }
    }

    private fun requestWorker(context: Context) {
        context.startForegroundService(Intent(context, TorrDownloadService::class.java).setAction(TorrDownloadService.ACTION_START))
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
