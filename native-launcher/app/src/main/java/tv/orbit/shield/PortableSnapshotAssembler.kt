package tv.orbit.shield

import java.util.Locale

/** Bind a live library locator to a copied file ID. Descriptor companions must be
 * derived from the validated source disc plan, not guessed from filenames. */
internal data class PortableVariantBinding(val fileId: String, val companionIds: List<String>? = null)

/** Builds metadata only after the caller copied and verified all source files.
 * It does not acquire permissions, verify the disc plan, re-check source stability,
 * write a commit or change live data. Locators are lookup keys and are never saved. */
internal object PortableSnapshotAssembler {
    fun assemble(id: String, live: PortableLiveLibrary, content: PortableGeneratedContent,
                 variants: Map<String, PortableVariantBinding>, covers: Map<String, String>): PortableSnapshot {
        val filesById = content.files.associateBy { it.id }
        require(filesById.size == content.files.size)
        val files = content.files.map { copied ->
            require(copied.path.firstOrNull() == content.generationName && copied.path.size > 1)
            PortableIndexedFile(copied.id, copied.path, copied.receipt.bytes, copied.receipt.sha256)
        }
        val gameIds = live.games.map { it.id }.toSet()
        require(gameIds.size == live.games.size)
        require(live.covers.keys.all { it in gameIds } && live.coverChoices.keys.all { it in gameIds }) {
            "Cover data exists for games outside the captured library"
        }
        val usedVariants = mutableSetOf<String>()
        val usedCovers = mutableSetOf<String>()
        val usedFiles = mutableSetOf<String>()
        fun contentFile(fileId: String): PortableCopiedContent = filesById[fileId] ?: error("Missing copied file")
        fun cover(key: String): String? {
            if (key.isEmpty()) return null
            val digest = managedCoverDigest(key) ?: error("Invalid managed cover")
            val fileId = covers[key] ?: error("Missing copied cover")
            val copied = contentFile(fileId)
            require(copied.receipt.sha256 == digest) { "Cover digest mismatch" }
            usedCovers += key; usedFiles += fileId
            return fileId
        }
        val games = live.games.map { game ->
            require(game.files.isNotEmpty()) { "Game has no transferable files" }
            val fileIds = game.files.map { locator ->
                val binding = variants[locator] ?: error("Missing source binding")
                val copied = contentFile(binding.fileId)
                val extension = copied.path.last().substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (extension in setOf("cue", "m3u", "gdi"))
                    require(!binding.companionIds.isNullOrEmpty()) { "Descriptor companions not verified" }
                binding.companionIds.orEmpty().forEach { companion -> contentFile(companion); usedFiles += companion }
                usedVariants += locator; usedFiles += binding.fileId
                binding.fileId
            }
            val art = live.covers[game.id] ?: ManagedCover()
            PortableIndexedGame(game.id, game.title, game.platform, game.emulator, game.component, fileIds,
                game.genre, game.year, game.developer, game.players, game.description, game.demo,
                cover(art.manual), cover(art.original), cover(art.wide), live.coverChoices[game.id] ?: "auto",
                game.cover, game.scene, art.sourceName, art.sourceUrl, art.author)
        }
        require(variants.keys == usedVariants && covers.keys == usedCovers && filesById.keys == usedFiles) {
            "Unexpected or unreferenced copied data"
        }
        val result = PortableSnapshot(id, PortableGameIndex(files, games), live.home, live.settings)
        PortableSnapshotCodec.encode(result) // Apply all schema, size, privacy and cross-reference checks.
        return result
    }
}
