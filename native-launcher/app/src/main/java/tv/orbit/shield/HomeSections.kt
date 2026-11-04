package tv.orbit.shield

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

internal enum class HomeSectionKind { CUSTOM, RECENT, FAVORITES, GAMES, APPS }

internal data class HomeSection(
    val id: String,
    val kind: HomeSectionKind,
    val name: String = "",
    val items: List<String> = emptyList(),
    val visible: Boolean = true
)

internal data class HomeSectionsOptions(
    val sections: List<HomeSection>,
    val favorites: List<String>
)

internal fun homeGameKey(id: String): String = "game:$id"
internal fun homeAppKey(packageName: String): String = "app:$packageName"

/** One document owns section order, membership, visibility and shared favorites after migration. */
internal object SectionPreferences {
    internal const val MAX_SECTION_NAME_LENGTH = 64
    private const val DOCUMENT_KEY = "home.sections.v1"
    private const val DOCUMENT_VERSION = 1
    internal const val MAX_SECTIONS = 64
    private const val MAX_SECTION_ITEMS = 4096
    private const val MAX_FAVORITES = 8192
    private const val MAX_DOCUMENT_LENGTH = 8 * 1024 * 1024
    private const val MAX_ITEM_KEY_LENGTH = 512
    private val sectionId = Regex("[A-Za-z0-9:_-]{1,80}")

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("orbit", Context.MODE_PRIVATE)

    private fun cleanName(value: String): String? {
        if (value.any(Char::isISOControl)) return null
        val name = value.trim().replace(Regex("\\s+"), " ")
        return name.takeIf { it.isNotEmpty() && it.codePointCount(0, it.length) <= MAX_SECTION_NAME_LENGTH }
    }

    private fun validItemKey(value: String): Boolean {
        if (value.length > MAX_ITEM_KEY_LENGTH || value.any(Char::isISOControl)) return false
        val payload = when {
            value.startsWith("game:") -> value.removePrefix("game:")
            value.startsWith("app:") -> value.removePrefix("app:")
            else -> return false
        }
        return payload.isNotBlank() && payload == payload.trim()
    }

    private fun cleanItems(items: Iterable<String>, maximum: Int): List<String> =
        items.asSequence().filter(::validItemKey).distinct().take(maximum).toList()

    private fun stringItems(array: JSONArray?, maximum: Int): List<String> {
        if (array == null) return emptyList()
        return cleanItems((0 until array.length()).asSequence().mapNotNull { array.opt(it) as? String }.asIterable(), maximum)
    }

    private fun decode(text: String?): HomeSectionsOptions? = runCatching {
        require(text != null && text.length <= MAX_DOCUMENT_LENGTH)
        val document = JSONObject(text)
        require(document.opt("version") == DOCUMENT_VERSION)
        val array = document.optJSONArray("sections") ?: return@runCatching null
        val favoriteArray = document.optJSONArray("favorites") ?: return@runCatching null
        val usedIds = mutableSetOf<String>()
        val usedKinds = mutableSetOf<HomeSectionKind>()
        val sections = buildList {
            for (index in 0 until array.length()) {
                if (size >= MAX_SECTIONS) break
                val entry = array.optJSONObject(index) ?: continue
                val id = (entry.opt("id") as? String)?.takeIf { sectionId.matches(it) } ?: continue
                val kind = HomeSectionKind.entries.firstOrNull {
                    it.name.lowercase(Locale.ROOT) == entry.opt("kind")
                } ?: continue
                val name = if (kind == HomeSectionKind.CUSTOM) cleanName(entry.opt("name") as? String ?: "") ?: continue else ""
                if (id in usedIds || (kind != HomeSectionKind.CUSTOM && kind in usedKinds)) continue
                usedIds.add(id)
                usedKinds.add(kind)
                add(HomeSection(id, kind, name,
                    if (kind == HomeSectionKind.CUSTOM) stringItems(entry.optJSONArray("items"), MAX_SECTION_ITEMS) else emptyList(),
                    entry.opt("visible") as? Boolean ?: true))
            }
        }
        HomeSectionsOptions(sections, stringItems(favoriteArray, MAX_FAVORITES))
    }.getOrNull()

    private fun encode(options: HomeSectionsOptions): String = JSONObject().apply {
        put("version", DOCUMENT_VERSION)
        put("sections", JSONArray().apply {
            options.sections.forEach { section ->
                put(JSONObject().apply {
                    put("id", section.id)
                    put("kind", section.kind.name.lowercase(Locale.ROOT))
                    if (section.kind == HomeSectionKind.CUSTOM) {
                        put("name", section.name)
                        put("items", JSONArray(section.items))
                    }
                    put("visible", section.visible)
                })
            }
        })
        put("favorites", JSONArray(options.favorites))
    }.toString()

    /** Corrupt/future documents never reread obsolete preferences or silently rewrite their bytes. */
    private fun safeDefaults(context: Context): HomeSectionsOptions = HomeSectionsOptions(
        listOf(
            HomeSection("video", HomeSectionKind.CUSTOM, context.getString(R.string.home_shelf_video)),
            HomeSection("recent", HomeSectionKind.RECENT),
            HomeSection("favorites", HomeSectionKind.FAVORITES),
            HomeSection("games", HomeSectionKind.GAMES),
            HomeSection("apps", HomeSectionKind.APPS)
        ), emptyList()
    )

    private fun legacySnapshot(context: Context): HomeSectionsOptions {
        val home = HomePreferences.read(context)
        val video = VideoPreferences.read(context)
        val videoItems = (video.order.filter { it in video.visible } + video.visible.sorted()).distinct().map(::homeAppKey)
        val sections = home.shelves.map { shelf ->
            when (shelf) {
                HomeShelf.VIDEO -> HomeSection(shelf.id, HomeSectionKind.CUSTOM,
                    context.getString(R.string.home_shelf_video), cleanItems(videoItems, MAX_SECTION_ITEMS), shelf in home.visibleShelves)
                HomeShelf.RECENT -> HomeSection(shelf.id, HomeSectionKind.RECENT, visible = shelf in home.visibleShelves)
                HomeShelf.FAVORITES -> HomeSection(shelf.id, HomeSectionKind.FAVORITES, visible = shelf in home.visibleShelves)
                HomeShelf.GAMES -> HomeSection(shelf.id, HomeSectionKind.GAMES, visible = shelf in home.visibleShelves)
                HomeShelf.APPS -> HomeSection(shelf.id, HomeSectionKind.APPS, visible = shelf in home.visibleShelves)
            }
        }
        val favorites = prefs(context).readStringSetOrDefault("favorites").sorted().map(::homeGameKey)
        return HomeSectionsOptions(sections, cleanItems(favorites, MAX_FAVORITES))
    }

    /** Read-only: callers may safely obtain the initial state while composing. */
    fun read(context: Context): HomeSectionsOptions {
        val saved = prefs(context)
        if (!saved.contains(DOCUMENT_KEY)) return legacySnapshot(context)
        return decode(saved.readStringOrDefault(DOCUMENT_KEY)) ?: safeDefaults(context)
    }

    /** Call outside composition. A single atomic preference write captures all legacy choices once. */
    @Synchronized fun ensureMigrated(context: Context) {
        val saved = prefs(context)
        if (!saved.contains(DOCUMENT_KEY)) saved.edit().putString(DOCUMENT_KEY, encode(legacySnapshot(context))).apply()
    }

    @Synchronized private fun update(context: Context, transform: (HomeSectionsOptions) -> HomeSectionsOptions): Boolean {
        ensureMigrated(context)
        val saved = prefs(context)
        val existing = saved.readStringOrDefault(DOCUMENT_KEY)
        // A downgrade must not turn a newer document into defaults plus one small edit.
        // Oversized documents are also left untouched rather than parsed on the UI thread.
        if (existing != null && existing.length > MAX_DOCUMENT_LENGTH) return false
        val version = runCatching { JSONObject(existing ?: "").opt("version") as? Number }.getOrNull()
        if (version != null && version.toDouble() != DOCUMENT_VERSION.toDouble()) return false
        val previous = read(context)
        val next = transform(previous)
        if (next == previous) return false
        val document = encode(next)
        if (document.length > MAX_DOCUMENT_LENGTH) return false
        saved.edit().putString(DOCUMENT_KEY, document).apply()
        return true
    }

    fun create(context: Context, name: String): String? {
        val normalized = cleanName(name) ?: return null
        val id = "custom-${UUID.randomUUID()}"
        val created = update(context) { previous ->
            if (previous.sections.size >= MAX_SECTIONS) previous
            else previous.copy(sections = previous.sections + HomeSection(id, HomeSectionKind.CUSTOM, normalized))
        }
        return id.takeIf { created }
    }

    fun rename(context: Context, id: String, name: String): Boolean {
        val normalized = cleanName(name) ?: return false
        return update(context) { previous -> previous.copy(sections = previous.sections.map {
            if (it.id == id && it.kind == HomeSectionKind.CUSTOM) it.copy(name = normalized) else it
        }) }
    }

    fun delete(context: Context, id: String): Boolean = update(context) { previous ->
        previous.copy(sections = previous.sections.filterNot { it.id == id && it.kind == HomeSectionKind.CUSTOM })
    }

    fun setVisible(context: Context, id: String, visible: Boolean): Boolean = update(context) { previous ->
        previous.copy(sections = previous.sections.map { if (it.id == id) it.copy(visible = visible) else it })
    }

    fun move(context: Context, id: String, offset: Int): Boolean = update(context) { previous ->
        val sections = previous.sections.toMutableList()
        val index = sections.indexOfFirst { it.id == id }
        if (index < 0 || offset == 0) previous else {
            val target = (index + offset.coerceIn(-1, 1)).coerceIn(sections.indices)
            sections.add(target, sections.removeAt(index))
            previous.copy(sections = sections)
        }
    }

    fun setItems(context: Context, id: String, items: List<String>): Boolean {
        val normalized = cleanItems(items, MAX_SECTION_ITEMS)
        return update(context) { previous -> previous.copy(sections = previous.sections.map {
            if (it.id == id && it.kind == HomeSectionKind.CUSTOM) it.copy(items = normalized) else it
        }) }
    }

    /** New memberships append; retained memberships keep their old position and unrelated items. */
    fun setMembership(context: Context, itemKey: String, sectionIds: Set<String>): Boolean {
        if (!validItemKey(itemKey)) return false
        return update(context) { previous -> previous.copy(sections = previous.sections.map { section ->
            when {
                section.kind != HomeSectionKind.CUSTOM -> section
                section.id !in sectionIds -> section.copy(items = section.items.filterNot { it == itemKey })
                itemKey in section.items || section.items.size >= MAX_SECTION_ITEMS -> section
                else -> section.copy(items = section.items + itemKey)
            }
        }) }
    }

    fun prioritize(context: Context, id: String, itemKey: String): Boolean = update(context) { previous ->
        previous.copy(sections = previous.sections.map { section ->
            if (section.id == id && section.kind == HomeSectionKind.CUSTOM && itemKey in section.items)
                section.copy(items = listOf(itemKey) + section.items.filterNot { it == itemKey }) else section
        })
    }

    fun toggleFavorite(context: Context, itemKey: String): Boolean {
        if (!validItemKey(itemKey)) return false
        return update(context) { previous ->
            when {
                itemKey in previous.favorites -> previous.copy(favorites = previous.favorites.filterNot { it == itemKey })
                previous.favorites.size >= MAX_FAVORITES -> previous
                else -> previous.copy(favorites = previous.favorites + itemKey)
            }
        }
    }

    /** Resolve the current item by identity so an open sorting dialog cannot restore stale membership. */
    private fun reorderFavorite(context: Context, itemKey: String, targetIndex: (Int, Int) -> Int): Boolean =
        update(context) { previous ->
            val index = previous.favorites.indexOf(itemKey)
            if (index < 0) previous else {
                val target = targetIndex(index, previous.favorites.lastIndex).coerceIn(previous.favorites.indices)
                if (target == index) previous else {
                    val favorites = previous.favorites.toMutableList()
                    favorites.add(target, favorites.removeAt(index))
                    previous.copy(favorites = favorites)
                }
            }
        }

    fun moveFavorite(context: Context, itemKey: String, offset: Int): Boolean =
        reorderFavorite(context, itemKey) { index, _ -> index + offset.coerceIn(-1, 1) }

    fun prioritizeFavorite(context: Context, itemKey: String): Boolean =
        reorderFavorite(context, itemKey) { _, _ -> 0 }

    fun placeFavoriteLast(context: Context, itemKey: String): Boolean =
        reorderFavorite(context, itemKey) { _, lastIndex -> lastIndex }

    fun changes(key: String?): Boolean = key == null || key == DOCUMENT_KEY
}

@Composable internal fun rememberHomeSections(context: Context = LocalContext.current): HomeSectionsOptions {
    val saved = remember(context) { context.getSharedPreferences("orbit", Context.MODE_PRIVATE) }
    var options by remember(context) { mutableStateOf(SectionPreferences.read(context)) }
    DisposableEffect(context, saved) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (SectionPreferences.changes(key)) options = SectionPreferences.read(context)
        }
        saved.registerOnSharedPreferenceChangeListener(listener)
        SectionPreferences.ensureMigrated(context)
        options = SectionPreferences.read(context)
        onDispose { saved.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return options
}
