package tv.orbit.shield

/** At most two retries; protocol/identity and storage failures require user action. */
internal fun torrRetryDelay(reason: String, previousRetries: Int, enabled: Boolean): Long? {
    if (!enabled || reason !in setOf("source_timeout", "source_connect", "source_io", "source_incomplete")) return null
    return when (previousRetries) { 0 -> 2_000L; 1 -> 5_000L; else -> null }
}
