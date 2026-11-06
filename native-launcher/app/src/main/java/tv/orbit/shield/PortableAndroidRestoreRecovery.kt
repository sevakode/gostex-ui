package tv.orbit.shield

import android.content.Context

/** Call before any component reads/writes these stores and under the same exclusion
 * gate as restore. A bad journal is left intact and must block ordinary access. */
internal object PortableAndroidRestoreRecovery {
    private const val GAME = "library-games"
    private const val COVER = "cover-index"
    private const val PREFS = "orbit-prefs"
    private const val ROOTS = "libraryRoots"
    private const val HOME = "home.sections.v1"
    private const val CHOICE = "covers.choice."

    fun recoverUnderExclusiveAccess(context: Context): Boolean {
        val files = PortableAndroidTransactionStorage(context.filesDir,
            mapOf(GAME to "imported-games.json", COVER to "covers/index.json"))
        val journal = files.readJournal() ?: return false
        val record = PortableRestoreTransaction.decode(journal)
        require(record.changes.all { it.slot in setOf(GAME, COVER, PREFS) }) { "Unknown restore slot" }
        record.changes.forEach { change ->
            when (change.slot) {
                GAME -> listOfNotNull(change.before, change.after).forEach { PortableLiveCapture.strictGames(it) }
                COVER -> listOfNotNull(change.before, change.after).forEach { PortableLiveCapture.strictCovers(it) }
            }
        }
        val prefChange = record.changes.singleOrNull { it.slot == PREFS }
        val storage: PortableTransactionStorage = if (prefChange == null) files else {
            val before = requireNotNull(prefChange.before)
            val after = requireNotNull(prefChange.after)
            val keys = PortableAndroidPreferencesTransactionStorage.keysFromSnapshot(before)
            require(keys == PortableAndroidPreferencesTransactionStorage.keysFromSnapshot(after)) { "Preference scope changed" }
            val settingKeys = PortableSettingsCodec.supportedKeys()
            require(keys.all { it == ROOTS || it == HOME || it in settingKeys ||
                (it.startsWith(CHOICE) && it.removePrefix(CHOICE).isNotBlank() && it.removePrefix(CHOICE).length <= 512) }) {
                "Unsupported preference in restore journal"
            }
            val adapter = PortableAndroidPreferencesTransactionStorage(files,
                context.getSharedPreferences("orbit", Context.MODE_PRIVATE), keys, PREFS)
            listOf(before, after).forEach { snapshot ->
                val values = adapter.decodeSnapshot(snapshot)
                values[HOME]?.let { PortableHomeStateCodec.decode(it as? String ?: error("Invalid home state")) }
                values[ROOTS]?.let {
                    val text = it as? String ?: error("Invalid roots")
                    LibraryRootCodec.requireLosslessLegacy(text)
                    LibraryRootCodec.decode(text)
                }
                values.filterKeys { it in settingKeys }.let(PortableSettingsCodec::encode)
                values.filterKeys { it.startsWith(CHOICE) }.values.forEach {
                    require(it is String && it in setOf("auto", "manual", "original"))
                }
            }
            adapter
        }
        return PortableRestoreTransaction.recover(storage)
    }
}
