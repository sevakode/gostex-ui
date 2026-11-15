package tv.orbit.shield

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract as Docs
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/** An explicit ZIP preparation operation. Each destination belongs to one attempt. */
internal object GameArchiveStorage {
    class Plan internal constructor(
        val title: String,
        internal val source: Uri,
        internal val archive: GameZipArchive.Plan,
    ) {
        val files: Int get() = archive.fileCount
        val totalBytes: Long get() = archive.totalBytes
    }

    suspend fun inspect(context: Context, source: Uri): Plan {
        val task = currentCoroutineContext()
        return runInterruptible(Dispatchers.IO) {
            check(!PortableMaintenance.running) { "Maintenance is running" }
            val resolver = context.contentResolver
            val title = resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0)?.take(256) else null
            }.orEmpty().ifBlank { "archive.zip" }
            withSource(resolver, source) { file ->
                Plan(title, source, GameZipArchive.inspect(file) { task.ensureActive() })
            }
        }
    }

    suspend fun extract(context: Context, plan: Plan, destination: Uri, onProgress: (Long) -> Unit): Uri {
        val task = currentCoroutineContext()
        return runInterruptible(Dispatchers.IO) {
            val checkpoint = {
                task.ensureActive()
                check(!PortableMaintenance.running) { "Maintenance is running" }
            }
            checkpoint()
            require(destination.scheme == ContentResolver.SCHEME_CONTENT && Docs.isTreeUri(destination))
            val target = Destination(context, destination, plan.totalBytes, checkpoint)
            var completed = false
            try {
                withSource(context.contentResolver, plan.source) { file ->
                    checkpoint()
                    val stem = plan.title.substringBeforeLast('.', plan.title).take(40)
                        .map { if (it.isISOControl() || it in "\\/:*?\"<>|") '_' else it }
                        .joinToString("").trim(' ', '.').ifBlank { "game" }
                    target.start("$stem-${UUID.randomUUID().toString().take(8)}")
                    GameZipArchive.extract(file, plan.archive,
                        createDirectory = target::directory,
                        openFile = target::open,
                        checkpoint = checkpoint,
                        onProgress = onProgress)
                    checkpoint()
                }
                val result = target.finish()
                completed = true
                result
            } finally {
                if (!completed) target.discard()
            }
        }
    }

    private inline fun <T> withSource(resolver: ContentResolver, source: Uri, block: (File) -> T): T {
        require(source.scheme == ContentResolver.SCHEME_CONTENT)
        return (resolver.openFileDescriptor(source, "r") ?: throw IOException("Cannot open ZIP")).use { descriptor ->
            if (descriptor.canDetectErrors()) descriptor.checkError()
            Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_CUR)
            val result = block(File("/proc/self/fd/${descriptor.fd}"))
            if (descriptor.canDetectErrors()) descriptor.checkError()
            result
        }
    }

    private class Destination(context: Context, private val tree: Uri, totalBytes: Long, private val checkpoint: () -> Unit) {
        private val resolver = context.contentResolver
        private val root = Docs.getTreeDocumentId(tree)
        private val reserve = TorrDownloadSpace.read(context).toLong() * 1024 * 1024
        private var remaining = totalBytes
        private val directories = mutableMapOf<String, String>()
        private val createdIds = mutableSetOf<String>()
        private var folderId: String? = null
        private var currentName = ""
        private var finalName = ""

        fun start(folderName: String) {
            finalName = folderName
            currentName = ".gostex-extract-${UUID.randomUUID()}.partial"
            val id = create(root, currentName, true)
            folderId = id
            directories[""] = id
            resolver.query(Docs.buildDocumentUriUsingTree(tree, id),
                arrayOf(Docs.Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    check(cursor.getInt(0) and Docs.Document.FLAG_SUPPORTS_RENAME != 0) {
                        "Destination cannot finish a prepared folder"
                    }
                }
            }
        }

        fun finish(): Uri {
            checkpoint()
            val id = checkNotNull(folderId)
            val before = children(root)
            check(before.singleOrNull { it.first == id }?.second == currentName) { "Prepared folder moved" }
            check(before.none { it.second.equals(finalName, ignoreCase = true) }) { "Final folder already exists" }
            val renamed = Docs.renameDocument(resolver, Docs.buildDocumentUriUsingTree(tree, id), finalName)
                ?: throw IOException("Cannot finish prepared folder")
            require(renamed.authority == tree.authority)
            val renamedId = Docs.getDocumentId(renamed)
            check(renamedId != root && before.none { it.first == renamedId && it.first != id }) { "Rename reused a document" }
            folderId = renamedId
            currentName = finalName
            createdIds.add(renamedId)
            check(children(root).singleOrNull { it.first == renamedId }?.second == finalName) { "Destination changed folder name" }
            return Docs.buildDocumentUriUsingTree(tree, renamedId)
        }

        fun directory(path: String) {
            if (path in directories) return
            val parent = path.substringBeforeLast('/', "")
            val id = create(directories.getValue(parent), path.substringAfterLast('/'), true)
            directories[path] = id
        }

        fun open(path: String): OutputStream {
            val parent = path.substringBeforeLast('/', "")
            val id = create(directories.getValue(parent), path.substringAfterLast('/'), false)
            val uri = Docs.buildDocumentUriUsingTree(tree, id)
            val descriptor = resolver.openFileDescriptor(uri, "w") ?: throw IOException("Cannot create game file")
            val output = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
            try {
                checkError(descriptor)
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

        private fun checkSpace(descriptor: ParcelFileDescriptor) {
            val stats = try { Os.fstatvfs(descriptor.fileDescriptor) } catch (_: Exception) { return }
            if (stats.f_bavail < 0 || stats.f_frsize <= 0) return
            val available = if (stats.f_bavail > Long.MAX_VALUE / stats.f_frsize) Long.MAX_VALUE
                else stats.f_bavail * stats.f_frsize
            if (available < remaining + reserve) throw IOException("Insufficient space for archive")
        }

        private fun checkError(descriptor: ParcelFileDescriptor) {
            if (descriptor.canDetectErrors()) descriptor.checkError()
        }

        private fun create(parent: String, requestedName: String, directory: Boolean): String {
            checkpoint()
            val before = children(parent)
            require(before.none { it.second.equals(requestedName, ignoreCase = true) }) { "Destination exists" }
            val mime = if (directory) Docs.Document.MIME_TYPE_DIR else "application/octet-stream"
            val parentUri = Docs.buildDocumentUriUsingTree(tree, parent)
            val uri = Docs.createDocument(resolver, parentUri, mime, requestedName)
                ?: throw IOException("Cannot create archive destination")
            require(uri.authority == tree.authority)
            val id = Docs.getDocumentId(uri)
            require(id != root && before.none { it.first == id } && createdIds.add(id)) { "Destination reused a document" }
            val actual = children(parent).singleOrNull { it.first == id }
            check(actual?.second == requestedName) { "Destination changed a file name" }
            return id
        }

        private fun children(parent: String): List<Pair<String, String>> {
            val columns = arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME)
            return resolver.query(Docs.buildChildDocumentsUriUsingTree(tree, parent), columns, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        check(size < 10000) { "Too many destination documents" }
                        add(checkNotNull(cursor.getString(0)) to checkNotNull(cursor.getString(1)))
                    }
                    check(!cursor.extras.getBoolean(Docs.EXTRA_LOADING) && cursor.extras.getString(Docs.EXTRA_ERROR) == null)
                }
            } ?: throw IOException("Cannot inspect archive destination")
        }

        fun discard() {
            val id = folderId ?: return
            runCatching {
                if (id in createdIds && children(root).singleOrNull { it.first == id }?.second == currentName) {
                    Docs.deleteDocument(resolver, Docs.buildDocumentUriUsingTree(tree, id))
                }
            }
        }
    }
}
