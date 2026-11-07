package tv.orbit.shield

import java.util.Locale

/** Presentation only: different files keep their own IDs, source grants and settings. */
internal data class GameGroupKey(val platform: String, val title: String)
internal data class GameVersion(val game: Game, val fileIndex: Int)

internal fun gameGroupKey(game: Game): GameGroupKey {
    val title = game.title.trim().lowercase(Locale.ROOT)
    return GameGroupKey(game.platform, title.ifEmpty { "\u0000${game.id}" })
}

internal fun groupedGames(games: List<Game>, enabled: Boolean): List<Game> =
    if (enabled) games.distinctBy(::gameGroupKey) else games

internal fun gameVersions(game: Game, groups: Map<GameGroupKey, List<Game>>, enabled: Boolean): List<GameVersion> {
    val members = if (enabled) groups[gameGroupKey(game)].orEmpty() else listOf(game)
    // The card the owner opened stays first, even in custom/favorite shelves.
    return (listOf(game) + members.filter { it.id != game.id }).flatMap { member ->
        member.files.indices.map { GameVersion(member, it) }
    }
}
