package tv.orbit.shield

/** A result of the selected import, derived from the committed library snapshot. */
internal data class GameImportSummary(
    val platform: String,
    val available: Int,
    val added: Int,
    val failed: Boolean,
    val warnings: List<String> = emptyList(),
)

internal fun summarizeGameImport(
    platform: String,
    scanned: List<Game>?,
    previous: List<Game>,
    committed: List<Game>,
    warnings: List<String>,
): GameImportSummary {
    if (scanned == null) return GameImportSummary(platform, 0, 0, true, warnings.toList())
    val selectedFiles = scanned.asSequence().filter { it.platform == platform }
        .flatMap { it.files.asSequence() }.map(::libraryFileKey).toHashSet()
    val previousFiles = previous.asSequence().filter { it.platform == platform }
        .flatMap { it.files.asSequence() }.map(::libraryFileKey).toHashSet()
    val selectedCards = committed.asSequence().filter { it.platform == platform }
        .filter { game -> game.files.any { libraryFileKey(it) in selectedFiles } }.distinctBy { it.id }.toList()
    // A new variant or a second grant for an existing document is not a new card.
    val added = selectedCards.count { game -> game.files.none { libraryFileKey(it) in previousFiles } }
    return GameImportSummary(platform, selectedCards.size, added, false, warnings.toList())
}
