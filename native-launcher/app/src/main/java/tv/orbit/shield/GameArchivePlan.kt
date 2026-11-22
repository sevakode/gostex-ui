package tv.orbit.shield

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32

internal enum class GameArchiveFormat(val extension: String) {
    ZIP("zip"), SEVEN_Z("7z"), RAR("rar");

    private val suffix = ".$extension"

    companion object {
        private val sevenZSignature = byteArrayOf(0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c)
        private val rarPrefix = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07)

        fun supportsName(name: String): Boolean = entries.any { name.endsWith(it.suffix, ignoreCase = true) }

        fun detect(channel: SeekableByteChannel): GameArchiveFormat {
            val position = channel.position()
            val header = ByteBuffer.allocate(sevenZSignature.size)
            try {
                channel.position(0)
                while (header.hasRemaining()) {
                    if (channel.read(header) <= 0) break
                }
                return when {
                    header.array().contentEquals(sevenZSignature) -> SEVEN_Z
                    header.array().contentEquals(rarPrefix) -> RAR
                    // ZipFile checks the central directory and accepts a preamble.
                    else -> ZIP
                }
            } finally {
                channel.position(position)
            }
        }
    }
}

internal data class GameArchiveEntry(
    val path: String,
    val directory: Boolean,
    val size: Long,
    val compressedSize: Long,
    val crc: Long,
    val method: Int,
)

internal data class GameArchivePlan(
    val format: GameArchiveFormat,
    val entries: List<GameArchiveEntry>,
    val totalBytes: Long,
    val fileCount: Int,
    val sourceMetadata: String? = null,
)

internal const val MAX_ARCHIVE_ENTRIES = 10_000
internal const val MAX_RAR_ENTRIES = 65_536
internal const val MAX_ARCHIVE_DIRECTORY_CHILDREN = 10_000
private const val MAX_ARCHIVE_DEPTH = 16
internal const val MAX_ARCHIVE_OUTPUT = 64L * 1024 * 1024 * 1024

/** Verify names and the complete output budget before creating any destination documents. */
internal fun checkedArchivePlan(
    format: GameArchiveFormat,
    entries: Sequence<GameArchiveEntry>,
    checkpoint: () -> Unit,
    sourceMetadata: String? = null,
): GameArchivePlan {
    val ordered = mutableListOf<GameArchiveEntry>()
    val explicitPaths = mutableSetOf<String>()
    val nodes = mutableMapOf<String, Pair<String, Boolean>>()
    val childrenPerDirectory = if (format == GameArchiveFormat.RAR) mutableMapOf<String, Int>() else null
    val maxEntries = if (format == GameArchiveFormat.RAR) MAX_RAR_ENTRIES else MAX_ARCHIVE_ENTRIES
    var total = 0L
    var files = 0
    for (entry in entries) {
        checkpoint()
        require(ordered.size < maxEntries) { "Too many archive entries" }
        val parts = TorrDownloadPaths.components(entry.path)
        require(parts.size <= MAX_ARCHIVE_DEPTH) { "Archive path is too deep" }
        require(explicitPaths.add(entry.path.lowercase(Locale.ROOT))) { "Duplicate archive entry" }
        var parent = ""
        parts.forEachIndexed { index, part ->
            val parentPath = parent
            parent = if (parent.isEmpty()) part else "$parent/$part"
            val directory = index < parts.lastIndex || entry.directory
            val node = parent to directory
            val previous = nodes.putIfAbsent(parent.lowercase(Locale.ROOT), node)
            require(previous == null || previous == node) { "Ambiguous archive path" }
            require(nodes.size <= maxEntries) { "Too many archive documents" }
            if (previous == null && childrenPerDirectory != null) {
                val childCount = (childrenPerDirectory[parentPath] ?: 0) + 1
                require(childCount <= MAX_ARCHIVE_DIRECTORY_CHILDREN) { "Too many documents in an archive directory" }
                childrenPerDirectory[parentPath] = childCount
            }
        }
        require(entry.size in 0..MAX_ARCHIVE_OUTPUT && entry.compressedSize in 0..MAX_ARCHIVE_OUTPUT &&
            entry.crc in 0..0xffffffffL) { "Invalid archive entry metadata" }
        if (entry.directory) require(entry.size == 0L && entry.crc == 0L) { "Invalid archive directory" }
        else {
            require(entry.size <= MAX_ARCHIVE_OUTPUT - total) { "Expanded archive is too large" }
            total += entry.size
            files++
        }
        ordered += entry
    }
    require(files > 0) { "Archive contains no files" }
    return GameArchivePlan(format, ordered, total, files, sourceMetadata)
}

/** ZIP v1's ordered metadata encoding is also used by existing on-device journals. */
internal fun gameArchiveFingerprint(plan: GameArchivePlan): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun number(value: Long) {
        for (shift in 56 downTo 0 step 8) digest.update((value ushr shift).toByte())
    }
    fun text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        number(bytes.size.toLong())
        digest.update(bytes)
    }
    when (plan.format) {
        GameArchiveFormat.ZIP -> text("gostex-zip-plan-v1")
        GameArchiveFormat.SEVEN_Z -> {
            text("gostex-7z-plan-v1")
            text(plan.sourceMetadata.orEmpty())
        }
        GameArchiveFormat.RAR -> {
            text("gostex-rar-plan-v1")
            text(plan.sourceMetadata.orEmpty())
        }
    }
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

internal class GameArchiveDirectories(
    private val createDirectory: (String) -> Unit,
    private val checkpoint: () -> Unit,
) {
    private val created = mutableSetOf<String>()

    fun prepare(entry: GameArchiveEntry) {
        val parts = entry.path.split('/')
        val count = if (entry.directory) parts.size else parts.size - 1
        var parent = ""
        for (index in 0 until count) {
            parent = if (parent.isEmpty()) parts[index] else "$parent/${parts[index]}"
            if (created.add(parent)) {
                checkpoint()
                createDirectory(parent)
            }
        }
    }
}

/** A null writer verifies a stream that must be decoded to reach the next solid member. */
internal fun copyGameArchiveMember(
    entry: GameArchiveEntry,
    buffer: ByteArray,
    read: (ByteArray) -> Int,
    write: ((ByteArray, Int) -> Unit)?,
    checkpoint: () -> Unit,
    onBytes: (Int) -> Unit,
) {
    val crc = CRC32()
    var size = 0L
    while (true) {
        checkpoint()
        val count = read(buffer)
        if (count < 0) break
        if (count == 0 || count > buffer.size || count.toLong() > entry.size - size) {
            throw IOException("Archive member size mismatch")
        }
        checkpoint()
        write?.invoke(buffer, count)
        crc.update(buffer, 0, count)
        size += count
        onBytes(count)
    }
    if (size != entry.size || crc.value != entry.crc) throw IOException("Archive member checksum mismatch")
}
