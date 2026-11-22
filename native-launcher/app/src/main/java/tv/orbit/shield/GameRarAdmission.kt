package tv.orbit.shield

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.security.MessageDigest
import java.util.zip.CRC32

/** Check and bound raw metadata before Junrar builds its full header list. */
internal object GameRarAdmission {
    const val MAX_HEADERS = 65_536
    const val MAX_METADATA_BYTES = 32 * 1024 * 1024
    const val MAX_DICTIONARY_BYTES = 64L * 1024 * 1024
    private const val MAX_CONTENT_BYTES = 64L * 1024 * 1024 * 1024
    private const val MAX_HEADER_BYTES = 2 * 1024 * 1024
    private const val BASE_HEADER = 7
    private val signature = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07)

    data class FileMetadata(
        val offset: Long,
        val headerSize: Int,
        val packedSize: Long,
        val unpackedSize: Long,
        val crc: Long,
        val directory: Boolean,
    )

    data class Result(
        val archiveSize: Long,
        val rar5: Boolean,
        val headerSha256: String,
        val fileHeaders: List<FileMetadata>,
        val blockCount: Int,
    )

    fun inspect(channel: SeekableByteChannel, checkpoint: () -> Unit = {}): Result {
        val position = channel.position()
        try {
            channel.position(0)
            return Reader(channel, checkpoint).inspect()
        } finally {
            if (channel.isOpen) channel.position(position)
        }
    }

    private enum class Kind { MAIN, FILE, SERVICE, END }
    private data class Block(val kind: Kind, val dataSize: Long = 0, val file: FileMetadata? = null)

    private class Reader(private val channel: SeekableByteChannel, private val checkpoint: () -> Unit) {
        private val size = channel.size()
        private val bytes = ByteArray(MAX_HEADER_BYTES)
        private val buffer = ByteBuffer.wrap(bytes)
        private val digest = MessageDigest.getInstance("SHA-256")
        private val crc = CRC32()
        private var metadataBytes = 0

        fun inspect(): Result {
            checkRar(size in BASE_HEADER.toLong()..MAX_CONTENT_BYTES, "Invalid RAR source size")
            read(0, BASE_HEADER)
            checkRar(signature.indices.all { bytes[it] == signature[it] }, "Missing RAR signature")
            val rar5 = when (bytes[6].toInt()) {
                0 -> false
                1 -> {
                    read(BASE_HEADER, 1)
                    checkRar(bytes[7].toInt() == 0, "Unsupported RAR signature")
                    true
                }
                else -> throw IOException("Unsupported RAR signature")
            }
            recordHeader(if (rar5) 8 else BASE_HEADER)
            val files = ArrayList<FileMetadata>()
            var expanded = 0L
            var count = 0
            while (channel.position() < size) {
                checkpoint()
                checkRar(count < MAX_HEADERS, "Too many RAR headers")
                val start = channel.position()
                read(0, BASE_HEADER)
                val block = if (rar5) rar5(start) else rar4(start)
                checkRar((count == 0) == (block.kind == Kind.MAIN), "Invalid RAR main header")
                count++
                val end = channel.position()
                checkRar(block.dataSize in 0..size - end, "RAR packed range exceeds the source")
                block.file?.let { file ->
                    checkRar(file.unpackedSize <= MAX_CONTENT_BYTES - expanded, "Expanded RAR is too large")
                    expanded += file.unpackedSize
                    files += file
                }
                if (block.kind == Kind.END) {
                    checkRar(end == size && channel.size() == size, "RAR has trailing data or changed")
                    checkRar(files.any { !it.directory }, "RAR contains no files")
                    val sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    return Result(size, rar5, sha256, files, count)
                }
                if (block.dataSize != 0L) channel.position(end + block.dataSize)
            }
            throw IOException("RAR end marker is missing")
        }

        private fun read(offset: Int, count: Int) {
            checkRar(count.toLong() <= size - channel.position(), "Truncated RAR header")
            buffer.clear().position(offset).limit(offset + count)
            while (buffer.hasRemaining()) {
                checkpoint()
                checkRar(channel.read(buffer) > 0, "Truncated RAR header")
            }
        }

        private fun readHeader(size: Int, checksumBytes: Int) {
            checkRar(size in BASE_HEADER..MAX_HEADER_BYTES, "Invalid RAR header size")
            checkRar(size <= MAX_METADATA_BYTES - metadataBytes, "RAR metadata exceeds the limit")
            read(BASE_HEADER, size - BASE_HEADER)
            crc.reset()
            crc.update(bytes, checksumBytes, size - checksumBytes)
            val expected = if (checksumBytes == 2) little(0, 2) else little(0, 4)
            val actual = if (checksumBytes == 2) crc.value and 0xffff else crc.value
            checkRar(expected == actual, "RAR header checksum mismatch")
            recordHeader(size)
        }

        private fun recordHeader(size: Int) {
            digest.update(bytes, 0, size)
            metadataBytes += size
        }

        private fun rar4(offset: Long): Block {
            val type = unsigned(2)
            val flags = little(3, 2).toInt()
            val headerSize = little(5, 2).toInt()
            // Junrar's RAR4 API stores the on-disk header size in a signed Short.
            checkRar(headerSize <= Short.MAX_VALUE, "RAR4 header exceeds the decoder limit")
            readHeader(headerSize, 2)
            return when (type) {
                0x73 -> {
                    // Volume, first-volume, encryption and a data-bearing main header are unsupported.
                    checkRar(flags and 0x8381 == 0 && headerSize == 13, "Unsupported RAR4 main header")
                    Block(Kind.MAIN)
                }
                0x74, 0x7a -> rar4File(offset, headerSize, flags, service = type == 0x7a)
                0x75 -> {
                    checkRar(flags and 0x8000 == 0 && headerSize >= 13, "Invalid RAR4 comment header")
                    Block(Kind.SERVICE)
                }
                0x7b -> {
                    val crcBytes = if (flags and 0x02 != 0) 4 else 0
                    val reserved = if (flags and 0x04 != 0) 7 else 0
                    checkRar(flags and 0x8009 == 0 && headerSize == BASE_HEADER + crcBytes + reserved,
                        "Unsupported RAR4 end marker")
                    Block(Kind.END)
                }
                else -> throw IOException("Unsupported RAR4 header type")
            }
        }

        private fun rar4File(offset: Long, headerSize: Int, flags: Int, service: Boolean): Block {
            // Split files and file encryption change the framing or interpretation of data.
            checkRar(flags and 0x0407 == 0 && flags and 0x8000 != 0, "Unsupported RAR4 file flags")
            val large = flags and 0x0100 != 0
            val nameStart = if (large) 40 else 32
            checkRar(headerSize >= nameStart, "Truncated RAR4 file header")
            val nameSize = little(26, 2).toInt()
            checkRar(nameSize in 1..headerSize - nameStart, "Invalid RAR4 name length")
            val packed = little(7, 4) + if (large) little(32, 4) shl 32 else 0
            val unpacked = little(11, 4) + if (large) little(36, 4) shl 32 else 0
            val method = unsigned(25)
            checkRar(method in 0x30..0x35 && !(!large && unpacked == 0xffffffffL), "Unsupported RAR4 compression or size")
            checkSizes(packed, unpacked, method == 0x30)
            if (service) return Block(Kind.SERVICE, packed)

            val host = unsigned(15)
            checkRar(host in 0..5, "Unsupported RAR4 host OS")
            val directory = flags and 0xe0 == 0xe0
            checkAttributes(little(28, 4), unix = host == 3 || host == 5, directory)
            val fileCrc = little(16, 4)
            checkDirectory(directory, packed, unpacked, fileCrc)
            return Block(Kind.FILE, packed, FileMetadata(offset, headerSize, packed, unpacked, fileCrc, directory))
        }

        private fun rar5(offset: Long): Block {
            val prefix = Fields(4, BASE_HEADER)
            val length = prefix.vint(3)
            checkRar(length <= MAX_HEADER_BYTES - prefix.position, "RAR5 header exceeds the limit")
            val headerSize = prefix.position + length.toInt()
            readHeader(headerSize, 4)
            val fields = Fields(prefix.position, headerSize)
            val type = fields.vint()
            val flags = fields.vint()
            checkRar(flags and 0x7fL == flags && flags and 0x18L == 0L, "Unsupported RAR5 block flags")
            val extraSize = if (flags and 0x01L != 0L) fields.vint() else 0L
            val dataSize = if (flags and 0x02L != 0L) fields.vint() else 0L
            checkRar(extraSize <= fields.remaining, "Invalid RAR5 extra area size")
            val extraStart = headerSize - extraSize.toInt()
            fields.limit = extraStart
            val result = when (type) {
                1L -> {
                    val archiveFlags = fields.vint()
                    checkRar(dataSize == 0L && archiveFlags and 0x03L == 0L, "RAR5 multi-volume archives are unsupported")
                    Block(Kind.MAIN)
                }
                2L, 3L -> rar5File(offset, headerSize, fields, dataSize, service = type == 3L)
                5L -> {
                    checkRar(dataSize == 0L && extraSize == 0L && fields.vint() == 0L, "Unsupported RAR5 end marker")
                    Block(Kind.END)
                }
                else -> throw IOException("Unsupported RAR5 header type")
            }
            checkRar(fields.remaining == 0L, "Unexpected RAR5 header data")
            val extras = Fields(extraStart, headerSize)
            while (extras.remaining > 0L) {
                checkpoint()
                val fieldSize = extras.vint()
                checkRar(fieldSize in 1..extras.remaining, "Invalid RAR5 extra record")
                val end = extras.position + fieldSize.toInt()
                extras.limit = end
                val extraType = extras.vint()
                if (type == 2L || type == 3L) {
                    checkRar(extraType != 1L && extraType != 5L, "RAR5 encryption and redirection are unsupported")
                }
                extras.position = end
                extras.limit = headerSize
            }
            return result
        }

        private fun rar5File(offset: Long, headerSize: Int, fields: Fields, packed: Long, service: Boolean): Block {
            val flags = fields.vint()
            checkRar(flags and 7L == flags, "Unknown RAR5 file size or flags")
            val unpacked = fields.vint()
            val attributes = fields.vint()
            if (flags and 2L != 0L) fields.skip(4)
            val checksum = if (flags and 4L != 0L) fields.u32() else null
            val compression = fields.vint()
            val host = fields.vint()
            checkRar(host == 0L || host == 1L, "Unsupported RAR5 host OS")
            val nameSize = fields.vint()
            checkRar(nameSize > 0L, "Empty RAR5 name")
            fields.skip(nameSize)
            val method = checkRar5Compression(compression)
            checkSizes(packed, unpacked, method == 0)
            if (service) return Block(Kind.SERVICE, packed)
            val directory = flags and 1L != 0L
            val fileCrc = checksum ?: if (directory) 0L else throw IOException("RAR5 member lacks CRC32")
            checkAttributes(attributes, unix = host == 1L, directory)
            checkDirectory(directory, packed, unpacked, fileCrc)
            return Block(Kind.FILE, packed, FileMetadata(offset, headerSize, packed, unpacked, fileCrc, directory))
        }

        private fun checkRar5Compression(compression: Long): Int {
            val version = (compression and 0x3f).toInt()
            val method = (compression shr 7 and 7).toInt()
            val exponent = (compression shr 10 and 31).toInt()
            checkRar(method <= 5 && when (version) {
                0 -> compression shr 15 == 0L && exponent <= 15
                1 -> compression shr 21 == 0L
                else -> false
            }, "Unsupported RAR5 compression")
            val base = 128L * 1024 shl exponent
            val fraction = if (version == 1) compression shr 15 and 31 else 0L
            checkRar(base + base * fraction / 32 <= MAX_DICTIONARY_BYTES, "RAR5 dictionary exceeds the limit")
            return method
        }

        private fun unsigned(position: Int): Int = bytes[position].toInt() and 0xff
        private fun little(start: Int, count: Int): Long {
            var value = 0L
            for (index in 0 until count) value = value or (unsigned(start + index).toLong() shl (8 * index))
            return value
        }

        private inner class Fields(var position: Int, var limit: Int) {
            val remaining: Long get() = (limit - position).toLong()

            fun vint(maxBytes: Int = 10): Long {
                var value = 0L
                for (index in 0 until maxBytes) {
                    checkRar(position < limit, "Truncated RAR5 integer")
                    val next = unsigned(position++)
                    val bits = next and 0x7f
                    checkRar(index < 9 || bits == 0, "RAR5 integer overflow")
                    value = value or (bits.toLong() shl (7 * index))
                    if (next and 0x80 == 0) return value
                }
                throw IOException("Invalid RAR5 integer")
            }

            fun skip(bytes: Long) {
                checkRar(bytes in 0..remaining, "Truncated RAR5 field")
                position += bytes.toInt()
            }

            fun u32(): Long {
                val start = position
                skip(4)
                return little(start, 4)
            }
        }
    }

    private fun checkSizes(packed: Long, unpacked: Long, stored: Boolean) {
        checkRar(packed in 0..MAX_CONTENT_BYTES && unpacked in 0..MAX_CONTENT_BYTES &&
            (unpacked == 0L || packed > 0L) && (!stored || packed == unpacked), "Invalid RAR member sizes")
    }

    private fun checkDirectory(directory: Boolean, packed: Long, unpacked: Long, crc: Long) {
        if (directory) checkRar(packed == 0L && unpacked == 0L && crc == 0L, "Invalid RAR directory")
    }

    private fun checkAttributes(attributes: Long, unix: Boolean, directory: Boolean) {
        checkRar(attributes <= 0xffffffffL, "Invalid RAR attributes")
        if (unix) {
            val kind = attributes and 0xf000
            checkRar(kind == 0L || kind == (if (directory) 0x4000L else 0x8000L), "RAR member is not a regular file or directory")
        } else {
            checkRar(attributes and 0x440L == 0L, "RAR reparse point or device is unsupported")
        }
    }

    private fun checkRar(condition: Boolean, message: String) {
        if (!condition) throw IOException(message)
    }
}
