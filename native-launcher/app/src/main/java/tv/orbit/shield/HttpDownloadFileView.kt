package tv.orbit.shield

import android.content.ClipData
import android.content.Intent
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** An explicit open uses the verified local document, never the source URL. */
internal suspend fun openCompletedHttpDownload(activity: MainActivity, job: TorrDownloadJob) {
    val intent = withContext(Dispatchers.IO) {
        val document = TorrDownloadDocuments.verifyCompletedHttp(activity, job)
        val extension = job.finalName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: activity.contentResolver.getType(document) ?: "application/octet-stream"
        Intent(Intent.ACTION_VIEW).setDataAndType(document, type).apply {
            clipData = ClipData.newRawUri("", document)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    if (!activity.isFinishing && !activity.isDestroyed) activity.startActivity(intent)
}
