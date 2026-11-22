package tv.orbit.shield

import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException

internal class TorrDownloadLowSpace : IOException("Download storage reserve reached")
internal class TorrDownloadSpaceUnavailable : IOException("Cannot measure download storage")
internal class TorrSourceFailure(val reason: String) : IOException("Download source error")
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

internal data class TorrDownloadCheckpoint(val offset: Long, val etag: String?) {
    override fun toString(): String = "TorrDownloadCheckpoint(offset=$offset)"
}
internal data class TorrDownloadSource(val hash: String, val index: Int, val size: Long) {
    init {
        require(hash.matches(Regex("[0-9a-fA-F]{40}")))
        require(index > 0 && size >= 0)
    }
}

/**
 * Copies one validated source into a destination owned by the caller.
 * The caller persists checkpoints atomically only after sync().
 * Progress is transient; only saveCheckpoint supplies a durable resume offset.
 * Time checks occur between reads, while the byte bound also limits read size.
 * This class does not create jobs, grant access, or silently restart a download.
 */
internal object TorrDownloadEngine {
    private const val SPACE_CHECK_BYTES = 4L * 1024 * 1024
    private const val SOFT_CHECKPOINT_BYTES = 4L * 1024 * 1024
    private const val HARD_CHECKPOINT_BYTES = 16L * 1024 * 1024
    private const val CHECKPOINT_NANOS = 2_000_000_000L
    private const val PROGRESS_NANOS = 500_000_000L
    private const val BUFFER_BYTES = 64 * 1024
    private const val PLAYBACK_TAIL_BYTES = 64 * 1024

    private class ClosedRangeUnavailable : IOException()
    private data class PlaybackTail(val start: Long, val data: ByteArray, val etag: String)

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
        nowNanos: () -> Long = System::nanoTime,
        reportProgress: (Long) -> Unit = {},
        readable: ((prefix: Long, tailStart: Long) -> Unit)? = null,
    ): TorrDownloadCheckpoint {
        require(port in 1..65535 && readTimeoutMs in 1..120000)
        val offset = checkpoint.offset
        require(offset in 0..source.size)
        if (target.size() < offset) throw IOException("Destination is shorter than its checkpoint")
        if (offset == source.size) {
            verifyCompleted(source.size, target, checkpoint)
            readable?.invoke(offset, source.size)
            reportProgress(offset)
            return checkpoint
        }
        if (offset > 0 && !isStrongEtag(checkpoint.etag)) throw IOException("Missing resume identity")
        if (cancelled()) { reportProgress(offset); return checkpoint }
        val url = URL("http://127.0.0.1:$port/stream?link=${source.hash}&index=${source.index}&play=1")
        val tailStart = source.size - PLAYBACK_TAIL_BYTES
        if (readable != null && tailStart >= PLAYBACK_TAIL_BYTES && offset < tailStart &&
            // A provider need not support sparse files. Reserve enough even if it allocates the gap.
            spaceAllowance()?.let { it >= source.size - offset } != false) {
            try {
                val tail = fetchPlaybackTail(url, source.size, tailStart, checkpoint.etag, readTimeoutMs, cancelled)
                if (tail == null || cancelled()) { reportProgress(offset); return checkpoint }
                return withTorrentResponse(url, readTimeoutMs, "bytes=$offset-${tail.start - 1}", tail.etag) { connection ->
                    val etag = requireClosedRange(connection, source.size, offset, tail.start, tail.etag)
                    copyValidated(source.size, target, checkpoint, etag, { connection.inputStream }, cancelled,
                        saveCheckpoint, spaceAllowance, beforeWrite, nowNanos, reportProgress, abortable = true,
                        readable = readable, playbackTail = tail)
                }
            } catch (_: ClosedRangeUnavailable) {
                // Continue through the regular sequential path when the server ignores a closed range.
            }
        }
        val range = if (offset > 0) "bytes=$offset-" else null
        return withTorrentResponse(url, readTimeoutMs, range, checkpoint.etag) { connection ->
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
            copyValidated(source.size, target, checkpoint, requireNotNull(etag), { connection.inputStream },
                cancelled, saveCheckpoint, spaceAllowance, beforeWrite, nowNanos, reportProgress,
                abortable = readable != null, readable = readable)
        }
    }

    private inline fun <T> withTorrentResponse(
        url: URL,
        readTimeoutMs: Int,
        range: String?,
        etag: String?,
        consume: (HttpURLConnection) -> T,
    ): T {
        val connection = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = readTimeoutMs
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (range != null) {
                connection.setRequestProperty("Range", range)
                if (etag != null) connection.setRequestProperty("If-Range", etag)
            }
            return consume(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun requireClosedRange(
        connection: HttpURLConnection,
        size: Long,
        start: Long,
        end: Long,
        expectedEtag: String?,
    ): String {
        val status = sourceIo { connection.responseCode }
        if (status == 401 || status == 403) throw TorrSourceFailure("source_auth")
        if (status !in setOf(200, 206)) throw TorrSourceFailure("source_response")
        fun header(name: String): String? = try {
            connection.singleDownloadHeader(name, HttpDownloadAdmissionError.RESPONSE)
        } catch (_: HttpDownloadAdmissionException) {
            throw TorrSourceFailure("source_response")
        }
        val etag = header("ETag")
        if (expectedEtag != null && etag != expectedEtag) throw TorrSourceFailure("source_changed")
        if (status == 200) throw ClosedRangeUnavailable()
        val encoding = header("Content-Encoding")
        if (!isStrongEtag(etag) || (encoding != null && !encoding.equals("identity", ignoreCase = true)) ||
            header("Transfer-Encoding") != null || header("Content-Length") != (end - start).toString() ||
            header("Content-Range") != "bytes $start-${end - 1}/$size") {
            throw TorrSourceFailure("source_response")
        }
        return requireNotNull(etag)
    }

    private fun fetchPlaybackTail(
        url: URL,
        size: Long,
        start: Long,
        expectedEtag: String?,
        readTimeoutMs: Int,
        cancelled: () -> Boolean,
    ): PlaybackTail? = withTorrentResponse(url, readTimeoutMs, "bytes=$start-${size - 1}", expectedEtag) { connection ->
        val etag = requireClosedRange(connection, size, start, size, expectedEtag)
        if (cancelled()) return@withTorrentResponse null
        val data = ByteArray((size - start).toInt())
        sourceIo { connection.inputStream }.use { input ->
            var received = 0
            while (received < data.size && !cancelled()) {
                val count = sourceIo { input.read(data, received, data.size - received) }
                if (count < 0) throw TorrSourceFailure("source_incomplete")
                received += count
            }
            if (received == data.size) PlaybackTail(start, data, etag) else null
        }
    }

    /** Verify the exact admitted resource before changing even an uncommitted tail. */
    fun transfer(
        source: HttpDownloadOrigin,
        size: Long,
        target: TorrDownloadTarget,
        checkpoint: TorrDownloadCheckpoint,
        cancelled: () -> Boolean = { false },
        saveCheckpoint: (TorrDownloadCheckpoint) -> Unit,
        spaceAllowance: () -> Long? = { null },
        readTimeoutMs: Int = 30000,
        beforeWrite: (Int) -> Boolean = { true },
        nowNanos: () -> Long = System::nanoTime,
        reportProgress: (Long) -> Unit = {},
        abort: HttpDownloadAbort? = null,
        resetForRestart: (() -> Unit)? = null,
        session: HttpDownloadSession? = null,
    ): TorrDownloadCheckpoint {
        val etag = source.expectedEtag
        val canResume = etag != null
        require(size > 0 && readTimeoutMs in 1..120000 && (!canResume || isStrongHttpEtag(etag)))
        val offset = checkpoint.offset
        require(offset in 0..size)
        if ((offset > 0 || checkpoint.etag != null) && checkpoint.etag != etag) {
            throw IOException("Missing resume identity")
        }
        if (target.size() < offset) throw IOException("Destination is shorter than its checkpoint")
        if (offset == size) {
            verifyCompleted(size, target, checkpoint, requireStrongEtag = canResume)
            reportProgress(offset)
            return checkpoint
        }
        if (!canResume && offset != 0L && resetForRestart == null) throw TorrSourceFailure("source_restart_required")
        val url = if (source.sessionBound) {
            if (!canResume || source.url != null || session == null || !session.matchesSize(size)) {
                throw TorrSourceFailure("source_auth")
            }
            session.url
        } else {
            if (session != null) throw TorrSourceFailure("source_auth")
            requireNotNull(source.url)
        }
        val stopRequested = { abort?.cancelled == true || cancelled() || Thread.currentThread().isInterrupted }
        var start = checkpoint
        return try {
            withHttpDownloadResponse(url, 5000, readTimeoutMs, stopRequested, abort, session, configure = {
                if (canResume) {
                    setRequestProperty("Range", "bytes=$offset-")
                    setRequestProperty(if (offset == 0L) "If-Match" else "If-Range", etag)
                }
            }) { effective, connection, status ->
                fun header(name: String) = connection.singleDownloadHeader(name, HttpDownloadAdmissionError.RESPONSE)
                if (status == 401 || status == 403 || status == 407) throw TorrSourceFailure("source_auth")
                if (status == 200 || status == 206) session?.requireAttachment(connection)
                if (effective.resourceKey() != source.resourceKey || (canResume && status in setOf(200, 412, 416))) {
                    throw TorrSourceFailure("source_changed")
                }
                if (status != if (canResume) 206 else 200) throw TorrSourceFailure("source_response")
                if (canResume && header("ETag") != etag) throw TorrSourceFailure("source_changed")
                val encoding = header("Content-Encoding")
                if ((encoding != null && !encoding.equals("identity", ignoreCase = true)) ||
                    header("Transfer-Encoding") != null) {
                    throw TorrSourceFailure("source_response")
                }
                if (canResume) {
                    if (header("Content-Length") != (size - offset).toString() ||
                        !header("Content-Range").equals("bytes $offset-${size - 1}/$size", ignoreCase = true)) {
                        throw TorrSourceFailure("source_response")
                    }
                } else {
                    if (header("Content-Length") != size.toString()) throw TorrSourceFailure("source_changed")
                    if (header("Content-Range") != null) throw TorrSourceFailure("source_response")
                    if (stopRequested()) throw CancellationException()
                    if (offset != 0L) {
                        // Persist zero before replacing any bytes. If the journal write fails,
                        // the caller's old checkpoint and the full partial remain intact.
                        requireNotNull(resetForRestart).invoke()
                        start = TorrDownloadCheckpoint(0, null)
                    }
                }
                copyValidated(size, target, start, etag, { connection.inputStream },
                    stopRequested, saveCheckpoint, spaceAllowance, beforeWrite, nowNanos, reportProgress,
                    abortable = true)
            }
        } catch (error: HttpDownloadAdmissionException) {
            if (stopRequested()) { reportProgress(start.offset); return start }
            throw TorrSourceFailure(when (error.reason) {
                HttpDownloadAdmissionError.TIMEOUT -> "source_timeout"
                HttpDownloadAdmissionError.NETWORK -> "source_io"
                HttpDownloadAdmissionError.AUTH -> "source_auth"
                else -> "source_response"
            })
        } catch (error: CancellationException) {
            if (!stopRequested()) throw error
            reportProgress(start.offset)
            start
        }
    }

    /** Verify a durable, completed document without constructing or contacting a source. */
    fun verifyCompleted(size: Long, target: TorrDownloadTarget, checkpoint: TorrDownloadCheckpoint,
                        requireStrongEtag: Boolean = true) {
        val invalidValidator = if (requireStrongEtag) size > 0 && !isStrongEtag(checkpoint.etag) else checkpoint.etag != null
        if (size < 0 || checkpoint.offset != size || target.size() != size || invalidValidator) {
            throw IOException("Invalid completed checkpoint")
        }
    }

    private fun copyValidated(
        size: Long,
        target: TorrDownloadTarget,
        checkpoint: TorrDownloadCheckpoint,
        etag: String?,
        openStream: () -> InputStream,
        cancelled: () -> Boolean,
        saveCheckpoint: (TorrDownloadCheckpoint) -> Unit,
        spaceAllowance: () -> Long?,
        beforeWrite: (Int) -> Boolean,
        nowNanos: () -> Long,
        reportProgress: (Long) -> Unit,
        abortable: Boolean = false,
        readable: ((prefix: Long, tailStart: Long) -> Unit)? = null,
        playbackTail: PlaybackTail? = null,
    ): TorrDownloadCheckpoint {
        val offset = checkpoint.offset
        if (abortable && cancelled()) { reportProgress(offset); return checkpoint }
        if (playbackTail != null && (!beforeWrite(playbackTail.data.size) || cancelled())) {
            reportProgress(offset)
            return checkpoint
        }
        // Withdraw any staged bytes from a previous attempt before trimming the document.
        readable?.invoke(offset, size)
        // A crash can leave bytes after the last durable checkpoint; never trust them.
        if (target.size() > offset) target.truncate(offset)
        val tailStart = playbackTail?.start ?: size
        if (playbackTail != null) {
            target.seek(tailStart)
            target.write(playbackTail.data, playbackTail.data.size)
            target.sync()
            readable?.invoke(offset, tailStart)
        }
        target.seek(offset)
        var written = offset
        var committed = offset
        var allowance = 0L
        var lastCheckpointNanos = nowNanos()
        var lastProgressNanos = lastCheckpointNanos
        fun saveTail() {
            if (written == committed) return
            target.sync()
            saveCheckpoint(TorrDownloadCheckpoint(written, etag))
            committed = written
            lastCheckpointNanos = nowNanos()
        }
        fun report(force: Boolean = false) {
            val now = nowNanos()
            if (force || now - lastProgressNanos >= PROGRESS_NANOS) {
                reportProgress(written)
                lastProgressNanos = now
            }
        }
        report(force = true)
        try {
            sourceIo(openStream).use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (written < tailStart && !cancelled()) {
                    if (allowance <= 0) {
                        // Keep the free-space guard independent of durable checkpoint cadence.
                        allowance = minOf(spaceAllowance() ?: SPACE_CHECK_BYTES, SPACE_CHECK_BYTES)
                        if (allowance <= 0) {
                            saveTail()
                            report(force = true)
                            throw TorrDownloadLowSpace()
                        }
                    }
                    val count = sourceIo { input.read(buffer, 0, minOf(
                        buffer.size.toLong(), tailStart - written, allowance,
                        HARD_CHECKPOINT_BYTES - (written - committed),
                    ).toInt()) }
                    if (count < 0) throw TorrSourceFailure("source_incomplete")
                    if (count == 0) continue
                    if (!beforeWrite(count) || cancelled()) break
                    target.write(buffer, count)
                    written += count
                    if (written == tailStart) written = size
                    // Playback can read written bytes while the durable checkpoint is being saved.
                    readable?.invoke(written, if (written == size) size else tailStart)
                    allowance -= count
                    val delta = written - committed
                    if (delta >= HARD_CHECKPOINT_BYTES ||
                        (delta >= SOFT_CHECKPOINT_BYTES && nowNanos() - lastCheckpointNanos >= CHECKPOINT_NANOS)) saveTail()
                    report()
                }
            }
        } catch (error: TorrSourceFailure) {
            // These bytes came from the validated response. Retain them before retrying;
            // a failed sync/save must instead propagate as a storage/journal failure.
            saveTail()
            report(force = true)
            if (abortable && cancelled()) return if (written == offset) checkpoint else TorrDownloadCheckpoint(written, etag)
            throw error
        } catch (error: TorrDownloadSpaceUnavailable) {
            saveTail()
            report(force = true)
            throw error
        }
        saveTail()
        report(force = true)
        if (abortable && written == offset && cancelled()) return checkpoint
        return TorrDownloadCheckpoint(written, etag)
    }

    private fun isStrongEtag(value: String?): Boolean =
        value != null && value.length in 2..8192 && value.startsWith('"') && value.endsWith('"') &&
            !value.contains('\r') && !value.contains('\n')
}
