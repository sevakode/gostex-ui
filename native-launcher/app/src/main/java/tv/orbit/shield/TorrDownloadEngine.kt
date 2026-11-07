package tv.orbit.shield

import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.ConnectException
import java.net.SocketTimeoutException

internal class TorrDownloadLowSpace : IOException("Download storage reserve reached")
internal class TorrDownloadSpaceUnavailable : IOException("Cannot measure download storage")
internal class TorrSourceFailure(val reason: String) : IOException("Torrent source error")
internal class TorrStorageFailure : IOException("Download destination error")

private inline fun <T> sourceIo(block: () -> T): T = try { block() }
catch (_: SocketTimeoutException) { throw TorrSourceFailure("source_timeout") }
catch (_: ConnectException) { throw TorrSourceFailure("source_connect") }
catch (_: IOException) { throw TorrSourceFailure("source_io") }

/** A destination created exclusively for one download; it must never refer to an existing user file. */
internal interface TorrDownloadTarget {
    fun size(): Long
    fun truncate(size: Long)
    fun seek(offset: Long)
    fun write(buffer: ByteArray, count: Int)
    fun sync()
}

internal class TorrFileTarget(private val file: RandomAccessFile) : TorrDownloadTarget {
    override fun size(): Long = file.length()
    override fun truncate(size: Long) = file.setLength(size)
    override fun seek(offset: Long) = file.seek(offset)
    override fun write(buffer: ByteArray, count: Int) = file.write(buffer, 0, count)
    override fun sync() = file.fd.sync()
}

internal data class TorrDownloadCheckpoint(val offset: Long, val etag: String?)
internal data class TorrDownloadSource(val hash: String, val index: Int, val size: Long) {
    init {
        require(hash.matches(Regex("[0-9a-fA-F]{40}")))
        require(index > 0 && size >= 0)
    }
}

/**
 * Copies one selected torrent file from the local server. The caller owns the
 * fresh destination and persists checkpoints atomically only after sync().
 * This class does not create jobs, grant access, or silently restart a download.
 */
internal object TorrDownloadEngine {
    private const val CHECKPOINT_BYTES = 4L * 1024 * 1024
    private const val BUFFER_BYTES = 64 * 1024

    fun transfer(
        source: TorrDownloadSource,
        target: TorrDownloadTarget,
        checkpoint: TorrDownloadCheckpoint,
        port: Int = 8090,
        cancelled: () -> Boolean = { false },
        saveCheckpoint: (TorrDownloadCheckpoint) -> Unit,
        spaceAllowance: () -> Long? = { null },
        readTimeoutMs: Int = 30000,
        beforeWrite: (Int) -> Boolean = { true },
    ): TorrDownloadCheckpoint {
        require(port in 1..65535 && readTimeoutMs in 1..120000)
        val offset = checkpoint.offset
        require(offset in 0..source.size)
        if (target.size() < offset) throw IOException("Destination is shorter than its checkpoint")
        if (offset == source.size) {
            if (target.size() != source.size || (source.size > 0 && !isStrongEtag(checkpoint.etag))) throw IOException("Invalid completed checkpoint")
            return checkpoint
        }
        if (offset > 0 && !isStrongEtag(checkpoint.etag)) throw IOException("Missing resume identity")
        if (cancelled()) return checkpoint
        val url = URL("http://127.0.0.1:$port/stream?link=${source.hash}&index=${source.index}&play=1")
        val connection = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = readTimeoutMs
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) {
                connection.setRequestProperty("Range", "bytes=$offset-")
                connection.setRequestProperty("If-Range", checkpoint.etag)
            }
            val status = sourceIo { connection.responseCode }
            if (status == 401 || status == 403) throw TorrSourceFailure("source_auth")
            if (status != if (offset == 0L) 200 else 206) throw TorrSourceFailure("source_response")
            val encoding = connection.getHeaderField("Content-Encoding")
            if (encoding != null && !encoding.equals("identity", ignoreCase = true)) throw TorrSourceFailure("source_response")
            val etag = connection.getHeaderField("ETag")
            if (!isStrongEtag(etag)) throw TorrSourceFailure("source_response")
            if (offset > 0 && etag != checkpoint.etag) throw TorrSourceFailure("source_changed")
            if (connection.contentLengthLong != source.size - offset) throw TorrSourceFailure("source_response")
            if (offset > 0) {
                val expected = "bytes $offset-${source.size - 1}/${source.size}"
                if (connection.getHeaderField("Content-Range") != expected) throw TorrSourceFailure("source_response")
            }
            // A crash can leave bytes after the last durable checkpoint; never trust them.
            if (target.size() > offset) target.truncate(offset)
            target.seek(offset)
            var written = offset
            var committed = offset
            var allowance = 0L
            sourceIo { connection.inputStream }.use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (written < source.size && !cancelled()) {
                    if (allowance <= 0) {
                        if (written != committed) {
                            target.sync()
                            saveCheckpoint(TorrDownloadCheckpoint(written, etag))
                            committed = written
                        }
                        allowance = minOf(spaceAllowance() ?: CHECKPOINT_BYTES, CHECKPOINT_BYTES)
                        if (allowance <= 0) throw TorrDownloadLowSpace()
                    }
                    val count = sourceIo { input.read(buffer, 0, minOf(buffer.size.toLong(), source.size - written, allowance).toInt()) }
                    if (count < 0) throw TorrSourceFailure("source_incomplete")
                    if (count == 0) continue
                    if (!beforeWrite(count) || cancelled()) break
                    target.write(buffer, count)
                    written += count
                    allowance -= count
                    if (written - committed >= CHECKPOINT_BYTES) {
                        target.sync()
                        saveCheckpoint(TorrDownloadCheckpoint(written, etag))
                        committed = written
                    }
                }
            }
            if (written != committed) {
                target.sync()
                saveCheckpoint(TorrDownloadCheckpoint(written, etag))
            }
            return TorrDownloadCheckpoint(written, etag)
        } finally {
            connection.disconnect()
        }
    }

    private fun isStrongEtag(value: String?): Boolean =
        value != null && value.length in 2..256 && value.startsWith('"') && value.endsWith('"') &&
            !value.contains('\r') && !value.contains('\n')
}
