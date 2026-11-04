package tv.orbit.shield

/** How a painting develops, independent of travel direction or its franchise. */
internal enum class SceneDevelopment(val id: String) {
    CONTOURS("contours"), LIGHT("light");

    companion object {
        fun fromId(id: String?): SceneDevelopment? = entries.firstOrNull { it.id == id }
    }
}

/** An authored area of a painting, expressed in normalized canvas coordinates. */
internal data class RevealAnchor(
    val x: Double,
    val y: Double,
    val radiusX: Double,
    val radiusY: Double,
    val delay: Double,
) {
    init {
        require(x.isFinite() && x in 0.0..1.0)
        require(y.isFinite() && y in 0.0..1.0)
        require(radiusX.isFinite() && radiusX > 0.0 && radiusX <= 1.0)
        require(radiusY.isFinite() && radiusY > 0.0 && radiusY <= 1.0)
        require(delay.isFinite() && delay in 0.0..1.0)
    }
}

/** Composition metadata, not a preference: each painting develops around its own subjects. */
internal data class SceneRevealProfile(
    val anchors: List<RevealAnchor> = listOf(
        RevealAnchor(.35, .45, .4, .55, 0.0),
        RevealAnchor(.7, .6, .35, .45, .2),
    ),
    val development: SceneDevelopment = SceneDevelopment.CONTOURS,
) {
    init { require(anchors.size in 1..MAX_ANCHORS) }

    companion object { const val MAX_ANCHORS = 8 }
}
