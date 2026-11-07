package tv.orbit.shield

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/** Offer a source only for a confirmed missing package. Installation remains Android's decision. */
internal fun MainActivity.offerMissingEmulator(spec: PlatformSpec): Boolean {
    val pkg = ComponentName.unflattenFromString(spec.component)?.packageName ?: return false
    val missing = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(pkg, 0)
        false
    } catch (_: PackageManager.NameNotFoundException) { true }
      catch (_: Exception) { return false }
    if (!missing) return false
    android.app.AlertDialog.Builder(this)
        .setTitle(R.string.emulator_install_title)
        .setMessage(getString(R.string.emulator_install_message, spec.emulator))
        .setPositiveButton(R.string.emulator_install_store) { _, _ ->
            val market = Uri.parse("market://details").buildUpon().appendQueryParameter("id", pkg).build()
            val web = Uri.parse("https://play.google.com/store/apps/details").buildUpon().appendQueryParameter("id", pkg).build()
            val opened = runCatching { startActivity(Intent(Intent.ACTION_VIEW, market).setPackage("com.android.vending")) }.isSuccess ||
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, web)) }.isSuccess
            if (!opened) notice = getString(R.string.emulator_install_no_store)
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
    return true
}
