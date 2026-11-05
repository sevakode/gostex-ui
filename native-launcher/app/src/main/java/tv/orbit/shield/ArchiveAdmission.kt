package tv.orbit.shield

import java.util.Locale
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.concurrent.ConcurrentHashMap

/** File extension and archive metadata alone cannot prove torrent completion. */
internal fun isUnverifiedArchive(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("zip", "7z", "rar")

/** Preserve earlier cards while declining to manufacture new unverified cards. */
internal fun admitArchiveCandidate(name: String, alreadyImported: Boolean): Boolean =
    !isUnverifiedArchive(name) || alreadyImported

/** Validate one ZIP; never extract or trust an extension alone.
 * Existing sources are not re-read on every refresh. A non-seekable provider cannot be
 * verified and remains rejected. The budget limits CPU/disk work and archive bombs. */
internal fun verifySelectedZip(file: File, romExtensions: Set<String>, checkpoint: () -> Unit = {}): Boolean {
    val maxRomBytes = 512L * 1024 * 1024
    val maxEntries = 256
    return ZipFile(file).use outer@ { zip ->
        val entries = zip.entries()
        val candidates = mutableListOf<java.util.zip.ZipEntry>()
        var count = 0
        while (entries.hasMoreElements()) {
            checkpoint()
            val entry = entries.nextElement()
            if (++count > maxEntries) return@outer false
            val path = entry.name
            if (path.isEmpty() || path.startsWith('/') || '\\' in path || ':' in path ||
                path.any { it.code < 32 } || path.split('/').any { it == ".." || it == "." }) return@outer false
            if (!entry.isDirectory && entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in romExtensions) candidates += entry
        }
        if (candidates.size != 1) return@outer false
        val rom = candidates.single()
        if (rom.size !in 1..maxRomBytes || rom.crc < 0 || rom.compressedSize !in 1..maxRomBytes) return@outer false
        val checksum = CRC32()
        var total = 0L
        zip.getInputStream(rom).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkpoint()
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > rom.size || total > maxRomBytes) return@outer false
                checksum.update(buffer, 0, n)
            }
        }
        total == rom.size && checksum.value == rom.crc
    }
}

/** Avoid repeatedly rereading unfinished/corrupt downloads on quick UI refreshes.
 * Failures are deliberately short-lived: the file may finish downloading later. */
internal class ArchiveFailureCache<K : Any>(private val ttlMs: Long = 60_000, private val capacity: Int = 256) {
    private val until = ConcurrentHashMap<K, Long>()
    fun skip(key: K, nowMs: Long): Boolean {
        val expiry = until[key] ?: return false
        if (nowMs < expiry) return true
        until.remove(key, expiry)
        return false
    }
    fun record(key: K, nowMs: Long) {
        if (until.size >= capacity) until.clear()
        until[key] = nowMs + ttlMs
    }
    fun remove(key: K) { until.remove(key) }
}

internal data class ArchiveFileStamp(val size: Long, val modifiedMs: Long)

/** A short-lived optimisation for scans interrupted before their snapshot is committed.
 * File metadata is only a heuristic, so absent/changed metadata never reuses evidence. */
internal class ArchiveSuccessCache<K : Any>(private val ttlMs: Long = 300_000, private val capacity: Int = 256) {
    private data class Entry(val stamp: ArchiveFileStamp, val untilMs: Long)
    private val entries = LinkedHashMap<K, Entry>(16, .75f, true)
    @Synchronized fun reuse(key: K, stamp: ArchiveFileStamp?, nowMs: Long): Boolean {
        val entry = entries[key] ?: return false
        if (stamp == null || stamp != entry.stamp || nowMs >= entry.untilMs) {
            entries.remove(key)
            return false
        }
        return true
    }
    @Synchronized fun remember(key: K, before: ArchiveFileStamp?, after: ArchiveFileStamp?, nowMs: Long) {
        if (before == null || before != after || capacity <= 0) { entries.remove(key); return }
        entries[key] = Entry(before, nowMs + ttlMs)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }
}
