package dev.gostex.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.gostex.desktop.platform.DesktopBuild
import kotlinx.coroutines.*
import java.io.File
import java.net.URI

@Composable internal fun UpdatesSettings(model: LauncherState, s: Strings, chooseReport: ((File) -> Unit) -> Unit, onBack: () -> Unit) {
    val updates = model.updates ?: return
    val settings = model.settings
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    var feed by remember(settings.feedUrl) { mutableStateOf(settings.feedUrl) }
    var invalid by remember { mutableStateOf(false) }
    var reportState by remember { mutableStateOf<String?>(null) }
    Text("${s["updates_version"]} ${DesktopBuild.name}", color = GostexBrand.White)
    Text(s[updates.status], color = GostexBrand.Lime)
    updates.release?.let { Text(it.versionName, color = GostexBrand.White) }
    Action(s["updates_check"], Modifier.focusRequester(first)) { model.scope.launch { updates.check(model.settings, true) } }
    if (updates.release != null && !updates.ready) Action(s["updates_download"]) { model.scope.launch { updates.download() } }
    if (updates.ready) Text(s["updates_restart_hint"], color = GostexBrand.Muted)
    Toggle(s["updates_checks"], settings.updatesEnabled) { model.save { it.copy(updatesEnabled = !it.updatesEnabled) } }
    Toggle(s["updates_auto"], settings.autoDownload) { model.save { it.copy(autoDownload = !it.autoDownload) } }
    Action("${s["updates_interval"]}: ${settings.updateIntervalHours} ${s["updates_hours"]}") {
        model.save { val choices = listOf(1, 6, 24); it.copy(updateIntervalHours = choices[(choices.indexOf(it.updateIntervalHours) + 1) % choices.size]) }
    }
    OutlinedTextField(feed, { feed = it; invalid = false }, label = { Text(s["updates_feed"]) },
        singleLine = true, isError = invalid, modifier = Modifier.fillMaxWidth())
    Action(s["save"]) {
        val valid = feed.isBlank() || runCatching {
            val uri = URI(feed.trim())
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && uri.query == null
        }.getOrDefault(false)
        if (!valid) invalid = true else model.save { it.copy(feedUrl = feed.trim()) }
    }
    if (invalid) Text(s["updates_url_error"], color = Color(0xFFFFAD99))
    HorizontalDivider()
    Text(s["debug_title"], color = GostexBrand.Lime)
    Toggle(s["debug_enabled"], settings.debugEnabled) { model.save { it.copy(debugEnabled = !it.debugEnabled) } }
    Text(s["debug_hint"], color = GostexBrand.Muted)
    Action(s["debug_export"]) {
        chooseReport { destination ->
            model.scope.launch {
                reportState = "debug_exporting"
                try { updates.export(destination, model.settings); reportState = "debug_exported" }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { reportState = "debug_export_error" }
            }
        }
    }
    Action(s["debug_clear"]) { model.scope.launch(Dispatchers.IO) { updates.log.clear() } }
    reportState?.let { Text(s[it], color = GostexBrand.White) }
    Action(s["back"], onClick = onBack)
}
