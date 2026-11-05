package tv.orbit.shield

import android.content.Context

/** Persist before a clean installation can gain user data, so later additions never
 * masquerade as an upgrade from a release that contained the historical catalog. */
internal object LegacyRecoveryState {
    private const val KEY = "library.legacyRecovery"
    private const val CLEAN = "clean"
    private const val PENDING = "pending"

    fun initialize(context: Context, catalog: List<Game>): Boolean {
        val prefs = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)
        val values = prefs.all
        val marker = values[KEY]
        check(marker == null || marker == CLEAN || marker == PENDING) { "Invalid recovery state" }
        if (catalog.any { it.tree.isNotBlank() && it.files.isNotEmpty() }) return false
        if (marker == CLEAN) return false
        if (marker == PENDING) return true
        val pending = LegacyCatalogBridge.hasLibraryState(context)
        check(prefs.edit().putString(KEY, if (pending) PENDING else CLEAN).commit()) { "Cannot save recovery state" }
        return pending
    }
}

internal enum class LegacyNetworkState { OFFLINE, METERED, UNMETERED }
internal fun legacyRecoveryAllowed(network: LegacyNetworkState, meteredApproved: Boolean): Boolean =
    network == LegacyNetworkState.UNMETERED || (network == LegacyNetworkState.METERED && meteredApproved)
