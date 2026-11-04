package tv.orbit.shield

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File

internal object RetroArchLauncher {
    /** No ROM copies or persisted metadata rewrites: resolve only an existing public volume. */
    fun arguments(context: Context, spec: PlatformSpec, gameUri: Uri, treeUri: Uri?): RetroArchLaunchArguments {
        fun unsupported(): Nothing = throw IllegalArgumentException(context.getString(R.string.nds_retroarch_location_error))
        val storage = context.getSystemService(StorageManager::class.java) ?: unsupported()
        val volumes = storage.storageVolumes.mapNotNull { volume ->
            val id = if (volume.isPrimary) "primary" else volume.uuid ?: return@mapNotNull null
            val directory = volume.directory ?: return@mapNotNull null
            id to directory.absolutePath
        }.toMap()
        val romPath = when (gameUri.scheme) {
            "content" -> {
                if (gameUri.authority != RetroArchPath.EXTERNAL_STORAGE_AUTHORITY) unsupported()
                if (treeUri != null && (treeUri.scheme != "content" || treeUri.authority != gameUri.authority)) unsupported()
                val documentId = runCatching { DocumentsContract.getDocumentId(gameUri) }.getOrNull() ?: unsupported()
                val treeId = treeUri?.let { runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull() ?: unsupported() }
                RetroArchPath.externalDocument(gameUri.authority, documentId, treeId, volumes)
            }
            "file" -> gameUri.path?.let { RetroArchPath.storedFile(it, volumes) }
            null -> RetroArchPath.storedFile(gameUri.toString(), volumes)
            else -> null
        } ?: unsupported()
        val storageRoot = volumes["primary"] ?: unsupported()
        val component = requireNotNull(ComponentName.unflattenFromString(spec.component))
        @Suppress("DEPRECATION")
        val application = context.packageManager.getApplicationInfo(component.packageName, 0)
        val externalFiles = File(storageRoot, "Android/data/${component.packageName}/files").absolutePath
        return RetroArchLaunchArguments(
            romPath = romPath,
            corePath = File(application.dataDir, "cores/melondsds_libretro_android.so").absolutePath,
            dataDir = application.dataDir,
            apkPath = application.sourceDir,
            storageRoot = storageRoot,
            externalFiles = externalFiles,
            configFile = File(externalFiles, "retroarch.cfg").absolutePath,
            inputMethod = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        )
    }
}
