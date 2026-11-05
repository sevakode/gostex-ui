package tv.orbit.shield

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

/** A successful empty scan means deletion; an unavailable root keeps its last snapshot. */
internal fun libraryRootSnapshot(root: String, scanned: List<Game>?, previous: List<Game>, platform: String? = null): List<Game> =
    scanned ?: previous.filter { it.tree == root && (platform == null || it.platform == platform) }

/** Normalize only the SAF root identity; a document ID remains opaque and case-sensitive. */
private fun libraryRootKey(root: String): String = runCatching {
    if (!root.startsWith("content:")) "com.android.externalstorage.documents|$root"
    else {
        val uri = URI(root)
        val encoded = uri.rawPath.removePrefix("/tree/").substringBefore('/')
        check(uri.rawPath.startsWith("/tree/") && encoded.isNotEmpty())
        "${uri.authority}|${URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8") }"
    }
}.getOrDefault(root)

private fun rootQualifiedGameId(id: String, root: String): String {
    val hash = MessageDigest.getInstance("SHA-256").digest(libraryRootKey(root).toByteArray(Charsets.UTF_8))
        .take(12).joinToString("") { "%02x".format(it) }
    val suffix = "-root-$hash"
    return if (id.endsWith(suffix)) id else id + suffix
}

/** Keep legacy favorites on the original catalog folder. Other grants receive a stable
 * identity at import time, so removing or reordering folders never renames their cards. */
internal fun catalogImportEntry(catalog: Game, file: String, root: String): Game = catalog.copy(
    id = if (libraryRootKey(catalog.tree) == libraryRootKey(root)) catalog.id else rootQualifiedGameId(catalog.id, root),
    files = listOf(file), tree = root
)

/** Compare the same external-storage document across tree and single-file grants.
 * Keep opaque IDs, filename case and literal '+' intact; never equate different files. */
internal fun libraryFileKey(file: String): String = runCatching {
    if (file.startsWith("/storage/emulated/0/")) "com.android.externalstorage.documents|primary:" + file.removePrefix("/storage/emulated/0/")
    else if (file.startsWith("content:")) {
        val uri = URI(file)
        val segments = uri.rawPath.split('/')
        val index = segments.indexOf("document")
        if (index >= 0 && index + 1 < segments.size)
            "${uri.authority}|${URLDecoder.decode(segments[index + 1].replace("+", "%2B"), "UTF-8") }"
        else file
    } else file
}.getOrDefault(file)

/** Reuse a persisted card only for the same granted source and exact document.
 * A previously scanned card owns its stable ID and metadata even if the bundled
 * catalog changes. Ambiguous snapshots abort the scan instead of guessing. */
internal fun previousImportIndex(previous: List<Game>, root: String, platform: String): Map<String, Game> {
    val rootKey = libraryRootKey(root)
    val candidates = mutableMapOf<String, MutableList<Game>>()
    previous.asSequence().filter { it.platform == platform && libraryRootKey(it.tree) == rootKey }.forEach { game ->
        game.files.forEach { file -> candidates.getOrPut(libraryFileKey(file)) { mutableListOf() }.add(game) }
    }
    return candidates.mapValues { (_, games) ->
        val unique = games.distinct()
        check(unique.size == 1) { "Ambiguous persisted game identity" }
        unique.single()
    }
}

/** Merge variants only within one grant. Overlapping grants for the same document can
 * share a card; legacy cross-root entries with distinct files must retain separate cards. */
internal fun mergeImportedVariants(games: List<Game>): List<Game> {
    val seenDocuments = mutableSetOf<Pair<String, String>>()
    return games.groupBy { it.id }.values.flatMap { sameId ->
        val roots = sameId.groupBy { it.tree }
        var hasCard = false
        roots.entries.mapNotNull { (_, variants) ->
            val first = variants.first()
            val files = variants.flatMap { it.files }.distinct().filter { seenDocuments.add(first.platform to libraryFileKey(it)) }
            if (files.isEmpty()) null else first.copy(
                id = if (!hasCard) first.id else rootQualifiedGameId(first.id, first.tree),
                files = files
            ).also { hasCard = true }
        }
    }
}
