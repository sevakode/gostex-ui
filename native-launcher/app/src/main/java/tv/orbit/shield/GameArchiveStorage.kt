package tv.orbit.shield

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract as Docs
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.SeekableByteChannel
import java.util.Locale
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.CRC32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

/** Extract archive members into an owned SAF directory and publish it after verification. */
internal object GameArchiveStorage {
    class Plan internal constructor(
        val title: String,
        internal val source: Uri,
        internal val archive: GameArchivePlan,
    ) {
        val files: Int get() = archive.fileCount
        val totalBytes: Long get() = archive.totalBytes
    }

    private val operation = ReentrantLock()
    private const val MAX_SOURCE_BYTES = 64L * 1024 * 1024 * 1024

    suspend fun inspect(context: Context, source: Uri): Plan =
        perform { checkpoint ->
            val resolver = context.contentResolver
            val title = resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0)?.take(256) else null
            }.orEmpty().map { if (it.isISOControl()) '_' else it }.joinToString("")
            withSource(context, source, checkpoint) { input ->
                val archive = input.inspect(checkpoint)
                val fallback = if (archive.format == GameArchiveFormat.ZIP) "archive.zip" else "archive.7z"
                Plan(title.ifBlank { fallback }, source, archive)
            }
        }

    suspend fun extract(
        context: Context, plan: Plan, destination: Uri, platform: String, onProgress: (Long) -> Unit,
    ): Uri = perform { checkpoint ->
        check(GameArchiveRecovery.read(context) == null) { "Another archive must be resolved first" }
        require(destination.scheme == ContentResolver.SCHEME_CONTENT && Docs.isTreeUri(destination))
        val id = UUID.randomUUID().toString()
        val stem = plan.title.substringBeforeLast('.', plan.title).take(40)
            .map { if (it.isISOControl() || Character.isSurrogate(it) || it in "\\/:*?\"<>|") '_' else it }
            .joinToString("").trim(' ', '.').ifBlank { "game" }
        val job = GameArchiveRecovery.Job(
            id = id, platform = platform, source = plan.source, tree = destination, title = plan.title,
            fingerprint = gameArchiveFingerprint(plan.archive), finalName = "$stem-${id.take(8)}",
            totalBytes = plan.totalBytes,
        )
        val durable = GameArchiveRecovery.retainAccess(context, job)
        if (durable) GameArchiveRecovery.begin(context, job)
        prepare(context, job, plan.archive, durable, checkpoint, onProgress)
    }

    suspend fun resume(context: Context, job: GameArchiveRecovery.Job, onProgress: (Long) -> Unit): Uri =
        perform { checkpoint ->
            check(GameArchiveRecovery.read(context) == job) { "Archive preparation changed" }
            if (job.phase == GameArchiveRecovery.Phase.READY) {
                return@perform Destination(context, job, checkpoint).ready()
            }
            check(GameArchiveRecovery.hasAccess(context, job)) { "Archive or destination access is missing" }
            prepare(context, job, null, true, checkpoint, onProgress)
        }

    suspend fun discard(context: Context, job: GameArchiveRecovery.Job) = perform { checkpoint ->
        check(GameArchiveRecovery.read(context) == job) { "Archive preparation changed" }
        if (job.phase != GameArchiveRecovery.Phase.READY) {
            check(GameArchiveRecovery.hasDestinationAccess(context, job)) { "Destination access is missing" }
            val target = Destination(context, job, checkpoint)
            val staging = target.stagingForDiscard()
            if (staging != null) {
                if (!target.isEmpty(staging)) {
                    val plan = withSource(context, job.source, checkpoint) { input -> input.inspect(checkpoint) }
                    verifyPlan(job, plan)
                    target.useFolder(staging, plan, recovered = true, complete = false, verifyContents = false)
                }
                target.deleteStaging()
            }
        }
        GameArchiveRecovery.clear(context, job)
    }

    private suspend fun <T> perform(block: (() -> Unit) -> T): T {
        val task = currentCoroutineContext()
        return runInterruptible(Dispatchers.IO) {
            check(operation.tryLock()) { "Another archive operation is running" }
            try {
                val checkpoint = {
                    task.ensureActive()
                    check(!PortableMaintenance.running) { "Maintenance is running" }
                }
                checkpoint()
                block(checkpoint)
            } finally {
                operation.unlock()
            }
        }
    }

    private fun prepare(
        context: Context,
        initial: GameArchiveRecovery.Job,
        preview: GameArchivePlan?,
        durable: Boolean,
        checkpoint: () -> Unit,
        onProgress: (Long) -> Unit,
    ): Uri {
        var job = initial
        val target = Destination(context, job, checkpoint)
        var finished = false
        fun record(replacement: GameArchiveRecovery.Job) {
            if (durable) GameArchiveRecovery.update(context, job, replacement)
            job = replacement
        }
        try {
            val alreadyRenamed = withSource(context, job.source, checkpoint) { input ->
                val plan = preview ?: input.inspect(checkpoint)
                verifyPlan(job, plan)
                check(input.format == plan.format) { "Source archive format changed" }
                if (job.phase == GameArchiveRecovery.Phase.RENAMING) {
                    val final = target.recoverRenamed()
                    if (final != null) {
                        target.useFolder(final, plan, recovered = true, complete = true)
                        onProgress(plan.totalBytes)
                        return@withSource final
                    }
                }
                val existing = target.findStaging()
                val staging = existing ?: target.createStaging()
                if (job.stagingId == null) record(job.copy(stagingId = staging.id))
                target.useFolder(staging, plan, recovered = existing != null, complete = false)
                input.extract(plan, target, checkpoint, onProgress)
                checkpoint()
                null
            }
            if (job.phase == GameArchiveRecovery.Phase.EXTRACTING) {
                record(job.copy(phase = GameArchiveRecovery.Phase.RENAMING))
            }
            val ready = alreadyRenamed ?: target.finish()
            record(job.copy(phase = GameArchiveRecovery.Phase.READY, finalId = ready.id))
            finished = true
            return target.uri(ready.id)
        } finally {
            if (!finished && !durable) runCatching { target.deleteStaging() }
        }
    }

    private class Source(
        val format: GameArchiveFormat,
        private val channel: SeekableByteChannel,
        private val zipFile: File? = null,
    ) {
        fun inspect(checkpoint: () -> Unit): GameArchivePlan = when (format) {
            GameArchiveFormat.ZIP -> GameZipArchive.inspect(checkNotNull(zipFile), checkpoint)
            GameArchiveFormat.SEVEN_Z -> GameSevenZArchive.inspect(channel.position(0), checkpoint)
        }

        fun extract(plan: GameArchivePlan, target: Destination, checkpoint: () -> Unit, onProgress: (Long) -> Unit) {
            val completed: (GameArchiveEntry) -> Boolean = { it.path in target.completed }
            val promote: (GameArchiveEntry) -> Unit = { target.promote(it.path) }
            when (format) {
                GameArchiveFormat.ZIP -> GameZipArchive.extract(checkNotNull(zipFile), plan,
                    target::directory, target::open, checkpoint, onProgress, completed, promote)
                GameArchiveFormat.SEVEN_Z -> GameSevenZArchive.extract(channel.position(0), plan,
                    target::directory, target::open, checkpoint, onProgress, completed, promote)
            }
        }
    }

    private fun <T> withSource(context: Context, source: Uri, checkpoint: () -> Unit, block: (Source) -> T): T {
        require(source.scheme == ContentResolver.SCHEME_CONTENT)
        val scratch = File(context.cacheDir, "archive-preparation-source.zip")
        // A force-stop may have interrupted the previous provider copy.
        if (scratch.exists() && !scratch.delete()) throw IOException("Cannot reset archive cache")
        return (context.contentResolver.openFileDescriptor(source, "r") ?: throw IOException("Cannot open archive")).use { descriptor ->
            checkError(descriptor)
            val seekable = try {
                Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
                true
            } catch (_: ErrnoException) { false }
            val result = if (seekable) {
                val duplicate = ParcelFileDescriptor.dup(descriptor.fileDescriptor)
                ParcelFileDescriptor.AutoCloseInputStream(duplicate).use { stream ->
                    val channel = stream.channel
                    val format = GameArchiveFormat.detect(channel)
                    val path = File("/proc/self/fd/${descriptor.fd}")
                    when {
                        format == GameArchiveFormat.SEVEN_Z -> block(Source(format, channel))
                        canReopen(path) -> block(Source(format, channel, path))
                        else -> withSourceCopy(context, descriptor, scratch, checkpoint, block)
                    }
                }
            } else {
                withSourceCopy(context, descriptor, scratch, checkpoint, block)
            }
            checkError(descriptor)
            result
        }
    }

    private fun canReopen(path: File): Boolean = try {
        Os.close(Os.open(path.path, OsConstants.O_RDONLY, 0))
        true
    } catch (_: ErrnoException) { false }

    private fun <T> withSourceCopy(
        context: Context, descriptor: ParcelFileDescriptor, scratch: File,
        checkpoint: () -> Unit, block: (Source) -> T,
    ): T = try {
        // ZipFile requires a seekable path. Some providers grant a readable descriptor
        // without allowing it to be reopened; pipes require a copy for either format.
        copySource(context, descriptor, scratch, checkpoint)
        scratch.inputStream().use { stream ->
            block(Source(GameArchiveFormat.detect(stream.channel), stream.channel, scratch))
        }
    } finally {
        scratch.delete()
    }

    private fun copySource(context: Context, descriptor: ParcelFileDescriptor, scratch: File, checkpoint: () -> Unit) {
        val reserve = TorrDownloadSpace.read(context).toLong() * 1024 * 1024
        val declared = descriptor.statSize
        if (declared > MAX_SOURCE_BYTES) throw IOException("Archive source is too large")
        if (declared > context.cacheDir.usableSpace - reserve) throw TorrDownloadLowSpace()
        val duplicate = ParcelFileDescriptor.dup(descriptor.fileDescriptor)
        ParcelFileDescriptor.AutoCloseInputStream(duplicate).use { input ->
            scratch.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                var remainingBudget = 0L
                while (true) {
                    checkpoint()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count.toLong() > MAX_SOURCE_BYTES - total) throw IOException("Archive source is too large")
                    if (remainingBudget < count) {
                        remainingBudget = minOf(context.cacheDir.usableSpace - reserve, 4L * 1024 * 1024)
                        if (remainingBudget < count) throw TorrDownloadLowSpace()
                    }
                    output.write(buffer, 0, count)
                    total += count
                    remainingBudget -= count
                }
                if (declared >= 0 && declared != total) throw IOException("Archive source changed")
                checkError(descriptor)
            }
        }
    }

    private fun checkError(descriptor: ParcelFileDescriptor) {
        if (descriptor.canDetectErrors()) descriptor.checkError()
    }

    private fun verifyPlan(job: GameArchiveRecovery.Job, plan: GameArchivePlan) {
        check(plan.totalBytes == job.totalBytes && gameArchiveFingerprint(plan) == job.fingerprint) {
            "Source archive changed"
        }
    }

    private data class Document(val id: String, val name: String, val directory: Boolean)
    private data class Member(val entry: GameArchiveEntry, val scratchName: String)
    private data class Expected(val name: String, val directory: Boolean, val member: Member?, val scratch: Boolean = false)

    private class Destination(
        context: Context,
        private val job: GameArchiveRecovery.Job,
        private val checkpoint: () -> Unit,
    ) {
        private val tree = job.tree
        private val resolver = context.contentResolver
        private val root = Docs.getTreeDocumentId(tree)
        private val reserve = TorrDownloadSpace.read(context).toLong() * 1024 * 1024
        private var remaining = 0L
        private var staging: Document? = null
        private val directories = mutableMapOf<String, String>()
        private val index = mutableMapOf<String, Directory>()
        private val knownIds = mutableSetOf(root)
        private val members = mutableMapOf<String, Member>()
        private val expected = mutableMapOf<String, MutableMap<String, Expected>>()
        val completed = mutableSetOf<String>()

        private class Directory {
            private val byName = linkedMapOf<String, MutableList<Document>>()
            val documents: List<Document> get() = byName.values.flatten()

            operator fun get(name: String): Document? {
                val matches = byName[key(name)] ?: return null
                check(matches.size == 1) { "Ambiguous destination documents" }
                return matches[0]
            }

            fun add(row: Document) { byName.getOrPut(key(row.name)) { mutableListOf() }.add(row) }
            fun remove(row: Document) { byName.remove(key(row.name)) }
            fun isEmpty(): Boolean = byName.isEmpty()
        }

        fun uri(id: String): Uri = Docs.buildDocumentUriUsingTree(tree, id)
        fun isEmpty(folder: Document): Boolean = children(folder.id).isEmpty()

        fun ready(): Uri {
            val row = children(root)[job.finalName] ?: throw IOException("Prepared folder is missing")
            check(row.id == job.finalId && row.name == job.finalName && row.directory) { "Prepared folder moved" }
            requireChild(root, row)
            return uri(row.id)
        }

        fun findStaging(): Document? {
            val rootRows = children(root)
            check(job.finalId == null && rootRows[job.finalName] == null) { "Final folder already exists" }
            val row = rootRows[job.stagingName]
            if (row == null) {
                check(job.stagingId == null) { "Preparation folder moved or is missing" }
                return null
            }
            check(row.directory && row.name == job.stagingName) { "Unexpected preparation folder" }
            requireChild(root, row)
            if (job.stagingId == null) {
                // The journal is saved before createDocument. A crash between create and recording
                // its ID can leave just this empty, uniquely named directory.
                check(children(row.id).isEmpty()) { "Unregistered preparation has files" }
            } else check(row.id == job.stagingId) { "Preparation folder was replaced" }
            staging = row
            return row
        }

        fun recoverRenamed(): Document? {
            val rootRows = children(root)
            val final = rootRows[job.finalName] ?: return null
            check(rootRows[job.stagingName] == null && final.name == job.finalName && final.directory) {
                "Cannot verify interrupted rename"
            }
            // ExternalStorageProvider changes a path-based ID on rename. Other providers keep it.
            // In both cases the random final name is already recorded, and the full contents are checked.
            requireChild(root, final)
            return final
        }

        fun createStaging(): Document {
            val row = create(root, job.stagingName, directory = true)
            staging = row
            resolver.query(uri(row.id), arrayOf(Docs.Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    check(cursor.getInt(0) and Docs.Document.FLAG_SUPPORTS_RENAME != 0) {
                        "Destination cannot finish a prepared folder"
                    }
                }
            }
            return row
        }

        fun useFolder(
            folder: Document, plan: GameArchivePlan, recovered: Boolean, complete: Boolean, verifyContents: Boolean = true,
        ) {
            remaining = plan.totalBytes
            directories[""] = folder.id
            for ((number, entry) in plan.entries.withIndex()) {
                checkpoint()
                val parts = entry.path.split('/')
                var parent = ""
                val directoryCount = if (entry.directory) parts.size else parts.size - 1
                for (part in parts.take(directoryCount)) {
                    expect(parent, Expected(part, true, null))
                    parent = if (parent.isEmpty()) part else "$parent/$part"
                }
                if (entry.directory) continue
                val member = Member(entry, ".gostex-${job.id}-$number.partial")
                members[entry.path] = member
                expect(parent, Expected(parts.last(), false, member))
                expect(parent, Expected(member.scratchName, false, member, true))
            }
            if (recovered) {
                val buffer = if (verifyContents) ByteArray(64 * 1024) else null
                validateExisting("", folder.id, complete, buffer)
                if (complete) check(remaining == 0L && completed.size == plan.fileCount) { "Prepared files are missing" }
            }
        }

        private fun expect(parent: String, row: Expected) {
            val previous = expected.getOrPut(parent) { mutableMapOf() }.putIfAbsent(key(row.name), row)
            check(previous == null || (row.directory && previous == row)) { "Archive member conflicts with a temporary name" }
        }

        private fun validateExisting(path: String, parent: String, complete: Boolean, buffer: ByteArray?) {
            val expectedChildren = expected[path].orEmpty()
            val existing = children(parent)
            for (row in existing.documents) {
                checkpoint()
                check(existing[row.name] == row) { "Ambiguous preparation document" }
                val wanted = expectedChildren[key(row.name)] ?: throw IOException("Unexpected document in preparation")
                check(wanted.name == row.name && wanted.directory == row.directory) { "Unexpected preparation document" }
                requireChild(parent, row)
                if (wanted.scratch) {
                    val outputName = checkNotNull(wanted.member).entry.path.substringAfterLast('/')
                    check(!complete && existing[outputName] == null) { "Temporary document conflicts with an output" }
                } else if (wanted.directory) {
                    val child = if (path.isEmpty()) row.name else "$path/${row.name}"
                    directories[child] = row.id
                    validateExisting(child, row.id, complete, buffer)
                } else if (buffer != null) {
                    val entry = checkNotNull(wanted.member).entry
                    verifyFile(row, entry, buffer)
                    remaining -= entry.size
                    completed += entry.path
                }
            }
            if (complete) check(expectedChildren.values.all { it.scratch || existing[it.name] != null }) {
                "Prepared documents are missing"
            }
        }

        private fun verifyFile(row: Document, entry: GameArchiveEntry, buffer: ByteArray) {
            val descriptor = resolver.openFileDescriptor(uri(row.id), "r") ?: throw IOException("Cannot verify archive member")
            val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            stream.use { input ->
                checkError(descriptor)
                var size = 0L
                val crc = CRC32()
                while (true) {
                    checkpoint()
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    check(size <= entry.size) { "Prepared file is too long" }
                    crc.update(buffer, 0, count)
                }
                checkError(descriptor)
                check(size == entry.size && crc.value == entry.crc) { "Prepared file changed" }
            }
        }

        fun directory(path: String) {
            if (path in directories) return
            val parent = directories.getValue(path.substringBeforeLast('/', ""))
            directories[path] = create(parent, path.substringAfterLast('/'), directory = true).id
        }

        fun open(path: String): OutputStream {
            val parent = directories.getValue(path.substringBeforeLast('/', ""))
            val scratchName = members.getValue(path).scratchName
            val row = children(parent)[scratchName]?.also { requireChild(parent, it) }
                ?: create(parent, scratchName, directory = false)
            val descriptor = resolver.openFileDescriptor(uri(row.id), "rwt") ?: throw IOException("Cannot write archive member")
            val output = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
            try {
                checkError(descriptor)
                check(Os.fstat(descriptor.fileDescriptor).st_size == 0L) { "Temporary file could not be reset" }
                checkSpace(descriptor)
            } catch (error: Exception) {
                output.close()
                throw error
            }
            return object : OutputStream() {
                private var untilSpaceCheck = 4L * 1024 * 1024
                private var closed = false
                override fun write(value: Int) = write(byteArrayOf(value.toByte()))
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    checkpoint()
                    if (untilSpaceCheck <= 0) {
                        checkSpace(descriptor)
                        untilSpaceCheck = 4L * 1024 * 1024
                    }
                    output.write(bytes, offset, length)
                    remaining -= length
                    untilSpaceCheck -= length
                }
                override fun close() {
                    if (closed) return
                    closed = true
                    try {
                        output.flush()
                        Os.fsync(descriptor.fileDescriptor)
                        checkError(descriptor)
                    } finally {
                        output.close()
                    }
                }
            }
        }

        fun promote(path: String) {
            val parent = directories.getValue(path.substringBeforeLast('/', ""))
            val temporary = checkNotNull(children(parent)[members.getValue(path).scratchName])
            rename(parent, temporary, path.substringAfterLast('/'))
        }

        fun finish(): Document {
            checkpoint()
            check(remaining == 0L) { "Preparation did not write all bytes" }
            val row = checkNotNull(staging)
            // Refresh this single parent before exposing the result. Member operations use
            // one initial listing per directory plus direct metadata queries for changes.
            index.remove(root)?.documents?.forEach { knownIds.remove(it.id) }
            check(children(root)[job.stagingName] == row) { "Prepared folder moved" }
            return rename(root, row, job.finalName).also { staging = null }
        }

        fun stagingForDiscard(): Document? {
            val rows = children(root)
            if (rows[job.stagingName] == null) return null
            return findStaging()
        }

        fun deleteStaging() {
            val row = staging ?: return
            check(row.name == job.stagingName && row.directory)
            if (readChildren(root)[job.stagingName] != row) throw IOException("Preparation folder moved")
            requireChild(root, row)
            check(Docs.deleteDocument(resolver, uri(row.id))) { "Cannot remove temporary preparation" }
            staging = null
        }

        private fun checkSpace(descriptor: ParcelFileDescriptor) {
            val stats = try { Os.fstatvfs(descriptor.fileDescriptor) } catch (_: Exception) { return }
            if (stats.f_bavail < 0 || stats.f_frsize <= 0) return
            val available = if (stats.f_bavail > Long.MAX_VALUE / stats.f_frsize) Long.MAX_VALUE
                else stats.f_bavail * stats.f_frsize
            if (available < remaining + reserve) throw IOException("Insufficient space for archive")
        }

        private fun create(parent: String, name: String, directory: Boolean): Document {
            checkpoint()
            val rows = children(parent)
            require(rows[name] == null) { "Destination exists" }
            val type = if (directory) Docs.Document.MIME_TYPE_DIR else "application/octet-stream"
            val result = Docs.createDocument(resolver, uri(parent), type, name) ?: throw IOException("Cannot create archive document")
            val row = returned(parent, result, name, directory)
            check(knownIds.add(row.id)) { "Destination reused a document" }
            rows.add(row)
            return row
        }

        private fun rename(parent: String, old: Document, name: String): Document {
            checkpoint()
            val rows = children(parent)
            check(rows[old.name] == old && rows[name] == null) { "Destination changed" }
            requireChild(parent, old)
            val result = Docs.renameDocument(resolver, uri(old.id), name) ?: throw IOException("Cannot rename archive document")
            val row = returned(parent, result, name, old.directory)
            check(row.id == old.id || knownIds.add(row.id)) { "Rename reused a document" }
            rows.remove(old)
            rows.add(row)
            return row
        }

        private fun returned(parent: String, result: Uri, name: String, directory: Boolean): Document {
            require(result.scheme == ContentResolver.SCHEME_CONTENT && result.authority == tree.authority)
            val id = Docs.getDocumentId(result)
            val row = Document(id, name, directory)
            requireChild(parent, row)
            return row
        }

        private fun requireChild(parent: String, row: Document) {
            check(row.id.isNotBlank() && row.id != root && row.id != parent) { "Invalid archive document" }
            val actual = resolver.query(uri(row.id), DOCUMENT_COLUMNS, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) null else {
                    val document = document(cursor)
                    check(!cursor.moveToNext()) { "Ambiguous archive document" }
                    document
                }
            }
            check(actual == row) { "Archive document changed" }
            if (Build.VERSION.SDK_INT >= 29) {
                check(Docs.isChildDocument(resolver, uri(parent), uri(row.id))) { "Document moved outside its folder" }
                val stagingId = staging?.id
                if (parent != root && stagingId != null && parent != stagingId) {
                    check(Docs.isChildDocument(resolver, uri(stagingId), uri(parent))) { "Folder moved outside preparation" }
                }
            }
        }

        private fun children(parent: String): Directory = index.getOrPut(parent) {
            readChildren(parent).also { rows ->
                for (row in rows.documents) check(knownIds.add(row.id)) { "Repeated archive document ID" }
            }
        }

        private fun readChildren(parent: String): Directory {
            val children = Docs.buildChildDocumentsUriUsingTree(tree, parent)
            return resolver.query(children, DOCUMENT_COLUMNS, null, null, null)?.use { cursor ->
                val rows = Directory()
                val ids = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    check(ids.size < 10000) { "Too many destination documents" }
                    val row = document(cursor)
                    check(ids.add(row.id)) { "Ambiguous destination document ID" }
                    rows.add(row)
                }
                check(!cursor.extras.getBoolean(Docs.EXTRA_LOADING) && cursor.extras.getString(Docs.EXTRA_ERROR) == null)
                rows
            } ?: throw IOException("Cannot inspect archive destination")
        }

        private fun document(cursor: android.database.Cursor): Document {
            val id = checkNotNull(cursor.getString(0))
            val name = checkNotNull(cursor.getString(1))
            return Document(id, name, checkNotNull(cursor.getString(2)) == Docs.Document.MIME_TYPE_DIR)
        }

    }

    private fun key(name: String): String = name.lowercase(Locale.ROOT)
    private val DOCUMENT_COLUMNS = arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME, Docs.Document.COLUMN_MIME_TYPE)
}
