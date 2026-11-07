package tv.orbit.shield

import android.app.AlertDialog
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared preparation UI for the media library and direct torrent playback. */
internal object TorrPreparationDialog {
    fun show(activity: ComponentActivity, hash: String, fileId: Int, onOpen: () -> Unit, onDismiss: () -> Unit): AlertDialog {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.movies_buffer_title)
            .setMessage(R.string.movies_buffer_starting)
            .setNegativeButton(R.string.movies_cancel, null)
            .setPositiveButton(R.string.movies_buffer_open, null)
            .create()
        var monitor: Job? = null
        var preload: Job? = null
        var lastStatus = ""
        var preloadMessage = ""
        dialog.setOnDismissListener { monitor?.cancel(); preload?.cancel(); onDismiss() }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { dialog.dismiss(); onOpen() }
        monitor = activity.lifecycleScope.launch {
            while (isActive && dialog.isShowing) {
                val entry = runCatching { withContext(Dispatchers.IO) { TorrServerCatalog.status(hash) } }.getOrNull()
                if (isActive && dialog.isShowing) {
                    lastStatus = entry?.let { torrentStatusText(activity, it, false) } ?: activity.getString(R.string.movies_status_unavailable)
                    dialog.setMessage(listOf(lastStatus, preloadMessage).filter { it.isNotEmpty() }.joinToString("\n\n"))
                }
                delay(2000)
            }
        }
        preload = activity.lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { TorrServerCatalog.preload(hash, fileId) } }
            if (!isActive || !dialog.isShowing) return@launch
            preloadMessage = activity.getString(if (result.isSuccess) R.string.movies_buffer_finished else R.string.movies_buffer_waiting)
            dialog.setMessage(listOf(lastStatus, preloadMessage).filter { it.isNotEmpty() }.joinToString("\n\n"))
        }
        return dialog
    }
}

internal fun torrentStatusText(context: Context, entry: TorrCatalogEntry, snapshot: Boolean): String {
    val unknown = context.getString(R.string.movies_buffer_unknown)
    fun size(value: Long?) = value?.let { android.text.format.Formatter.formatFileSize(context, it) } ?: unknown
    val speed = entry.downloadBytesPerSecond?.let { size(it) + "/s" } ?: unknown
    val state = when (entry.serverState) {
        0 -> R.string.movies_state_added
        1 -> R.string.movies_state_metadata
        2 -> R.string.movies_state_preload
        3 -> R.string.movies_state_working
        4 -> R.string.movies_state_closed
        5 -> R.string.movies_state_stored
        else -> R.string.movies_buffer_unknown
    }
    val values = context.getString(R.string.movies_status_metrics, context.getString(state), size(entry.cachedBytes),
        size(entry.preloadBytes), speed, entry.connectedSeeders?.toString() ?: unknown)
    return values + "\n\n" + context.getString(if (snapshot) R.string.movies_status_snapshot else R.string.movies_status_open_hint)
}
