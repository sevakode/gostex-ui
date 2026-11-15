package tv.orbit.shield

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

private data class ImportEmulatorState(
    val missing: Boolean = false,
    val available: Boolean = false,
    val launch: Intent? = null,
)

private fun importEmulatorState(activity: MainActivity, spec: PlatformSpec?): ImportEmulatorState {
    val pkg = spec?.let { ComponentName.unflattenFromString(it.component)?.packageName }
        ?: return ImportEmulatorState()
    try {
        @Suppress("DEPRECATION")
        activity.packageManager.getPackageInfo(pkg, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        return ImportEmulatorState(missing = true)
    } catch (_: Exception) {
        return ImportEmulatorState()
    }
    val launch = runCatching {
        activity.packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: activity.packageManager.getLaunchIntentForPackage(pkg)
    }.getOrNull()
    return ImportEmulatorState(
        available = runCatching { PlatformRegistry.unavailableReason(activity, spec) == null }.getOrDefault(false),
        launch = launch,
    )
}

/** Updates emulator availability without replacing the open result dialog or its focus. */
@Composable
internal fun GameImportSummaryHost(activity: MainActivity) {
    val result = activity.gameImportSummary ?: return
    val options = LocalLibraryOptions.current
    val spec = remember(result.platform, options.ndsEmulator) {
        PlatformRegistry.resolve(result.platform, options.ndsEmulator)
    }
    val state = remember(spec, activity.resumeVersion, activity.emulatorInstall.revision) {
        importEmulatorState(activity, spec)
    }
    var actionError by remember(result, spec, activity.resumeVersion, activity.emulatorInstall.revision) { mutableStateOf("") }
    val message = buildList {
        add(activity.getString(when {
            result.failed -> R.string.game_import_failed
            result.available == 0 -> R.string.game_import_empty
            else -> R.string.game_import_counts
        }, result.available, result.added))
        if (spec != null) {
            if (!result.failed && result.available == 0) {
                add(activity.getString(R.string.game_import_formats, spec.extensions.sorted().joinToString(", ") { ".$it" }))
            }
            add(activity.getString(when {
                state.missing -> R.string.game_import_emulator_missing
                state.available -> R.string.game_import_emulator_available
                else -> R.string.game_import_emulator_unavailable
            }, spec.emulator))
            spec.hintRes?.let { add(activity.getString(it)) }
        }
        if (result.warnings.isNotEmpty()) add(activity.getString(R.string.game_import_details, result.warnings.joinToString("\n")))
        if (actionError.isNotEmpty()) add(actionError)
    }.joinToString("\n\n")
    val dialog = remember(activity, result) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.game_import_result_title, PlatformRegistry.title(activity, result.platform)))
            .setMessage(message)
            .setNegativeButton(R.string.close) { _, _ ->
                if (activity.gameImportSummary == result) activity.gameImportSummary = null
            }
            // Keep the button instance stable when the installer returns.
            .setPositiveButton(R.string.game_import_open_emulator, null)
            .create()
    }
    DisposableEffect(dialog) {
        dialog.setOnCancelListener {
            if (activity.gameImportSummary == result) activity.gameImportSummary = null
        }
        dialog.show()
        onDispose {
            dialog.setOnCancelListener(null)
            dialog.dismiss()
        }
    }
    SideEffect {
        dialog.setMessage(message)
        val action = dialog.getButton(AlertDialog.BUTTON_POSITIVE) ?: return@SideEffect
        val canAct = spec != null && (state.missing || state.launch != null)
        val returnFocusToClose = !canAct && action.hasFocus()
        action.isEnabled = canAct
        action.visibility = if (canAct) android.view.View.VISIBLE else android.view.View.GONE
        if (returnFocusToClose) dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
        action.setText(if (state.missing) R.string.game_import_install_emulator else R.string.game_import_open_emulator)
        action.setOnClickListener {
            val currentSpec = PlatformRegistry.resolve(result.platform, LibraryPreferences.read(activity).ndsEmulator)
                ?: return@setOnClickListener
            actionError = ""
            // Recheck on click: package state may change while the dialog is open.
            val current = importEmulatorState(activity, currentSpec)
            if (current.missing) {
                if (!activity.offerMissingEmulator(currentSpec)) {
                    actionError = activity.getString(R.string.emulator_unavailable, currentSpec.emulator)
                }
            } else if (current.launch == null || runCatching { activity.startActivity(current.launch) }.isFailure) {
                actionError = activity.getString(R.string.emulator_unavailable, currentSpec.emulator)
            }
        }
    }
}
