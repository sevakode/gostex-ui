package tv.orbit.shield

/** Stable artwork identity, independent of the user's category priorities. */
internal enum class ArtWorld(val id: String) {
    HYRULE("hyrule"),
    TATOOINE("tatooine"),
    GOTHAM("gotham"),
    LANDS_BETWEEN("lands-between"),
    BERSERK("berserk"),
    CASTLEVANIA("castlevania"),
    TETROMINO("tetromino"),
    LONDON("london"),
    B612("b612"),
    TRANQUILITY("tranquility"),
    SNAKE("snake");

    companion object {
        fun fromId(id: String?): ArtWorld? = entries.firstOrNull { it.id == id }
    }
}
