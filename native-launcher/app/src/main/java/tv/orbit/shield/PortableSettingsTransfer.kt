package tv.orbit.shield

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.StringReader

/** On-demand file transfer of the settings allowed by [PortableSettingsCodec]. */
internal object PortableSettingsTransfer {
    private const val MAX_DOCUMENT_BYTES = 128 * 1024

    class Preview internal constructor(
        internal val original: Map<String, Any?>,
        internal val changes: Map<String, Any>,
    ) {
        val count: Int get() = changes.size
        val needsRecreation: Boolean get() = "language" in changes
    }

    suspend fun export(context: Context, destination: Uri) {
        require(destination.scheme == ContentResolver.SCHEME_CONTENT)
        val prefs = preferences(context)
        val snapshot = withContext(Dispatchers.Main.immediate) {
            checkAvailable()
            prefs.all
        }
        runInterruptible(Dispatchers.IO) {
            val document = PortableSettingsCodec.encode(PortableSettingsCodec.capture(snapshot))
            val bytes = document.encodeToByteArray(throwOnInvalidSequence = true)
            val output = context.contentResolver.openOutputStream(destination, "wt")
                ?: error("Cannot open settings destination")
            output.use { it.write(bytes) }
        }
    }

    suspend fun preview(context: Context, source: Uri): Preview {
        require(source.scheme == ContentResolver.SCHEME_CONTENT)
        val imported = runInterruptible(Dispatchers.IO) {
            PortableSettingsCodec.decode(readDocument(context.contentResolver, source))
        }
        return withContext(Dispatchers.Main.immediate) {
            checkAvailable()
            val current = preferences(context).all
            val changes = imported.filter { (key, value) -> current[key] != value }
            Preview(changes.keys.associateWith(current::get), changes)
        }
    }

    /** Returns false if a setting included in the confirmation has since changed. */
    suspend fun apply(context: Context, preview: Preview): Boolean = withContext(Dispatchers.Main.immediate) {
        checkAvailable()
        val prefs = preferences(context)
        val current = prefs.all
        if (preview.original.any { (key, before) -> current[key] != before }) return@withContext false
        if (preview.count == 0) return@withContext true
        val editor = prefs.edit()
        for ((key, value) in preview.changes) {
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Set<*> -> editor.putStringSet(key, value.map { it as String }.toSet())
                else -> error("Unsupported setting type")
            }
        }
        editor.apply()
        true
    }

    private fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("orbit", Context.MODE_PRIVATE)

    private fun checkAvailable() {
        check(PortableRestoreStartup.ready && !PortableMaintenance.running) { "Settings are not available" }
    }

    private fun readDocument(resolver: ContentResolver, source: Uri): String {
        val input = resolver.openInputStream(source) ?: error("Cannot open settings copy")
        val bytes = ByteArrayOutputStream()
        input.use {
            val buffer = ByteArray(8192)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val count = it.read(buffer, 0, minOf(buffer.size, MAX_DOCUMENT_BYTES - bytes.size() + 1))
                if (count < 0) break
                require(bytes.size() + count <= MAX_DOCUMENT_BYTES) { "Settings copy is too large" }
                bytes.write(buffer, 0, count)
            }
        }
        val document = bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
        requirePortableJson(document, MAX_DOCUMENT_BYTES)
        JsonReader(StringReader(document)).use { reader ->
            require(reader.peek() == JsonToken.BEGIN_OBJECT) { "Invalid settings copy" }
            reader.skipValue()
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing data in settings copy" }
        }
        return document
    }
}
