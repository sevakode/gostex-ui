package tv.orbit.shield

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import android.provider.DocumentsContract as Docs
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/** One explicit ZIP preparation is recoverable. Keep local document grants out of backups. */
internal object GameArchiveRecovery {
    enum class Phase { EXTRACTING, RENAMING, READY }

    data class Job(
        val id: String,
        val platform: String,
        val source: Uri,
        val tree: Uri,
        val title: String,
        val fingerprint: String,
        val finalName: String,
        val totalBytes: Long,
        val stagingId: String? = null,
        val phase: Phase = Phase.EXTRACTING,
        val finalId: String? = null,
    ) {
        val stagingName: String get() = ".gostex-extract-$id.partial"
        override fun toString(): String = "Archive preparation (${phase.name})"
    }

    private const val MAGIC = 0x47415A52
    private const val VERSION = 1
    private const val MAX_RECORD_BYTES = 64 * 1024
    private const val MAX_EXPANDED_BYTES = 64L * 1024 * 1024 * 1024
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val fingerprint = Regex("[0-9a-f]{64}")

    private fun file(context: Context) = File(context.noBackupFilesDir, "game-archive-recovery")

    @Synchronized
    fun read(context: Context): Job? {
        val file = file(context)
        // An interrupted first write cannot have started creating SAF documents.
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return try {
            AtomicFile(file).openRead().use { input ->
                require(input.channel.size() in 1..MAX_RECORD_BYTES.toLong())
                DataInputStream(BufferedInputStream(input)).use { data ->
                    require(data.readInt() == MAGIC && data.readInt() == VERSION)
                    val job = Job(
                        id = data.readUTF(),
                        platform = data.readUTF(),
                        source = Uri.parse(data.readUTF()),
                        tree = Uri.parse(data.readUTF()),
                        title = data.readUTF(),
                        fingerprint = data.readUTF(),
                        finalName = data.readUTF(),
                        totalBytes = data.readLong(),
                        stagingId = data.readUTF().ifEmpty { null },
                        phase = Phase.valueOf(data.readUTF()),
                        finalId = data.readUTF().ifEmpty { null },
                    )
                    require(data.read() == -1)
                    validate(job)
                    job
                }
            }
        } catch (_: Exception) {
            throw IOException("Invalid archive preparation record")
        }
    }

    @Synchronized
    fun begin(context: Context, job: Job) {
        validate(job)
        if (job.phase != Phase.EXTRACTING || job.stagingId != null || read(context) != null) {
            throw IOException("An archive preparation already exists")
        }
        write(context, job)
    }

    @Synchronized
    fun update(context: Context, expected: Job, replacement: Job) {
        if (read(context) != expected) throw IOException("Archive preparation changed")
        if (expected == replacement) return
        validate(replacement)
        if (replacement.copy(stagingId = expected.stagingId, phase = expected.phase, finalId = expected.finalId) != expected ||
            !allowsTransition(expected, replacement)) {
            throw IOException("Invalid archive preparation transition")
        }
        write(context, replacement)
    }

    private fun allowsTransition(expected: Job, replacement: Job): Boolean = when (expected.phase) {
        Phase.EXTRACTING -> when (replacement.phase) {
            Phase.EXTRACTING -> expected.stagingId == null && replacement.stagingId != null
            Phase.RENAMING -> expected.stagingId != null && replacement.stagingId == expected.stagingId
            Phase.READY -> false
        }
        Phase.RENAMING -> replacement.phase == Phase.READY && replacement.stagingId == expected.stagingId
        Phase.READY -> false
    }

    @Synchronized
    fun clear(context: Context, expected: Job) {
        val file = file(context)
        if (read(context) != expected) throw IOException("Archive preparation changed")
        AtomicFile(file).delete()
        if (file.exists() || File(file.path + ".new").exists() || File(file.path + ".bak").exists()) {
            throw IOException("Could not clear archive preparation record")
        }
        syncDirectory(file)
    }

    private fun write(context: Context, job: Job) {
        val record = ByteArrayOutputStream()
        DataOutputStream(record).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeUTF(job.id)
            data.writeUTF(job.platform)
            data.writeUTF(job.source.toString())
            data.writeUTF(job.tree.toString())
            data.writeUTF(job.title)
            data.writeUTF(job.fingerprint)
            data.writeUTF(job.finalName)
            data.writeLong(job.totalBytes)
            data.writeUTF(job.stagingId.orEmpty())
            data.writeUTF(job.phase.name)
            data.writeUTF(job.finalId.orEmpty())
        }
        if (record.size() > MAX_RECORD_BYTES) throw IOException("Archive preparation record is too large")
        val file = file(context)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            record.writeTo(output)
            output.fd.sync()
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
        atomic.finishWrite(output)
        syncDirectory(file)
        if (read(context) != job) throw IOException("Could not save archive preparation record")
    }

    private fun syncDirectory(file: File) {
        val fd = Os.open(requireNotNull(file.parent), OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }

    private fun validate(job: Job) {
        val sourceIds = job.source.pathSegments
        val sourceValid = validUri(job.source) && when (sourceIds.size) {
            2 -> sourceIds[0] == "document" && validDocumentId(sourceIds[1])
            4 -> sourceIds[0] == "tree" && validDocumentId(sourceIds[1]) &&
                sourceIds[2] == "document" && validDocumentId(sourceIds[3])
            else -> false
        }
        val destinationIds = job.tree.pathSegments
        val treeValid = validUri(job.tree) && destinationIds.size == 2 && destinationIds[0] == "tree" &&
            validDocumentId(destinationIds[1])
        val phaseValid = when (job.phase) {
            Phase.EXTRACTING -> job.finalId == null
            Phase.RENAMING -> job.stagingId != null && job.finalId == null
            Phase.READY -> job.stagingId != null && job.finalId != null
        }
        val finalNameValid = !job.finalName.startsWith(".gostex-extract-", ignoreCase = true) &&
            TorrDownloadPaths.components(job.finalName).size == 1
        if (!uuid.matches(job.id) || PlatformRegistry.find(job.platform) == null || !sourceValid || !treeValid ||
            job.title.isBlank() || job.title.length > 256 || job.title.any { it.isISOControl() } ||
            !fingerprint.matches(job.fingerprint) || !finalNameValid || !phaseValid || job.totalBytes !in 0..MAX_EXPANDED_BYTES ||
            listOfNotNull(job.stagingId, job.finalId).any { !validDocumentId(it) || it == destinationIds.last() }) {
            throw IOException("Invalid archive preparation record")
        }
    }

    private fun validUri(uri: Uri): Boolean = uri.scheme == ContentResolver.SCHEME_CONTENT &&
        !uri.authority.isNullOrBlank() && uri.query == null && uri.fragment == null && uri.toString().length <= 8192

    private fun validDocumentId(id: String): Boolean = id.isNotBlank() && id.length <= 4096 && id.none { it.isISOControl() }

    /** Use the permissions selected for this operation or already retained by the downloads queue. */
    fun retainAccess(context: Context, job: Job): Boolean {
        // A provider can return a temporary content URI even when it cannot retain a document grant.
        if (runCatching { validate(job) }.isFailure) return false
        val resolver = context.contentResolver
        val grants = resolver.persistedUriPermissions
        val sourceRetained = sourceGranted(grants, job.source)
        val destinationRetained = destinationGranted(grants, job.tree)
        if (sourceRetained && destinationRetained) return true
        if (!sourceRetained && !take(resolver, job.source, Intent.FLAG_GRANT_READ_URI_PERMISSION)) return false
        if (!destinationRetained &&
            !take(resolver, job.tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) return false
        return hasAccess(context, job)
    }

    fun hasAccess(context: Context, job: Job): Boolean {
        val grants = context.contentResolver.persistedUriPermissions
        return sourceGranted(grants, job.source) && destinationGranted(grants, job.tree)
    }

    fun hasDestinationAccess(context: Context, job: Job): Boolean =
        destinationGranted(context.contentResolver.persistedUriPermissions, job.tree)

    private fun sourceGranted(grants: List<UriPermission>, source: Uri): Boolean = grants.any { grant ->
        grant.isReadPermission && (grant.uri == source || inGrantedTree(source, grant.uri))
    }

    private fun inGrantedTree(source: Uri, tree: Uri): Boolean {
        if (source.scheme != tree.scheme || source.authority != tree.authority || !Docs.isTreeUri(tree)) return false
        // The DocumentsProvider enforces descendant access when the caller opens this tree URI.
        val parts = tree.pathSegments
        val document = source.pathSegments
        return parts.size == 2 && document.size == 4 && document[2] == "document" && document.take(2) == parts
    }

    private fun destinationGranted(grants: List<UriPermission>, tree: Uri): Boolean = grants.any {
        it.uri == tree && it.isReadPermission && it.isWritePermission
    }

    private fun take(resolver: ContentResolver, uri: Uri, flags: Int): Boolean = try {
        resolver.takePersistableUriPermission(uri, flags)
        true
    } catch (_: SecurityException) {
        false
    }
}
