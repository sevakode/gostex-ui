package tv.orbit.shield

import android.content.SharedPreferences

/** A stale value of another type must not prevent the launcher or Remote from starting. */
internal fun SharedPreferences.readStringOrDefault(key: String, default: String? = null): String? =
    runCatching { getString(key, default) }.getOrDefault(default)

internal fun SharedPreferences.readBooleanOrDefault(key: String, default: Boolean): Boolean =
    runCatching { getBoolean(key, default) }.getOrDefault(default)

/** Copy sets returned by SharedPreferences; callers must never mutate its backing set. */
internal fun SharedPreferences.readStringSetOrDefault(key: String, default: Set<String> = emptySet()): Set<String> =
    runCatching { getStringSet(key, default)?.toSet() ?: default.toSet() }.getOrElse { default.toSet() }
