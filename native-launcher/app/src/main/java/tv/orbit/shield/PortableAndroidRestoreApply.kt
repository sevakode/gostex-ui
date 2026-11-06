package tv.orbit.shield

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest

/** Internal restore commit. The caller MUST hold a gate excluding every live library,
 * cover and preference reader/writer from before revalidation until commit/recovery,
 * and must run recovery before any component accesses them after a process restart.
 * No production caller currently satisfies that precondition. */
internal object PortableAndroidRestoreApply {
    private const val GAME_SLOT = "library-games"
    private const val COVER_SLOT = "cover-index"
    private const val PREF_SLOT = "orbit-prefs"
    private const val HOME_KEY = "home.sections.v1"
    private const val ROOTS_KEY = "libraryRoots"
    private const val CHOICE_PREFIX = "covers.choice."

    private fun verifyCovers(context: Context, result: PortableRestoreMergeResult, restore: PortableRestorePlan) {
        val keys = result.covers.values.flatMap { listOf(it.manual, it.original, it.wide) }.filter(String::isNotEmpty).toSet()
        val referenced = restore.covers.values.flatMap { listOf(it.manual, it.original, it.wide) }
            .filter(String::isNotEmpty).map { managedCoverDigest(it) ?: error("Invalid cover reference") }.toSet()
        require(restore.coverFiles.map { it.digest }.distinct().size == restore.coverFiles.size)
        require(restore.coverFiles.map { it.digest }.toSet() == referenced) { "Incomplete cover index" }
        restore.coverFiles.filter { managedCoverKey(it.digest) in keys }.forEach { cover ->
            require(coverDigestValid(cover.digest) && cover.bytes in 1..(10L * 1024 * 1024))
            val file = File(context.filesDir, "covers/${cover.digest}.image")
            require(file.isFile && file.length() == cover.bytes) { "Cover not staged" }
            val bytes = file.readBytes()
            require(bytes.size.toLong() == cover.bytes)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            require(digest == cover.digest) { "Staged cover changed" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outMimeType in setOf("image/png", "image/jpeg", "image/webp") &&
                bounds.outWidth in 32..8192 && bounds.outHeight in 32..8192 &&
                bounds.outWidth.toLong() * bounds.outHeight <= 33_554_432L) { "Invalid cover" }
            var sample = 1
            while (bounds.outWidth.toLong() * bounds.outHeight / sample / sample > 2_097_152) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: error("Cover cannot be decoded")
            bitmap.recycle()
        }
    }

    fun applyUnderExclusiveAccess(context: Context, expectedLive: PortableLiveLibrary,
                                  expectedRoots: List<LibraryRootRecord>, expectedRestore: PortableRestorePlan,
                                  decisions: Map<PortableRestoreConflict, PortableRestoreChoice>,
                                  revalidateRestore: () -> PortableRestorePlan): PortableRestoreMergeResult {
        val prefs = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)
        val live = PortableLiveCapture.capture(context)
        val roots = LibraryImport.roots(context).map { LibraryRootRecord(it.platform, it.uri, it.source) }
        check(live == expectedLive && roots == expectedRoots) { "Live state changed" }
        val restore = revalidateRestore()
        check(restore == expectedRestore) { "Portable copy changed" }
        val result = resolvePortableRestore(live, roots, restore, decisions)
        check(result.ready) { "Unresolved restore conflicts" }
        verifyCovers(context, result, restore)
        val gamesJson = LibraryImport.encode(result.games)
        requirePortableJson(gamesJson, 16 * 1024 * 1024)
        check(LibraryImport.decode(gamesJson) == result.games)
        val coversJson = ManagedCoverManifest.encodeIndex(result.covers)
        requirePortableJson(coversJson, ManagedCoverManifest.MAX_BYTES)
        check(ManagedCoverManifest.decodeIndex(coversJson) == result.covers)
        PortableSettingsCodec.encode(result.settings)
        val homeJson = PortableHomeStateCodec.encode(requireNotNull(result.home))
        val currentRoots = if (prefs.contains(ROOTS_KEY)) prefs.getString(ROOTS_KEY, null) ?: error("Invalid roots") else "[]"
        LibraryRootCodec.requireLosslessLegacy(currentRoots)
        val rootsJson = LibraryRootCodec.encode(result.roots)
        val keys = (setOf(HOME_KEY, ROOTS_KEY) + live.settings.keys + result.settings.keys +
            (live.coverChoices.keys + result.choices.keys).map { CHOICE_PREFIX + it }).toSet()
        val files = PortableAndroidTransactionStorage(context.filesDir,
            mapOf(GAME_SLOT to "imported-games.json", COVER_SLOT to "covers/index.json"))
        val storage = PortableAndroidPreferencesTransactionStorage(files, prefs, keys, PREF_SLOT)
        val values = prefs.all.toMutableMap()
        values[HOME_KEY] = homeJson
        values[ROOTS_KEY] = rootsJson
        (live.settings.keys + result.settings.keys).forEach { key ->
            if (key in result.settings) values[key] = result.settings.getValue(key) else values.remove(key)
        }
        (live.coverChoices.keys + result.choices.keys).forEach { id ->
            val key = CHOICE_PREFIX + id
            if (id in result.choices) values[key] = result.choices.getValue(id) else values.remove(key)
        }
        val beforeGames = storage.read(GAME_SLOT)
        val beforeCovers = storage.read(COVER_SLOT)
        val beforePrefs = storage.read(PREF_SLOT)
        val afterGames = if (beforeGames == null && result.games.isEmpty()) null else gamesJson
        val afterCovers = if (beforeCovers == null && result.covers.isEmpty()) null else coversJson
        val afterPrefs = storage.snapshotFromValues(values)
        val changes = listOf(PortableTransactionChange(GAME_SLOT, beforeGames, afterGames),
            PortableTransactionChange(COVER_SLOT, beforeCovers, afterCovers),
            PortableTransactionChange(PREF_SLOT, beforePrefs, afterPrefs)).filter { it.before != it.after }
        if (changes.isNotEmpty()) PortableRestoreTransaction.begin(storage, changes)
        return result
    }
}
