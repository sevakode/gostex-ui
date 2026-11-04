package dev.gostex.release

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Bounded, app-owned operational diagnostics. Callers supply event codes and deliberately chosen
 * metadata, never application content, preferences, environment dumps or device identifiers.
 * Writes are best effort; explicit [export] and [clear] report IO failures to the caller.
 */
class DiagnosticLog(directory: File) {
    @Volatile var enabled: Boolean = true

    private val directory = directory.absoluteFile
    private val lock = Any()
    private val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun event(type: String, detail: String = "") {
        if (!enabled) return
        val safeType = utf8(sanitize(type), MAX_TYPE_BYTES).toString(Charsets.UTF_8)
        val safeDetail = singleLine(sanitize(detail))
        try {
            synchronized(lock) {
                if (!enabled) return
                val root = root(create = true)
                val files = LOG_NAMES.map { managedFile(root, it) }
                // These names belong exclusively to this log. Discard corrupt oversized files,
                // rather than allowing a previous writer to escape the storage bound.
                files.forEach { if (it.exists() && it.length() > MAX_FILE_BYTES) delete(it) }
                val prefix = "${timestamp.format(Date())}\t${singleLine(safeType)}\t"
                val prefixBytes = prefix.toByteArray(Charsets.UTF_8)
                val detailBytes = utf8(safeDetail, MAX_EVENT_BYTES - prefixBytes.size - 1)
                val bytes = prefixBytes + detailBytes + byteArrayOf('\n'.code.toByte())
                if (files[0].length() + bytes.size > MAX_FILE_BYTES) rotate(files)
                FileOutputStream(files[0], true).use { it.write(bytes) }
            }
        } catch (_: IOException) {
            // Diagnostics must not interrupt launching or updating an application.
        } catch (_: SecurityException) {
            // A denied diagnostic directory must not interrupt the caller either.
        }
    }

    fun failure(where: String, error: Throwable) {
        if (!enabled) return
        val detail = StringBuilder()
        val seen = IdentityHashMap<Throwable, Boolean>()
        var current: Throwable? = error
        var causes = 0
        while (current != null && causes < MAX_CAUSES && detail.length < MAX_INPUT_CHARS) {
            if (seen.put(current, true) != null) {
                detail.append("\n[circular cause]")
                break
            }
            if (causes > 0) detail.append("\nCaused by: ")
            detail.appendBounded(current.javaClass.name)
            current.message?.let { detail.append(": ").appendBounded(it) }
            val frames = current.stackTrace
            for (index in 0 until minOf(frames.size, MAX_STACK_FRAMES)) {
                detail.append("\n at ").appendBounded(frames[index].toString())
                if (detail.length >= MAX_INPUT_CHARS) break
            }
            if (frames.size > MAX_STACK_FRAMES) detail.append("\n[more frames]")
            current = current.cause
            causes++
        }
        if (current != null && causes == MAX_CAUSES) detail.append("\n[more causes]")
        event("failure", "$where: $detail")
    }

    /**
     * Exports bounded copies of the four known logs, supplied metadata and an explicit thread snapshot.
     * No filesystem, preferences, machine information, ROM or screenshot discovery occurs.
     * The destination is replaced only after a complete ZIP has been written successfully.
     */
    @Throws(IOException::class)
    fun export(destination: File, metadata: Map<String, String>): File {
        val snapshot = synchronized(lock) {
            val root = root(create = false)
            LOG_NAMES.asReversed().mapNotNull { name ->
                val file = managedFile(root, name)
                if (!file.exists()) null else file.inputStream().use { input ->
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var remaining = MAX_FILE_BYTES
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (count == -1) break
                        bytes.write(buffer, 0, count)
                        remaining -= count
                    }
                    bytes.toByteArray()
                }
            }
        }
        val target = destination.absoluteFile
        val root = directory.canonicalFile
        val canonicalTarget = target.canonicalFile
        if (canonicalTarget == root || LOG_NAMES.any { canonicalTarget == File(root, it) }) {
            throw IOException("Diagnostic export cannot replace its log files")
        }
        val parent = target.parentFile ?: throw IOException("Missing export directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create export directory")
        if (target.isDirectory) throw IOException("Diagnostic destination is a directory")
        val safeMetadata = metadataBytes(metadata)
        val safeEvents = eventBytes(snapshot)
        val safeThreads = threadBytes()
        val temporary = File.createTempFile("gostex-diagnostic-", ".zip", parent)
        try {
            FileOutputStream(temporary).use { output ->
                ZipOutputStream(BoundedOutputStream(output, MAX_EXPORT_BYTES)).use { zip ->
                    zip.putNextEntry(ZipEntry("metadata.txt"))
                    zip.write(safeMetadata)
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("events.log"))
                    zip.write(safeEvents)
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("threads.txt"))
                    zip.write(safeThreads)
                    zip.closeEntry()
                }
            }
            if (!temporary.renameTo(target)) throw IOException("Cannot publish diagnostic export")
            return destination
        } finally {
            temporary.delete()
        }
    }

    /** Deletes only this logger's four filenames; unrelated files are never removed. */
    @Throws(IOException::class)
    fun clear() {
        synchronized(lock) {
            val root = root(create = false)
            LOG_NAMES.forEach { name ->
                val file = File(root, name)
                // Deleting a link itself is safe; reading or appending through it is not.
                if (file.exists() || file.canonicalFile != file.absoluteFile) delete(file)
            }
        }
    }

    private fun root(create: Boolean): File {
        val root = directory.canonicalFile
        if (create && !root.isDirectory && !root.mkdirs()) throw IOException("Cannot create diagnostic directory")
        if (root.exists() && !root.isDirectory) throw IOException("Diagnostic directory is not a directory")
        return root
    }

    private fun managedFile(root: File, name: String): File {
        val file = File(root, name)
        if (file.canonicalFile != file.absoluteFile) throw IOException("Diagnostic log link is not allowed")
        if (file.exists() && !file.isFile) throw IOException("Diagnostic log is not a regular file")
        return file
    }

    private fun rotate(files: List<File>) {
        if (files.last().exists()) delete(files.last())
        for (index in files.lastIndex - 1 downTo 0) {
            if (files[index].exists() && !files[index].renameTo(files[index + 1])) {
                throw IOException("Cannot rotate diagnostic log")
            }
        }
    }

    private fun delete(file: File) {
        if (!file.delete()) throw IOException("Cannot delete diagnostic log")
    }

    private fun metadataBytes(metadata: Map<String, String>): ByteArray {
        val result = ByteArrayOutputStream()
        result.write("format=gostex-diagnostics-v1\n".toByteArray(Charsets.UTF_8))
        var count = 0
        for ((key, value) in metadata) {
            if (count++ >= MAX_METADATA_FIELDS) break
            // Sanitize the whole pair so a sensitive key also hides an otherwise opaque value.
            val line = singleLine(sanitize("${key.take(128)}=${value.take(4096)}"))
            val bytes = utf8(line, minOf(4096, MAX_METADATA_BYTES - result.size() - 1))
            if (bytes.isEmpty()) break
            result.write(bytes)
            result.write('\n'.code)
            if (result.size() >= MAX_METADATA_BYTES - 1) break
        }
        return result.toByteArray()
    }

    private fun eventBytes(snapshot: List<ByteArray>): ByteArray {
        val result = ByteArrayOutputStream()
        for (file in snapshot) {
            for (line in file.toString(Charsets.UTF_8).lineSequence()) {
                if (line.isEmpty()) continue
                val remaining = MAX_EVENT_EXPORT_BYTES - result.size()
                if (remaining <= 1) return result.toByteArray()
                val bytes = utf8(singleLine(sanitize(line)), minOf(MAX_EVENT_BYTES - 1, remaining - 1))
                result.write(bytes)
                result.write('\n'.code)
            }
        }
        return result.toByteArray()
    }

    /** Runs only for explicit export; bounded enumeration avoids a whole-JVM stack dump. */
    private fun threadBytes(): ByteArray {
        val current = Thread.currentThread()
        val threads = ArrayList<Thread>(MAX_EXPORT_THREADS).apply { add(current) }
        try {
            var group = current.threadGroup
            while (group?.parent != null) group = group.parent
            val candidates = arrayOfNulls<Thread>(MAX_EXPORT_THREADS - 1)
            val count = group?.enumerate(candidates, true) ?: 0
            for (index in 0 until minOf(count, candidates.size)) {
                val thread = candidates[index] ?: continue
                if (threads.none { it.id == thread.id }) threads.add(thread)
            }
        } catch (_: SecurityException) {
            // The exporting thread is still useful when thread-group inspection is denied.
        }
        val output = ByteArrayOutputStream()
        fun line(value: String): Boolean {
            val remaining = MAX_THREAD_BYTES - output.size()
            if (remaining <= 1) return false
            output.write(utf8(singleLine(sanitize(value)), minOf(2048, remaining - 1)))
            output.write('\n'.code)
            return output.size() < MAX_THREAD_BYTES
        }
        for (thread in threads) {
            // Thread names can contain content or user data; never read or serialize them.
            if (!line("thread id=${thread.id} state=${thread.state.name}")) break
            val frames = try { thread.stackTrace } catch (_: SecurityException) { emptyArray() }
            for (index in 0 until minOf(frames.size, MAX_STACK_FRAMES)) {
                val frame = frames[index]
                // Avoid class-loader/module labels from StackTraceElement.toString().
                val text = " at ${frame.className}.${frame.methodName}(${frame.fileName ?: "Unknown Source"}:${frame.lineNumber})"
                if (!line(text)) return output.toByteArray()
            }
        }
        return output.toByteArray()
    }

    private fun StringBuilder.appendBounded(value: String): StringBuilder =
        append(value, 0, minOf(value.length, maxOf(0, MAX_INPUT_CHARS - length)))

    private class BoundedOutputStream(private val output: OutputStream, private val maximum: Int) : OutputStream() {
        private var size = 0
        override fun write(value: Int) {
            reserve(1)
            output.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            reserve(length)
            output.write(bytes, offset, length)
        }
        override fun flush() = output.flush()
        override fun close() = output.close()
        private fun reserve(count: Int) {
            if (count > maximum - size) throw IOException("Diagnostic export exceeds size limit")
            size += count
        }
    }

    private companion object {
        const val MAX_FILE_BYTES = 128 * 1024
        const val MAX_EVENT_BYTES = 8 * 1024
        const val MAX_EXPORT_BYTES = 2 * 1024 * 1024
        const val MAX_EVENT_EXPORT_BYTES = 4 * MAX_FILE_BYTES
        const val MAX_METADATA_BYTES = 64 * 1024
        const val MAX_METADATA_FIELDS = 64
        const val MAX_THREAD_BYTES = 64 * 1024
        const val MAX_EXPORT_THREADS = 32
        const val MAX_INPUT_CHARS = 32 * 1024
        const val MAX_TYPE_BYTES = 96
        const val MAX_CAUSES = 4
        const val MAX_STACK_FRAMES = 16
        val LOG_NAMES = listOf("events.log", "events.1.log", "events.2.log", "events.3.log")
        const val TRUNCATED = "...[truncated]"

        val privateKey = Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)")
        val url = Regex("(?i)\\b(?:[a-z][a-z0-9+.-]{1,31}://|(?:https?|file|mailto):|www\\.)[^\\s<>\\\"']+")
        val authorization = Regex("(?i)(\\b(?:authorization|proxy-authorization|cookie|set-cookie)[\\\"']?\\s*[:=]\\s*)[^\\r\\n]*")
        val bearer = Regex("(?i)\\b(Bearer|Basic)\\s+[^\\s,;\\\"'<>]+")
        const val SENSITIVE = "(?:password|passwd|pwd|token|secret|api[_-]?key|private[_-]?key|client[_-]?secret|access[_-]?token|refresh[_-]?token|id[_-]?token|session[_-]?(?:id|key)|credential)"
        val sensitive = Regex("(?i)(\\b$SENSITIVE[\\\"']?\\s*(?:[:=]\\s*|\\s+))(\\\"(?:[^\\\"\\\\]|\\\\.)*\\\"|'(?:[^'\\\\]|\\\\.)*'|[^\\s,;]+)")
        val identity = Regex("(?i)(\\b(?:username|user|account|login|owner|email)[\\\"']?\\s*[:=]\\s*)(\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)")
        val email = Regex("\\b[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")
        val knownToken = Regex("\\b(?:gh[pousr]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+|sk-[A-Za-z0-9_-]{12,}|eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)\\b")
        // A path with spaces has no reliable end in free text. Hide the rest of that line too.
        val absolutePath = Regex("(?i)(?<![\\w])(?:[a-z]:[\\\\/]|\\\\\\\\|~[/\\\\]|/(?!/))[^\\r\\n]*")

        fun sanitize(raw: String): String {
            var value = raw.take(MAX_INPUT_CHARS)
            value = privateKey.replace(value, "[private key]")
            value = url.replace(value, "[url]")
            value = authorization.replace(value) { "${it.groupValues[1]}[redacted]" }
            value = bearer.replace(value) { "${it.groupValues[1]} [redacted]" }
            value = sensitive.replace(value) { "${it.groupValues[1]}[redacted]" }
            value = identity.replace(value) { "${it.groupValues[1]}[identity]" }
            value = email.replace(value, "[identity]")
            value = knownToken.replace(value, "[redacted]")
            return absolutePath.replace(value, "[path]")
        }

        fun singleLine(value: String): String = buildString {
            for (character in value) when {
                character == '\n' || character == '\u2028' || character == '\u2029' -> append("\\n")
                character == '\r' -> append("\\r")
                character == '\t' -> append("\\t")
                character.isISOControl() -> append('?')
                else -> append(character)
            }
        }

        /** UTF-8 byte bound that never splits a Unicode code point. */
        fun utf8(value: String, maximum: Int): ByteArray {
            if (maximum <= 0) return byteArrayOf()
            val bytes = value.toByteArray(Charsets.UTF_8)
            if (bytes.size <= maximum) return bytes
            val suffix = TRUNCATED.toByteArray(Charsets.UTF_8)
            if (maximum <= suffix.size) return suffix.copyOf(maximum)
            var end = maximum - suffix.size
            while (end > 0 && (bytes[end].toInt() and 0xc0) == 0x80) end--
            return bytes.copyOf(end) + suffix
        }
    }
}
