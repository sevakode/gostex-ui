package tv.orbit.shield

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

internal data class ThreeDsGameMetadata(val title: String, val publisher: String, val titleId: String?)

/**
 * Reads public title metadata from unencrypted NCCH/CXI/.app and the main NCSD/CCI partition.
 * Format references: Azahar 2126.2 core/file_sys/ncch_container.h and core/loader/smdh.h.
 * This is not a CIA installer or a decryptor. It never reads RomFS or executable code.
 */
internal object ThreeDsMetadata {
    private const val HEADER_BYTES = 0x200
    private const val SMDH_BYTES = 0x36c0
    private const val TITLE_BYTES = 0x200
    private val whitespace = Regex("\\s+")

    /**
     * The reader must seek to [offset] and return exactly [length] bytes, or null for a short read.
     * At most four bounded reads (15,552 bytes in total) are needed for a cartridge; three for .app.
     * Storage errors and cancellation deliberately propagate so an importer can retain cached roots.
     * Null means no validated executable title metadata; callers may use filenames for normal ROMs.
     */
    fun read(size: Long, language: String, readAt: (offset: Long, length: Int) -> ByteArray?): ThreeDsGameMetadata? {
        fun bytes(offset: Long, length: Int): ByteArray? {
            if (!within(offset, length.toLong(), size)) return null
            return readAt(offset, length)?.takeIf { it.size == length }
        }
        val header = bytes(0, HEADER_BYTES) ?: return null
        return when {
            magic(header, 0x100, "NCCH") -> readNcch(header, 0, size, language, ::bytes)
            magic(header, 0x100, "NCSD") -> {
                // Partition zero is the boot application. Manual/update partitions are not games.
                val offset = u32(header, 0x120) * HEADER_BYTES
                val length = u32(header, 0x124) * HEADER_BYTES
                if (offset < HEADER_BYTES || length < HEADER_BYTES || !within(offset, length, size)) null
                else bytes(offset, HEADER_BYTES)?.let { readNcch(it, offset, length, language, ::bytes) }
            }
            else -> null
        }
    }

    private fun readNcch(header: ByteArray, base: Long, span: Long, language: String,
                         readAt: (Long, Int) -> ByteArray?): ThreeDsGameMetadata? {
        if (!magic(header, 0x100, "NCCH")) return null
        if ((u8(header, 0x18d) and 2) == 0 || (u8(header, 0x18f) and 4) == 0) return null
        // Bounded unsigned-sector multiplication; oversized exponents cannot wrap into valid offsets.
        val exponent = u8(header, 0x18e)
        if (exponent > 22) return null
        val unit = HEADER_BYTES.toLong() shl exponent
        val contentBytes = u32(header, 0x104) * unit
        if (contentBytes < HEADER_BYTES || contentBytes > span) return null
        val exefsOffset = u32(header, 0x1a0) * unit
        val exefsBytes = u32(header, 0x1a4) * unit
        if (exefsOffset < HEADER_BYTES || exefsBytes < HEADER_BYTES ||
            !within(exefsOffset, exefsBytes, contentBytes)) return null
        val exefs = readAt(base + exefsOffset, HEADER_BYTES) ?: return null
        val iconEntry = (0 until 8).firstOrNull { slot ->
            val start = slot * 16
            magic(exefs, start, "icon") && (start + 4 until start + 8).all { exefs[it] == 0.toByte() }
        } ?: return null
        val iconOffset = u32(exefs, iconEntry * 16 + 8)
        val iconBytes = u32(exefs, iconEntry * 16 + 12)
        if (iconBytes < SMDH_BYTES || !within(iconOffset, iconBytes, exefsBytes - HEADER_BYTES)) return null
        val smdh = readAt(base + exefsOffset + HEADER_BYTES + iconOffset, SMDH_BYTES) ?: return null
        val titleId = (0x118 + 7 downTo 0x118).joinToString("") { "%02x".format(Locale.ROOT, u8(header, it)) }
            .takeUnless { it == "0000000000000000" }
        return readSmdh(smdh, language, titleId)
    }

    /** Standalone SMDH files use the same validated text extraction as embedded ExeFS icons. */
    fun readSmdh(bytes: ByteArray, language: String, titleId: String? = null): ThreeDsGameMetadata? {
        if (bytes.size < SMDH_BYTES || !magic(bytes, 0, "SMDH")) return null
        val preferred = when (language.replace('_', '-').lowercase(Locale.ROOT).substringBefore('-')) {
            "ja" -> 0; "en" -> 1; "fr" -> 2; "de" -> 3; "it" -> 4; "es" -> 5
            "zh" -> if (language.contains("Hant", true) || language.endsWith("TW", true)) 11 else 6
            "ko" -> 7; "nl" -> 8; "pt" -> 9; "ru" -> 10; else -> 1
        }
        for (slot in (listOf(preferred, 1) + (0 until 12)).distinct()) {
            val start = 8 + slot * TITLE_BYTES
            val title = text(bytes, start, 0x80).ifBlank { text(bytes, start + 0x80, 0x100) }
            if (title.isNotBlank()) return ThreeDsGameMetadata(title, text(bytes, start + 0x180, 0x80), titleId)
        }
        return null
    }

    private fun text(bytes: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && (bytes[end] != 0.toByte() || bytes[end + 1] != 0.toByte())) end += 2
        val decoded = try {
            Charsets.UTF_16LE.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, end - offset)).toString()
        } catch (_: CharacterCodingException) { return "" }
        if (decoded.any { it == '\ufffd' || (it.isISOControl() && !it.isWhitespace()) }) return ""
        return decoded.replace(whitespace, " ").trim()
    }

    private fun magic(bytes: ByteArray, offset: Int, expected: String) =
        expected.indices.all { bytes[offset + it].toInt() == expected[it].code }
    private fun u8(bytes: ByteArray, offset: Int) = bytes[offset].toInt() and 0xff
    private fun u32(bytes: ByteArray, offset: Int) = (0 until 4).fold(0L) { n, i -> n or (u8(bytes, offset + i).toLong() shl (i * 8)) }
    private fun within(offset: Long, length: Long, size: Long) =
        offset >= 0 && length >= 0 && size >= 0 && offset <= size && length <= size - offset
}
