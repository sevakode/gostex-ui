package tv.orbit.shield

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val dialog = android.app.AlertDialog.Builder(this)
        .setTitle(R.string.emulator_install_title)
        .setMessage(getString(R.string.emulator_install_message, spec.emulator))
        .setPositiveButton(R.string.emulator_install_store) { _, _ ->
            val market = Uri.parse("market://details").buildUpon().appendQueryParameter("id", pkg).build()
            val web = Uri.parse("https://play.google.com/store/apps/details").buildUpon().appendQueryParameter("id", pkg).build()
            val storeIntent = Intent(Intent.ACTION_VIEW, market)
            @Suppress("DEPRECATION")
            val handlers = runCatching { packageManager.queryIntentActivities(storeIntent, PackageManager.MATCH_DEFAULT_ONLY) }.getOrDefault(emptyList())
            val opened = (handlers.isNotEmpty() && runCatching {
                if (handlers.size == 1) startActivity(storeIntent.setPackage(handlers.single().activityInfo.packageName))
                else startActivity(Intent.createChooser(storeIntent, getString(R.string.emulator_install_store)))
            }.isSuccess) || runCatching { startActivity(Intent(Intent.ACTION_VIEW, web)) }.isSuccess
            if (!opened) notice = getString(R.string.emulator_install_no_store)
        }
        .setNegativeButton(R.string.cancel, null)
    if (OfficialEmulatorApk.supports(pkg)) dialog.setNeutralButton(R.string.emulator_install_direct) { _, _ -> downloadOfficialEmulator(pkg) }
    dialog.show()
    return true
}

internal fun MainActivity.downloadOfficialEmulator(pkg: String) {
    if (!OfficialEmulatorApk.supports(pkg)) return
    if (emulatorInstallJob != null) {
        notice = getString(R.string.emulator_install_downloading)
        return
    }
    if (!packageManager.canRequestPackageInstalls()) {
        notice = getString(R.string.emulator_install_permission)
        pendingEmulatorPermission = pkg
        runCatching { emulatorPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
            .onFailure { pendingEmulatorPermission = null; notice = getString(R.string.emulator_install_failed) }
        return
    }
    val progress = getString(R.string.emulator_install_downloading)
    notice = progress
    var job: Job? = null
    val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) job?.cancel() }
    job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
        lifecycle.addObserver(observer)
        try {
            val file = withContext(Dispatchers.IO) { OfficialEmulatorApk.download(this@downloadOfficialEmulator, pkg) }
            val uri = FileProvider.getUriForFile(this@downloadOfficialEmulator, "$packageName.torrserve-installer", file)
            pendingEmulatorApk = file
            lifecycle.removeObserver(observer)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            if (notice == progress) notice = ""
        } catch (e: CancellationException) {
            if (notice == progress) notice = ""
            throw e
        } catch (_: EmulatorApkIncompatible) {
            pendingEmulatorApk?.delete(); pendingEmulatorApk = null
            notice = getString(R.string.emulator_install_incompatible)
        } catch (_: Exception) {
            pendingEmulatorApk?.delete(); pendingEmulatorApk = null
            notice = getString(R.string.emulator_install_failed)
        } finally {
            lifecycle.removeObserver(observer)
            emulatorInstallJob = null
        }
    }
    emulatorInstallJob = job
    job.start()
}
