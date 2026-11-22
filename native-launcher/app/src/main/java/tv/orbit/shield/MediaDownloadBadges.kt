package tv.orbit.shield

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

internal sealed interface MediaDownloadBadge {
    data class Running(val percent: Int) : MediaDownloadBadge
    data class Completed(val count: Int) : MediaDownloadBadge
}

internal object MediaDownloadBadges {
    // All visible cards share the same projection. Drop it when the last card stops collecting.
    private val badges = TorrDownloadStore.jobs.map(::project).distinctUntilChanged().conflate().shareIn(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        SharingStarted.WhileSubscribed(replayExpirationMillis = 0),
        replay = 1,
    )

    fun observe(movieId: String): Flow<MediaDownloadBadge?> =
        badges.map { it[movieId] }.distinctUntilChanged()

    private fun project(jobs: List<TorrDownloadJob>): Map<String, MediaDownloadBadge> = buildMap {
        for (job in jobs) {
            val movieId = job.prefetchMovieId
            if (movieId.isEmpty()) continue
            val current = get(movieId)
            // The first active job wins, even when later siblings have completed.
            if (current is MediaDownloadBadge.Running) continue
            when (job.state) {
                "running" -> {
                    val percent = if (job.total > 0) {
                        (job.offset.toDouble() * 100 / job.total).toInt().coerceIn(0, 100)
                    } else 0
                    put(movieId, MediaDownloadBadge.Running(percent))
                }
                "done" -> {
                    val count = (current as? MediaDownloadBadge.Completed)?.count ?: 0
                    put(movieId, MediaDownloadBadge.Completed(count + 1))
                }
            }
        }
    }
}
