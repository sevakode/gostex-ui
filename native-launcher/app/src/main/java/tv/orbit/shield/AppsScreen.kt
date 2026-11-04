package tv.orbit.shield

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/** Reuses the installed-app snapshot and artwork cache; only visible grid cells are composed. */
@Composable internal fun AppsScreen(
    apps: List<TvApp>, ready: Boolean, favorites: Set<String>,
    selectedPackage: String?, onSelect: (String) -> Unit,
    scroll: LazyGridState, selectedFocus: FocusRequester, inputEnabled: Boolean,
    onOpen: (TvApp) -> Unit, onMenu: (TvApp, FocusRequester) -> Unit,
    modifier: Modifier = Modifier
) {
    val selected = apps.firstOrNull { it.packageName == selectedPackage } ?: apps.firstOrNull()
    val packages = remember(apps) { apps.map { it.packageName } }
    var initialFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(ready, packages, inputEnabled) {
        if (!ready || !inputEnabled || apps.isEmpty()) return@LaunchedEffect
        // Opening/closing a menu or settings must not steal focus from its return target.
        if (initialFocusDone && selectedPackage in packages) return@LaunchedEffect
        val index = packages.indexOf(selected?.packageName).coerceAtLeast(0)
        onSelect(packages[index])
        scroll.scrollToItem(index)
        repeat(3) {
            withFrameNanos { }
            if (runCatching { selectedFocus.requestFocus() }.getOrDefault(false)) {
                initialFocusDone = true
                return@LaunchedEffect
            }
        }
    }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 42.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.apps).uppercase(), color = Color(0xFFF5F5F5), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            if (ready) Text(pluralStringResource(R.plurals.apps_count, apps.size, apps.size), color = Color(0xFFADADB0), fontSize = 13.sp)
        }
        if (apps.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(tr(if (ready) R.string.apps_empty else R.string.apps_loading), color = Color(0xFFADADB0), fontSize = 18.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(LocalTileSize.current.columns), state = scroll,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = TileLayout.screenInset + TileLayout.focusInset,
                    end = TileLayout.screenInset + TileLayout.focusInset, top = 8.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(TileLayout.gap),
                verticalArrangement = Arrangement.spacedBy(TileLayout.gap)
            ) {
                items(apps, key = { it.packageName }, contentType = { "app" }) { app ->
                    val cardFocus = remember(app.packageName) { FocusRequester() }
                    AppTile(app,
                        modifier = Modifier.fillMaxWidth()
                            .then(if (app.packageName == selected?.packageName) Modifier.focusRequester(selectedFocus) else Modifier)
                            .focusRequester(cardFocus),
                        favorite = homeAppKey(app.packageName) in favorites,
                        onFocus = { if (inputEnabled) onSelect(app.packageName) },
                        onClick = { onOpen(app) }, onLongClick = { onMenu(app, cardFocus) })
                }
            }
        }
    }
}
