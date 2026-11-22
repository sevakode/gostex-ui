package tv.orbit.shield

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Docs
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/** Private evidence for recovering an ExternalStorageProvider rename that changed the document ID. */
internal object TorrFinalizeProof {
    private const val AUTHORITY = "com.android.externalstorage.documents"
    private const val FILE_PREFIX = "torr-finalize-proof-v1-"
    private const val SHA_BYTES = 32
    private const val RECORD_BYTES = SHA_BYTES * 2
    private const val READ_BYTES = 256 * 1024
    private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private val jobId = Regex(UUID_PATTERN)
    private val proofName = Regex("$FILE_PREFIX($UUID_PATTERN)(?:\\.new|\\.bak)?")

    fun supports(job: TorrDownloadJob): Boolean =
        jobId.matches(job.id) && document(job, ".gostex-${job.id}.partial")?.toString() == job.document

    fun record(context: Context, job: TorrDownloadJob, document: Uri, checkActive: () -> Unit) {
        require(isFinalizing(job) && supports(job) && document.toString() == job.document)
        val bytes = key(job) + digest(context.contentResolver, document, job, checkActive)
        checkActive()
        val proof = AtomicFile(file(context, job.id))
        val output = proof.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            checkActive()
        } catch (failure: Exception) {
            proof.failWrite(output)
            throw failure
        }
        proof.finishWrite(output)
        val directory = Os.open(context.noBackupFilesDir.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(directory)
        } finally {
            Os.close(directory)
        }
        if (!MessageDigest.isEqual(bytes, read(proof))) throw IOException("Cannot retain download finalization proof")
    }

    fun matches(context: Context, job: TorrDownloadJob, candidate: Uri, checkActive: () -> Unit): Boolean {
        if (!isFinalizing(job) || !supports(job) || document(job, job.finalName) != candidate) return false
        return try {
            checkActive()
            val proof = read(AtomicFile(file(context, job.id)))
            MessageDigest.isEqual(key(job), proof.copyOfRange(0, SHA_BYTES)) &&
                MessageDigest.isEqual(proof.copyOfRange(SHA_BYTES, RECORD_BYTES),
                    digest(context.contentResolver, candidate, job, checkActive))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: SavedVideoIntegrity.InvalidHeader) {
            throw invalid
        } catch (_: Exception) {
            checkActive()
            false
        }
    }

    fun clear(context: Context, id: String) {
        if (jobId.matches(id)) AtomicFile(file(context, id)).delete()
    }

    fun prune(context: Context, isLive: (String) -> Boolean) {
        context.noBackupFilesDir.listFiles().orEmpty().mapNotNull {
            proofName.matchEntire(it.name)?.groupValues?.get(1)
        }.distinct().forEach { id ->
            if (!isLive(id)) clear(context, id)
        }
    }

    private fun isFinalizing(job: TorrDownloadJob) =
        job.state == "finalizing" && job.total >= 0 && job.offset == job.total

    private fun file(context: Context, id: String) = File(context.noBackupFilesDir, "$FILE_PREFIX$id")

    private fun document(job: TorrDownloadJob, name: String): Uri? {
        if (name.isBlank() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it.isISOControl() }) return null
        val tree = Uri.parse(job.tree)
        val parts = tree.pathSegments
        if (tree.scheme != ContentResolver.SCHEME_CONTENT || tree.authority != AUTHORITY ||
            parts.size != 2 || parts[0] != "tree" || Docs.buildTreeDocumentUri(AUTHORITY, parts[1]) != tree) return null
        val root = parts[1]
        val parent = job.parentId.ifBlank { root }
        val rootParts = idParts(root) ?: return null
        val parentParts = idParts(parent) ?: return null
        if (parentParts.first != rootParts.first ||
            (rootParts.second.isNotEmpty() && parentParts.second != rootParts.second &&
                !parentParts.second.startsWith("${rootParts.second}/"))) return null
        val id = parent + (if (parentParts.second.isEmpty()) "" else "/") + name
        return Docs.buildDocumentUriUsingTree(tree, id)
    }

    private fun idParts(id: String): Pair<String, String>? {
        val colon = id.indexOf(':')
        if (colon <= 0) return null
        val volume = id.substring(0, colon)
        if (!volume.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' }) return null
        val path = id.substring(colon + 1)
        if (path.isNotEmpty() && path.split('/').any { it.isEmpty() || it == "." || it == ".." ||
                it.any { char -> char == '\\' || char.isISOControl() } }) return null
        return volume to path
    }

    private fun read(proof: AtomicFile): ByteArray = proof.openRead().use { input ->
        val bytes = ByteArray(RECORD_BYTES)
        var read = 0
        while (read < bytes.size) {
            val count = input.read(bytes, read, bytes.size - read)
            if (count <= 0) throw IOException("Incomplete download finalization proof")
            read += count
        }
        if (input.read() != -1) throw IOException("Unexpected download finalization proof size")
        bytes
    }

    private fun key(job: TorrDownloadJob): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            fun optional(value: String?) {
                output.writeBoolean(value != null)
                if (value != null) output.writeUTF(value)
            }
            output.writeUTF(FILE_PREFIX)
            output.writeUTF(job.id)
            val http = job.http
            output.writeBoolean(http != null)
            if (http == null) {
                output.writeUTF(job.hash)
                output.writeInt(job.index)
            } else {
                output.writeUTF(http.resourceKey)
                optional(http.expectedEtag)
                output.writeBoolean(http.sessionBound)
            }
            output.writeUTF(job.tree)
            output.writeUTF(job.document)
            output.writeUTF(job.parentId)
            output.writeBoolean(job.grouped)
            output.writeUTF(job.finalName)
            output.writeLong(job.total)
            output.writeLong(job.offset)
            optional(job.etag)
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
    }

    private fun digest(resolver: ContentResolver, document: Uri, job: TorrDownloadJob, checkActive: () -> Unit): ByteArray {
        checkActive()
        val total = job.total
        val descriptor = resolver.openFileDescriptor(document, "r") ?: throw TorrStorageFailure()
        return descriptor.use {
            val fd = it.fileDescriptor
            val before = Os.fstat(fd)
            if (!OsConstants.S_ISREG(before.st_mode) || before.st_size != total) throw TorrStorageFailure()
            Os.lseek(fd, 0, OsConstants.SEEK_CUR)
            if (it.canDetectErrors()) it.checkError()
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(READ_BYTES)
            var offset = 0L
            if (TorrPlayback.isVideo(job.finalName)) {
                val headerSize = minOf(total, SavedVideoIntegrity.HEADER_BYTES.toLong()).toInt()
                var received = 0
                while (received < headerSize) {
                    checkActive()
                    val count = Os.pread(fd, buffer, received, headerSize - received, received.toLong())
                    if (count <= 0) throw TorrStorageFailure()
                    received += count
                }
                if (!SavedVideoIntegrity.matchesHeader(job.finalName, buffer, received)) throw SavedVideoIntegrity.InvalidHeader()
                digest.update(buffer, 0, received)
                offset = received.toLong()
            }
            while (offset < total) {
                checkActive()
                val count = Os.pread(fd, buffer, 0, minOf(buffer.size.toLong(), total - offset).toInt(), offset)
                if (count <= 0) throw TorrStorageFailure()
                digest.update(buffer, 0, count)
                offset += count
            }
            if (it.canDetectErrors()) it.checkError()
            val after = Os.fstat(fd)
            if (after.st_size != total || after.st_mtime != before.st_mtime || after.st_ctime != before.st_ctime) {
                throw TorrStorageFailure()
            }
            checkActive()
            digest.digest()
        }
    }
}
