package tv.orbit.shield

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract as Docs
import android.util.AtomicFile
import androidx.compose.runtime.*
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal data class ManagedCoverOptions(val folder: String = "", val automatic: Boolean = true,
    val choices: Map<String, CoverChoice> = emptyMap())
internal val LocalManagedCovers = staticCompositionLocalOf<Map<String, ManagedCover>> { emptyMap() }
internal val LocalManagedCoverOptions = staticCompositionLocalOf { ManagedCoverOptions() }

internal object ManagedCoverPreferences {
    private const val PREFIX = "covers."
    private fun prefs(context: Context) = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)
    fun read(context: Context): ManagedCoverOptions {
        val p = prefs(context)
        val folder = p.readStringOrDefault(PREFIX + "folder", "").orEmpty().takeIf {
            it.length <= 4096 && (it.isEmpty() || runCatching { Docs.isTreeUri(Uri.parse(it)) }.getOrDefault(false))
        }.orEmpty()
        val choices = p.all.mapNotNull { (key, value) ->
            key.removePrefix(PREFIX + "choice.").takeIf { key.startsWith(PREFIX + "choice.") && it.isNotBlank() }
                ?.let { it to CoverChoice.read(value as? String) }
        }.toMap()
        return ManagedCoverOptions(folder, p.readBooleanOrDefault(PREFIX + "automatic", true), choices)
    }
    fun folder(context: Context, uri: String) { prefs(context).edit().putString(PREFIX + "folder", uri).apply() }
    fun automatic(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean(PREFIX + "automatic", enabled).apply() }
    fun choice(context: Context, id: String, choice: CoverChoice) {
        prefs(context).edit().putString(PREFIX + "choice." + id, choice.id).apply()
    }
    fun affects(key: String?) = key == null || key.startsWith(PREFIX)
}

@Composable internal fun rememberManagedCoverOptions(): ManagedCoverOptions {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("orbit", Context.MODE_PRIVATE) }
    var options by remember(context) { mutableStateOf(ManagedCoverPreferences.read(context)) }
    DisposableEffect(context, prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (ManagedCoverPreferences.affects(key)) options = ManagedCoverPreferences.read(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        options = ManagedCoverPreferences.read(context)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return options
}

internal data class CoverSyncResult(val entries: Map<String, ManagedCover>, val applied: Int, val rejected: Int)

/** Images are immutable and content-addressed, so decoded cards cannot show a stale overwrite.
 * A failed batch leaves both the prior index and its original images available. */
internal object ManagedCoverStore {
    private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
    private const val STORE_BUDGET = 128L * 1024 * 1024
    private val lock = Mutex()
    private var lastImport = ""
    private var lastExport = ""
    private fun directory(context: Context) = File(context.filesDir, "covers").apply { mkdirs() }
    fun imageFile(context: Context, key: String): File? = managedCoverDigest(key)?.let { File(directory(context), "$it.image") }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun index(context: Context) = AtomicFile(File(directory(context), "index.json"))
    fun read(context: Context): Map<String, ManagedCover> = runCatching {
        ManagedCoverManifest.decodeIndex(index(context).openRead().use { bounded(it, ManagedCoverManifest.MAX_BYTES).toString(Charsets.UTF_8) })
    }.getOrElse {
        runCatching { ManagedCoverManifest.decodeIndex(File(directory(context), "previous.json").readText()) }.getOrDefault(emptyMap())
    }
    private fun save(context: Context, entries: Map<String, ManagedCover>) {
        require(entries.size <= ManagedCoverManifest.MAX_ENTRIES)
        val bytes = ManagedCoverManifest.encodeIndex(entries).toByteArray()
        require(bytes.size <= ManagedCoverManifest.MAX_BYTES)
        val target = index(context)
        if (target.baseFile.exists()) {
            val previous = AtomicFile(File(directory(context), "previous.json"))
            val backup = previous.startWrite()
            try { backup.write(target.readFully()); previous.finishWrite(backup) }
            catch (e: Exception) { previous.failWrite(backup); throw e }
        }
        val stream = target.startWrite()
        try { stream.write(bytes); target.finishWrite(stream) }
        catch (e: Exception) { target.failWrite(stream); throw e }
    }
    private fun bounded(input: InputStream, max: Int): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(bytes.size() + n <= max) { "File exceeds size limit" }
            bytes.write(buffer, 0, n)
        }
        return bytes.toByteArray()
    }
    private fun putImage(context: Context, bytes: ByteArray, expected: String? = null, wide: Boolean = false): String {
        require(bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES)
        val sha = digest(bytes)
        require(expected == null || expected == sha) { "Image checksum mismatch" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outMimeType in setOf("image/png", "image/jpeg", "image/webp") &&
            bounds.outWidth in 32..8192 && bounds.outHeight in 32..8192 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 33_554_432L) { "Unsupported or invalid image" }
        if (wide) require(kotlin.math.abs(bounds.outWidth.toFloat() / bounds.outHeight - TileLayout.aspectRatio) <= .04f) { "Wide cover does not match tile proportions" }
        var sample = 1
        while (bounds.outWidth.toLong() * bounds.outHeight / sample / sample > 2_097_152) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Image cannot be decoded")
        bitmap.recycle()
        val key = managedCoverKey(sha)
        val file = imageFile(context, key)!!
        if (!file.exists() || runCatching { digest(file.readBytes()) != sha }.getOrDefault(true)) {
            val used = directory(context).listFiles().orEmpty().sumOf { it.length() }
            require(used - file.length() + bytes.size <= STORE_BUDGET) { "Cover storage limit reached" }
            val target = AtomicFile(file)
            val stream = target.startWrite()
            try { stream.write(bytes); target.finishWrite(stream) }
            catch (e: Exception) { target.failWrite(stream); throw e }
        }
        return key
    }
    suspend fun importManual(context: Context, gameId: String, uri: Uri): Map<String, ManagedCover> = lock.withLock {
        val bytes = context.contentResolver.openInputStream(uri)?.use { bounded(it, MAX_IMAGE_BYTES) } ?: error("Image unavailable")
        currentCoroutineContext().ensureActive()
        val key = putImage(context, bytes)
        val entries = read(context).toMutableMap()
        entries[gameId] = (entries[gameId] ?: ManagedCover()).copy(manual = key, sourceName = "", sourceUrl = "", author = "")
        save(context, entries)
        entries.toMap()
    }
    suspend fun importOnline(context: Context, gameId: String, bytes: ByteArray, image: OnlineCoverImage): Map<String, ManagedCover> = lock.withLock {
        currentCoroutineContext().ensureActive()
        val key = putImage(context, bytes)
        val entries = read(context).toMutableMap()
        entries[gameId] = (entries[gameId] ?: ManagedCover()).copy(manual = key,
            sourceName = image.provider.label, sourceUrl = image.sourceUrl.take(1000), author = image.author.take(120))
        currentCoroutineContext().ensureActive()
        save(context, entries)
        entries.toMap()
    }
    private data class Document(val uri: Uri, val name: String, val mime: String)
    private class Tree(val context: Context, val root: Uri) {
        val resolver = context.contentResolver
        val rootDoc = Docs.buildDocumentUriUsingTree(root, Docs.getTreeDocumentId(root))
        fun children(parent: Uri): List<Document> {
            val query = Docs.buildChildDocumentsUriUsingTree(root, Docs.getDocumentId(parent))
            return resolver.query(query, arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME, Docs.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        check(size < 4096) { "Folder contains too many files" }
                        add(Document(Docs.buildDocumentUriUsingTree(root, cursor.getString(0)), cursor.getString(1), cursor.getString(2)))
                    }
                }
            } ?: error("Folder unavailable")
        }
        fun find(path: String): Uri? {
            require(ManagedCoverManifest.imagePath(path) || path in setOf("updates.json", "library.json"))
            var parent = rootDoc
            val parts = path.split('/')
            parts.forEachIndexed { i, name ->
                val matches = children(parent).filter { it.name == name }
                require(matches.size <= 1) { "Ambiguous file name" }
                val item = matches.firstOrNull() ?: return null
                require(i == parts.lastIndex || item.mime == Docs.Document.MIME_TYPE_DIR)
                parent = item.uri
            }
            return parent
        }
        fun read(path: String, max: Int) = find(path)?.let { resolver.openInputStream(it)?.use { stream -> bounded(stream, max) } }
        fun folder(name: String): Uri = children(rootDoc).firstOrNull { it.name == name && it.mime == Docs.Document.MIME_TYPE_DIR }?.uri
            ?: Docs.createDocument(resolver, rootDoc, Docs.Document.MIME_TYPE_DIR, name) ?: error("Cannot create folder")
        fun write(parent: Uri, name: String, mime: String, bytes: ByteArray) {
            val existing = children(parent).filter { it.name == name }
            require(existing.size <= 1)
            val uri = existing.firstOrNull()?.uri ?: Docs.createDocument(resolver, parent, mime, name) ?: error("Cannot create file")
            resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Cannot write file")
        }
    }
    suspend fun images(context: Context, folder: String): List<Pair<String, Uri>> {
        val tree = Tree(context, Uri.parse(folder))
        val found = mutableListOf<Pair<String, Uri>>()
        var visited = 0
        suspend fun walk(parent: Uri, prefix: String, depth: Int) {
            for (child in tree.children(parent)) {
                currentCoroutineContext().ensureActive()
                check(++visited <= 4096) { "Folder contains too many files" }
                val path = prefix + child.name
                if (child.mime == Docs.Document.MIME_TYPE_DIR && depth < 3 && child.name !in setOf(".", ".."))
                    walk(child.uri, "$path/", depth + 1)
                else if (child.mime != Docs.Document.MIME_TYPE_DIR && ManagedCoverManifest.imagePath(path)) {
                    check(found.size < ManagedCoverManifest.MAX_ENTRIES) { "Folder contains too many images" }
                    found += path to child.uri
                }
            }
        }
        walk(tree.rootDoc, "", 0)
        return found.sortedWith(compareBy<Pair<String, Uri>> { !it.first.startsWith("incoming/") }.thenBy { it.first })
    }
    suspend fun sync(context: Context, games: List<Game>, folder: String, importUpdates: Boolean, forceExport: Boolean = false): CoverSyncResult = lock.withLock {
        val tree = Tree(context, Uri.parse(folder))
        var entries = read(context)
        var applied = 0
        var rejected = 0
        if (importUpdates) {
            val bytes = tree.read("updates.json", ManagedCoverManifest.MAX_BYTES)
            if (bytes != null) {
                val fingerprint = digest(bytes) + games.map { it.id }.sorted().joinToString("|") + folder
                if (fingerprint != lastImport) {
                    val updates = try { ManagedCoverManifest.parse(bytes.toString(Charsets.UTF_8)) }
                    catch (_: Exception) { rejected++; emptyList() }
                    val ids = games.filterNot { it.demo }.map { it.id }.toSet()
                    val next = entries.toMutableMap()
                    for (update in updates) {
                        currentCoroutineContext().ensureActive()
                        if (update.gameId !in ids) { rejected++; continue }
                        try {
                            fun load(path: String, sha: String, wide: Boolean = false): String? = path.takeIf { it.isNotEmpty() }?.let {
                                putImage(context, tree.read(it, MAX_IMAGE_BYTES) ?: error("Image missing"), sha, wide)
                            }
                            val old = next[update.gameId] ?: ManagedCover()
                            val updated = old.copy(original = load(update.original, update.originalSha256) ?: old.original,
                                wide = load(update.wide, update.wideSha256, wide = true) ?: old.wide)
                            if (updated != old) { next[update.gameId] = updated; applied++ }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { rejected++ }
                    }
                    if (next != entries) { save(context, next); entries = next.toMap() }
                    // Invalid/missing image entries can be retried after their files become ready.
                    if (rejected == 0) lastImport = fingerprint
                }
            }
        }
        export(context, tree, games, entries, forceExport)
        CoverSyncResult(entries, applied, rejected)
    }
    private suspend fun export(context: Context, tree: Tree, games: List<Game>, entries: Map<String, ManagedCover>, force: Boolean) {
        val options = ManagedCoverPreferences.read(context)
        val fingerprint = digest((tree.root.toString() + games.filterNot { it.demo }.joinToString { "${it.id}|${it.title}|${it.platform}|${it.cover}|${it.scene}" } +
            ManagedCoverManifest.encodeIndex(entries) + options.choices.toSortedMap()).toByteArray())
        val cache = File(context.cacheDir, "cover-export-fingerprint")
        if (!force && (fingerprint == lastExport || runCatching { cache.readText().take(128) == fingerprint }.getOrDefault(false)) && tree.find("library.json") != null) return
        val originals = tree.folder("originals")
        val existing = tree.children(originals).map { it.name }.toMutableSet()
        val rows = JSONArray()
        for (game in games.filterNot { it.demo }) {
            currentCoroutineContext().ensureActive()
            val base = game.cover.ifEmpty { game.scene }
            val entry = entries[game.id]
            val choice = options.choices[game.id] ?: CoverChoice.AUTO
            val selected = selectManagedCover(base, entry, choice)
            suspend fun exportImage(path: String): String? {
                if (path.isEmpty()) return null
                val bytes = runCatching {
                    val file = imageFile(context, path)
                    if (file != null) file.inputStream().use { bounded(it, MAX_IMAGE_BYTES) }
                    else context.assets.open(path).use { bounded(it, MAX_IMAGE_BYTES) }
                }.getOrNull() ?: return null
                val hash = digest(bytes)
                val ext = when {
                    bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
                    bytes.size > 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" -> "webp"
                    else -> "jpg"
                }
                val name = "$hash.$ext"
                if (existing.add(name)) tree.write(originals, name, "image/$ext", bytes)
                return "originals/$name"
            }
            val original = exportImage(base)
            val current = if (selected.original == base) original else exportImage(selected.original)
            val bundledWide = if (selected.original == base && choice != CoverChoice.ORIGINAL)
                CoverExtensions.find(context, base)?.image else null
            val wide = exportImage(selected.wide.ifEmpty { bundledWide.orEmpty() })
            val row = JSONObject().put("id", game.id).put("title", game.title).put("platform", game.platform)
                .put("choice", choice.id)
                .put("source", when {
                    choice == CoverChoice.MANUAL && !entry?.manual.isNullOrEmpty() -> "manual"
                    choice == CoverChoice.ORIGINAL -> "bundled-original"
                    !entry?.original.isNullOrEmpty() || !entry?.wide.isNullOrEmpty() -> "shared-folder"
                    else -> "bundled"
                })
                .put("original", original ?: JSONObject.NULL)
                .put("current", current ?: JSONObject.NULL).put("wide", wide ?: JSONObject.NULL)
                .put("needsCover", current == null).put("hasWideCover", wide != null)
                .put("manualOverride", choice == CoverChoice.MANUAL && !entry?.manual.isNullOrEmpty())
                .put("selectedCredit", if (choice == CoverChoice.MANUAL && !entry?.sourceName.isNullOrEmpty()) JSONObject()
                    .put("source",entry.sourceName).put("url",entry.sourceUrl).put("author",entry.author) else JSONObject.NULL)
            rows.put(row)
        }
        val payload = JSONObject().put("version", 1).put("tileAspectRatio", TileLayout.aspectRatio.toDouble())
            .put("games", rows).toString(2).toByteArray()
        tree.write(tree.rootDoc, "library.json", "application/json", payload)
        lastExport = fingerprint
        runCatching { cache.writeText(fingerprint) }
    }
}
