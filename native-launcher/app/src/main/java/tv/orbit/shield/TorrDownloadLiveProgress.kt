package tv.orbit.shield

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class TorrDownloadProgress(val id: String, val offset: Long)

/** UI-only transfer progress. Recovery always uses the synced download journal. */
internal object TorrDownloadLiveProgress {
    private val mutable = MutableStateFlow<TorrDownloadProgress?>(null)
    val current: StateFlow<TorrDownloadProgress?> = mutable.asStateFlow()

    @Synchronized fun report(id: String, offset: Long) {
        mutable.value = TorrDownloadProgress(id, offset.coerceAtLeast(0))
    }

    /** Finishing an old job must not erase a newer worker's progress. */
    @Synchronized fun clear(id: String) {
        if (mutable.value?.id == id) mutable.value = null
    }
}

internal fun downloadDisplayOffset(job: TorrDownloadJob, progress: TorrDownloadProgress?): Long {
    val total = job.total.coerceAtLeast(0)
    val durable = job.offset.coerceIn(0, total)
    if (job.state != "running" || progress?.id != job.id) return durable
    // A checkpoint can reach Compose before an older transient emission.
    return maxOf(durable, progress.offset).coerceIn(0, total)
}
