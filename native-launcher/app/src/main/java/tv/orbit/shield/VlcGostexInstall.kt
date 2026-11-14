package tv.orbit.shield

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed

/** Uses the existing on-demand installer; opening/installing never selects a playback default. */
@Composable internal fun VlcGostexInstallSetting(activity: MainActivity, modifier: Modifier = Modifier) {
    val pkg = OfficialEmulatorApk.VLC_PACKAGE
    val owner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val resultRevision = activity.emulatorInstallRevision
    val status = remember(activity, revision, resultRevision) { OfficialEmulatorApk.installStatus(activity, pkg) }
    val downloading = activity.emulatorInstallPackage == pkg
    val installing = activity.pendingEmulatorPackage == pkg
    val permission = activity.pendingEmulatorPermission == pkg
    val returnFocus = activity.emulatorInstallReturnFocus == pkg
    LaunchedEffect(returnFocus, revision, resultRevision) {
        if (returnFocus) {
            withFrameNanos { }
            val focused = owner.lifecycle.withResumed { runCatching { focus.requestFocus() }.getOrDefault(false) }
            if (focused && activity.emulatorInstallReturnFocus == pkg) activity.emulatorInstallReturnFocus = null
        }
    }
    val title = when {
        downloading || installing || permission -> R.string.vlc_install_name
        status == OfficialEmulatorApk.InstallStatus.MISSING -> R.string.vlc_install_title
        status == OfficialEmulatorApk.InstallStatus.CURRENT -> R.string.vlc_install_open
        status == OfficialEmulatorApk.InstallStatus.UPDATE_AVAILABLE -> R.string.vlc_install_update
        else -> R.string.vlc_install_name
    }
    val hint = when {
        downloading -> R.string.vlc_install_cancel_hint
        installing -> R.string.app_install_confirm
        permission -> R.string.vlc_install_permission_hint
        status == OfficialEmulatorApk.InstallStatus.CURRENT -> R.string.vlc_install_installed
        status == OfficialEmulatorApk.InstallStatus.UNTRUSTED -> R.string.vlc_install_conflict
        else -> R.string.vlc_install_source
    }
    SettingRow(tr(title), tr(hint), modifier.focusRequester(focus)) {
        when {
            downloading -> activity.emulatorInstallJob?.cancel()
            installing -> { activity.notice = activity.getString(R.string.app_install_confirm) }
            permission -> activity.finishEmulatorPermission(pkg)
            status == OfficialEmulatorApk.InstallStatus.UNTRUSTED -> { activity.notice = activity.getString(R.string.vlc_install_conflict) }
            status == OfficialEmulatorApk.InstallStatus.CURRENT -> {
                val intent = activity.packageManager.getLeanbackLaunchIntentForPackage(pkg)
                    ?: activity.packageManager.getLaunchIntentForPackage(pkg)
                if (intent == null || runCatching { activity.startActivity(intent) }.isFailure)
                    activity.notice = activity.getString(R.string.vlc_install_failed)
            }
            else -> activity.downloadOfficialEmulator(pkg)
        }
    }
}
