package tv.orbit.shield

/** Near-end playback is treated as watched; unknown durations preserve the saved position. */
internal fun videoIsNearlyFinished(position: Long, duration: Long, restartNearEnd: Boolean = true): Boolean =
    position >= 10_000L && duration > 0L &&
        position >= if (restartNearEnd) duration - maxOf(10_000L, duration / 20L) else duration

internal fun videoResumePosition(position: Long, duration: Long, restartNearEnd: Boolean = true): Long =
    if (videoIsNearlyFinished(position, duration, restartNearEnd)) 0L else position.coerceAtLeast(0L)

/** A near-end resume policy is not evidence that a video has actually ended. */
internal fun videoActuallyCompleted(position: Long, duration: Long): Boolean =
    duration >= 10_000L && position >= duration - 2_000L && (position <= duration || position - duration <= 2_000L)

internal fun videoShouldAdvance(success: Boolean, exactItem: Boolean, hasPosition: Boolean, hasDuration: Boolean, position: Long, duration: Long): Boolean =
    success && exactItem && hasPosition && hasDuration && videoActuallyCompleted(position, duration)

/** An immediate end result can come from a failed open, even when the player reports EOF.
 * Count only the current launch, after any resume prompt; stale positions cannot shorten
 * this floor. A very short return remains available for an explicit Next action. */
internal fun videoShouldAdvanceAfterLaunch(
    success: Boolean,
    exactItem: Boolean,
    requiresExplicitEnd: Boolean,
    explicitEnd: Boolean,
    hasPosition: Boolean,
    hasDuration: Boolean,
    position: Long,
    duration: Long,
    launchedAtMs: Long?,
    returnedAtMs: Long,
): Boolean {
    if (!success || !exactItem || launchedAtMs == null || launchedAtMs < 0L ||
        returnedAtMs < launchedAtMs || returnedAtMs - launchedAtMs < 1_500L) return false
    // Our patched VLC reports EndReached explicitly. A plain Back near the end
    // is not completion; keep the positional fallback only for older external VLC.
    return if (requiresExplicitEnd) explicitEnd
        else videoShouldAdvance(success, exactItem, hasPosition, hasDuration, position, duration)
}

/** Null means an unreliable or too-early sample; zero means a completed video. */
internal fun videoCheckpoint(position: Long, duration: Long, restartNearEnd: Boolean = true): Long? {
    if (position < 10_000L || duration < 0L || (duration > 0L && position > duration)) return null
    return if (videoIsNearlyFinished(position, duration, restartNearEnd)) 0L else position
}
