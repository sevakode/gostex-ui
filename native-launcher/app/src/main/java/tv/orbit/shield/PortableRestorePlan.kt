package tv.orbit.shield

internal data class PortableTrustedPlatform(val emulator: String, val component: String)
internal data class PortableRestoreCover(val digest: String, val documentId: String, val bytes: Long)
internal data class PortableRestoreRoot(val platform: String, val treeUri: String)
internal data class PortableRestorePlan(
    val snapshot: PortableSnapshot,
    val games: List<Game>,
    val roots: List<PortableRestoreRoot>,
    val covers: Map<String, ManagedCover>,
    val coverChoices: Map<String, String>,
    val coverFiles: List<PortableRestoreCover>,
    val unsupportedPlatforms: Set<String>,
)
internal data class PortableRestoreAssessment(val inspection: PortableBackupInspection, val plan: PortableRestorePlan?)

/** Read-only preview; no settings, roots, private files or existing library are changed.
 * The caller must later validate image decodability, implement a transaction and use a
 * portable-aware root mode before applying this plan. File contents/provider state may
 * change after inspection and must be revalidated before any real restore. */
internal object PortableRestorePlanner {
    fun inspect(stateDirectory: PortableStagingDirectory, contentTree: PortableContentTree, commitId: String,
                treeUri: String, documentUri: (String) -> String,
                platform: (String) -> PortableTrustedPlatform?, checkActive: () -> Unit = {},
                onProgress: (String, Long, Long) -> Unit = { _, _, _ -> }): PortableRestoreAssessment {
        require(treeUri.isNotBlank())
        val inspection = inspectPortableBackup(stateDirectory, contentTree, commitId, checkActive, onProgress)
        val snapshot = inspection.committed.snapshot
        if (inspection.content.issues.isNotEmpty()) return PortableRestoreAssessment(inspection, null)
        val documents = inspection.content.verified
        require(documents.keys == snapshot.index.files.map { it.id }.toSet())
        require(documents.values.distinct().size == documents.size) { "Aliased verified documents" }
        val indexed = snapshot.index.files.associateBy { it.id }
        val coverFiles = linkedMapOf<String, PortableRestoreCover>()
        fun cover(fileId: String?): String {
            if (fileId == null) return ""
            val file = indexed[fileId] ?: error("Missing cover metadata")
            val doc = documents[fileId] ?: error("Missing cover document")
            coverFiles.putIfAbsent(file.sha256, PortableRestoreCover(file.sha256, doc, file.bytes))
            return managedCoverKey(file.sha256)
        }
        val covers = linkedMapOf<String, ManagedCover>()
        val choices = linkedMapOf<String, String>()
        val unsupported = linkedSetOf<String>()
        val games = snapshot.index.games.map { game ->
            checkActive()
            val trusted = platform(game.platform)
            if (trusted == null) unsupported += game.platform
            val entry = ManagedCover(cover(game.manualCover), cover(game.originalCover), cover(game.wideCover),
                game.coverSourceName, game.coverSourceUrl, game.coverAuthor)
            if (entry != ManagedCover()) covers[game.id] = entry
            choices[game.id] = game.coverChoice
            val locators = game.variants.map { fileId ->
                val locator = documentUri(documents[fileId] ?: error("Missing game document"))
                require(locator.isNotBlank())
                locator
            }
            Game(game.id, game.title, game.platform, trusted?.emulator ?: game.emulator, trusted?.component.orEmpty(),
                locators, treeUri, game.baseCover, game.scene, game.genre, game.year, game.developer, game.players,
                game.demo, game.description)
        }
        val roots = games.map { it.platform }.distinct().map { PortableRestoreRoot(it, treeUri) }
        return PortableRestoreAssessment(inspection, PortableRestorePlan(snapshot, games, roots, covers, choices,
            coverFiles.values.toList(), unsupported.toSet()))
    }
}
