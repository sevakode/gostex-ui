package tv.orbit.shield

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.util.Log

/** Starts only the user's fixed Remote script through Termux's permission-checked API. */
class RemoteBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ArtSchedule.install(context)
            RemoteStartup.start(context)
        }
    }
}
object RemoteStartup {
    private const val KEY = "remoteAutostart"

    /** Freeze the initial default: a subsequent upgrade of a fresh install must
     * not silently turn this feature on. Explicit and invalid values are retained. */
    fun enabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)
        val saved = prefs.all
        if (saved.containsKey(KEY)) return saved[KEY] as? Boolean ?: false
        val default = try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            legacyRemoteDefault(info.firstInstallTime, info.lastUpdateTime)
        } catch (_: Exception) { null } ?: return false
        return prefs.edit().putBoolean(KEY, default).commit() && default
    }

    fun start(context: Context): Boolean {
        if (!enabled(context)) return false
        if (context.checkSelfPermission("com.termux.permission.RUN_COMMAND") != PackageManager.PERMISSION_GRANTED) return false
        return try {
            context.startForegroundService(Intent("com.termux.RUN_COMMAND").apply {
                component = ComponentName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/home/start-codex.sh")
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Shield Remote")
            })
            Log.i("OrbitRemote", "Requested Shield Remote startup")
            true
        } catch (e: Exception) { Log.w("OrbitRemote", "Remote startup failed", e); false }
    }
}
