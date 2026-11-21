package tv.orbit.shield

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Prepares ZIP metadata before output, then validates every file while extracting it once. */
internal object GameZipArchive {
    private const val MAX_ENTRIES = 10_000
    private const val MAX_DEPTH = 16
    private const val MAX_BYTES = 64L * 1024 * 1024 * 1024

    data class Entry(
        val path: String,
        val directory: Boolean,
        val size: Long,
        val compressedSize: Long,
        val crc: Long,
        val method: Int,
    )

    data class Plan(val entries: List<Entry>, val totalBytes: Long, val fileCount: Int)

    /** Identifies ordered metadata for recovery; entry CRCs still require byte verification. */
    fun fingerprint(plan: Plan): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun number(value: Long) {
            for (shift in 56 downTo 0 step 8) digest.update((value ushr shift).toByte())
        }
        fun text(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            number(bytes.size.toLong())
            digest.update(bytes)
        }
        text("gostex-zip-plan-v1")
        number(plan.entries.size.toLong())
        number(plan.totalBytes)
        number(plan.fileCount.toLong())
        for (entry in plan.entries) {
            text(entry.path)
            number(if (entry.directory) 1L else 0L)
            number(entry.size)
            number(entry.compressedSize)
            number(entry.crc)
            number(entry.method.toLong())
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun inspect(file: File, checkpoint: () -> Unit = {}): Plan =
        ZipFile(file).use { inspect(it, checkpoint) }

    private fun inspect(zip: ZipFile, checkpoint: () -> Unit): Plan {
        val entries = mutableListOf<Entry>()
        val explicitPaths = mutableSetOf<String>()
        val nodes = mutableMapOf<String, Pair<String, Boolean>>()
        var total = 0L
        var files = 0
        val input = zip.entries()
        while (input.hasMoreElements()) {
            checkpoint()
            val entry = input.nextElement()
            require(entries.size < MAX_ENTRIES) { "Too many ZIP entries" }
            val path = entry.name.removeSuffix("/")
            val parts = TorrDownloadPaths.components(path)
            require(parts.size <= MAX_DEPTH) { "ZIP path is too deep" }
            require(explicitPaths.add(path.lowercase(Locale.ROOT))) { "Duplicate ZIP entry" }
            var parent = ""
            parts.forEachIndexed { index, part ->
                parent = if (parent.isEmpty()) part else "$parent/$part"
                val directory = index < parts.lastIndex || entry.isDirectory
                val key = parent.lowercase(Locale.ROOT)
                val node = parent to directory
                val previous = nodes.putIfAbsent(key, node)
                require(previous == null || previous == node) { "Ambiguous ZIP path" }
                require(nodes.size <= MAX_ENTRIES) { "Too many ZIP documents" }
            }
            require(entry.method == ZipEntry.STORED || entry.method == ZipEntry.DEFLATED) { "Unsupported ZIP compression" }
            require(entry.size in 0..MAX_BYTES && entry.compressedSize in 0..MAX_BYTES && entry.crc in 0..0xffffffffL) {
                "Invalid ZIP entry metadata"
            }
            if (entry.isDirectory) require(entry.size == 0L && entry.crc == 0L) { "Invalid ZIP directory" }
            else {
                require(entry.size <= MAX_BYTES - total) { "Expanded ZIP is too large" }
                total += entry.size
                files++
            }
            entries += Entry(path, entry.isDirectory, entry.size, entry.compressedSize, entry.crc, entry.method)
        }
        require(files > 0) { "ZIP contains no files" }
        return Plan(entries.toList(), total, files)
    }

    /** The caller validates completed files and owns document creation, durability and recovery. */
    fun extract(
        file: File,
        expected: Plan,
        createDirectory: (String) -> Unit,
        openFile: (String) -> OutputStream,
        checkpoint: () -> Unit = {},
        onProgress: (Long) -> Unit = {},
        completed: (Entry) -> Boolean = { false },
        onFileCompleted: (Entry) -> Unit = {},
    ) {
        ZipFile(file).use { zip ->
            val current = inspect(zip, checkpoint)
            require(current == expected) { "ZIP changed after inspection" }
            val createdDirectories = mutableSetOf<String>()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            onProgress(total)
            for (entry in current.entries) {
                checkpoint()
                if (!entry.directory && completed(entry)) {
                    checkpoint()
                    total += entry.size
                    onProgress(total)
                    continue
                }
                val parts = entry.path.split('/')
                val directoryCount = if (entry.directory) parts.size else parts.size - 1
                var parent = ""
                for (index in 0 until directoryCount) {
                    parent = if (parent.isEmpty()) parts[index] else "$parent/${parts[index]}"
                    if (createdDirectories.add(parent)) {
                        checkpoint()
                        createDirectory(parent)
                    }
                }
                if (entry.directory) continue
                val archived = zip.getEntry(entry.path) ?: throw IOException("Missing ZIP entry")
                val crc = CRC32()
                var written = 0L
                zip.getInputStream(archived).use { input ->
                    checkpoint()
                    openFile(entry.path).use { output ->
                        while (true) {
                            checkpoint()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count.toLong() > entry.size - written) throw IOException("ZIP size mismatch")
                            checkpoint()
                            output.write(buffer, 0, count)
                            crc.update(buffer, 0, count)
                            written += count
                            total += count
                            onProgress(total)
                        }
                        if (written != entry.size || crc.value != entry.crc) throw IOException("ZIP checksum mismatch")
                    }
                }
                checkpoint()
                onFileCompleted(entry)
            }
            checkpoint()
            check(total == expected.totalBytes)
        }
    }
}
