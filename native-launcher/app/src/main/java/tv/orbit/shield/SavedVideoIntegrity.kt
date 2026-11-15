package tv.orbit.shield

import android.content.ContentResolver
import android.net.Uri
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** Reject obvious corruption without decoding the video or reading beyond its first 4 KiB. */
internal object SavedVideoIntegrity {
    enum class Result { VALID, INVALID, UNAVAILABLE }

    const val HEADER_BYTES = 4096
    private val ebml = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())

    fun check(resolver: ContentResolver, uri: Uri, filename: String, size: Long,
              checkActive: () -> Unit = {}): Result {
        if (!TorrPlayback.isVideo(filename)) return Result.VALID
        if (size <= 0) return Result.INVALID
        val extension = filename.substringAfterLast('.').lowercase(Locale.ROOT)
        val requireEbml = extension == "mkv" || extension == "webm"
        val header = ByteArray(minOf(size, HEADER_BYTES.toLong()).toInt())
        if (requireEbml && header.size < ebml.size) return Result.INVALID

        fun checkCancellation() {
            checkActive()
            if (Thread.currentThread().isInterrupted) throw CancellationException("Video check interrupted")
        }

        checkCancellation()
        return try {
            val result = resolver.openInputStream(uri)?.use { stream ->
                var offset = 0
                while (offset < header.size) {
                    checkCancellation()
                    val count = stream.read(header, offset, header.size - offset)
                    if (count < 0) return@use Result.INVALID
                    if (count == 0) return@use Result.UNAVAILABLE
                    offset += count
                }
                val valid = if (requireEbml) ebml.indices.all { header[it] == ebml[it] }
                    else header.any { it != 0.toByte() }
                if (valid) Result.VALID else Result.INVALID
            } ?: Result.UNAVAILABLE
            checkCancellation()
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            checkCancellation()
            Result.UNAVAILABLE
        }
    }
}
