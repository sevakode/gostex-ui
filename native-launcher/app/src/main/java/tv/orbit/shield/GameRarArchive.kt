package tv.orbit.shield

import com.github.junrar.Archive
import com.github.junrar.ArchiveOptions
import com.github.junrar.RarFormat
import com.github.junrar.exception.RarException
import com.github.junrar.io.SeekableReadOnlyByteChannel
import com.github.junrar.rarfile.FileHeader
import com.github.junrar.rarfile.HostSystem
import com.github.junrar.rarfile.rar5.Rar5HostOS
import com.github.junrar.volume.Volume
import com.github.junrar.volume.VolumeManager
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.zip.CRC32

/** Sequential extraction preserves solid dictionaries when replaying an interrupted preparation. */
internal object GameRarArchive {
    private const val WINDOWS_REPARSE_OR_DEVICE = 0x440

    fun inspect(channel: SeekableByteChannel, checkpoint: () -> Unit): GameArchivePlan =
        withArchive(channel, checkpoint) { _, plan -> plan }

    fun extract(
        channel: SeekableByteChannel,
        expected: GameArchivePlan,
        createDirectory: (String) -> Unit,
        openFile: (String) -> OutputStream,
        checkpoint: () -> Unit = {},
        onProgress: (Long) -> Unit = {},
        completed: (GameArchiveEntry) -> Boolean = { false },
        onFileCompleted: (GameArchiveEntry) -> Unit = {},
    ) = withArchive(channel, checkpoint) { archive, plan ->
        if (plan != expected) throw IOException("RAR changed after inspection")
        val directories = GameArchiveDirectories(createDirectory, checkpoint)
        var total = 0L
        onProgress(total)
        val update: (Int) -> Unit = { bytes ->
            total += bytes
            onProgress(total)
        }
        val headers = archive.fileHeaders
        plan.entries.forEachIndexed { index, entry ->
            checkpoint()
            when {
                entry.directory -> directories.prepare(entry)
                completed(entry) -> decode(archive, headers[index], entry, null, checkpoint, update)
                else -> {
                    directories.prepare(entry)
                    openFile(entry.path).use { output -> decode(archive, headers[index], entry, output, checkpoint, update) }
                    checkpoint()
                    onFileCompleted(entry)
                }
            }
        }
        checkpoint()
        check(total == plan.totalBytes)
    }

    private fun <T> withArchive(
        channel: SeekableByteChannel,
        checkpoint: () -> Unit,
        block: (IndexedArchive, GameArchivePlan) -> T,
    ): T {
        val admission = GameRarAdmission.inspect(channel, checkpoint)
        if (channel.size() != admission.archiveSize) throw IOException("RAR length changed after inspection")
        val reader = BorrowedChannel(channel.position(0), admission.archiveSize, admission.blockCount, checkpoint)
        val volumes = VolumeManager { archive, previous ->
            if (previous == null) object : Volume {
                override fun getChannel(): SeekableReadOnlyByteChannel = reader
                override fun getLength(): Long = admission.archiveSize
                override fun getArchive(): Archive = archive
            } else null
        }
        try {
            return IndexedArchive(volumes).use { archive ->
                reader.finishOpening()
                archive.indexFiles(admission.fileHeaders.size)
                val plan = archive.plan(admission, checkpoint)
                block(archive, plan)
            }
        } catch (failure: RarException) {
            checkpoint()
            // Junrar wraps exceptions raised from the borrowed source or the output callback.
            val causes = generateSequence(failure.cause) { it.cause }
            causes.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
            throw IOException("Could not read RAR archive", failure)
        }
    }

    private fun IndexedArchive.plan(admission: GameRarAdmission.Result, checkpoint: () -> Unit): GameArchivePlan {
        val expectedFormat = if (admission.rar5) RarFormat.RAR50 else RarFormat.RAR15
        if (format != expectedFormat || hasBrokenHeaders() || isEncrypted || mainHeader?.isMultiVolume == true) {
            throw IOException("Unsupported or damaged RAR archive")
        }
        val headers = fileHeaders
        val members = headers.asSequence().mapIndexed { index, header ->
            val admitted = admission.fileHeaders[index]
            val entry = header.checkedEntry(admission.rar5)
            if (header.positionInFile != admitted.offset || header.getDataStartOffset(false) != admitted.offset + admitted.headerSize ||
                entry.directory != admitted.directory || entry.size != admitted.unpackedSize ||
                entry.compressedSize != admitted.packedSize || entry.crc != admitted.crc) {
                throw IOException("RAR file metadata changed")
            }
            entry
        }
        return checkedArchivePlan(GameArchiveFormat.RAR, members, checkpoint, admission.headerSha256)
    }

    private fun FileHeader.checkedEntry(rar5: Boolean): GameArchiveEntry {
        if (isEncrypted || isSplitBefore || isSplitAfter || isUnpSizeUnknown || redirection != null ||
            rar5WinSize > GameRarAdmission.MAX_DICTIONARY_BYTES) {
            throw IOException("Unsupported RAR member")
        }
        val unix = if (rar5) rar5HostOS == Rar5HostOS.UNIX else hostOS == HostSystem.unix
        if (unix) {
            val type = fileAttr and 0xf000
            val expected = if (isDirectory) 0x4000 else 0x8000
            if (type != 0 && type != expected) throw IOException("RAR links and special files are unsupported")
        } else if (fileAttr and WINDOWS_REPARSE_OR_DEVICE != 0) {
            throw IOException("RAR links and special files are unsupported")
        }
        if (!isDirectory && !hasFileCrc()) throw IOException("RAR member checksum is missing")
        val name = if (rar5 || unix) fileName else fileName.replace('\\', '/')
        return GameArchiveEntry(if (isDirectory) name.removeSuffix("/") else name, isDirectory,
            fullUnpackSize, fullPackSize, if (isDirectory) 0L else fileCRC.toUInt().toLong(), unpMethod.toUByte().toInt())
    }

    private fun decode(
        archive: IndexedArchive,
        header: FileHeader,
        entry: GameArchiveEntry,
        output: OutputStream?,
        checkpoint: () -> Unit,
        onBytes: (Int) -> Unit,
    ) {
        val crc = CRC32()
        var written = 0L
        val sink = object : OutputStream() {
            private fun beforeWrite(count: Int) {
                checkpoint()
                if (count.toLong() > entry.size - written) throw IOException("RAR member size mismatch")
            }

            private fun afterWrite(count: Int) {
                written += count
                onBytes(count)
            }

            override fun write(value: Int) {
                beforeWrite(1)
                output?.write(value)
                crc.update(value)
                afterWrite(1)
            }

            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                beforeWrite(count)
                output?.write(bytes, offset, count)
                crc.update(bytes, offset, count)
                afterWrite(count)
            }
        }
        // Use extractFile even for reused members. Junrar's internal skip can suppress CRC failures.
        archive.extractFile(header, sink)
        checkpoint()
        if (written != entry.size || crc.value != entry.crc) throw IOException("RAR member checksum mismatch")
    }

    private class IndexedArchive(manager: VolumeManager) : Archive(manager,
        ArchiveOptions.builder().maxDictionarySize(GameRarAdmission.MAX_DICTIONARY_BYTES).build()) {
        private var index: List<FileHeader>? = null

        fun indexFiles(expectedCount: Int) {
            val headers = super.getFileHeaders()
            if (headers.size != expectedCount) throw IOException("RAR file count changed")
            val ordinals = IdentityHashMap<FileHeader, Int>(headers.size)
            headers.forEachIndexed { ordinal, header -> ordinals[header] = ordinal }
            index = object : AbstractList<FileHeader>() {
                override val size: Int get() = headers.size
                override fun get(index: Int): FileHeader = headers[index]
                override fun indexOf(element: FileHeader): Int = ordinals[element] ?: -1
            }
        }

        // Archive calls this during construction; use its normal listing until the index is ready.
        override fun getFileHeaders(): List<FileHeader> = index ?: super.getFileHeaders()
    }

    /** Junrar owns this view. The SAF scope retains the underlying channel. */
    private class BorrowedChannel(
        private val source: SeekableByteChannel,
        private val length: Long,
        headerBlocks: Int,
        private val checkpoint: () -> Unit,
    ) : SeekableReadOnlyByteChannel {
        private var offset = 0L
        private var opening = true
        private var openingBytes = 0L
        private var openingCalls = 0
        private var openingSeeks = 0
        // Allow Junrar's signature probe and per-block lookahead, while bounding reparsing of a changed source.
        private val readBudget = GameRarAdmission.MAX_METADATA_BYTES.toLong() + 16L * headerBlocks + 64L
        private val readCallLimit = headerBlocks * 16 + 64
        private val seekLimit = headerBlocks * 2 + 16
        private val single = ByteArray(1)

        fun finishOpening() { opening = false }

        override fun getPosition(): Long = offset
        override fun setPosition(position: Long) {
            checkpoint()
            if (position !in 0..length) throw EOFException("RAR seek is outside the archive")
            if (opening && ++openingSeeks > seekLimit) throw IOException("Too many RAR headers during decoding")
            source.position(position)
            offset = position
        }

        override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toUByte().toInt()

        override fun read(buffer: ByteArray, start: Int, count: Int): Int {
            checkpoint()
            if (count == 0) return 0
            if (offset == length) return -1
            val available = minOf(count.toLong(), length - offset).toInt()
            if (opening && (++openingCalls > readCallLimit || available > readBudget - openingBytes)) {
                throw IOException("RAR metadata exceeds the decoder budget")
            }
            val read = source.read(ByteBuffer.wrap(buffer, start, available))
            if (read == 0) throw IOException("RAR source did not advance")
            if (read > 0) {
                offset += read
                if (opening) openingBytes += read
            }
            return read
        }

        override fun readFully(buffer: ByteArray, count: Int): Int {
            var bytes = 0
            while (bytes < count) {
                val read = read(buffer, bytes, count - bytes)
                if (read < 0) throw EOFException("RAR source is truncated")
                bytes += read
            }
            return bytes
        }

        override fun close() = Unit
    }
}
