package tv.orbit.shield

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private const val JUST_PLAYER = "com.brouken.player"

@Composable internal fun JustPlayerInstallSetting(activity: MainActivity, modifier: Modifier = Modifier) {
    val owner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val installed = remember(revision) {
        try { activity.packageManager.getPackageInfo(JUST_PLAYER, 0); true }
        catch (_: PackageManager.NameNotFoundException) { false }
        catch (_: Exception) { false }
    }
    SettingRow(tr(R.string.just_player_title), tr(if (installed) R.string.just_player_installed else R.string.just_player_install_hint), modifier) {
        if (installed) {
            val intent = activity.packageManager.getLeanbackLaunchIntentForPackage(JUST_PLAYER)
                ?: activity.packageManager.getLaunchIntentForPackage(JUST_PLAYER)
            if (intent == null || runCatching { activity.startActivity(intent) }.isFailure)
                activity.notice = activity.getString(R.string.just_player_failed)
        } else {
            android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.just_player_title)
                .setMessage(R.string.just_player_source_hint)
                .setPositiveButton(R.string.emulator_install_store) { _, _ ->
                    val market = Uri.parse("market://details").buildUpon().appendQueryParameter("id", JUST_PLAYER).build()
                    val web = Uri.parse("https://play.google.com/store/apps/details").buildUpon().appendQueryParameter("id", JUST_PLAYER).build()
                    val opened = runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, market)) }.isSuccess ||
                        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, web)) }.isSuccess
                    if (!opened) activity.notice = activity.getString(R.string.emulator_install_no_store)
                }
                .setNeutralButton(R.string.emulator_install_direct) { _, _ -> activity.downloadOfficialEmulator(JUST_PLAYER) }
                .setNegativeButton(R.string.cancel, null).show()
        }
    }
}
