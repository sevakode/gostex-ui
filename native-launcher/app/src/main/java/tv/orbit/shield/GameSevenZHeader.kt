package tv.orbit.shield

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.security.MessageDigest
import java.util.zip.CRC32
import org.tukaani.xz.LZMA2InputStream
import org.tukaani.xz.LZMAInputStream

/** Bounds and snapshots the input metadata before passing a plain header to Commons Compress. */
internal object GameSevenZHeader {
    const val DECODER_MEMORY_KIB = 68 * 1024
    private const val SIGNATURE_BYTES = 32
    private const val MAX_HEADER_BYTES = 8 * 1024 * 1024
    private const val MAX_DICTIONARY_BYTES = 64 * 1024 * 1024
    private val signature = byteArrayOf(0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c)

    fun matches(bytes: ByteArray): Boolean = bytes.size >= signature.size && signature.indices.all { bytes[it] == signature[it] }

    data class Snapshot(val input: SeekableByteChannel, val identity: String, val folderCrcs: List<Long?>)

    fun read(source: SeekableByteChannel, checkpoint: () -> Unit): Snapshot {
        val originalSignature = readBytes(source, 0L, SIGNATURE_BYTES, checkpoint)
        requireArchive(matches(originalSignature) && originalSignature[6] == 0.toByte(), "Unsupported 7z signature")
        val fields = ByteBuffer.wrap(originalSignature).order(ByteOrder.LITTLE_ENDIAN).apply { position(8) }
        val startCrc = fields.int.toUInt().toLong()
        requireArchive(checksum(originalSignature, 12) == startCrc, "7z start header checksum mismatch")
        val nextOffset = fields.long
        val nextSize = fields.long
        val nextCrc = fields.int.toUInt().toLong()
        val bodySize = source.size() - SIGNATURE_BYTES
        requireArchive(nextOffset in 0..bodySize && nextSize in 1..MAX_HEADER_BYTES.toLong() && nextSize == bodySize - nextOffset,
            "Invalid or oversized 7z header")
        val nextStart = SIGNATURE_BYTES + nextOffset
        val next = readBytes(source, nextStart, nextSize.toInt(), checkpoint)
        requireArchive(checksum(next) == nextCrc, "7z next header checksum mismatch")
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(originalSignature)
            update(next)
        }

        val decoded: ByteArray
        val headerStart: Long
        if (next[0] == ENCODED_HEADER.toByte()) {
            val encoded = Reader(next).apply { expect(ENCODED_HEADER) }.streams(nextOffset, encoded = true, checkpoint = checkpoint)
            val folder = encoded.folders.single()
            val unpackSize = folder.size.toInt()
            headerStart = SIGNATURE_BYTES + encoded.packOffset
            val packed = readBytes(source, headerStart, encoded.packedSizes.single().toInt(), checkpoint)
            requireArchive(encoded.packedCrc == null || checksum(packed) == encoded.packedCrc, "7z packed header checksum mismatch")
            digest.update(packed)
            val decoder = folder.coders.single()
            val input = ByteArrayInputStream(packed)
            checkpoint()
            decoded = when (decoder.method) {
                COPY -> input
                LZMA -> LZMAInputStream(input, unpackSize.toLong(), decoder.properties[0], decoder.dictionary).apply {
                    enableRelaxedEndCondition()
                }
                LZMA2 -> LZMA2InputStream(input, decoder.dictionary)
                else -> throw IOException("Unsupported 7z header compression")
            }.use { stream ->
                ByteArray(unpackSize).also { bytes ->
                    var offset = 0
                    while (offset < bytes.size) {
                        checkpoint()
                        val read = stream.read(bytes, offset, minOf(bytes.size - offset, 64 * 1024))
                        requireArchive(read > 0, "Incomplete 7z encoded header")
                        offset += read
                    }
                    requireArchive(stream.read() == -1, "7z encoded header is larger than declared")
                }
            }
            requireArchive(folder.crc == null || checksum(decoded) == folder.crc, "7z decoded header checksum mismatch")
        } else {
            headerStart = nextStart
            decoded = next
        }
        requireArchive(decoded.size <= Long.MAX_VALUE - headerStart, "7z header position is out of range")
        val folderCrcs = validatePlainHeader(decoded, headerStart - SIGNATURE_BYTES, checkpoint)
        val canonicalSignature = originalSignature.copyOf()
        ByteBuffer.wrap(canonicalSignature).order(ByteOrder.LITTLE_ENDIAN).apply {
            position(12)
            putLong(headerStart - SIGNATURE_BYTES)
            putLong(decoded.size.toLong())
            putInt(checksum(decoded).toInt())
            putInt(8, checksum(canonicalSignature, 12).toInt())
        }
        if (decoded !== next) digest.update(decoded)
        return Snapshot(MetadataChannel(source, canonicalSignature, headerStart, decoded),
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, folderCrcs)
    }

    /** Validate the stream geometry and decoder selection while numbers still have hard bounds. */
    private fun validatePlainHeader(bytes: ByteArray, packedLimit: Long, checkpoint: () -> Unit): List<Long?> {
        val input = Reader(bytes)
        input.expect(HEADER)
        var property = input.byte()
        if (property == ARCHIVE_PROPERTIES) {
            while (input.number() != 0L) input.skip(input.count(MAX_HEADER_BYTES))
            property = input.byte()
        }
        val streams = if (property == MAIN_STREAMS) {
            val streams = input.streams(packedLimit, encoded = false, checkpoint = checkpoint)
            property = input.byte()
            streams
        } else null
        // No external metadata streams. The library validates individual file properties.
        input.expectValue(property, FILES, "Unsupported 7z header layout")
        input.count(MAX_ARCHIVE_ENTRIES)
        return buildList {
            streams?.folders?.forEachIndexed { index, folder ->
                val count = streams.substreamCounts[index]
                repeat(count) { add(folder.crc.takeIf { count == 1 }) }
            }
        }
    }

    private data class Coder(val method: Int, val properties: ByteArray, val dictionary: Int)
    private data class Folder(val coders: List<Coder>, var size: Long = 0, var crc: Long? = null)
    private data class Streams(val packOffset: Long, val packedSizes: LongArray, val folders: List<Folder>,
        val packedCrc: Long?, val substreamCounts: IntArray)

    private fun Reader.streams(packedLimit: Long, encoded: Boolean, checkpoint: () -> Unit): Streams {
        val firstProperty = byte()
        if (!encoded && (firstProperty == SUBSTREAMS || firstProperty == END)) {
            if (firstProperty == SUBSTREAMS) {
                readSubStreams(emptyList(), encoded = false)
                expect(END)
            }
            return Streams(0, LongArray(0), emptyList(), null, IntArray(0))
        }
        expectValue(firstProperty, PACK_INFO, "Missing 7z packed streams")
        val packOffset = number()
        requireArchive(packOffset <= packedLimit, "7z packed streams are outside the archive")
        val streams = count(if (encoded) 1 else MAX_ARCHIVE_ENTRIES)
        requireArchive(streams > 0, "7z streams are missing")
        expect(SIZE)
        var remaining = packedLimit - packOffset
        val packed = LongArray(streams) {
            val size = number()
            requireArchive(size <= remaining && (!encoded || size in 1..MAX_HEADER_BYTES.toLong()), "Invalid 7z packed size")
            remaining -= size
            size
        }
        var property = byte()
        var packedCrc: Long? = null
        if (property == CRC) {
            val defined = readDefinitions(streams)
            if (encoded && defined.single()) packedCrc = crc()
            else skip(defined.count { it } * Int.SIZE_BYTES)
            property = byte()
        }
        expectValue(property, END, "Invalid 7z packed stream information")
        expect(UNPACK_INFO)
        expect(FOLDER)
        requireArchive(count(MAX_ARCHIVE_ENTRIES) == streams, "Unsupported 7z stream layout")
        expect(0) // Folders are stored in this header.
        val folders = List(streams) {
            checkpoint()
            val coderCount = count(if (encoded) 1 else 2)
            requireArchive(coderCount >= 1, "Missing 7z compression method")
            val coders = List(coderCount) { coder(encoded) }
            if (coderCount == 2) {
                val input = count(1)
                val output = count(1)
                requireArchive(input != output && isFilter(coders[input].method) && !isFilter(coders[output].method),
                    "Unsupported 7z compression chain")
            } else requireArchive(!isFilter(coders.single().method), "7z filter lacks its compression method")
            Folder(coders)
        }
        expect(CODER_SIZE)
        var unpackRemaining = if (encoded) MAX_HEADER_BYTES.toLong() else MAX_ARCHIVE_OUTPUT
        for (folder in folders) {
            val sizes = LongArray(folder.coders.size) { number() }
            // A filter does not change length, so both output sizes in that chain must agree.
            requireArchive(sizes.all { it == sizes.first() }, "Inconsistent 7z stream sizes")
            folder.size = sizes.first()
            requireArchive(folder.size in 1..unpackRemaining,
                if (encoded) "Oversized 7z decoded header" else "Expanded 7z is too large")
            unpackRemaining -= folder.size
        }
        property = byte()
        if (property == CRC) {
            val defined = readDefinitions(folders.size)
            folders.forEachIndexed { index, folder -> if (defined[index]) folder.crc = crc() }
            property = byte()
        }
        expectValue(property, END, "Invalid 7z unpack stream information")
        property = byte()
        val substreamCounts = if (property == SUBSTREAMS) {
            val counts = readSubStreams(folders, encoded)
            property = byte()
            counts
        } else IntArray(folders.size) { 1 }
        expectValue(property, END, "Invalid 7z stream information")
        if (encoded) requireArchive(!hasRemaining(), "Unexpected data after 7z encoded header")
        return Streams(packOffset, packed, folders, packedCrc, substreamCounts)
    }

    private fun Reader.readSubStreams(folders: List<Folder>, encoded: Boolean): IntArray {
        var property = byte()
        val counts = if (property == SUBSTREAM_COUNT) {
            IntArray(folders.size) { count(MAX_ARCHIVE_ENTRIES) }.also { property = byte() }
        } else IntArray(folders.size) { 1 }
        requireArchive(counts.sumOf { it.toLong() } <= MAX_ARCHIVE_ENTRIES && (!encoded || counts.single() == 1), "Too many 7z substreams")
        if (property == SIZE) {
            folders.forEachIndexed { index, folder ->
                var remaining = folder.size
                repeat(maxOf(counts[index] - 1, 0)) {
                    val size = number()
                    requireArchive(size <= remaining, "Invalid 7z substream size")
                    remaining -= size
                }
            }
            property = byte()
        } else requireArchive(counts.all { it <= 1 }, "Missing 7z substream sizes")
        if (property == CRC) {
            val digests = folders.indices.sumOf { if (counts[it] == 1 && folders[it].crc != null) 0 else counts[it] }
            val defined = readDefinitions(digests)
            if (encoded && digests == 1 && defined.single()) folders.single().crc = crc()
            else skip(defined.count { it } * Int.SIZE_BYTES)
            property = byte()
        }
        expectValue(property, END, "Invalid 7z substream information")
        return counts
    }

    private fun Reader.coder(encoded: Boolean): Coder {
        val flags = byte()
        val idSize = flags and 0x0f
        requireArchive(idSize in 1..4 && flags and 0xd0 == 0, "Unsupported 7z coder properties")
        var method = 0
        repeat(idSize) { method = (method shl 8) or byte() }
        val expectedIdSize = when (method) {
            COPY, LZMA2, DELTA -> 1
            LZMA, DEFLATE, DEFLATE64, BZIP2 -> 3
            BCJ_X86, BCJ_PPC, BCJ_IA64, BCJ_ARM, BCJ_ARM_THUMB, BCJ_SPARC -> 4
            else -> throw IOException("Unsupported 7z compression or encryption")
        }
        requireArchive(idSize == expectedIdSize, "Unknown 7z compression method")
        val properties = if (flags and 0x20 != 0) ByteArray(count(5)) { byte().toByte() } else byteArrayOf()
        val dictionary = when (method) {
            COPY -> {
                requireArchive(properties.isEmpty(), "Invalid stored 7z coder")
                0
            }
            LZMA -> {
                requireArchive(properties.size == 5, "Invalid 7z LZMA properties")
                ByteBuffer.wrap(properties).order(ByteOrder.LITTLE_ENDIAN).getInt(1).also {
                    requireArchive(it in 0..MAX_DICTIONARY_BYTES, "7z LZMA dictionary is too large")
                    requireArchive(LZMAInputStream.getMemoryUsage(it, properties[0]) <= DECODER_MEMORY_KIB, "7z LZMA decoder is too large")
                }
            }
            LZMA2 -> {
                requireArchive(properties.size == 1 && properties[0] in 0..28, "7z LZMA2 dictionary is too large")
                val bits = properties[0].toInt()
                (2 or (bits and 1)) shl (bits / 2 + 11)
            }
            DEFLATE, DEFLATE64, BZIP2 -> {
                requireArchive(!encoded && properties.isEmpty(), "Unsupported 7z header compression")
                0
            }
            DELTA -> {
                requireArchive(!encoded && properties.size == 1, "Unsupported 7z delta filter")
                0
            }
            BCJ_X86, BCJ_PPC, BCJ_IA64, BCJ_ARM, BCJ_ARM_THUMB, BCJ_SPARC -> {
                requireArchive(!encoded && (properties.isEmpty() || properties.size == 4 && properties.all { it == 0.toByte() }),
                    "Unsupported 7z BCJ filter")
                0
            }
            else -> throw IOException("Unsupported 7z compression or encryption")
        }
        return Coder(method, properties, dictionary)
    }

    private fun isFilter(method: Int) = when (method) {
        DELTA, BCJ_X86, BCJ_PPC, BCJ_IA64, BCJ_ARM, BCJ_ARM_THUMB, BCJ_SPARC -> true
        else -> false
    }

    private class Reader(bytes: ByteArray) {
        private val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        fun hasRemaining() = input.hasRemaining()
        fun byte(): Int {
            requireArchive(input.hasRemaining(), "Incomplete 7z header")
            return input.get().toInt() and 255
        }
        fun number(): Long {
            val first = byte()
            var mask = 0x80
            var value = 0L
            for (index in 0..7) {
                if (first and mask == 0) return value or ((first and (mask - 1)).toLong() shl (8 * index))
                value = value or (byte().toLong() shl (8 * index))
                mask = mask ushr 1
            }
            requireArchive(value >= 0, "7z integer is too large")
            return value
        }
        fun count(maximum: Int): Int = number().also { requireArchive(it <= maximum, "7z metadata limit exceeded") }.toInt()
        fun expect(value: Int) = expectValue(byte(), value, "Invalid 7z header property")
        fun expectValue(actual: Int, expected: Int, message: String) = requireArchive(actual == expected, message)
        fun skip(bytes: Int) {
            requireArchive(bytes <= input.remaining(), "Incomplete 7z header property")
            input.position(input.position() + bytes)
        }
        fun crc(): Long {
            requireArchive(input.remaining() >= Int.SIZE_BYTES, "Incomplete 7z checksum")
            return input.int.toUInt().toLong()
        }
        fun readDefinitions(count: Int): BooleanArray {
            val type = byte()
            requireArchive(type in 0..1, "Invalid 7z bit field")
            if (type == 1) return BooleanArray(count) { true }
            var bits = 0
            return BooleanArray(count) { index ->
                if (index % 8 == 0) bits = byte()
                bits and (128 shr (index % 8)) != 0
            }
        }
    }

    /** Read-only view. Header bytes stay immutable and the source remains owned by the caller. */
    private class MetadataChannel(
        private val source: SeekableByteChannel,
        private val signatureBytes: ByteArray,
        private val headerStart: Long,
        private val headerBytes: ByteArray,
    ) : SeekableByteChannel {
        private var open = true
        private var offset = 0L
        private var sourceOffset = -1L

        override fun read(dst: ByteBuffer): Int {
            checkOpen()
            if (!dst.hasRemaining()) return 0
            if (offset >= size()) return -1
            val count = when {
                offset < SIGNATURE_BYTES -> readArray(dst, signatureBytes, 0)
                offset >= headerStart -> readArray(dst, headerBytes, headerStart)
                else -> {
                    if (sourceOffset != offset) source.position(offset)
                    val oldLimit = dst.limit()
                    dst.limit(dst.position() + minOf(dst.remaining().toLong(), headerStart - offset).toInt())
                    val read = try { source.read(dst) } finally { dst.limit(oldLimit) }
                    if (read >= 0) sourceOffset = offset + read
                    read
                }
            }
            if (count >= 0) offset += count
            return count
        }

        private fun readArray(dst: ByteBuffer, data: ByteArray, start: Long): Int {
            val index = (offset - start).toInt()
            val count = minOf(dst.remaining(), data.size - index)
            dst.put(data, index, count)
            return count
        }

        override fun position(): Long {
            checkOpen()
            return offset
        }
        override fun position(newPosition: Long): SeekableByteChannel {
            checkOpen()
            require(newPosition >= 0)
            offset = newPosition
            return this
        }
        override fun size(): Long {
            checkOpen()
            return headerStart + headerBytes.size
        }
        override fun close() { open = false }
        override fun isOpen() = open && source.isOpen
        override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()
        override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
        private fun checkOpen() { if (!isOpen) throw ClosedChannelException() }
    }

    private fun readBytes(source: SeekableByteChannel, position: Long, size: Int, checkpoint: () -> Unit): ByteArray {
        val bytes = ByteArray(size)
        val buffer = ByteBuffer.wrap(bytes)
        source.position(position)
        while (buffer.position() < size) {
            checkpoint()
            buffer.limit(minOf(size, buffer.position() + 64 * 1024))
            requireArchive(source.read(buffer) > 0, "Incomplete 7z archive")
        }
        return bytes
    }

    private fun checksum(bytes: ByteArray, start: Int = 0): Long = CRC32().apply { update(bytes, start, bytes.size - start) }.value
    private fun requireArchive(ok: Boolean, message: String) { if (!ok) throw IOException(message) }

    private const val END = 0
    private const val HEADER = 0x01
    private const val ARCHIVE_PROPERTIES = 0x02
    private const val MAIN_STREAMS = 0x04
    private const val FILES = 0x05
    private const val PACK_INFO = 0x06
    private const val UNPACK_INFO = 0x07
    private const val SUBSTREAMS = 0x08
    private const val SIZE = 0x09
    private const val CRC = 0x0a
    private const val FOLDER = 0x0b
    private const val CODER_SIZE = 0x0c
    private const val SUBSTREAM_COUNT = 0x0d
    private const val ENCODED_HEADER = 0x17
    private const val COPY = 0x00
    private const val LZMA = 0x030101
    private const val LZMA2 = 0x21
    private const val DEFLATE = 0x040108
    private const val DEFLATE64 = 0x040109
    private const val BZIP2 = 0x040202
    private const val DELTA = 0x03
    private const val BCJ_X86 = 0x03030103
    private const val BCJ_PPC = 0x03030205
    private const val BCJ_IA64 = 0x03030401
    private const val BCJ_ARM = 0x03030501
    private const val BCJ_ARM_THUMB = 0x03030701
    private const val BCJ_SPARC = 0x03030805
}
