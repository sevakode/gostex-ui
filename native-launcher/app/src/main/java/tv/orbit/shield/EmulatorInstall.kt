package tv.orbit.shield

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

internal fun MainActivity.downloadOfficialEmulator(pkg: String) = emulatorInstall.download(pkg)

/** Owns the install request and its Android results for the activity that opened it. */
internal class EmulatorInstall(
    private val activity: ComponentActivity,
    private val updateNotice: (String) -> Unit = {},
    private val currentNotice: (() -> String)? = null,
) {
    private var stagedApk: File? = null
    private var downloadJob: Job? = null
    private var pendingPermissionPackage by mutableStateOf<String?>(null)
    private var permissionAbandoned by mutableStateOf(false)
    var downloadingPackage by mutableStateOf<String?>(null)
        private set
    var installingPackage by mutableStateOf<String?>(null)
        private set
    val permissionPackage: String? get() = pendingPermissionPackage.takeUnless { permissionAbandoned }
    var returnFocusPackage by mutableStateOf<String?>(null)
        private set
    var revision by mutableIntStateOf(0)
        private set
    var message by mutableStateOf("")
        private set

    private val permissionLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val pkg = pendingPermissionPackage ?: return@registerForActivityResult
        if (permissionAbandoned) {
            pendingPermissionPackage = null
            permissionAbandoned = false
            revision++
            replaceMessage(activity.getString(R.string.app_install_confirm), "")
        } else finishPermission(pkg)
    }
    private val apkLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        finishInstall(it.resultCode)
    }

    fun restore(state: Bundle?) {
        if (state == null) return
        pendingPermissionPackage = state.getString("pendingEmulatorPermission")?.takeIf(OfficialEmulatorApk::supports)
        permissionAbandoned = pendingPermissionPackage != null && state.getBoolean("pendingEmulatorPermissionAbandoned")
        val pkg = state.getString("pendingEmulatorPackage")?.takeIf(OfficialEmulatorApk::supports) ?: return
        stagedApk = OfficialEmulatorApk.restoreAttempt(activity, state.getString("pendingEmulatorApk"))
        if (stagedApk != null) installingPackage = pkg
    }

    fun save(state: Bundle) {
        state.putString("pendingEmulatorPermission", pendingPermissionPackage)
        state.putBoolean("pendingEmulatorPermissionAbandoned", permissionAbandoned)
        state.putString("pendingEmulatorPackage", installingPackage)
        state.putString("pendingEmulatorApk", stagedApk?.name)
    }

    fun clearReturnFocus(pkg: String) {
        if (returnFocusPackage == pkg) returnFocusPackage = null
    }

    fun cancelDownload(pkg: String) {
        if (downloadingPackage == pkg) downloadJob?.cancel()
    }

    /** Retain the result slot until Android returns so a late grant cannot resume or replace an attempt. */
    fun abandonPermission(pkg: String) {
        if (permissionPackage != pkg) return
        permissionAbandoned = true
        replaceMessage(activity.getString(R.string.emulator_install_permission), "")
    }

    private fun showMessage(value: String) {
        message = value
        updateNotice(value)
    }

    private fun replaceMessage(previous: String, value: String) {
        val visibleNotice = currentNotice?.invoke() ?: message
        if (message == previous) message = value
        if (visibleNotice == previous) updateNotice(value)
    }

    fun download(pkg: String) {
        if (!OfficialEmulatorApk.supports(pkg) || PortableMaintenance.running) return
        val isPlayer = pkg == "com.brouken.player"
        val isVlc = pkg == OfficialEmulatorApk.VLC_PACKAGE
        fun failed() = activity.getString(when {
            isVlc -> R.string.vlc_install_failed
            isPlayer -> R.string.just_player_failed
            else -> R.string.emulator_install_failed
        })
        if (downloadJob != null || pendingPermissionPackage != null || installingPackage != null) {
            val waitingForAndroid = installingPackage != null || permissionAbandoned
            showMessage(activity.getString(if (waitingForAndroid) R.string.app_install_confirm else R.string.app_install_busy))
            return
        }
        if (isVlc) when (OfficialEmulatorApk.installStatus(activity, pkg)) {
            OfficialEmulatorApk.InstallStatus.CURRENT -> { showMessage(activity.getString(R.string.vlc_install_installed)); return }
            OfficialEmulatorApk.InstallStatus.UNTRUSTED -> { showMessage(activity.getString(R.string.vlc_install_conflict)); return }
            else -> Unit
        }
        returnFocusPackage = null
        if (!activity.packageManager.canRequestPackageInstalls()) {
            showMessage(activity.getString(R.string.emulator_install_permission))
            pendingPermissionPackage = pkg
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            runCatching { permissionLauncher.launch(intent) }.onFailure {
                pendingPermissionPackage = null
                returnFocusPackage = pkg
                showMessage(failed())
            }
            return
        }
        val progress = activity.getString(when {
            isVlc -> R.string.vlc_install_downloading
            isPlayer -> R.string.just_player_downloading
            else -> R.string.emulator_install_downloading
        })
        showMessage(progress)
        val lifecycle = activity.lifecycle
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) cancelDownload(pkg) }
        val job = activity.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            lifecycle.addObserver(observer)
            var attempt: File? = null
            var handedOff = false
            try {
                val file = withContext(Dispatchers.IO) {
                    val target = OfficialEmulatorApk.createAttempt(activity)
                    attempt = target
                    OfficialEmulatorApk.download(activity, pkg, target)
                }
                coroutineContext.ensureActive()
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.torrserve-installer", file)
                // A download may finish under another window. Never open the installer
                // while paused, and never delete its file merely because Home resumes us.
                lifecycle.withResumed {
                    check(!activity.isFinishing && !activity.isDestroyed && !PortableMaintenance.running)
                    stagedApk = file
                    installingPackage = pkg
                    lifecycle.removeObserver(observer)
                    @Suppress("DEPRECATION")
                    val install = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                        .putExtra(Intent.EXTRA_RETURN_RESULT, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    apkLauncher.launch(install)
                    handedOff = true
                    replaceMessage(progress, activity.getString(R.string.app_install_confirm))
                }
            } catch (e: CancellationException) {
                replaceMessage(progress, "")
                throw e
            } catch (_: EmulatorApkIncompatible) {
                showMessage(activity.getString(when {
                    isVlc -> R.string.vlc_install_incompatible
                    isPlayer -> R.string.just_player_incompatible
                    else -> R.string.emulator_install_incompatible
                }))
            } catch (_: Exception) {
                showMessage(failed())
            } finally {
                lifecycle.removeObserver(observer)
                if (!handedOff) {
                    OfficialEmulatorApk.discard(activity, attempt)
                    if (stagedApk == attempt) {
                        stagedApk = null
                        installingPackage = null
                    }
                }
                downloadJob = null
                downloadingPackage = null
            }
        }
        downloadingPackage = pkg
        downloadJob = job
        job.start()
    }

    /** The system installer owns the staged file until it returns, including a Home detour. */
    private fun finishInstall(resultCode: Int) {
        val pkg = installingPackage
        OfficialEmulatorApk.discard(activity, stagedApk)
        stagedApk = null
        installingPackage = null
        revision++
        if (pkg != null) returnFocusPackage = pkg
        if (pkg == OfficialEmulatorApk.VLC_PACKAGE && OfficialEmulatorApk.installStatus(activity, pkg) == OfficialEmulatorApk.InstallStatus.CURRENT) {
            showMessage(activity.getString(R.string.vlc_install_installed))
        } else if (resultCode != Activity.RESULT_CANCELED && resultCode != Activity.RESULT_OK) {
            showMessage(activity.getString(if (pkg == OfficialEmulatorApk.VLC_PACKAGE) R.string.vlc_install_failed else R.string.emulator_install_failed))
        } else replaceMessage(activity.getString(R.string.app_install_confirm), "")
    }

    /** Also lets a Home detour cancel a still-open permission request, without a second launch. */
    fun finishPermission(pkg: String) {
        if (permissionPackage != pkg) return
        pendingPermissionPackage = null
        revision++
        returnFocusPackage = pkg
        if (activity.packageManager.canRequestPackageInstalls() && !PortableMaintenance.running) download(pkg)
        else showMessage("")
    }
}
