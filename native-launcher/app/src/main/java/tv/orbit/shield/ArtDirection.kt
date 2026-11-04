package tv.orbit.shield

/** A gallery entry is either a painting or an independent procedural performance. */
internal enum class ArtActKind(val id: String) {
    PAINTING("painting"), SNAKES("snakes");

    companion object {
        fun fromId(id: String?): ArtActKind? = entries.firstOrNull { it.id == id }
    }
}

/** Authored presentation of one artwork, never a user's global pixel-size preference. */
internal data class ArtTreatment(
    val style: RevealStyle = RevealStyle.SCENE,
    val columns: Int = 160,
    val rows: Int = 90,
    val retainPixelGrid: Boolean = false,
) {
    init {
        require(style in PAINTING_STYLES) { "A painting treatment must be a concrete painting style" }
        require(columns in 2..MAX_COLUMNS && rows in 2..MAX_ROWS && columns * rows <= MAX_CELLS) {
            "Artwork grid exceeds its rendering budget"
        }
    }

    companion object {
        const val MAX_COLUMNS = 256
        const val MAX_ROWS = 256
        const val MAX_CELLS = 160 * 90
        val PAINTING_STYLES = setOf(RevealStyle.SCENE, RevealStyle.INK, RevealStyle.TILES,
            RevealStyle.FOCUS, RevealStyle.BLOCKS, RevealStyle.WEAVE, RevealStyle.STILL)
    }
}

/** Treatments are curated with the picture. A snake act does not reveal another image. */
internal data class ArtDirection(
    val kind: ArtActKind = ArtActKind.PAINTING,
    val treatments: List<ArtTreatment> = listOf(ArtTreatment()),
) {
    val isInterlude: Boolean get() = kind != ArtActKind.PAINTING

    init {
        if (kind == ArtActKind.PAINTING) {
            require(treatments.size in 1..MAX_TREATMENTS) { "A painting needs curated treatments" }
            require(treatments.distinct().size == treatments.size) { "Duplicate artwork treatment" }
        } else {
            require(treatments.isEmpty()) { "Independent acts cannot contain painting treatments" }
        }
    }

    companion object {
        const val MAX_TREATMENTS = 8
    }
}
