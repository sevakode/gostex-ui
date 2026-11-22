package tv.orbit.shield

import java.io.IOException
import java.io.OutputStream
import java.nio.channels.SeekableByteChannel
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile

/** Reads each solid stream in order; a resumed extraction verifies skipped output without rewriting it. */
internal object GameSevenZArchive {
    fun inspect(channel: SeekableByteChannel, checkpoint: () -> Unit): GameArchivePlan {
        val header = GameSevenZHeader.read(channel, checkpoint)
        return open(header.input).use { it.plan(header, checkpoint) }
    }

    fun extract(
        channel: SeekableByteChannel,
        expected: GameArchivePlan,
        createDirectory: (String) -> Unit,
        openFile: (String) -> OutputStream,
        checkpoint: () -> Unit = {},
        onProgress: (Long) -> Unit = {},
        completed: (GameArchiveEntry) -> Boolean = { false },
        onFileCompleted: (GameArchiveEntry) -> Unit = {},
    ) {
        val header = GameSevenZHeader.read(channel, checkpoint)
        open(header.input).use { archive ->
            val plan = archive.plan(header, checkpoint)
            require(plan == expected) { "7z archive changed after inspection" }
            val directories = GameArchiveDirectories(createDirectory, checkpoint)
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            onProgress(total)
            val update: (Int) -> Unit = { bytes ->
                total += bytes
                onProgress(total)
            }
            for (entry in plan.entries) {
                checkpoint()
                if (archive.nextEntry?.checkedEntry(entry.crc) != entry) throw IOException("7z member order changed")
                if (entry.directory) {
                    directories.prepare(entry)
                } else if (completed(entry)) {
                    copyGameArchiveMember(entry, buffer, archive::read, null, checkpoint, update)
                } else {
                    directories.prepare(entry)
                    checkpoint()
                    openFile(entry.path).use { output ->
                        copyGameArchiveMember(entry, buffer, archive::read,
                            { bytes, count -> output.write(bytes, 0, count) }, checkpoint, update)
                    }
                    checkpoint()
                    onFileCompleted(entry)
                }
            }
            checkpoint()
            check(total == plan.totalBytes)
        }
    }

    private fun open(channel: SeekableByteChannel): SevenZFile = SevenZFile.builder()
        .setSeekableByteChannel(channel)
        .setMaxMemoryLimitKiB(GameSevenZHeader.DECODER_MEMORY_KIB)
        .setTryToRecoverBrokenArchives(false)
        .get()

    private fun SevenZFile.plan(header: GameSevenZHeader.Snapshot, checkpoint: () -> Unit): GameArchivePlan {
        var streamIndex = 0
        val members = entries.asSequence().map { entry ->
            // Commons Compress omits inherited folder CRCs when SubStreamsInfo has no CRC property.
            val folderCrc = if (entry.hasStream()) header.folderCrcs.getOrNull(streamIndex++) else null
            entry.checkedEntry(folderCrc)
        }
        val plan = checkedArchivePlan(GameArchiveFormat.SEVEN_Z, members, checkpoint, header.identity)
        if (streamIndex != header.folderCrcs.size) throw IOException("7z member stream count mismatch")
        return plan
    }

    private fun SevenZArchiveEntry.checkedEntry(folderCrc: Long?): GameArchiveEntry {
        if (isAntiItem) throw IOException("7z anti-items are unsupported")
        if (hasWindowsAttributes) {
            // 7-Zip stores UNIX mode in the upper word and DOS attributes in the lower word.
            val posixType = (windowsAttributes ushr 16) and 0xf000
            val expectedType = if (isDirectory) 0x4000 else 0x8000
            if (windowsAttributes and WINDOWS_REPARSE_OR_DEVICE != 0 || posixType != 0 && posixType != expectedType) {
                throw IOException("7z links and special files are unsupported")
            }
        }
        val path = name ?: throw IOException("7z member name is missing")
        val stream = hasStream()
        if (!stream && size != 0L || isDirectory && stream) throw IOException("7z member metadata is inconsistent")
        val crc = when {
            hasCrc -> crcValue
            stream -> folderCrc ?: throw IOException("7z member checksum is missing")
            else -> 0L
        }
        return GameArchiveEntry(if (isDirectory) path.removeSuffix("/") else path, isDirectory,
            size, 0, crc, 0)
    }

    private const val WINDOWS_REPARSE_OR_DEVICE = 0x440
}
