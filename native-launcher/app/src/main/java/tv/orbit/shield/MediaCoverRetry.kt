package tv.orbit.shield

/** Foreground-only deduplication and retry throttling; does not schedule background work. */
internal class MediaCoverRetry(private val delayMillis: Long = 60_000L) {
    private val active = mutableSetOf<Pair<String, String>>()
    private val failed = LinkedHashMap<Pair<String, String>, Long>()

    @Synchronized fun begin(id: String, poster: String, now: Long): Boolean {
        val key = id to poster
        val lastFailure = failed[key]
        if (key in active || (lastFailure != null && now >= lastFailure && now - lastFailure < delayMillis)) return false
        failed.remove(key)
        active.add(key)
        return true
    }

    @Synchronized fun finish(id: String, poster: String, now: Long, failedAttempt: Boolean) {
        val key = id to poster
        active.remove(key)
        if (failedAttempt) {
            failed[key] = now
            if (failed.size > 256) failed.remove(failed.keys.first())
        } else failed.remove(key)
    }
}
