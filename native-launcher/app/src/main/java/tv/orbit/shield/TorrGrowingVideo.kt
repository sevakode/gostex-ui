package tv.orbit.shield

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.SystemClock
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Shares the worker's document through revocable, process-local read grants. */
internal object TorrGrowingVideo {
    private const val START_BYTES = 64L * 1024
    private const val READ_WAIT_MS = 8_000L
    private const val IDLE_GRANT_MS = 5 * 60_000L
    private const val MAX_GRANTS_PER_TRANSFER = 2
    private val readers = Semaphore(4)
    private val expiryHandler by lazy { Handler(Looper.getMainLooper()) }
    private val transfers = ConcurrentHashMap<String, Transfer>()
    private val sessions = ConcurrentHashMap<Uri, Session>()

    internal data class Metadata(val name: String, val total: Long)

    private class Transfer(job: TorrDownloadJob, val descriptor: ParcelFileDescriptor) {
        val document = job.document
        val metadata = Metadata(job.finalName, job.total)
        val lock = ReentrantLock()
        val changed = lock.newCondition()
        var prefix = 0L
        var tailStart = job.total
        var finished = false
        var failed = false
        var grants = 0

        fun ready(): Boolean = !finished && !failed &&
            prefix >= minOf(START_BYTES, metadata.total) &&
            (prefix == metadata.total || tailStart < metadata.total)

        fun canRead(offset: Long, end: Long): Boolean =
            end <= prefix || offset >= tailStart || prefix >= tailStart

        fun releaseIfFinished() {
            if (finished && (failed || grants == 0)) runCatching { descriptor.close() }
        }
    }

    private class Session(val transfer: Transfer) {
        var closed = false
        var openReaders = 0
        var expiration: Runnable? = null
    }

    /** Duplicate the exact SAF FD already checked by the writer. The FD survives document rename. */
    fun bind(job: TorrDownloadJob, fd: FileDescriptor): Boolean {
        if (job.http != null || job.total <= 0 || !TorrPlayback.isVideo(job.finalName)) return false
        return try {
            val stat = Os.fstat(fd)
            if (!OsConstants.S_ISREG(stat.st_mode)) return false
            val existing = transfers[job.id]
            if (existing != null) {
                val same = existing.lock.withLock {
                    val bound = Os.fstat(existing.descriptor.fileDescriptor)
                    existing.document == job.document && existing.metadata == Metadata(job.finalName, job.total) &&
                        !existing.finished && stat.st_dev == bound.st_dev && stat.st_ino == bound.st_ino
                }
                if (!same) finish(job.id, false)
                same
            } else {
                val descriptor = ParcelFileDescriptor.dup(fd)
                if (transfers.putIfAbsent(job.id, Transfer(job, descriptor)) == null) true
                else {
                    descriptor.close()
                    false
                }
            }
        } catch (_: Exception) {
            finish(job.id, false)
            false
        }
    }

    /** This must be delivered synchronously before withdrawing coverage and truncating the file. */
    fun update(job: TorrDownloadJob, prefix: Long, tailStart: Long) {
        val transfer = transfers[job.id] ?: return
        if (job.document != transfer.document || job.total != transfer.metadata.total ||
            prefix !in 0..job.total || tailStart !in 0..job.total) return
        transfer.lock.withLock {
            if (transfer.finished) return
            transfer.prefix = prefix
            transfer.tailStart = tailStart
            transfer.changed.signalAll()
        }
    }

    fun ready(jobId: String): Boolean {
        val transfer = transfers[jobId] ?: return false
        return transfer.lock.withLock { transfer.ready() }
    }

    fun finish(jobId: String, success: Boolean) {
        val transfer = transfers.remove(jobId) ?: return
        transfer.lock.withLock {
            transfer.finished = true
            transfer.failed = !success
            if (success) transfer.prefix = transfer.metadata.total
            transfer.changed.signalAll()
            transfer.releaseIfFinished()
        }
    }

    fun open(context: Context, jobId: String): Uri? {
        val transfer = transfers[jobId] ?: return null
        return transfer.lock.withLock {
            if (!transfer.ready() || transfer.grants >= MAX_GRANTS_PER_TRANSFER) return null
            val uri = Uri.Builder().scheme("content").authority("${context.packageName}.growing-video")
                .appendPath(UUID.randomUUID().toString())
                .appendPath(transfer.metadata.name)
                .build()
            val session = Session(transfer)
            transfer.grants++
            sessions[uri] = session
            scheduleExpiry(context.applicationContext, uri, session)
            uri
        }
    }

    fun close(context: Context, uri: Uri) {
        val session = sessions[uri] ?: return
        expire(context, uri, session)
    }

    internal fun metadata(uri: Uri): Metadata? = sessions[uri]?.transfer?.metadata

    internal fun openProxy(context: Context, uri: Uri): ParcelFileDescriptor {
        val session = sessions[uri] ?: throw FileNotFoundException("Unavailable video")
        val transfer = session.transfer
        transfer.lock.withLock {
            if (session.closed || transfer.failed || !readers.tryAcquire())
                throw FileNotFoundException("Unavailable video")
            session.openReaders++
            cancelExpiry(session)
        }

        val app = context.applicationContext
        val thread = HandlerThread("gostex-video-reader")
        val released = AtomicBoolean(false)
        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = transfer.metadata.total

            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                if (offset < 0 || size !in 0..data.size) throw ErrnoException("read", OsConstants.EINVAL)
                return transfer.lock.withLock {
                    if (session.closed || transfer.failed) throw ErrnoException("read", OsConstants.EIO)
                    val total = transfer.metadata.total
                    if (offset >= total || size == 0) return 0
                    val count = minOf(size.toLong(), total - offset).toInt()
                    val end = offset + count
                    val deadline = SystemClock.elapsedRealtime() + READ_WAIT_MS
                    while (!transfer.canRead(offset, end)) {
                        if (session.closed || transfer.failed) throw ErrnoException("read", OsConstants.EIO)
                        val remaining = deadline - SystemClock.elapsedRealtime()
                        if (remaining <= 0) throw ErrnoException("read", OsConstants.EAGAIN)
                        try {
                            transfer.changed.awaitNanos(remaining * 1_000_000)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw ErrnoException("read", OsConstants.EINTR)
                        }
                    }
                    if (session.closed || transfer.failed) throw ErrnoException("read", OsConstants.EIO)

                    // Hold the same lock used to withdraw coverage before the worker truncates.
                    var copied = 0
                    while (copied < count) {
                        val amount = try {
                            Os.pread(transfer.descriptor.fileDescriptor, data, copied, count - copied, offset + copied)
                        } catch (_: InterruptedIOException) {
                            throw ErrnoException("pread", OsConstants.EINTR)
                        }
                        if (amount <= 0) throw ErrnoException("pread", OsConstants.EIO)
                        copied += amount
                    }
                    copied
                }
            }

            override fun onRelease() {
                if (!released.compareAndSet(false, true)) return
                transfer.lock.withLock {
                    session.openReaders--
                    if (!session.closed && session.openReaders == 0) scheduleExpiry(app, uri, session)
                }
                readers.release()
                thread.quitSafely()
            }
        }
        return try {
            thread.start()
            val handler = Handler(thread.looper)
            context.getSystemService(StorageManager::class.java)
                .openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, handler)
        } catch (_: Exception) {
            callback.onRelease()
            throw FileNotFoundException("Cannot open video")
        }
    }

    private fun scheduleExpiry(context: Context, uri: Uri, session: Session) {
        check(session.expiration == null)
        val task = object : Runnable {
            override fun run() = expire(context, uri, session, this)
        }
        session.expiration = task
        expiryHandler.postDelayed(task, IDLE_GRANT_MS)
    }

    private fun cancelExpiry(session: Session) {
        session.expiration?.let(expiryHandler::removeCallbacks)
        session.expiration = null
    }

    private fun expire(context: Context, uri: Uri, session: Session, expiration: Runnable? = null) {
        val transfer = session.transfer
        transfer.lock.withLock {
            if (session.closed || (expiration != null && session.expiration !== expiration)) return
            cancelExpiry(session)
            session.closed = true
            sessions.remove(uri, session)
            transfer.grants--
            transfer.changed.signalAll()
            transfer.releaseIfFinished()
        }
        context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
