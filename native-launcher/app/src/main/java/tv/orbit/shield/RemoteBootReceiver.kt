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
    fun start(context: Context): Boolean {
        if (!context.getSharedPreferences("orbit", Context.MODE_PRIVATE).readBooleanOrDefault("remoteAutostart", true)) return false
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
