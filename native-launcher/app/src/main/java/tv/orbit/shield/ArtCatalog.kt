package tv.orbit.shield

import android.content.Context
import org.json.JSONObject

/** Content is data: every gallery entry carries its reference and honest artwork credit. */
internal data class ArtEntry(
    val id: String,
    val theme: ArtTheme,
    val asset: String?,
    val titles: Map<String, String>,
    val references: Map<String, String>,
    val source: String,
    val world: ArtWorld,
    val reveal: SceneRevealProfile = SceneRevealProfile(),
    val direction: ArtDirection = ArtDirection(),
) {
    private fun localized(context: Context, values: Map<String, String>): String =
        values[context.resources.configuration.locales[0].language] ?: values.getValue("en")
    fun title(context: Context) = localized(context, titles)
    fun reference(context: Context) = localized(context, references)
    fun credit(context: Context) = context.getString(if (asset == null) R.string.art_credit_generated else R.string.art_credit_ai)
}

internal object ArtCatalog {
    fun load(context: Context): List<ArtEntry> = runCatching {
        val json = context.assets.open("art/catalog.json").bufferedReader().use { JSONObject(it.readText()) }
        val entries = json.getJSONArray("artworks")
        List(entries.length()) { index ->
            val item = entries.getJSONObject(index)
            fun translations(key: String): Map<String, String> {
                val strings = item.getJSONObject(key)
                return strings.keys().asSequence().associateWith { strings.getString(it) }.also {
                    require(!it["en"].isNullOrBlank())
                }
            }
            ArtEntry(item.getString("id"), ArtTheme.entries.first { it.id == item.getString("theme") },
                item.optString("asset").takeIf { it.isNotBlank() }, translations("title"),
                translations("reference"), item.getString("source"),
                requireNotNull(ArtWorld.fromId(item.getString("world"))), revealProfile(item), directionProfile(item))
        }.also { entries ->
            require(entries.isNotEmpty() && entries.map(ArtEntry::id).distinct().size == entries.size)
            require(entries.any { it.direction.kind == ArtActKind.PAINTING })
            require(entries.map(ArtEntry::theme).all { theme -> entries.any {
                it.theme == theme && it.asset == null && it.direction.kind == ArtActKind.PAINTING
            } })
        }
    }.getOrElse {
        listOf(ArtEntry("weekly-games", ArtTheme.GAMES, null,
            mapOf("en" to context.getString(R.string.art_fallback_title)),
            mapOf("en" to context.getString(R.string.art_fallback_reference)), "https://tetris.com/", ArtWorld.TETROMINO))
    }

    private fun revealProfile(item: JSONObject): SceneRevealProfile = runCatching {
        val profile = item.optJSONObject("reveal") ?: return@runCatching SceneRevealProfile()
        val anchors = profile.getJSONArray("anchors")
        require(anchors.length() in 1..SceneRevealProfile.MAX_ANCHORS)
        SceneRevealProfile(List(anchors.length()) { index ->
            val anchor = anchors.getJSONObject(index)
            fun number(key: String): Double = (anchor.get(key) as? Number)?.toDouble()
                ?: error("Reveal anchor $key must be a number")
            RevealAnchor(number("x"), number("y"), number("radiusX"), number("radiusY"), number("delay"))
        }, requireNotNull(SceneDevelopment.fromId(profile.getString("development"))))
    }.getOrElse { SceneRevealProfile() }

    /** A malformed content profile falls back locally, without replacing the artwork or its world. */
    private fun directionProfile(item: JSONObject): ArtDirection = runCatching {
        val profile = item.optJSONObject("direction") ?: return@runCatching ArtDirection()
        val kind = requireNotNull(ArtActKind.fromId(profile.getString("kind")))
        val treatments = profile.getJSONArray("treatments")
        ArtDirection(kind, List(treatments.length()) { index ->
            val treatment = treatments.getJSONObject(index)
            fun integer(key: String, fallback: Int): Int {
                if (!treatment.has(key)) return fallback
                val number = treatment.get(key) as? Number ?: error("Artwork $key must be an integer")
                val value = number.toDouble()
                require(value.isFinite() && value == value.toInt().toDouble())
                return value.toInt()
            }
            val retainPixelGrid = if (!treatment.has("retainPixelGrid")) false
                else treatment.get("retainPixelGrid") as? Boolean ?: error("retainPixelGrid must be boolean")
            ArtTreatment(requireNotNull(RevealStyle.entries.firstOrNull { it.id == treatment.getString("style") }),
                integer("columns", 160), integer("rows", 90), retainPixelGrid)
        })
    }.getOrElse {
        // If the act kind itself is valid, never silently turn it into another kind of scene.
        if (item.optJSONObject("direction")?.optString("kind") == ArtActKind.SNAKES.id)
            ArtDirection(ArtActKind.SNAKES, emptyList()) else ArtDirection()
    }

    /** Earlier priorities appear more often (5:4:3:2:1), while every theme remains reachable. */
    fun playlist(entries: List<ArtEntry>, priorities: List<ArtTheme>, cycle: Long = 0L): List<ArtEntry> {
        val groups = entries.groupBy(ArtEntry::theme)
        val occurrences = mutableMapOf<ArtTheme, Int>()
        return buildList {
            for (round in priorities.indices) for ((rank, theme) in priorities.withIndex()) {
                val available = groups[theme].orEmpty()
                if (rank < priorities.size - round && available.isNotEmpty()) {
                    val count = occurrences.getOrDefault(theme, 0)
                    // Rotate the starting variant each cycle even for the lowest-priority theme.
                    add(available[((count + cycle) % available.size).toInt()])
                    occurrences[theme] = count + 1
                }
            }
        }.ifEmpty { entries }
    }
}
