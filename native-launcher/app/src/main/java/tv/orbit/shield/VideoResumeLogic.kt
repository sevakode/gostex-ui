package tv.orbit.shield

/** Near-end playback is treated as watched; unknown durations preserve the saved position. */
internal fun videoIsNearlyFinished(position: Long, duration: Long): Boolean =
    position >= 10_000L && duration > 0L && position >= duration - maxOf(10_000L, duration / 20L)

internal fun videoResumePosition(position: Long, duration: Long): Long =
    if (videoIsNearlyFinished(position, duration)) 0L else position.coerceAtLeast(0L)
