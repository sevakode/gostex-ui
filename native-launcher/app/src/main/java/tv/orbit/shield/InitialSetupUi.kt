package tv.orbit.shield

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private sealed interface SetupFolder {
    data object Unset : SetupFolder
    data object Checking : SetupFolder
    data object AccessLost : SetupFolder
    data object Unavailable : SetupFolder
    data class Ready(val name: String?) : SetupFolder
}

private const val FOLDER_CHECK_TIMEOUT_MS = 3_000L

/** Read the tree root's metadata without enumerating the drive or creating a file. */
private fun checkSetupFolder(context: Context, folder: String, signal: CancellationSignal): SetupFolder {
    return try {
        val tree = Uri.parse(folder)
        if (tree.scheme != ContentResolver.SCHEME_CONTENT || !DocumentsContract.isTreeUri(tree)) {
            return SetupFolder.Unavailable
        }
        val resolver = context.contentResolver
        if (resolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission && it.isWritePermission }) {
            return SetupFolder.AccessLost
        }
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS)
        resolver.query(root, columns, null, null, null, signal)?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING) ||
                cursor.extras.getString(DocumentsContract.EXTRA_ERROR) != null ||
                cursor.getString(1) != Document.MIME_TYPE_DIR ||
                (cursor.getInt(2) and Document.FLAG_DIR_SUPPORTS_CREATE) == 0
            ) SetupFolder.Unavailable
            else SetupFolder.Ready(cursor.getString(0)?.takeIf { it.isNotBlank() })
        } ?: SetupFolder.Unavailable
    } catch (_: SecurityException) {
        SetupFolder.AccessLost
    } catch (_: Exception) {
        SetupFolder.Unavailable
    }
}

private suspend fun probeSetupFolder(context: Context, folder: String): SetupFolder {
    if (folder.isBlank()) return SetupFolder.Unset
    return withTimeoutOrNull(FOLDER_CHECK_TIMEOUT_MS) {
        suspendCancellableCoroutine<SetupFolder> { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation {
                // Cancelling a document query can itself call the remote provider.
                Dispatchers.IO.dispatch(EmptyCoroutineContext) { runCatching { signal.cancel() } }
            }
            Dispatchers.IO.dispatch(continuation.context) {
                if (continuation.isActive) continuation.resume(checkSetupFolder(context, folder, signal))
            }
        }
    } ?: SetupFolder.Unavailable
}

@Composable
internal fun InitialSetupSettings(activity: MainActivity, rowFocus: (String) -> Modifier, onCatalog: () -> Unit) {
    val folder = activity.torrDownloadFolder
    val context = activity.applicationContext
    var storage by remember(activity, folder) { mutableStateOf<SetupFolder>(SetupFolder.Checking) }
    var mediaGranted by remember(activity) { mutableStateOf(false) }
    LaunchedEffect(activity, folder) {
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch { mediaGranted = withContext(Dispatchers.IO) { MediaAccess.isGranted(context) } }
            storage = SetupFolder.Checking
            storage = probeSetupFolder(context, folder)
        }
    }
    val folderSummary = when (val status = storage) {
        SetupFolder.Unset -> tr(R.string.torr_download_folder_hint)
        SetupFolder.Checking -> tr(R.string.initial_setup_storage_checking)
        SetupFolder.AccessLost -> tr(R.string.initial_setup_storage_access_lost)
        SetupFolder.Unavailable -> tr(R.string.initial_setup_storage_unavailable)
        is SetupFolder.Ready -> if (status.name == null) tr(R.string.initial_setup_storage_ready)
            else tr(R.string.initial_setup_storage_named, status.name)
    }
    SettingRow(tr(R.string.torr_download_choose_folder), folderSummary, rowFocus("setupStorage"),
        onClick = activity::chooseTorrFolder)
    SettingRow(tr(R.string.app_catalog_title), tr(R.string.app_catalog_summary), rowFocus("setupApps"),
        onClick = onCatalog)
    TorrServeInstallSetting(activity, rowFocus("setupTorrServe"))
    TorrServerStatusSetting(rowFocus("setupTorrServer"))
    SettingRow(tr(R.string.media_access),
        tr(if (mediaGranted) R.string.media_access_granted else R.string.initial_setup_media_optional),
        rowFocus("setupMediaAccess"), onClick = {
            if (!MediaAccess.openSettings(activity)) activity.notice = activity.getString(R.string.media_settings_unavailable)
        })
    DefaultHomeSetting(activity, rowFocus("setupHome"))
}
