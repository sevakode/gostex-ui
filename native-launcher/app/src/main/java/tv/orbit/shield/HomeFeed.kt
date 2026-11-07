package tv.orbit.shield

import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

internal sealed interface HomeTileItem {
    val key: String
    data class GameItem(val game: Game) : HomeTileItem { override val key = homeGameKey(game.id) }
    data class AppItem(val app: TvApp) : HomeTileItem { override val key = homeAppKey(app.packageName) }
    data class MovieItem(val movie: SavedMovie) : HomeTileItem { override val key = homeMovieKey(movie.id) }
}

internal data class HomeShelfContent(val section: HomeSection, val items: List<HomeTileItem>) {
    val shelf: String get() = section.id
    val keys: List<String> = items.map { it.key }
}

internal fun homeShelfContents(
    options: HomeSectionsOptions, games: List<Game>, apps: List<TvApp>, played: Map<String, Long>,
    includeAndroidGames: Boolean = true, movies: List<SavedMovie> = emptyList(), moviePlayed: Map<String, Long> = emptyMap()
): List<HomeShelfContent> {
    val gameItems = games.distinctBy(Game::id).map(HomeTileItem::GameItem)
    val appItems = apps.distinctBy(TvApp::packageName).map(HomeTileItem::AppItem)
    val movieItems = movies.distinctBy(SavedMovie::id).map(HomeTileItem::MovieItem)
    val available = (gameItems + appItems + movieItems).associateBy { it.key }
    fun resolve(keys: List<String>) = keys.distinct().mapNotNull(available::get)
    return options.sections.filter { it.visible }.map { section ->
        val items = when (section.kind) {
            HomeSectionKind.CUSTOM -> resolve(section.items)
            HomeSectionKind.FAVORITES -> resolve(options.favorites)
            HomeSectionKind.RECENT -> (gameItems.filter { (played[it.game.id] ?: 0L) > 0L } +
                movieItems.filter { (moviePlayed[it.movie.id] ?: 0L) > 0L })
                .sortedWith(compareByDescending<HomeTileItem> { item -> when (item) {
                    is HomeTileItem.GameItem -> played[item.game.id] ?: 0L
                    is HomeTileItem.MovieItem -> moviePlayed[item.movie.id] ?: 0L
                    is HomeTileItem.AppItem -> 0L
                } }.thenBy { it.key })
            HomeSectionKind.GAMES -> gameItems + if (includeAndroidGames) appItems.filter { it.app.isGame } else emptyList()
            HomeSectionKind.APPS -> appItems
        }
        HomeShelfContent(section, items)
    }.filter { it.items.isNotEmpty() }
}

internal data class HomeFeedAnchor(val shelf: String?, val index: Int, val key: String?)
internal data class HomeFeedRequest(val sequence: Long, val step: Int, val anchor: HomeFeedAnchor? = null)

/** Focus and scroll position belong to a shelf, including when the same game occurs twice. */
@Stable internal class HomeFeedState {
    val scroll = LazyListState()
    private val rowScroll = mutableMapOf<String, LazyListState>()
    fun scroll(shelf: String): LazyListState = rowScroll.getOrPut(shelf) { LazyListState() }
    val selected = mutableMapOf<String, String>()
    private val requesters = mutableMapOf<Pair<String, String>, FocusRequester>()
    var activeShelf by mutableStateOf<String?>(null)
    var activeIndex = 0
    var handledSequence = -1L
    var request by mutableStateOf(HomeFeedRequest(0, 0))
        private set
    fun moveBy(step: Int) { request = HomeFeedRequest(request.sequence + 1, step.coerceIn(-1, 1)) }
    fun anchor() = HomeFeedAnchor(activeShelf, activeIndex, selected[activeShelf])
    fun restore(anchor: HomeFeedAnchor? = null) { request = HomeFeedRequest(request.sequence + 1, 0, anchor) }
    fun focus(shelf: String, key: String): FocusRequester = requesters.getOrPut(shelf to key) { FocusRequester() }
    fun retain(rows: List<HomeShelfContent>) {
        val valid = rows.flatMap { row -> row.keys.map { row.shelf to it } }.toSet()
        requesters.keys.retainAll(valid)
        val shelves = rows.map { it.shelf }.toSet()
        rowScroll.keys.retainAll(shelves)
        selected.keys.retainAll(shelves)
    }
}

@Composable internal fun HomeFeed(
    state: HomeFeedState, rows: List<HomeShelfContent>, favorites: Set<String>, ready: Boolean,
    inputEnabled: Boolean, modifier: Modifier = Modifier,
    onGameFocus: (Game) -> Unit,
    onGameOpen: (Game, FocusRequester) -> Unit,
    onGameMenu: (Game, FocusRequester) -> Unit,
    onAppOpen: (TvApp) -> Unit,
    onAppMenu: (TvApp, FocusRequester) -> Unit,
    onMovieOpen: (SavedMovie) -> Unit,
    onMovieMenu: (SavedMovie, FocusRequester) -> Unit,
    onMovieCover: suspend (SavedMovie) -> Unit,
    onEmptySettings: () -> Unit
) {
    val currentRows by rememberUpdatedState(rows)
    LaunchedEffect(rows) { state.retain(rows) }
    suspend fun focusRow(index: Int, anchor: HomeFeedAnchor? = null) {
        val row = currentRows.getOrNull(index) ?: return
        state.activeShelf = row.shelf
        state.activeIndex = index
        val selectedKey = anchor?.takeIf { it.shelf == row.shelf }?.key ?: state.selected[row.shelf]
        val selectedIndex = row.keys.indexOf(selectedKey).coerceAtLeast(0)
        val key = row.keys.getOrNull(selectedIndex) ?: return
        state.scroll.scrollToItem(index)
        state.scroll(row.shelf).scrollToItem(selectedIndex)
        // A lazy row may need one frame to compose after its parent scrolls into view.
        repeat(3) {
            withFrameNanos { }
            if (runCatching { state.focus(row.shelf, key).requestFocus() }.getOrDefault(false)) return
        }
    }
    var initialFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(ready, state.request, rows.isNotEmpty(), inputEnabled) {
        if (!ready || !inputEnabled || currentRows.isEmpty()) return@LaunchedEffect
        if (initialFocusDone && state.handledSequence == state.request.sequence) return@LaunchedEffect
        val isNew = state.handledSequence != state.request.sequence
        val step = if (isNew) state.request.step else 0
        val anchor = state.request.anchor.takeIf { isNew }
        state.handledSequence = state.request.sequence
        val active = currentRows.indexOfFirst { it.shelf == (anchor?.shelf ?: state.activeShelf) }
        val index = if (active < 0) (anchor?.index ?: state.activeIndex).coerceIn(currentRows.indices) else (active + step).coerceIn(currentRows.indices)
        focusRow(index, anchor)
        initialFocusDone = true
    }
    // Removal of the final favorite/video item can remove a whole row under an open menu.
    // Its dismissal requests restoration against this current list, never a stale card.
    if (rows.isEmpty()) {
        val emptyFocus = remember { FocusRequester() }
        Column(modifier.fillMaxWidth().padding(42.dp), verticalArrangement = Arrangement.Center) {
            Text(tr(R.string.home_feed_empty), color = Color(0xFFADADB0), fontSize = 18.sp)
            Spacer(Modifier.height(18.dp))
            SettingRow(tr(R.string.home_feed_customize), modifier = Modifier.focusRequester(emptyFocus), onClick = onEmptySettings)
        }
        LaunchedEffect(ready, inputEnabled) {
            if (ready && inputEnabled) { withFrameNanos { }; runCatching { emptyFocus.requestFocus() } }
        }
        return
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val tileWidth = TileLayout.width(maxWidth, LocalTileSize.current)
        LazyColumn(state = state.scroll, modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            val key = event.nativeKeyEvent
            val step = when (key.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> -1
                KeyEvent.KEYCODE_DPAD_DOWN -> 1
                else -> 0
            }
            val index = rows.indexOfFirst { it.shelf == state.activeShelf }
            if (!inputEnabled || step == 0 || (step < 0 && index <= 0)) false
            else {
                if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0 && !key.isCanceled && index + step in rows.indices) state.moveBy(step)
                true
            }
        }, contentPadding = PaddingValues(top = 8.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(rows, key = { it.shelf }, contentType = { "shelf" }) { row ->
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 42.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(sectionTitle(row.section), color = if (state.activeShelf == row.shelf) Color(0xFFCCFF00) else Color(0xFFF5F5F5),
                            fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(12.dp))
                        Text(row.keys.size.toString(), color = Color(0xFFADADB0), fontSize = 12.sp)
                    }
                    LazyRow(state = state.scroll(row.shelf),
                        contentPadding = PaddingValues(horizontal = TileLayout.screenInset + TileLayout.focusInset, vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(TileLayout.gap)) {
                        items(row.items, key = { it.key }, contentType = { when (it) {
                            is HomeTileItem.GameItem -> "game"
                            is HomeTileItem.AppItem -> "app"
                            is HomeTileItem.MovieItem -> "movie"
                        } }) { item ->
                            val focus = state.focus(row.shelf, item.key)
                            val onFocus = {
                                if (inputEnabled) {
                                    val changed = state.activeShelf != row.shelf || state.selected[row.shelf] != item.key
                                    state.activeShelf = row.shelf
                                    state.activeIndex = rows.indexOfFirst { it.shelf == row.shelf }
                                    state.selected[row.shelf] = item.key
                                    if (changed && item is HomeTileItem.GameItem) onGameFocus(item.game)
                                }
                            }
                            when (item) {
                                is HomeTileItem.GameItem -> Poster(item.game,
                                    Modifier.width(tileWidth).focusRequester(focus), item.key in favorites,
                                    onFocus = onFocus, onClick = { onFocus(); onGameOpen(item.game, focus) },
                                    onLongClick = { onFocus(); onGameMenu(item.game, focus) })
                                is HomeTileItem.AppItem -> AppTile(item.app,
                                    modifier = Modifier.width(tileWidth).focusRequester(focus), favorite = item.key in favorites,
                                    onFocus = onFocus, onClick = { onFocus(); onAppOpen(item.app) },
                                    onLongClick = { onFocus(); onAppMenu(item.app, focus) })
                                is HomeTileItem.MovieItem -> {
                                    val autoCover = rememberMediaAutoCover()
                                    LaunchedEffect(item.movie.id, item.movie.poster, autoCover) { if (autoCover) onMovieCover(item.movie) }
                                    MediaTile(item.movie, modifier = Modifier.width(tileWidth).focusRequester(focus), favorite = item.key in favorites,
                                        onFocus = onFocus, onClick = { onFocus(); onMovieOpen(item.movie) },
                                        onLongClick = { onFocus(); onMovieMenu(item.movie, focus) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
