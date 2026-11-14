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
import androidx.lifecycle.withResumed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

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
    if (!OfficialEmulatorApk.supports(pkg) || PortableMaintenance.running) return
    val isPlayer = pkg == "com.brouken.player"
    val isVlc = pkg == OfficialEmulatorApk.VLC_PACKAGE
    fun failed() = getString(when {
        isVlc -> R.string.vlc_install_failed
        isPlayer -> R.string.just_player_failed
        else -> R.string.emulator_install_failed
    })
    if (emulatorInstallJob != null || pendingEmulatorPermission != null || pendingEmulatorPackage != null) {
        notice = getString(if (pendingEmulatorPackage != null) R.string.app_install_confirm else R.string.app_install_busy)
        return
    }
    if (isVlc) when (OfficialEmulatorApk.installStatus(this, pkg)) {
        OfficialEmulatorApk.InstallStatus.CURRENT -> { notice = getString(R.string.vlc_install_installed); return }
        OfficialEmulatorApk.InstallStatus.UNTRUSTED -> { notice = getString(R.string.vlc_install_conflict); return }
        else -> Unit
    }
    emulatorInstallReturnFocus = null
    if (!packageManager.canRequestPackageInstalls()) {
        notice = getString(R.string.emulator_install_permission)
        pendingEmulatorPermission = pkg
        runCatching { emulatorPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
            .onFailure { pendingEmulatorPermission = null; emulatorInstallReturnFocus = pkg; notice = failed() }
        return
    }
    val progress = getString(when {
        isVlc -> R.string.vlc_install_downloading
        isPlayer -> R.string.just_player_downloading
        else -> R.string.emulator_install_downloading
    })
    notice = progress
    var job: Job? = null
    val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) job?.cancel() }
    job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
        lifecycle.addObserver(observer)
        var attempt: File? = null
        var handedOff = false
        try {
            val file = withContext(Dispatchers.IO) {
                val target = OfficialEmulatorApk.createAttempt(this@downloadOfficialEmulator)
                attempt = target
                OfficialEmulatorApk.download(this@downloadOfficialEmulator, pkg, target)
            }
            coroutineContext.ensureActive()
            val uri = FileProvider.getUriForFile(this@downloadOfficialEmulator, "$packageName.torrserve-installer", file)
            // A download may finish under another window. Never open the installer
            // while paused, and never delete its file merely because Home resumes us.
            lifecycle.withResumed {
                check(!isFinishing && !isDestroyed && !PortableMaintenance.running)
                pendingEmulatorApk = file
                pendingEmulatorPackage = pkg
                lifecycle.removeObserver(observer)
                @Suppress("DEPRECATION")
                val install = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                    .putExtra(Intent.EXTRA_RETURN_RESULT, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                emulatorApkLauncher.launch(install)
                handedOff = true
                if (notice == progress) notice = getString(R.string.app_install_confirm)
            }
        } catch (e: CancellationException) {
            if (notice == progress) notice = ""
            throw e
        } catch (_: EmulatorApkIncompatible) {
            notice = getString(when {
                isVlc -> R.string.vlc_install_incompatible
                isPlayer -> R.string.just_player_incompatible
                else -> R.string.emulator_install_incompatible
            })
        } catch (_: Exception) {
            notice = failed()
        } finally {
            lifecycle.removeObserver(observer)
            if (!handedOff) {
                OfficialEmulatorApk.discard(this@downloadOfficialEmulator, attempt)
                if (pendingEmulatorApk == attempt) { pendingEmulatorApk = null; pendingEmulatorPackage = null }
            }
            emulatorInstallJob = null
            emulatorInstallPackage = null
        }
    }
    emulatorInstallPackage = pkg
    emulatorInstallJob = job
    job.start()
}

/** The system installer owns the staged file until it returns, including a Home detour. */
internal fun MainActivity.finishEmulatorInstall(resultCode: Int) {
    val pkg = pendingEmulatorPackage
    OfficialEmulatorApk.discard(this, pendingEmulatorApk)
    pendingEmulatorApk = null
    pendingEmulatorPackage = null
    emulatorInstallRevision++
    if (pkg != null) emulatorInstallReturnFocus = pkg
    if (pkg == OfficialEmulatorApk.VLC_PACKAGE && OfficialEmulatorApk.installStatus(this, pkg) == OfficialEmulatorApk.InstallStatus.CURRENT) {
        notice = getString(R.string.vlc_install_installed)
    } else if (resultCode != android.app.Activity.RESULT_CANCELED && resultCode != android.app.Activity.RESULT_OK) {
        notice = getString(if (pkg == OfficialEmulatorApk.VLC_PACKAGE) R.string.vlc_install_failed else R.string.emulator_install_failed)
    } else if (notice == getString(R.string.app_install_confirm)) notice = ""
}

/** Also lets a Home detour cancel a still-open permission request, without a second launch. */
internal fun MainActivity.finishEmulatorPermission(pkg: String) {
    if (pendingEmulatorPermission != pkg) return
    pendingEmulatorPermission = null
    emulatorInstallRevision++
    emulatorInstallReturnFocus = pkg
    if (packageManager.canRequestPackageInstalls() && !PortableMaintenance.running) downloadOfficialEmulator(pkg)
    else notice = ""
}
