package tv.orbit.shield

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Storage adapter for one *new*, uniquely named staging document. The caller must
 * ensure openNew never truncates an existing document or the source. A failed copy
 * may leave a partial staging document; the caller owns cleanup and must never treat
 * it as committed. Real SAF providers need their own exclusive-name handling. */
internal interface PortableCopyDestination {
    fun openNew(): OutputStream
    fun openForVerification(): InputStream
}

internal data class PortableCopyReceipt(val bytes: Long, val sha256: String)

/** Re-read the source before publication, without retaining its bytes in memory.
 * This detects a changed source even if a provider reports unchanged metadata;
 * it cannot lock a writer or exclude a change after this verification. */
internal fun portableVerifySourceMatches(openSource: () -> InputStream, receipt: PortableCopyReceipt,
                                        checkActive: () -> Unit = {}) {
    require(receipt.bytes >= 0 && receipt.sha256.matches(Regex("[a-f0-9]{64}")))
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var bytes = 0L
    checkActive()
    openSource().use { input ->
        while (true) {
            checkActive()
            val size = input.read(buffer)
            if (size == -1) break
            if (size <= 0 || size.toLong() > receipt.bytes - bytes) throw IOException("Source length changed")
            digest.update(buffer, 0, size)
            bytes += size
        }
    }
    val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    if (bytes != receipt.bytes || hash != receipt.sha256) throw IOException("Source content changed")
}

/** Copy one file without buffering it in memory, then read the destination back and
 * compare both length and SHA-256. Neither a receipt nor a library manifest may be
 * committed until verification succeeds. The caller supplies a reasonable maximum
 * and cancellation check; calling code must run this off the UI thread. This does
 * not itself rename, sync, overwrite, clean up, detect concurrent source changes,
 * or access Android storage. */
internal fun portableVerifiedCopy(
    openSource: () -> InputStream,
    destination: PortableCopyDestination,
    maxBytes: Long,
    checkActive: () -> Unit = {},
): PortableCopyReceipt {
    require(maxBytes >= 0) { "Negative copy limit" }
    val writtenDigest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var written = 0L
    checkActive()
    openSource().use { input ->
        destination.openNew().use { output ->
            while (true) {
                checkActive()
                val size = input.read(buffer)
                if (size == -1) break
                if (size <= 0) throw IOException("Source did not make progress")
                if (size.toLong() > maxBytes - written) throw IOException("Copy exceeds size limit")
                output.write(buffer, 0, size)
                writtenDigest.update(buffer, 0, size)
                written += size
            }
            output.flush()
        }
    }
    val verifiedDigest = MessageDigest.getInstance("SHA-256")
    var verified = 0L
    checkActive()
    destination.openForVerification().use { input ->
        while (true) {
            checkActive()
            val size = input.read(buffer)
            if (size == -1) break
            if (size <= 0) throw IOException("Verification did not make progress")
            if (size.toLong() > written - verified) throw IOException("Destination length mismatch")
            verifiedDigest.update(buffer, 0, size)
            verified += size
        }
    }
    val expected = writtenDigest.digest()
    val actual = verifiedDigest.digest()
    if (verified != written || !MessageDigest.isEqual(expected, actual))
        throw IOException("Destination verification failed")
    return PortableCopyReceipt(written, actual.joinToString("") { "%02x".format(it.toInt() and 0xff) })
}
