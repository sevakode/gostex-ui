package dev.gostex.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import dev.gostex.core.*
import dev.gostex.desktop.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.awt.FileDialog
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Path
import java.nio.file.Files
import java.util.Locale
import java.util.ResourceBundle
import java.util.UUID

private val Ink = GostexBrand.Ink
private val Panel = GostexBrand.Panel
private val Muted = GostexBrand.Muted
private val Lime = GostexBrand.Lime

internal class Strings(language: String) {
    private val locale = if (language == "system") Locale.getDefault() else Locale.forLanguageTag(language)
    private val bundle = ResourceBundle.getBundle("i18n.strings", locale,
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT))
    operator fun get(key: String): String = bundle.getString(key)
}

private fun demos(s: Strings): List<ContentItem> {
    val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toAbsolutePath().toString()
    val packaged = (System.getProperty("jpackage.app-path") ?: System.getenv("GOSTEX_EXECUTABLE"))
        ?.takeIf { runCatching { Files.isExecutable(Path.of(it)) }.getOrDefault(false) }
    return listOf("demo:return", "demo:second").mapIndexed { index, id ->
        ContentItem(id, s[if (index == 0) "demo_title" else "demo_second"],
            s[if (index == 0) "demo_description" else "demo_second_description"], ContentKind.APP,
            LaunchTarget.Process(packaged ?: java, (if (packaged != null) listOf("--return-test") else
                listOf("-cp", System.getProperty("java.class.path"), "dev.gostex.desktop.ReturnTest")) +
                listOf(s["demo_window"], s["demo_close"], s["demo_button"])))
    }
}

internal class LauncherState(val store: DesktopSettingsStore, initial: DesktopSettings, val scope: CoroutineScope, val updates: DesktopUpdates? = null) {
    var settings by mutableStateOf(initial)
        private set
    var selectedId by mutableStateOf(initial.selectedId)
    var drawer by mutableStateOf(false)
    var editor by mutableStateOf<ContentItem?>(null)
    var adding by mutableStateOf(false)
    var updatesPage by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var notice by mutableStateOf<String?>(if (store.warnings.isEmpty()) null else "config_warning")
    var restore by mutableIntStateOf(0)
    var returnAnchor by mutableStateOf<ReturnAnchor?>(null)
    private val writes = Mutex()
    private val launcher = ProcessLauncher()

    fun save(change: (DesktopSettings) -> DesktopSettings) = save(change, {})
    fun save(change: (DesktopSettings) -> DesktopSettings, after: () -> Unit) {
        scope.launch {
            writes.withLock {
                try {
                    val next = change(settings)
                    withContext(Dispatchers.IO) { store.save(next) }
                    settings = next
                    after()
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { notice = "save_failed" }
            }
        }
    }
    fun close(onClose: () -> Unit) {
        scope.launch {
            writes.withLock {
                try {
                    if (store.warnings.isEmpty()) {
                        val finalSettings = settings.copy(selectedId = selectedId)
                        withContext(Dispatchers.IO) { store.save(finalSettings) }
                    }
                } finally { onClose() }
            }
        }
    }
    fun launch(item: ContentItem, items: List<ContentItem>, onReturn: () -> Unit) {
        if (busy) return
        val anchor = ReturnAnchor("desktop:library", item.id, items.indexOfFirst { it.id == item.id })
        selectedId = item.id
        busy = true
        notice = "opening"
        updates?.log?.event("app.launch")
        scope.launch {
            try {
                val process = launcher.launch(item.target as LaunchTarget.Process)
                notice = "running"
                notice = if (launcher.awaitExit(process) == 0) "returned" else "failed"
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { notice = "failed"; updates?.log?.failure("app.launch", error) }
            finally {
                busy = false
                if (currentCoroutineContext().isActive) {
                    returnAnchor = anchor
                    restore++
                    onReturn()
                }
            }
        }
    }
}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--return-test") { ReturnTest.main(args.drop(1).toTypedArray()); return }
    val config = args.firstOrNull { it.startsWith("--config=") }?.substringAfter('=')?.let(Path::of)
    val store = if (config == null) DesktopSettingsStore() else DesktopSettingsStore(config)
    val settings = try { store.load() } catch (_: Exception) { System.err.println("GOSTEX: settings could not be read at ${store.path}"); return }
    val updates = DesktopUpdates()
    updates.log.enabled = settings.debugEnabled
    updates.log.event("app.start", DesktopBuild.name)
    val previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        runCatching { updates.log.failure("uncaught", error) }
        if (previousCrashHandler != null) previousCrashHandler.uncaughtException(thread, error) else error.printStackTrace()
    }
    application {
        val scope = rememberCoroutineScope()
        val model = remember { LauncherState(store, settings, scope, updates) }
        val state = rememberWindowState(width = 1280.dp, height = 800.dp)
        val strings = remember(model.settings.language) { Strings(model.settings.language) }
        val gamingMode = "--gaming-mode" in args || System.getenv("SteamGameId") != null || System.getenv("SteamDeck") == "1"
        LaunchedEffect(model.settings.fullscreen, model.settings.gamingFullscreen) {
            state.placement = if (if (gamingMode) model.settings.gamingFullscreen else model.settings.fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating
        }
        Window(onCloseRequest = { model.close(::exitApplication) }, title = "GOSTEX", state = state) {
            val mainWindow = window
            var active by remember { mutableStateOf(window.isFocused) }
            DisposableEffect(window) {
                val listener = object : WindowAdapter() {
                    override fun windowGainedFocus(event: WindowEvent) { active = true; model.restore++; updates.log.event("window.focus", "gained") }
                    override fun windowLostFocus(event: WindowEvent) { active = false; updates.setForeground(false); updates.log.event("window.focus", "lost") }
                }
                window.addWindowFocusListener(listener)
                onDispose { window.removeWindowFocusListener(listener) }
            }
            LaunchedEffect(active, model.busy, model.settings.feedUrl, model.settings.updatesEnabled,
                model.settings.autoDownload, model.settings.debugEnabled, model.settings.updateIntervalHours) {
                updates.log.enabled = model.settings.debugEnabled
                updates.setForeground(active && !model.busy)
                updates.configure(model.settings)
                if (active && !model.busy) updates.check(model.settings)
            }
            MaterialTheme(colorScheme = darkColorScheme(primary = Lime, background = Ink, surface = Panel, onPrimary = Ink)) {
                Launcher(model, strings, active, onLaunch = { item, items ->
                    model.launch(item, items) {
                        if (mainWindow.isDisplayable) {
                            mainWindow.isMinimized = false
                            mainWindow.toFront()
                            mainWindow.requestFocus()
                        }
                    }
                }, chooseFile = { selected ->
                    FileDialog(mainWindow, strings["choose"], FileDialog.LOAD).apply {
                        isVisible = true
                        if (file != null) selected(Path.of(directory, file).toAbsolutePath().toString())
                        dispose()
                    }
                }, onQuit = { model.close(::exitApplication) }, chooseReport = { selected ->
                    FileDialog(mainWindow, strings["debug_export"], FileDialog.SAVE).apply {
                        file = "gostex-diagnostics.zip"
                        isVisible = true
                        if (file != null) selected(Path.of(directory, file).toFile())
                        dispose()
                    }
                })
            }
        }
    }
}

@Composable internal fun Launcher(
    model: LauncherState, s: Strings, active: Boolean,
    onLaunch: (ContentItem, List<ContentItem>) -> Unit,
    chooseFile: ((String) -> Unit) -> Unit,
    onQuit: () -> Unit,
    chooseReport: ((java.io.File) -> Unit) -> Unit = {}
) {
    val settings = model.settings
    val items = remember(settings.items, settings.showDemo, s) { settings.items + if (settings.showDemo) demos(s) else emptyList() }
    val requesters = remember(items.map { it.id }) { items.associate { it.id to FocusRequester() } }
    val emptyFocus = remember { FocusRequester() }
    val selected = items.firstOrNull { it.id == model.selectedId } ?: items.firstOrNull()
    val focusManager = LocalFocusManager.current
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    val pressed = remember { mutableSetOf<Key>() }
    val drawerRequesters = remember { mutableMapOf<String, FocusRequester>() }
    var drawerReturnKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(active) { if (!active) pressed.clear() }
    LaunchedEffect(model.restore, model.drawer, active, items.map { it.id }, model.selectedId) {
        if (active && !model.drawer) {
            if (items.isEmpty()) {
                withFrameNanos { }; runCatching { emptyFocus.requestFocus() }
            } else {
                val anchor = model.returnAnchor ?: ReturnAnchor("desktop:library", model.selectedId)
                val index = anchor.resolve(items)
                model.returnAnchor = null
                model.selectedId = items[index].id
                rowState.scrollToItem(index)
                repeat(3) {
                    withFrameNanos { }
                    if (runCatching { requesters[items[index].id]?.requestFocus() == true }.getOrDefault(false)) return@LaunchedEffect
                }
            }
        }
    }
    LaunchedEffect(model.selectedId) {
        val id = model.selectedId
        delay(250)
        // Merely restoring focus must not rewrite invalid/unknown saved preferences.
        if (model.store.warnings.isEmpty() && id != model.settings.selectedId) model.save { it.copy(selectedId = id) }
    }
    LaunchedEffect(model.notice) {
        val notice = model.notice
        if (notice != null && notice != "running" && notice != "opening") {
            delay(8000)
            if (model.notice == notice) model.notice = null
        }
    }
    Box(Modifier.fillMaxSize().background(Ink).onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyUp) { pressed.remove(event.key); return@onPreviewKeyEvent false }
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val first = pressed.add(event.key)
        when (event.key) {
            Key.F1 -> { if (first) { model.drawer = !model.drawer; model.adding = false; model.editor = null; model.updatesPage = false }; true }
            Key.Escape -> { if (first) {
                if (model.adding || model.editor != null) { model.adding = false; model.editor = null }
                else if (model.updatesPage) model.updatesPage = false
                else model.drawer = !model.drawer
            }; true }
            Key.DirectionLeft, Key.DirectionRight -> if (!model.drawer && items.isNotEmpty()) {
                val current = items.indexOfFirst { it.id == selected?.id }.coerceAtLeast(0)
                val next = (current + if (event.key == Key.DirectionLeft) -1 else 1).coerceIn(items.indices)
                model.selectedId = items[next].id
                true
            } else false
            Key.DirectionUp, Key.DirectionDown -> if (model.drawer && !model.adding && model.editor == null) {
                focusManager.moveFocus(if (event.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down); true
            } else false
            Key.Enter, Key.NumPadEnter, Key.Spacebar -> if (!first) true else false
            else -> false
        }
    }) {
        PixelGrid(Modifier.fillMaxSize())
        if (selected != null) PixelAppGlyph(selected.kind,
            Modifier.align(Alignment.TopEnd).padding(top = 144.dp, end = 64.dp).size(192.dp),
            color = Lime.copy(alpha = 0.12f))
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GostexWordmark(Modifier.width(144.dp).height(49.dp))
                Spacer(Modifier.width(20.dp))
                Text(s["prototype"].uppercase(), color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                model.updates?.release?.let { release ->
                    Action("${s["updates_available"]} ${release.versionName}", Modifier.focusProperties { canFocus = !model.drawer }) { model.drawer = true; model.updatesPage = true }
                    Spacer(Modifier.width(12.dp))
                }
                Action(s["settings"], Modifier.focusRequester(emptyFocus).focusProperties { canFocus = !model.drawer }) { model.drawer = true }
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = GostexBrand.Line)
            Spacer(Modifier.weight(1f))
            Text(s["library"].uppercase(), color = Lime, fontSize = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
            Spacer(Modifier.height(12.dp))
            Text((selected?.title ?: s["library"]).uppercase(), color = GostexBrand.White, fontSize = 38.sp, lineHeight = 42.sp,
                fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(.72f))
            Spacer(Modifier.height(12.dp))
            Text(selected?.description ?: s["subtitle"], color = GostexBrand.White, fontSize = 17.sp, lineHeight = 25.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(.72f).heightIn(min = 52.dp))
            Spacer(Modifier.height(30.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s["library"].uppercase(), color = GostexBrand.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.width(12.dp)); PixelDigits(items.size.toString().padStart(2, '0'), Modifier.height(14.dp), color = Lime)
            }
            Spacer(Modifier.height(12.dp))
            if (items.isEmpty()) Text(s["empty"], color = Muted, modifier = Modifier.padding(vertical = 50.dp))
            else LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(8.dp)) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    var focused by remember { mutableStateOf(false) }
                    Column(Modifier.width(252.dp).height(192.dp)
                        .focusRequester(requesters.getValue(item.id))
                        .onFocusChanged { focused = it.isFocused; if (it.isFocused) model.selectedId = item.id }
                        .clickable(enabled = !model.drawer) { onLaunch(item, items) }) {
                        Box(Modifier.fillMaxWidth().height(142.dp).background(GostexBrand.Card)
                            .border(1.dp, if (focused) Lime else GostexBrand.Line).pixelFocus(focused)) {
                            PixelAppGlyph(item.kind, Modifier.align(Alignment.Center).size(64.dp),
                                color = if (focused) Lime else Muted)
                            PixelDigits((index + 1).toString().padStart(2, '0'), Modifier.align(Alignment.TopStart).padding(16.dp).height(10.dp),
                                color = if (focused) Lime else Muted)
                            if (focused) Text(s["open"].uppercase(), color = Ink, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.align(Alignment.BottomEnd).background(Lime).padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(item.title, color = if (focused) Lime else GostexBrand.White, fontSize = 18.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(model.notice?.let { s[it] } ?: s["preview_note"], color = if (model.notice == "failed" || model.notice == "save_failed") Color(0xFFFFAD99) else Muted, fontSize = 14.sp)
            Spacer(Modifier.weight(0.6f))
            HorizontalDivider(color = GostexBrand.Line)
            Spacer(Modifier.height(16.dp))
            Text(s["hints"], color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        if (model.drawer) {
            // A focus group owns all drawer controls; closing it restores the saved card.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).focusProperties { canFocus = false }.clickable { model.drawer = false })
            Column(Modifier.align(Alignment.CenterEnd).width(500.dp).fillMaxHeight().background(Panel)
                .padding(28.dp).focusGroup().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val drawerFocus = remember { FocusRequester() }
                LaunchedEffect(model.adding, model.editor?.id, model.updatesPage) {
                    if (!model.adding && model.editor == null && !model.updatesPage) {
                        withFrameNanos { }
                        runCatching { (drawerRequesters[drawerReturnKey] ?: drawerFocus).requestFocus() }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s["settings"].uppercase(), color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    Spacer(Modifier.weight(1f))
                    Action(s["close"], Modifier.focusRequester(drawerFocus)) { model.drawer = false; model.editor = null; model.adding = false; model.updatesPage = false }
                }
                if (model.updatesPage) {
                    UpdatesSettings(model, s, chooseReport) { model.updatesPage = false }
                } else if (model.adding || model.editor != null) {
                    AppEditor(model.editor, s, chooseFile, onCancel = { model.editor = null; model.adding = false }, onSave = { item ->
                        model.save({ old -> old.copy(items = if (old.items.any { it.id == item.id }) old.items.map { if (it.id == item.id) item else it } else old.items + item) }) {
                            model.editor = null; model.adding = false
                        }
                    })
                } else {
                    Action("${s["language"]} · ${if (settings.language == "system") s["system"] else settings.language.uppercase()}") {
                        val choices = listOf("system", "en", "es", "ru")
                        model.save { it.copy(language = choices[(choices.indexOf(it.language) + 1) % choices.size]) }
                    }
                    Toggle(s["fullscreen"], settings.fullscreen) { model.save { it.copy(fullscreen = !it.fullscreen) } }
                    Toggle(s["gaming_fullscreen"], settings.gamingFullscreen) { model.save { it.copy(gamingFullscreen = !it.gamingFullscreen) } }
                    Toggle(s["demo_visible"], settings.showDemo) { model.save { it.copy(showDemo = !it.showDemo) } }
                    if (model.updates != null) Action(s["updates_title"], Modifier.focusRequester(drawerRequesters.getOrPut("updates") { FocusRequester() })) {
                        drawerReturnKey = "updates"; model.updatesPage = true
                    }
                    HorizontalDivider()
                    Text(s["apps"].uppercase(), color = Lime, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                    Action(s["add_app"], Modifier.focusRequester(drawerRequesters.getOrPut("add") { FocusRequester() })) {
                        drawerReturnKey = "add"; model.adding = true
                    }
                    if (settings.items.isEmpty()) Text(s["no_apps"], color = Muted)
                    settings.items.forEachIndexed { index, item ->
                        Text(item.title, color = Color.White, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Action(s["edit"], Modifier.focusRequester(drawerRequesters.getOrPut(item.id) { FocusRequester() })) {
                                drawerReturnKey = item.id; model.editor = item
                            }
                            Action(s["remove"]) { model.save { old -> old.copy(items = old.items.filterNot { it.id == item.id }) } }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (index > 0) Action(s["move_left"]) { model.save { old -> old.copy(items = moved(old.items, item.id, -1)) } }
                            if (index < settings.items.lastIndex) Action(s["move_right"]) { model.save { old -> old.copy(items = moved(old.items, item.id, 1)) } }
                        }
                    }
                    HorizontalDivider()
                    Text(s["input_title"], color = Lime)
                    Text(s["input_hint"], color = Muted, fontSize = 14.sp)
                    Text(s["config_path"], color = Lime)
                    Text(model.store.path.toString(), color = Muted, fontSize = 12.sp)
                    Action(s["quit"], onClick = onQuit)
                }
            }
        }
    }
}

private fun moved(items: List<ContentItem>, id: String, delta: Int): List<ContentItem> {
    val from = items.indexOfFirst { it.id == id }
    if (from < 0 || from + delta !in items.indices) return items
    return items.toMutableList().apply { add(from + delta, removeAt(from)) }
}

@Composable internal fun Action(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    OutlinedButton(onClick, modifier.onFocusChanged { focused = it.isFocused },
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) Lime else GostexBrand.Line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (focused) Lime else Color.White),
        shape = androidx.compose.ui.graphics.RectangleShape) { Text(label, fontSize = 14.sp) }
}

@Composable internal fun Toggle(label: String, value: Boolean, onClick: () -> Unit) {
    Action("${if (value) "✓" else "○"}  $label", Modifier.fillMaxWidth(), onClick)
}

@Composable private fun AppEditor(item: ContentItem?, s: Strings, chooseFile: ((String) -> Unit) -> Unit, onCancel: () -> Unit, onSave: (ContentItem) -> Unit) {
    var title by remember(item?.id) { mutableStateOf(item?.title.orEmpty()) }
    var executable by remember(item?.id) { mutableStateOf((item?.target as? LaunchTarget.Process)?.executable.orEmpty()) }
    var args by remember(item?.id) { mutableStateOf((item?.target as? LaunchTarget.Process)?.arguments.orEmpty()) }
    var description by remember(item?.id) { mutableStateOf(item?.description.orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(item?.id) { withFrameNanos { }; runCatching { titleFocus.requestFocus() } }
    Text(s["add_hint"], color = Muted, fontSize = 14.sp)
    OutlinedTextField(title, { title = it }, label = { Text(s["name"]) }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(titleFocus))
    OutlinedTextField(executable, { executable = it }, label = { Text(s["executable"]) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Action(s["choose"]) { chooseFile { executable = it; if (title.isBlank()) title = Path.of(it).fileName.toString() } }
    Text(s["arguments"], color = Muted)
    args.forEachIndexed { index, value ->
        OutlinedTextField(value, { next -> args = args.toMutableList().apply { set(index, next) } },
            label = { Text("${s["argument"]} ${index + 1}") }, modifier = Modifier.fillMaxWidth())
        Action(s["remove"]) { args = args.toMutableList().apply { removeAt(index) } }
    }
    Action(s["add_argument"]) { args = args + "" }
    OutlinedTextField(description, { description = it }, label = { Text(s["description"]) }, modifier = Modifier.fillMaxWidth())
    if (invalid) Text(s["validation"], color = Color(0xFFFFAD99))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Action(s["save"]) {
            val valid = title.isNotBlank() && runCatching { Path.of(executable).isAbsolute }.getOrDefault(false)
            if (!valid) invalid = true
            else onSave(ContentItem(item?.id ?: "app:desktop:${UUID.randomUUID()}", title.trim(), description, ContentKind.APP,
                LaunchTarget.Process(executable, args, (item?.target as? LaunchTarget.Process)?.workingDirectory)))
        }
        Action(s["cancel"], onClick = onCancel)
    }
}
