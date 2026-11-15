package tv.orbit.shield

import android.app.AlertDialog
import android.content.Intent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.withResumed
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class CatalogPackageState(
    val status: OfficialEmulatorApk.InstallStatus,
    val launch: Intent?,
)

private fun catalogPackageState(activity: MainActivity, pkg: String): CatalogPackageState = try {
    val status = OfficialEmulatorApk.installStatus(activity, pkg)
    val launch = if (status == OfficialEmulatorApk.InstallStatus.MISSING) null else runCatching {
        activity.packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: activity.packageManager.getLaunchIntentForPackage(pkg)
    }.getOrNull()
    CatalogPackageState(status, launch)
} catch (cancelled: CancellationException) { throw cancelled }
catch (_: Exception) { CatalogPackageState(OfficialEmulatorApk.InstallStatus.UNTRUSTED, null) }

/** A bounded, on-demand catalog. Package queries stop when this panel leaves composition. */
@Composable
internal fun InstallableAppCatalogSettings(
    activity: MainActivity,
    initialFocus: FocusRequester,
    onOptions: () -> Unit,
) {
    val options = LocalAppsOptions.current
    val entries = InstallableAppCatalog.visible(options)
    val installer = activity.emulatorInstall
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val rowFocus = remember { mutableMapOf<String, FocusRequester>() }
    var refreshVersion by remember { mutableIntStateOf(0) }
    var checkingPackage by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    val snapshots by produceState<Map<String, CatalogPackageState>?>(null, activity,
        activity.resumeVersion, installer.revision, refreshVersion) {
        value = withContext(Dispatchers.IO) {
            InstallableAppCatalog.all.associate {
                ensureActive()
                it.packageName to catalogPackageState(activity, it.packageName)
            }
        }
    }
    val returnPackage = installer.returnFocusPackage
    LaunchedEffect(returnPackage, entries.map { it.packageName }, activity.resumeVersion, installer.revision) {
        if (returnPackage != null) {
            val target = if (entries.any { it.packageName == returnPackage })
                rowFocus.getOrPut(returnPackage) { FocusRequester() } else initialFocus
            repeat(3) {
                withFrameNanos { }
                val focused = owner.lifecycle.withResumed { runCatching { target.requestFocus() }.getOrDefault(false) }
                if (focused) {
                    installer.clearReturnFocus(returnPackage)
                    return@LaunchedEffect
                }
            }
        }
    }
    fun canAct() = !PortableMaintenance.running && !activity.isFinishing && !activity.isDestroyed &&
        owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        view.isAttachedToWindow && view.hasWindowFocus()

    fun act(entry: InstallableApp, updateOnly: Boolean = false) {
        if (!canAct()) return
        val pkg = entry.packageName
        when {
            installer.downloadingPackage == pkg -> installer.cancelDownload(pkg)
            installer.installingPackage == pkg -> { error = activity.getString(R.string.app_install_confirm) }
            installer.permissionPackage == pkg -> { error = ""; installer.finishPermission(pkg) }
            checkingPackage != null -> Unit
            else -> {
                checkingPackage = pkg
                error = ""
                scope.launch {
                    try {
                        // Never act on a stale package snapshot after an external installation.
                        val current = withContext(Dispatchers.IO) { catalogPackageState(activity, pkg) }
                        if (canAct()) when {
                            current.status == OfficialEmulatorApk.InstallStatus.UNTRUSTED ->
                                error = activity.getString(R.string.app_catalog_untrusted)
                            updateOnly && current.status == OfficialEmulatorApk.InstallStatus.UPDATE_AVAILABLE -> installer.download(pkg)
                            updateOnly -> error = activity.getString(R.string.app_catalog_changed)
                            current.status == OfficialEmulatorApk.InstallStatus.MISSING -> installer.download(pkg)
                            else -> {
                                if (current.launch == null || runCatching { activity.startActivity(current.launch) }.isFailure)
                                    error = activity.getString(R.string.app_catalog_open_failed, activity.getString(entry.titleRes))
                            }
                        }
                        refreshVersion++
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = activity.getString(R.string.app_catalog_open_failed, activity.getString(entry.titleRes))
                    } finally { checkingPackage = null }
                }
            }
        }
    }

    SettingRow(tr(R.string.app_catalog_options_title), tr(R.string.app_catalog_options_summary),
        modifier = Modifier.focusRequester(initialFocus), onClick = onOptions)
    Text(tr(R.string.app_catalog_hint), color = Color(0xFFADADB0), fontSize = 12.sp, lineHeight = 17.sp)
    if (installer.message.isNotBlank()) Text(installer.message, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp)
    if (error.isNotBlank()) Text(error, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp)
    if (entries.isEmpty()) Text(tr(R.string.app_catalog_empty), color = Color(0xFFADADB0), fontSize = 13.sp)

    entries.forEach { entry -> key(entry.packageName) {
        val pkg = entry.packageName
        val snapshot = snapshots?.get(pkg)
        val focus = remember(pkg) { rowFocus.getOrPut(pkg) { FocusRequester() } }
        var focused by remember { mutableStateOf(false) }
        val downloading = installer.downloadingPackage == pkg
        val installing = installer.installingPackage == pkg
        val permission = installer.permissionPackage == pkg
        val statusText = when {
            downloading -> R.string.app_catalog_downloading
            installing -> R.string.app_install_confirm
            permission -> R.string.app_catalog_permission
            checkingPackage == pkg || snapshot == null -> R.string.app_catalog_checking
            snapshot.status == OfficialEmulatorApk.InstallStatus.MISSING -> R.string.app_catalog_not_installed
            snapshot.status == OfficialEmulatorApk.InstallStatus.UPDATE_AVAILABLE -> R.string.app_catalog_update
            snapshot.status == OfficialEmulatorApk.InstallStatus.UNTRUSTED -> R.string.app_catalog_untrusted
            snapshot.launch == null -> R.string.app_catalog_no_screen
            else -> R.string.app_catalog_installed
        }
        val source = OfficialApkPolicy.sources[pkg]
        val description = buildList {
            add(tr(entry.kindRes) + " · " + tr(statusText))
            if (source != null) {
                add(activity.getString(R.string.app_catalog_source, source.repo))
                source.pin?.let { add(activity.getString(R.string.app_catalog_release, it.tag)) }
            }
        }.joinToString("\n")
        val missing = snapshot?.status == OfficialEmulatorApk.InstallStatus.MISSING
        SettingRow(tr(entry.titleRes), description,
            modifier = Modifier.focusRequester(focus).onFocusChanged { focused = it.hasFocus }
                .alpha(if (missing && !focused && !downloading && !permission) .65f else 1f),
            onClick = { act(entry) })
        if (snapshot?.status == OfficialEmulatorApk.InstallStatus.UPDATE_AVAILABLE &&
            !downloading && !installing && !permission) {
            SettingRow(activity.getString(R.string.app_catalog_update_action, activity.getString(entry.titleRes)),
                onClick = {
                    // This row disappears during the download; keep Cancel within reach.
                    runCatching { focus.requestFocus() }
                    act(entry, updateOnly = true)
                })
        }
    } }
}

/** Visibility and ordering live in the same preferences as the existing Apps tab. */
@Composable
internal fun InstallableAppCatalogOptions(activity: MainActivity, initialFocus: FocusRequester) {
    val options = LocalAppsOptions.current
    val entries = InstallableAppCatalog.ordered(options)
    val scope = rememberCoroutineScope()
    val rowFocus = remember { mutableMapOf<String, FocusRequester>() }
    var menu by remember { mutableStateOf<AlertDialog?>(null) }
    DisposableEffect(activity) {
        onDispose { menu?.setOnDismissListener(null); menu?.dismiss() }
    }
    Text(tr(R.string.app_catalog_options_hint), color = Color(0xFFADADB0), fontSize = 12.sp, lineHeight = 17.sp)
    Spacer(Modifier.height(2.dp))
    entries.forEachIndexed { index, entry -> key(entry.packageName) {
        val pkg = entry.packageName
        val focus = remember(pkg) { rowFocus.getOrPut(pkg) { FocusRequester() } }
        val visible = pkg !in options.catalogHidden
        SettingRow(tr(entry.titleRes), tr(if (visible) R.string.app_catalog_visible else R.string.app_catalog_hidden),
            modifier = Modifier.then(if (index == 0) Modifier.focusRequester(initialFocus) else Modifier).focusRequester(focus),
            isSelected = visible, trailing = if (visible) "✓" else "—", onClick = {
                val choices = buildList {
                    add(0 to if (visible) R.string.app_catalog_hide else R.string.app_catalog_show)
                    if (index > 0) add(-1 to R.string.home_shelf_move_up)
                    if (index < entries.lastIndex) add(1 to R.string.home_shelf_move_down)
                }
                menu?.dismiss()
                menu = AlertDialog.Builder(activity).setTitle(activity.getString(entry.titleRes))
                    .setItems(choices.map { activity.getString(it.second) }.toTypedArray()) { _, selected ->
                        if (!PortableMaintenance.running) when (val action = choices[selected].first) {
                            0 -> AppsPreferences.setCatalogVisible(activity, pkg, !visible)
                            else -> AppsPreferences.moveCatalog(activity, pkg, action)
                        }
                    }.setNegativeButton(R.string.cancel, null).create().also { dialog ->
                        dialog.setOnDismissListener {
                            menu = null
                            scope.launch { withFrameNanos { }; runCatching { focus.requestFocus() } }
                        }
                        dialog.show()
                    }
            })
    } }
}
