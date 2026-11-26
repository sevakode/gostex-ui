package tv.orbit.shield

/** Try twice at the same durable position. A resumed transfer that advances can recover again. */
internal class TorrDownloadRetry(initialOffset: Long) {
    private var failedOffset = initialOffset
    private var retries = 0

    fun delayAfterFailure(reason: String, durableOffset: Long, enabled: Boolean): Long? {
        if (!enabled || reason !in setOf("source_timeout", "source_connect", "source_io", "source_incomplete")) return null
        if (durableOffset > failedOffset) {
            failedOffset = durableOffset
            retries = 0
        }
        return when (retries) {
            0 -> 2_000L
            1 -> 5_000L
            else -> return null
        }.also { retries++ }
    }
}
