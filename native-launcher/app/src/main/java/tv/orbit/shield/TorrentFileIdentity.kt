package tv.orbit.shield

import java.io.IOException
import java.security.MessageDigest

/** Hash the exact bencoded info dictionary, never a decoded/re-encoded copy. */
internal object TorrentFileIdentity {
    fun infoHash(data: ByteArray): String {
        if (data.isEmpty() || data.size > 4 * 1024 * 1024) throw IOException("Invalid torrent size")
        val parser = Parser(data)
        val range = parser.infoRange()
        return MessageDigest.getInstance("SHA-1").digest(data.copyOfRange(range.first, range.last + 1))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private class Parser(private val data: ByteArray) {
        private var pos = 0
        private fun fail(): Nothing = throw IOException("Invalid torrent encoding")
        private fun peek(): Int = if (pos < data.size) data[pos].toInt() and 255 else fail()
        private fun take(value: Char) { if (peek() != value.code) fail(); pos++ }
        private fun bytes(): IntRange {
            val start = pos
            var length = 0
            while (peek() in '0'.code..'9'.code) {
                if (pos > start && data[start] == '0'.code.toByte()) fail()
                val digit = peek() - '0'.code
                if (length > (data.size - digit) / 10) fail()
                length = length * 10 + digit
                pos++
            }
            if (pos == start) fail()
            take(':')
            if (length > data.size - pos) fail()
            val range = pos until pos + length
            pos += length
            return range
        }
        private fun value(depth: Int) {
            if (depth > 64) fail()
            when (peek()) {
                'i'.code -> {
                    pos++
                    val start = pos
                    if (peek() == '-'.code) pos++
                    val digits = pos
                    while (peek() in '0'.code..'9'.code) pos++
                    if (pos == digits || (pos - digits > 1 && data[digits] == '0'.code.toByte()) ||
                        (digits > start && data[digits] == '0'.code.toByte())) fail()
                    take('e')
                }
                'l'.code -> { pos++; while (peek() != 'e'.code) value(depth + 1); pos++ }
                'd'.code -> { pos++; while (peek() != 'e'.code) { bytes(); value(depth + 1) }; pos++ }
                in '0'.code..'9'.code -> { bytes() }
                else -> fail()
            }
        }
        fun infoRange(): IntRange {
            take('d')
            var info: IntRange? = null
            while (peek() != 'e'.code) {
                val key = bytes()
                val isInfo = key.count() == 4 && data[key.first] == 'i'.code.toByte() &&
                    data[key.first + 1] == 'n'.code.toByte() && data[key.first + 2] == 'f'.code.toByte() && data[key.first + 3] == 'o'.code.toByte()
                val start = pos
                if (isInfo && (info != null || peek() != 'd'.code)) fail()
                value(1)
                if (isInfo) info = start until pos
            }
            pos++
            if (pos != data.size) fail()
            return info ?: fail()
        }
    }
}
