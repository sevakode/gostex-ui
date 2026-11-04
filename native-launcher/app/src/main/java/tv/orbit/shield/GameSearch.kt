package tv.orbit.shield

import java.text.Normalizer
import java.util.Locale

/**
 * Offline library search. Build once for a library/language snapshot, not on each keystroke.
 * This searches known metadata; it never guesses aliases or changes a game's identity.
 */
internal class GameSearchIndex(
    games: List<Game>,
    platformLabels: Map<String, String> = emptyMap(),
) {
    private data class Entry(val game: Game, val fields: List<String>)

    private val snapshot = games.toList()
    private val entries = snapshot.map { game ->
        Entry(game, listOf(game.title, game.platform, platformLabels[game.platform].orEmpty(), game.developer)
            .map(::searchKey).filter(String::isNotEmpty).distinct())
    }

    /** All query words must match, possibly across different fields. Library order is retained. */
    fun filter(query: String): List<Game> {
        val key = searchKey(query)
        if (key.isEmpty()) return snapshot
        val words = key.split(' ').distinct()
        return entries.filter { entry ->
            words.all { word -> entry.fields.any { field -> word in field } }
        }.map(Entry::game)
    }
}

/** Punctuation separates words; accents, case and whitespace never require exact typing. */
private fun searchKey(text: String): String {
    val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD).lowercase(Locale.ROOT)
    return buildString(decomposed.length) {
        var offset = 0
        var pendingSpace = false
        while (offset < decomposed.length) {
            val codePoint = decomposed.codePointAt(offset)
            offset += Character.charCount(codePoint)
            when (Character.getType(codePoint)) {
                Character.NON_SPACING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt() -> Unit
                else -> if (Character.isLetterOrDigit(codePoint)) {
                    if (pendingSpace && isNotEmpty()) append(' ')
                    appendCodePoint(codePoint)
                    pendingSpace = false
                } else {
                    pendingSpace = true
                }
            }
        }
    }
}
