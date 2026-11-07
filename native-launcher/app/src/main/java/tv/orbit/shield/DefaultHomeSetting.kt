package tv.orbit.shield

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Uses Android's own chooser; never disables or removes another launcher. */
@Composable internal fun DefaultHomeSetting(activity: MainActivity, modifier: Modifier = Modifier) {
    val owner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { revision++ }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { revision++; failed = false }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val isDefault = remember(revision) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val manager = activity.getSystemService(Context.ROLE_SERVICE) as? RoleManager
                manager != null && manager.isRoleAvailable(RoleManager.ROLE_HOME) && manager.isRoleHeld(RoleManager.ROLE_HOME)
            } else {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                activity.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == activity.packageName
            }
        }.getOrDefault(false)
    }
    SettingRow(tr(R.string.default_home_title), tr(if (failed) R.string.default_home_failed else if (isDefault) R.string.default_home_current else R.string.default_home_hint), modifier,
        onClick = {
            val launched = runCatching {
                if (Build.VERSION.SDK_INT >= 29) {
                    val manager = activity.getSystemService(Context.ROLE_SERVICE) as? RoleManager
                    if (manager != null && manager.isRoleAvailable(RoleManager.ROLE_HOME) && !manager.isRoleHeld(RoleManager.ROLE_HOME)) {
                        roleRequest.launch(manager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                        return@runCatching true
                    }
                }
                val homeSettings = Intent(Settings.ACTION_HOME_SETTINGS)
                val target = activity.packageManager.resolveActivity(homeSettings, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
                if (target == null || target.name.endsWith(".EmptyStubActivity")) {
                    activity.startActivity(Intent(Settings.ACTION_SETTINGS))
                    activity.notice = activity.getString(R.string.default_home_failed)
                } else activity.startActivity(homeSettings)
                true
            }.getOrElse {
                runCatching { activity.startActivity(Intent(Settings.ACTION_SETTINGS)); true }.getOrDefault(false)
            }
            failed = !launched
        })
}
