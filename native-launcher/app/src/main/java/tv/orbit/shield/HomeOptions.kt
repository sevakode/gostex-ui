package tv.orbit.shield

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.json.JSONArray
import java.util.Locale

internal enum class HomeOrder(val id: String, val titleRes: Int) {
    RECENT("recent", R.string.home_order_recent),
    FAVORITES("favorites", R.string.home_order_favorites),
    TITLE("title", R.string.home_order_title)
}

internal enum class HomeShelf(val id: String, val titleRes: Int) {
    VIDEO("video", R.string.home_shelf_video),
    RECENT("recent", R.string.home_shelf_recent),
    FAVORITES("favorites", R.string.home_shelf_favorites),
    GAMES("games", R.string.home_shelf_games),
    APPS("apps", R.string.home_shelf_apps)
}

internal data class HomeOptions(
    val order: HomeOrder = HomeOrder.RECENT,
    val shelves: List<HomeShelf> = HomeShelf.entries.toList(),
    val visibleShelves: Set<HomeShelf> = HomeShelf.entries.toSet()
)

/** Include newly introduced shelves at the end, without changing an existing order. */
private fun normalizedHomeShelves(shelves: List<HomeShelf>): List<HomeShelf> =
    shelves.distinct() + HomeShelf.entries.filterNot { it in shelves }

internal fun orderedHomeShelves(options: HomeOptions): List<HomeShelf> =
    normalizedHomeShelves(options.shelves).filter { it in options.visibleShelves }

internal object HomePreferences {
    private const val ORDER_KEY = "home.order"
    private const val SELECTED_KEY = "home.selectedGame"
    private const val SHELF_ORDER_KEY = "home.shelves.order"
    private const val SHELF_VISIBLE_KEY = "home.shelves.visible"

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)
    private fun SharedPreferences.safeString(key: String): String? = runCatching { getString(key, null) }.getOrNull()

    fun read(context: Context): HomeOptions {
        val saved = prefs(context)
        val savedOrder = saved.safeString(ORDER_KEY)
        val shelves = runCatching {
            val array = JSONArray(saved.safeString(SHELF_ORDER_KEY) ?: "[]")
            (0 until array.length()).mapNotNull { index ->
                val id = array.opt(index) as? String
                HomeShelf.entries.firstOrNull { it.id == id }
            }
        }.getOrDefault(emptyList())
        val visible = runCatching {
            val ids = saved.getStringSet(SHELF_VISIBLE_KEY, null)?.toSet()
            if (ids == null) HomeShelf.entries.toSet()
            else {
                val known = HomeShelf.entries.filter { it.id in ids }.toSet()
                // An intentionally empty selection stays empty. Unknown-only data is corrupt/obsolete.
                if (ids.isNotEmpty() && known.isEmpty()) HomeShelf.entries.toSet() else known
            }
        }.getOrDefault(HomeShelf.entries.toSet())
        return HomeOptions(
            HomeOrder.entries.firstOrNull { it.id == savedOrder } ?: HomeOrder.RECENT,
            normalizedHomeShelves(shelves), visible
        )
    }

    fun setOrder(context: Context, order: HomeOrder) {
        prefs(context).edit().putString(ORDER_KEY, order.id).apply()
    }

    fun setVisibleShelves(context: Context, shelves: Set<HomeShelf>) {
        prefs(context).edit().putStringSet(SHELF_VISIBLE_KEY, shelves.map { it.id }.toSet()).apply()
    }

    fun moveShelf(context: Context, shelf: HomeShelf, offset: Int) {
        val order = read(context).shelves.toMutableList()
        val index = order.indexOf(shelf)
        val target = (index + offset.coerceIn(-1, 1)).coerceIn(order.indices)
        if (index == target) return
        order.removeAt(index)
        order.add(target, shelf)
        prefs(context).edit().putString(SHELF_ORDER_KEY, JSONArray(order.map { it.id }).toString()).apply()
    }

    fun selectedId(context: Context): String? = prefs(context).safeString(SELECTED_KEY)?.takeIf { it.isNotBlank() }

    fun select(context: Context, id: String) {
        if (id.isBlank() || id == selectedId(context)) return
        prefs(context).edit().putString(SELECTED_KEY, id).apply()
    }

    internal fun changesOptions(key: String?): Boolean =
        key == null || key == ORDER_KEY || key == SHELF_ORDER_KEY || key == SHELF_VISIBLE_KEY
}

/** Selection changes must not sort the library or invalidate the settings drawer. */
@Composable internal fun rememberHomeOptions(context: Context = LocalContext.current): HomeOptions {
    val prefs = remember(context) { context.getSharedPreferences("orbit", Context.MODE_PRIVATE) }
    var options by remember(context) { mutableStateOf(HomePreferences.read(context)) }
    DisposableEffect(context, prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (HomePreferences.changesOptions(key)) options = HomePreferences.read(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        options = HomePreferences.read(context)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return options
}

internal fun orderedHomeGames(
    games: List<Game>,
    favorites: Set<String>,
    played: Map<String, Long>,
    order: HomeOrder
): List<Game> {
    val byTitle = compareBy<Game> { it.title.lowercase(Locale.ROOT) }.thenBy { it.id }
    val byRecent = compareByDescending<Game> { played[it.id] ?: 0L }.then(byTitle)
    val comparator = when (order) {
        HomeOrder.RECENT -> byRecent
        HomeOrder.FAVORITES -> compareByDescending<Game> { it.id in favorites }.then(byRecent)
        HomeOrder.TITLE -> byTitle
    }
    return games.sortedWith(comparator)
}

@Composable internal fun HomeSettings(initialFocus: FocusRequester) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val options = rememberHomeOptions(context)
    var dialog by remember { mutableStateOf<AlertDialog?>(null) }
    DisposableEffect(Unit) { onDispose { dialog?.dismiss() } }
    Text(tr(R.string.home_shelves_hint), color = Color(0xFFADADB0), fontSize = 13.sp, lineHeight = 19.sp)
    SettingRow(tr(R.string.home_order), tr(options.order.titleRes), modifier = Modifier.focusRequester(initialFocus), onClick = {
        HomePreferences.setOrder(context, HomeOrder.entries[(options.order.ordinal + 1) % HomeOrder.entries.size])
    })
    val hint = when (options.order) {
        HomeOrder.RECENT -> R.string.home_recent_hint
        HomeOrder.FAVORITES -> R.string.home_favorites_hint
        HomeOrder.TITLE -> R.string.home_title_hint
    }
    Text(tr(hint), color = Color(0xFFADADB0), fontSize = 12.sp, lineHeight = 17.sp)
    SettingRow(tr(R.string.home_shelves_choose), tr(R.string.home_shelves_selected, options.visibleShelves.size), onClick = {
        val selected = options.visibleShelves.toMutableSet()
        val shelves = options.shelves
        dialog = AlertDialog.Builder(context).setTitle(R.string.home_shelves_choose)
            .setMultiChoiceItems(shelves.map { resources.getString(it.titleRes) }.toTypedArray(),
                shelves.map { it in selected }.toBooleanArray()) { _, index, enabled ->
                if (enabled) selected.add(shelves[index]) else selected.remove(shelves[index])
            }
            .setPositiveButton(R.string.home_shelves_apply) { _, _ -> HomePreferences.setVisibleShelves(context, selected) }
            .setNegativeButton(R.string.cancel, null).show()
    })
    Text(tr(R.string.home_shelves_order_hint), color = Color(0xFFADADB0), fontSize = 12.sp, lineHeight = 17.sp)
    options.shelves.forEachIndexed { index, shelf ->
        key(shelf.id) {
            SettingRow(tr(shelf.titleRes),
                if (shelf in options.visibleShelves) tr(R.string.home_shelf_position, index + 1) else tr(R.string.home_shelf_hidden),
                trailing = "↕", onClick = {
                    val moves = buildList {
                        if (index > 0) add(-1 to R.string.home_shelf_move_up)
                        if (index < options.shelves.lastIndex) add(1 to R.string.home_shelf_move_down)
                    }
                    dialog = AlertDialog.Builder(context).setTitle(shelf.titleRes)
                        .setItems(moves.map { resources.getString(it.second) }.toTypedArray()) { _, action ->
                            HomePreferences.moveShelf(context, shelf, moves[action].first)
                        }
                        .setNegativeButton(R.string.cancel, null).show()
                })
        }
    }
}
