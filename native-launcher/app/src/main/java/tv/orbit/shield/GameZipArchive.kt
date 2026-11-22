package tv.orbit.shield

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Uses the ZIP central directory to reopen only the members that still need output. */
internal object GameZipArchive {
    fun inspect(file: File, checkpoint: () -> Unit = {}): GameArchivePlan =
        ZipFile(file).use { inspect(it, checkpoint) }

    private fun inspect(zip: ZipFile, checkpoint: () -> Unit): GameArchivePlan = checkedArchivePlan(
        GameArchiveFormat.ZIP,
        zip.entries().asSequence().map { entry ->
            require(entry.method == ZipEntry.STORED || entry.method == ZipEntry.DEFLATED) { "Unsupported ZIP compression" }
            GameArchiveEntry(entry.name.removeSuffix("/"), entry.isDirectory, entry.size, entry.compressedSize, entry.crc, entry.method)
        },
        checkpoint,
    )

    fun extract(
        file: File,
        expected: GameArchivePlan,
        createDirectory: (String) -> Unit,
        openFile: (String) -> OutputStream,
        checkpoint: () -> Unit = {},
        onProgress: (Long) -> Unit = {},
        completed: (GameArchiveEntry) -> Boolean = { false },
        onFileCompleted: (GameArchiveEntry) -> Unit = {},
    ) {
        ZipFile(file).use { zip ->
            val current = inspect(zip, checkpoint)
            require(current == expected) { "ZIP changed after inspection" }
            val directories = GameArchiveDirectories(createDirectory, checkpoint)
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
                directories.prepare(entry)
                if (entry.directory) continue
                val archived = zip.getEntry(entry.path) ?: throw IOException("Missing ZIP entry")
                zip.getInputStream(archived).use { input ->
                    checkpoint()
                    openFile(entry.path).use { output ->
                        copyGameArchiveMember(entry, buffer, input::read, { bytes, count -> output.write(bytes, 0, count) }, checkpoint) {
                            total += it
                            onProgress(total)
                        }
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
