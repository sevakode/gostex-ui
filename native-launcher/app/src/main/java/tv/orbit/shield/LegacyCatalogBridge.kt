package tv.orbit.shield

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Transitional private copy for installations that used a bundled personal catalog.
 * It is not a library source: only persisted grants can add games to the library. */
internal object LegacyCatalogBridge {
    private const val MAX_BYTES = 1024 * 1024
    private val lock = Mutex()
    private var captured = false

    private fun file(context: Context) = AtomicFile(File(context.filesDir, "legacy-catalog.json"))

    private fun bounded(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            check(output.size() + n <= MAX_BYTES) { "Legacy catalog exceeds its size limit" }
            output.write(buffer, 0, n)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun legacy(text: String): List<Game>? = runCatching {
        LibraryImport.decode(text).takeIf { games -> games.any { it.tree.isNotBlank() && it.files.isNotEmpty() } }
    }.getOrNull()

    /** Default settings alone are not evidence of an existing personal library. */
    internal fun hasLibraryState(context: Context): Boolean {
        val values = context.getSharedPreferences("orbit", Context.MODE_PRIVATE).all
        val roots = (values["libraryRoots"] as? String)?.trim().orEmpty()
        if (roots.isNotEmpty() && roots != "[]") return true
        if (File(context.filesDir, "imported-games.json").length() > 2) return true
        if (File(context.filesDir, "covers/index.json").length() > 2) return true
        if (listOf("legacy-catalog.json", "legacy-catalog.json.bak", "legacy-catalog.json.new").any { File(context.filesDir, it).exists() }) return true
        if ((values["home.sections.v1"] as? String)?.contains("game:") == true) return true
        if ((values["home.selectedGame"] as? String)?.isNotBlank() == true) return true
        if ((values["favorites"] as? Set<*>)?.isNotEmpty() == true) return true
        return values.keys.any { it.startsWith("played:") || it.startsWith("covers.choice.") }
    }

    fun load(context: Context): List<Game> {
        val target = file(context)
        // AtomicFile.openRead can delete an outstanding .new when a base file exists.
        // Read the stable base directly in that case; leave both files untouched.
        val pending = File(target.baseFile.path + ".new")
        val saved = runCatching {
            if (pending.exists()) target.baseFile.takeIf { it.isFile }?.inputStream()?.use(::bounded)
            else target.openRead().use(::bounded)
        }.getOrNull()
        saved?.let { legacy(it)?.let { games -> return games } }
        return LibraryImport.decode(context.assets.open("catalog.json").bufferedReader().use { it.readText() })
    }

    /** Called only while holding lock; corrupt or interrupted copies are retained. */
    private fun validPrivateCopyExists(context: Context): Boolean {
        val target = file(context)
        // Check before openRead: it may otherwise remove an interrupted .new file.
        check(!File(target.baseFile.path + ".new").exists()) { "Saved legacy catalog cannot be read" }
        val existing = runCatching { target.openRead().use(::bounded) }.getOrNull()
        if (existing != null) {
            check(legacy(existing) != null) { "Saved legacy catalog cannot be verified" }
            return true
        }
        // A corrupt or incomplete existing file is retained for recovery, never replaced.
        check(!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists() &&
            !File(target.baseFile.path + ".new").exists()) { "Saved legacy catalog cannot be read" }
        return false
    }

    suspend fun hasVerifiedCopy(context: Context): Boolean = lock.withLock {
        currentCoroutineContext().ensureActive()
        validPrivateCopyExists(context)
    }

    /** Called only while holding lock; never replaces any existing private copy. */
    private fun adoptIfMissing(context: Context, text: String) {
        check(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES && legacy(text) != null) { "Invalid legacy catalog" }
        if (validPrivateCopyExists(context)) return
        val target = file(context)
        val stream = target.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (e: Exception) {
            target.failWrite(stream)
            throw e
        }
        check(runCatching { target.openRead().use(::bounded) == text }.getOrDefault(false)) { "Saved legacy catalog verification failed" }
    }

    /** Only use after authenticating the whole package and its catalog digest. */
    suspend fun adoptVerified(context: Context, text: String) = lock.withLock {
        currentCoroutineContext().ensureActive()
        adoptIfMissing(context, text)
        captured = true
    }

    /** Called from the existing IO scan, never from composition or a periodic task. */
    suspend fun capture(context: Context) = lock.withLock {
        if (captured) return@withLock
        if (!hasLibraryState(context)) return@withLock
        if (validPrivateCopyExists(context)) {
            captured = true
            return@withLock
        }
        val text = context.assets.open("catalog.json").use(::bounded)
        if (legacy(text) == null) return@withLock
        currentCoroutineContext().ensureActive()
        adoptIfMissing(context, text)
        captured = true
    }
}
