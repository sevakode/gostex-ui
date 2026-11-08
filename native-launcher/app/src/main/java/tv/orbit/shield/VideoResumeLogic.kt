package tv.orbit.shield

/** Near-end playback is treated as watched; unknown durations preserve the saved position. */
internal fun videoIsNearlyFinished(position: Long, duration: Long, restartNearEnd: Boolean = true): Boolean =
    position >= 10_000L && duration > 0L &&
        position >= if (restartNearEnd) duration - maxOf(10_000L, duration / 20L) else duration

internal fun videoResumePosition(position: Long, duration: Long, restartNearEnd: Boolean = true): Long =
    if (videoIsNearlyFinished(position, duration, restartNearEnd)) 0L else position.coerceAtLeast(0L)

/** Null means an unreliable or too-early sample; zero means a completed video. */
internal fun videoCheckpoint(position: Long, duration: Long, restartNearEnd: Boolean = true): Long? {
    if (position < 10_000L || duration < 0L || (duration > 0L && position > duration)) return null
    return if (videoIsNearlyFinished(position, duration, restartNearEnd)) 0L else position
}
