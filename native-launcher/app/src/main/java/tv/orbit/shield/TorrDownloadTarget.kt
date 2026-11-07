package tv.orbit.shield

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.IOException

private inline fun <T> storageIo(block: () -> T): T = try { block() }
catch (_: Exception) { throw TorrStorageFailure() }

/** Seekable SAF document owned exclusively by the download job. */
internal class TorrSafTarget private constructor(private val descriptor: ParcelFileDescriptor) : TorrDownloadTarget, Closeable {
    private val fd = descriptor.fileDescriptor
    private val output = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
    override fun size(): Long = storageIo { Os.fstat(fd).st_size }
    override fun truncate(size: Long) = storageIo { Os.ftruncate(fd, size) }
    override fun seek(offset: Long) { storageIo { Os.lseek(fd, offset, OsConstants.SEEK_SET) } }
    override fun write(buffer: ByteArray, count: Int) = storageIo { output.write(buffer, 0, count) }
    override fun sync() = storageIo { Os.fsync(fd) }
    fun availableBytes(): Long {
        val stats = try { Os.fstatvfs(fd) } catch (_: Exception) { throw TorrDownloadSpaceUnavailable() }
        if (stats.f_bavail < 0 || stats.f_frsize <= 0) throw TorrDownloadSpaceUnavailable()
        return if (stats.f_bavail > Long.MAX_VALUE / stats.f_frsize) Long.MAX_VALUE else stats.f_bavail * stats.f_frsize
    }
    override fun close() = storageIo { output.close() }

    companion object {
        fun open(resolver: ContentResolver, document: Uri): TorrSafTarget {
            require(document.scheme == ContentResolver.SCHEME_CONTENT)
            val descriptor = storageIo { resolver.openFileDescriptor(document, "rw") ?: throw IOException("Cannot open destination") }
            return try {
                // Fail before the transfer if this provider cannot support a safe resume.
                Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_CUR)
                TorrSafTarget(descriptor)
            } catch (error: Exception) {
                runCatching { descriptor.close() }
                throw TorrStorageFailure()
            }
        }
    }
}
