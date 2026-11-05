package tv.orbit.shield

/** The previous app defaulted Remote to on; a new installation starts with it off.
 * A saved user choice always takes precedence over this one-time default. */
internal fun legacyRemoteDefault(firstInstallMillis: Long, lastUpdateMillis: Long): Boolean? {
    if (firstInstallMillis <= 0 || lastUpdateMillis < firstInstallMillis) return null
    return lastUpdateMillis > firstInstallMillis
}
