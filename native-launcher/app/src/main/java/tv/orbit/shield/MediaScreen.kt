package tv.orbit.shield

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*

internal object MediaNavigation {
    const val KEY = "media.tabVisible"
    const val AUTO_COVER_KEY = "media.autoCover"
    fun visible(context: Context) = context.getSharedPreferences("orbit", Context.MODE_PRIVATE).readBooleanOrDefault(KEY, true)
    fun visible(context: Context, value: Boolean) { context.getSharedPreferences("orbit", Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply() }
}

@Composable internal fun rememberMediaAutoCover(): Boolean {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("orbit", Context.MODE_PRIVATE) }
    var enabled by remember(context) { mutableStateOf(prefs.readBooleanOrDefault(MediaNavigation.AUTO_COVER_KEY, true)) }
    DisposableEffect(context) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == MediaNavigation.AUTO_COVER_KEY) enabled = prefs.readBooleanOrDefault(MediaNavigation.AUTO_COVER_KEY, true)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        enabled = prefs.readBooleanOrDefault(MediaNavigation.AUTO_COVER_KEY, true)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}

@Composable internal fun MediaAutoCoverSetting() {
    val context = LocalContext.current
    val enabled = rememberMediaAutoCover()
    SettingRow(tr(R.string.media_auto_cover), tr(if (enabled) R.string.enabled else R.string.disabled), onClick = {
        context.getSharedPreferences("orbit", Context.MODE_PRIVATE).edit().putBoolean(MediaNavigation.AUTO_COVER_KEY, !enabled).apply()
    })
}

@Composable internal fun rememberMediaTabVisible(): Boolean {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("orbit", Context.MODE_PRIVATE) }
    var visible by remember(context) { mutableStateOf(MediaNavigation.visible(context)) }
    DisposableEffect(context) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == MediaNavigation.KEY) visible = MediaNavigation.visible(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        visible = MediaNavigation.visible(context)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return visible
}

@Composable internal fun MediaVisibilitySetting() {
    val context = LocalContext.current
    val visible = rememberMediaTabVisible()
    SettingRow(tr(R.string.media_tab_visibility), tr(if (visible) R.string.enabled else R.string.disabled),
        onClick = { MediaNavigation.visible(context, !visible) })
}

internal fun openMediaLibrary(context: Context, id: String? = null) {
    context.startActivity(Intent(context, MovieLibraryActivity::class.java).apply {
        if (id != null) putExtra(MovieLibraryActivity.EXTRA_MOVIE_ID, id)
    })
}

@Composable internal fun MediaTile(
    movie: SavedMovie, modifier: Modifier = Modifier, onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null, onFocus: () -> Unit = {}, favorite: Boolean = false
) {
    var focused by remember { mutableStateOf(false) }
    val key = "movie:${movie.id}"
    Column(modifier) {
        Surface(onClick = onClick, onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(TileLayout.aspectRatio)
                .touchSurface(onLongClick = onLongClick, onClick = onClick)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1F1F21), focusedContainerColor = Color(0xFF1F1F21)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
            border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(1.dp, Color(0xFF424244))), focusedBorder = Border(BorderStroke(2.dp, Color(0xFFCCFF00))))
        ) {
            val cover = LocalManagedCovers.current[key]
            Box(Modifier.fillMaxSize()) {
                CoverArtwork(cover?.manual?.takeIf { it.isNotEmpty() } ?: cover?.original.orEmpty(),
                    Modifier.fillMaxSize(), key, movie.title)
                if (favorite) FavoriteBadge(Modifier.align(Alignment.TopEnd).padding(7.dp))
            }
        }
        Spacer(Modifier.height(TileLayout.titleGap))
        Text(movie.title, color = if (focused) Color(0xFFCCFF00) else Color.White,
            fontWeight = FontWeight.Bold, fontSize = TileLayout.titleSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable internal fun MediaScreen(activity: MainActivity, movies: List<SavedMovie>, ready: Boolean, failed: Boolean,
    inputEnabled: Boolean, favorites: Set<String>, onMenu: (SavedMovie, FocusRequester) -> Unit, modifier: Modifier = Modifier) {
    val addFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    val autoCover = rememberMediaAutoCover()
    var lastId by remember { mutableStateOf<String?>(null) }
    var focusedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(inputEnabled, ready, movies.isEmpty()) {
        if (ready && inputEnabled && !focusedOnce) {
            withFrameNanos { }
            focusedOnce = runCatching { (if (movies.isEmpty()) addFocus else firstFocus).requestFocus() }.getOrDefault(false)
        }
    }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 42.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.media_tab_title).uppercase(), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(20.dp))
            SettingRow(tr(R.string.media_manage), modifier = Modifier.widthIn(max = 240.dp).focusRequester(addFocus), onClick = { openMediaLibrary(activity) })
        }
        if (!ready) Text(tr(R.string.movies_searching), color = Color.LightGray, modifier = Modifier.padding(42.dp))
        else if (failed) Text(tr(R.string.movies_corrupt), color = Color(0xFFFFA69A), modifier = Modifier.padding(42.dp))
        else if (movies.isEmpty()) Text(tr(R.string.media_library_empty), color = Color.LightGray, modifier = Modifier.padding(42.dp))
        else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(columns = GridCells.Fixed(TileLayout.columns(maxWidth, LocalTileSize.current)),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = TileLayout.screenInset + TileLayout.focusInset, end = TileLayout.screenInset + TileLayout.focusInset, top = 8.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(TileLayout.gap), verticalArrangement = Arrangement.spacedBy(TileLayout.gap)) {
                items(movies, key = { it.id }) { movie ->
                    LaunchedEffect(movie.id, movie.poster, autoCover) { if (autoCover) activity.fetchMediaCover(movie) }
                    val cardFocus = remember(movie.id) { FocusRequester() }
                    MediaTile(movie, modifier = Modifier.fillMaxWidth().then(if (movie.id == (lastId ?: movies.first().id)) Modifier.focusRequester(firstFocus) else Modifier).focusRequester(cardFocus),
                        onFocus = { lastId = movie.id }, onClick = { openMediaLibrary(activity, movie.id) },
                        onLongClick = { onMenu(movie, cardFocus) }, favorite = homeMovieKey(movie.id) in favorites)
                }
            }
        }
    }
}
