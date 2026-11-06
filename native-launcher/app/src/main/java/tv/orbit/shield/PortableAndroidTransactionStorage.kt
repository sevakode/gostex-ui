package tv.orbit.shield

import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Private-file adapter only. SharedPreferences require a separate adapter; all readers
 * and writers still need a shared exclusion gate before this can touch live state.
 * File and directory sync failures propagate. Filesystems and Android versions may
 * provide weaker power-loss guarantees than the calls report. */
internal class PortableAndroidTransactionStorage(
    private val appFiles: File,
    relativeSlots: Map<String, String>,
    journalPath: String = "portable-restore-journal.json",
) : PortableTransactionStorage {
    private val limit = 48 * 1024 * 1024
    private val root = appFiles.canonicalFile
    private val slotNames = Regex("[a-z][a-z0-9._-]{0,79}")
    private val segment = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}")
    private fun resolve(path: String): File {
        val parts = path.split('/')
        require(parts.isNotEmpty() && parts.all { segment.matches(it) && it != "." && it != ".." })
        require(!path.endsWith(".bak") && !path.endsWith(".new"))
        val file = File(root, path).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "Invalid private file path" }
        return file
    }
    private val slots = relativeSlots.mapValues { (key, path) ->
        require(slotNames.matches(key)); resolve(path)
    }
    private val journal = resolve(journalPath)
    init {
        require(slots.isNotEmpty() && slots.size <= 32)
        val targets = slots.values.toList() + journal
        require(targets.distinct().size == targets.size) { "Aliased transaction targets" }
        require(targets.none { a -> targets.any { b -> a != b && b.path.startsWith(a.path + File.separator) } })
    }
    private fun target(slot: String) = slots[slot] ?: error("Unknown transaction slot")
    private fun syncDirectory(directory: File) {
        check(directory.isDirectory) { "Private parent is not a directory" }
        val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
    private fun readFile(file: File): String? {
        val backup = File(file.path + ".bak")
        val pending = File(file.path + ".new")
        if (!file.exists() && !backup.exists()) {
            check(!pending.exists()) { "Unfinished private document" }
            return null
        }
        val bytes = AtomicFile(file).openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                check(count > 0 && count <= limit - output.size()) { "Private document too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    private fun writeFile(file: File, value: String?) {
        val parent = requireNotNull(file.parentFile)
        if (!parent.isDirectory) {
            check(parent.mkdirs() || parent.isDirectory) { "Cannot create private directory" }
            // New directories need their own parent metadata persisted as well.
            var current: File? = parent
            while (current != null && current != root) {
                syncDirectory(current)
                current = current.parentFile
            }
            syncDirectory(root)
        }
        val atomic = AtomicFile(file)
        if (value == null) {
            atomic.delete()
            check(!file.exists() && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists()) { "Private document deletion failed" }
        } else {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
            require(encoded.remaining() <= limit)
            val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
            val output = atomic.startWrite()
            try {
                output.write(bytes)
                output.fd.sync()
                atomic.finishWrite(output)
            } catch (e: Exception) {
                atomic.failWrite(output)
                throw e
            }
        }
        syncDirectory(parent)
    }
    override fun read(slot: String): String? = readFile(target(slot))
    override fun write(slot: String, value: String?) = writeFile(target(slot), value)
    override fun readJournal(): String? = readFile(journal)
    override fun writeJournal(value: String) = writeFile(journal, value)
    override fun clearJournal() = writeFile(journal, null)
}
