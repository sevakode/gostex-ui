package tv.orbit.shield

import android.content.Intent
import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val CatalogLime = Color(0xFFCCFF00)
private val CatalogInk = Color(0xFF0A0A0A)
private val CatalogWhite = Color(0xFFF5F5F5)
private val CatalogMuted = Color(0xFFADADB0)

private data class CatalogChoices(val favorites: Set<String> = emptySet(), val favoritesOnly: Boolean = false)

@Composable private fun rememberCatalogChoices(scope: String?): CatalogChoices {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("orbit", 0) }
    fun read() = if (scope == null) CatalogChoices() else CatalogChoices(
        GrafanaCatalogPreferences.readFavorites(context, scope), GrafanaCatalogPreferences.readFavoritesOnly(context, scope)
    )
    var choices by remember(context, scope) { mutableStateOf(read()) }
    DisposableEffect(context, scope, prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith(GrafanaCatalogPreferences.PREFIX)) choices = read()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        choices = read()
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return choices
}

/** Centered TV list. The parent restores its drawer-row focus in onDismiss. */
@Composable internal fun GrafanaDashboardPicker(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val options = rememberGrafanaOptions()
    val server = remember(options.url) { GrafanaCatalogServer.fromUrl(options.url) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var visible by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var reload by remember { mutableIntStateOf(0) }
    val request = remember(server, reload, visible) {
        if (server != null && visible) runCatching { GrafanaCatalogRequest(server, CookieManager.getInstance()) }.getOrNull() else null
    }
    // A canceled request keeps only its own state cells: its finally cannot hide a newer load.
    var result by remember(request) { mutableStateOf<GrafanaCatalogResult?>(null) }
    var error by remember(request) { mutableStateOf<GrafanaCatalogFailure?>(null) }
    var loading by remember(request) { mutableStateOf(request != null) }
    val choices = rememberCatalogChoices(result?.scope)
    val allFocus = remember { FocusRequester() }
    val favoritesFocus = remember { FocusRequester() }
    var restoreFavorites by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) visible = true
            if (event == Lifecycle.Event.ON_STOP) visible = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(request) { onDispose { request?.close() } }
    LaunchedEffect(request, visible) {
        if (!visible) return@LaunchedEffect
        if (request == null) { loading = false; if (server != null) error = GrafanaCatalogFailure.RESPONSE; return@LaunchedEffect }
        loading = true
        error = null
        result = null
        try {
            result = withContext(Dispatchers.IO) { request.load() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: GrafanaCatalogException) {
            error = failure.reason
        } finally {
            request.close()
            loading = false
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        val confirmGate = remember { MenuConfirmKeyGate() }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .72f)).padding(24.dp)
            .onPreviewKeyEvent { val event = it.nativeKeyEvent; confirmGate.consume(event.keyCode, event.action, event.repeatCount, event.isCanceled) },
            contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(.88f).fillMaxHeight(.94f)
                .background(CatalogInk).border(1.dp, Color(0xFF424244)).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr(R.string.grafana_catalog_title), color = CatalogWhite, fontSize = 25.sp, fontWeight = FontWeight.Black)
                Text(tr(R.string.grafana_catalog_hint), color = CatalogMuted, fontSize = 12.sp, lineHeight = 17.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    GrafanaCatalogButton(tr(R.string.grafana_catalog_all), Modifier.focusRequester(allFocus), selected = !choices.favoritesOnly) {
                        result?.scope?.let { GrafanaCatalogPreferences.setFavoritesOnly(context, it, false) }
                    }
                    GrafanaCatalogButton(tr(R.string.grafana_catalog_favorites), Modifier.focusRequester(favoritesFocus), selected = choices.favoritesOnly) {
                        result?.scope?.let { GrafanaCatalogPreferences.setFavoritesOnly(context, it, true) }
                    }
                    Spacer(Modifier.weight(1f))
                    GrafanaCatalogButton(tr(R.string.grafana_catalog_reload)) { reload++ }
                }
                val dashboards = result?.dashboards.orEmpty().filter { !choices.favoritesOnly || it.uid in choices.favorites }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        server == null -> CatalogMessage(tr(R.string.grafana_url_missing))
                        loading -> CatalogMessage(tr(R.string.grafana_loading))
                        error != null -> CatalogMessage(tr(when (error) {
                            GrafanaCatalogFailure.SIGN_IN -> R.string.grafana_catalog_signin_needed
                            GrafanaCatalogFailure.NETWORK -> R.string.grafana_catalog_network_error
                            else -> R.string.grafana_catalog_response_error
                        }))
                        dashboards.isEmpty() -> CatalogMessage(tr(if (choices.favoritesOnly) R.string.grafana_catalog_empty_favorites else R.string.grafana_catalog_empty))
                        else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(3.dp)) {
                            items(dashboards, key = { it.uid }) { dashboard ->
                                val selectedUrl = result?.let { grafanaDashboardUrl(it.server, it.orgId, dashboard, options.url) }
                                val currentUid = runCatching { java.net.URI(options.url).path.substringAfter("/d/", "").substringBefore('/') }.getOrDefault("")
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    GrafanaDashboardRow(dashboard, currentUid == dashboard.uid, Modifier.weight(1f)) {
                                        if (selectedUrl != null && GrafanaPreferences.setUrl(context, selectedUrl)) onDismiss()
                                    }
                                    val favorite = dashboard.uid in choices.favorites
                                    val description = tr(if (favorite) R.string.grafana_catalog_remove_favorite else R.string.grafana_catalog_add_favorite, dashboard.title)
                                    GrafanaCatalogButton(if (favorite) "★" else "☆", Modifier.width(52.dp).semantics { contentDescription = description }, selected = favorite) {
                                        result?.scope?.let { GrafanaCatalogPreferences.toggleFavorite(context, it, dashboard.uid) }
                                        if (favorite && choices.favoritesOnly) restoreFavorites++
                                    }
                                }
                            }
                        }
                    }
                }
                if (result?.truncated == true) Text(tr(R.string.grafana_catalog_limit), color = CatalogMuted, fontSize = 11.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (server != null) GrafanaCatalogButton(tr(R.string.grafana_catalog_login)) {
                        onDismiss()
                        context.startActivity(Intent(context, GrafanaPreviewActivity::class.java))
                    }
                    Spacer(Modifier.weight(1f))
                    GrafanaCatalogButton(tr(R.string.close), onClick = onDismiss)
                }
            }
        }
        LaunchedEffect(Unit) { withFrameNanos { }; allFocus.requestFocus() }
        LaunchedEffect(restoreFavorites) { if (restoreFavorites > 0) { withFrameNanos { }; favoritesFocus.requestFocus() } }
    }
}

@Composable private fun CatalogMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.Center) {
        Text(text, color = CatalogMuted, fontSize = 16.sp, lineHeight = 24.sp)
    }
}

@Composable private fun GrafanaDashboardRow(dashboard: GrafanaDashboard, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 56.dp).onFocusChanged { focused = it.isFocused }.semantics { this.selected = selected },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF171719), focusedContainerColor = CatalogLime),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, CatalogLime)))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(dashboard.title, color = if (focused) CatalogInk else CatalogWhite, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (dashboard.folder.isNotEmpty()) Text(dashboard.folder, color = if (focused) CatalogInk else CatalogMuted,
                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (selected) Text("✓", color = if (focused) CatalogInk else CatalogMuted, fontSize = 16.sp)
        }
    }
}

@Composable private fun GrafanaCatalogButton(text: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 42.dp).onFocusChanged { focused = it.isFocused }.semantics { this.selected = selected },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = if (selected) Color(0xFF333638) else Color(0xFF171719), focusedContainerColor = CatalogLime),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, CatalogLime)))) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(text, color = if (focused) CatalogInk else if (selected) CatalogWhite else CatalogMuted, fontSize = 13.sp, maxLines = 1)
        }
    }
}
