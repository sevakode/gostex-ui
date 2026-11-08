package tv.orbit.shield

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Docs
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object LibraryImport {
    private val archiveFailures = ArchiveFailureCache<Triple<String, String, String>>()
    private val archiveSuccesses = ArchiveSuccessCache<Triple<String, String, String>>()
    data class Root(val platform: String, val uri: String, val source: LibrarySource = LibrarySource.FILES)
    private fun writeRoots(context: Context, roots: List<Root>) {
        val prefs = context.getSharedPreferences("orbit", 0)
        if (roots.any { it.source == LibrarySource.PORTABLE_SNAPSHOT })
            LibraryRootCodec.requireLosslessLegacy(prefs.getString("libraryRoots", "[]") ?: error("Missing library roots"))
        val encoded = LibraryRootCodec.encode(roots.map { LibraryRootRecord(it.platform, it.uri, it.source) })
        prefs.edit().putString("libraryRoots", encoded).apply()
    }
    /** Old releases displayed the metadata catalog directly. Only adopt folders the owner
     * already granted; a catalog entry itself is never proof that a ROM is present. */
    fun migrateGrantedCatalogRoots(context: Context, catalog: List<Game>) {
        val preferences = context.getSharedPreferences("orbit", 0)
        if (runCatching { preferences.getBoolean("library.catalogRootsMigrated", false) }.getOrDefault(false)) return
        val existing = roots(context).toMutableList()
        val grants = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }.toSet()
        catalog.distinctBy { it.platform to it.tree }.forEach { game ->
            if (game.tree.isBlank() || PlatformRegistry.find(game.platform) == null) return@forEach
            val uri = runCatching {
                if (game.tree.startsWith("content:")) Uri.parse(game.tree)
                else Docs.buildTreeDocumentUri("com.android.externalstorage.documents", game.tree)
            }.getOrNull() ?: return@forEach
            if (uri in grants && existing.none { it.uri == uri.toString() && it.platform == game.platform }) existing += Root(game.platform, uri.toString())
        }
        writeRoots(context, existing)
        preferences.edit().putBoolean("library.catalogRootsMigrated", true).apply()
    }
    fun roots(context: Context): List<Root> = try {
        val preferences = context.getSharedPreferences("orbit", 0)
        val encoded = if (preferences.contains("libraryRoots")) preferences.getString("libraryRoots", null)
            ?: error("Missing library roots") else "[]"
        LibraryRootCodec.decode(encoded).map { Root(it.platform, it.uri, it.source) }
    } catch (e: Exception) {
        // An empty fallback would overwrite both source preferences and the saved snapshot.
        throw IllegalStateException(context.getString(R.string.library_roots_unavailable), e)
    }
    fun addRoot(context: Context, root: Root) {
        require(PlatformRegistry.find(root.platform) != null) { context.getString(R.string.platform_unknown) }
        val existing = roots(context)
        require(existing.none { it.uri == root.uri && it.platform == root.platform &&
            (it.source == LibrarySource.PORTABLE_SNAPSHOT) != (root.source == LibrarySource.PORTABLE_SNAPSHOT) }) {
            context.getString(R.string.portable_root_conflict)
        }
        val list = existing.filter { it.uri != root.uri || it.platform != root.platform } + root
        writeRoots(context, list)
    }
    fun removeRoot(context: Context, root: Root) {
        writeRoots(context, roots(context).filter { it != root })
    }
    fun read(context: Context): List<Game> {
        val file = java.io.File(context.filesDir, "imported-games.json")
        val backup = java.io.File(context.filesDir, "imported-games.json.bak")
        if (!file.exists() && !backup.exists()) return emptyList()
        return try {
            decode(android.util.AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        } catch (e: Exception) {
            // Treating an unreadable snapshot as empty would let the next scan erase it.
            throw IllegalStateException(context.getString(R.string.library_snapshot_unavailable), e)
        }
    }
    fun encode(games: List<Game>): String {
        val a = JSONArray()
        games.forEach { g -> a.put(JSONObject().put("id",g.id).put("title",g.title).put("platform",g.platform).put("emulator",g.emulator).put("component",g.component).put("files",JSONArray(g.files)).put("tree",g.tree).put("cover",g.cover).put("scene",g.scene).put("genre",g.genre).put("year",g.year).put("developer",g.developer).put("players",g.players).put("demo",g.demo).put("description",g.description)) }
        return a.toString()
    }
    fun write(context: Context, games: List<Game>): Boolean {
        val target = java.io.File(context.filesDir, "imported-games.json")
        val serialized = encode(games)
        if (target.exists() && target.readText() == serialized) return false
        val atomic = android.util.AtomicFile(target)
        val output = atomic.startWrite()
        try {
            output.write(serialized.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (e: Exception) {
            atomic.failWrite(output)
            throw e
        }
        return true
    }
    fun decode(text: String): List<Game> {
        val a=JSONArray(text)
        return (0 until a.length()).map { i ->
            val o=a.getJSONObject(i); val fs=o.getJSONArray("files")
            Game(o.getString("id"),o.getString("title"),o.getString("platform"),o.getString("emulator"),o.getString("component"),(0 until fs.length()).map { fs.getString(it) },o.optString("tree"),o.optString("cover"),o.optString("scene"),o.optString("genre"),o.optString("year"),o.optString("developer"),o.optString("players"),o.optBoolean("demo"),o.optString("description"))
        }
    }
    suspend fun scan(context: Context, root: Root, bundled: List<Game>, showHints: Boolean = true,
                     previousSnapshot: List<Game>? = null, onWarning: (String) -> Unit = {}): List<Game> {
        val scanContext = currentCoroutineContext()
        scanContext.ensureActive()
        val previous = previousSnapshot ?: read(context)
        if (root.source == LibrarySource.PORTABLE_SNAPSHOT)
            return portableRootSnapshot(root.uri, root.platform, previous)
        val platform = PlatformRegistry.find(root.platform) ?: error(context.getString(R.string.platform_unknown))
        if (root.source == LibrarySource.SINGLE_FILE) return scanSingle(context, root, bundled, previous)
        val tree = Uri.parse(root.uri)
        val indexed = mutableListOf<DiscImport.File>()
        var visited = 0
        val threeDs = root.platform == "Nintendo 3DS"
        val installed = threeDs && root.source == LibrarySource.AZAHAR_INSTALLED
        var hasSdmc = false
        var ciaPackages = 0
        val directories = mutableSetOf<String>()
        val known = catalogFiles(bundled, root.platform)
        val previousByFile = previousIndex(context, previous, root)
        val previousFiles = previous.filter { it.platform == root.platform }
            .flatMap { it.files }.map(::libraryFileKey).toSet()
        var unverifiedArchives = 0
        fun walk(id: String, parent: String, depth: Int) {
            scanContext.ensureActive()
            check(depth <= 16) { context.getString(R.string.too_deep) }
            check(directories.add(id)) { context.getString(R.string.folder_unavailable) }
            val children = Docs.buildChildDocumentsUriUsingTree(tree, id)
            val rows = context.contentResolver.query(children, arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME, Docs.Document.COLUMN_MIME_TYPE), null, null, null)
                ?: error(context.getString(R.string.folder_unavailable))
            rows.use { c ->
                fun requireComplete() {
                    val extras = c.extras
                    check(!extras.getBoolean(Docs.EXTRA_LOADING) && extras.getString(Docs.EXTRA_ERROR) == null) {
                        context.getString(R.string.import_folder_incomplete)
                    }
                }
                requireComplete()
                while (c.moveToNext()) {
                scanContext.ensureActive()
                check(++visited <= 10000) { context.getString(R.string.too_many_files) }
                val child = c.getString(0); val name = c.getString(1); val mime = c.getString(2)
                // Display names define relative references. Reject ambiguous provider names;
                // document IDs remain opaque and are only used with DocumentsContract.
                check(!name.isNullOrEmpty() && name !in setOf(".", "..") && '/' !in name && '\\' !in name) { context.getString(R.string.folder_unavailable) }
                val path = if (parent.isEmpty()) name else "$parent/$name"
                if (mime == Docs.Document.MIME_TYPE_DIR) {
                    if (path == "sdmc") hasSdmc = true
                    if (!installed || ThreeDsInstalledLayout.directory(path)) walk(child, path, depth + 1)
                    continue
                }
                if (installed && !ThreeDsInstalledLayout.application(path)) continue
                if (threeDs && name.endsWith(".cia", ignoreCase = true)) ciaPackages++
                if (platform.launchKind == LaunchKind.DUCKSTATION || platform.accepts(name)) indexed += DiscImport.File(path, child)
                }
                requireComplete()
            }
        }
        walk(Docs.getTreeDocumentId(tree), "", 0)
        check(!installed || hasSdmc) { context.getString(R.string.three_ds_wrong_data_folder) }
        if (showHints && ciaPackages > 0) onWarning(context.getString(R.string.three_ds_cia_found, ciaPackages))
        val entries = if (platform.launchKind == LaunchKind.DUCKSTATION) {
            val descriptors = DiscImport.inspect(indexed, readText = { file ->
                scanContext.ensureActive()
                val uri = Docs.buildDocumentUriUsingTree(tree, file.id)
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        scanContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > DiscImport.MAX_DESCRIPTOR_BYTES) throw DiscImport.Invalid(DiscImport.Reason.TOO_LARGE)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error(context.getString(R.string.folder_unavailable))
                try {
                    Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                } catch (_: java.nio.charset.CharacterCodingException) { throw DiscImport.Invalid(DiscImport.Reason.MALFORMED) }
            }, checkpoint = { scanContext.ensureActive() },
                // The exact bootPath mapping is verified for external-storage document IDs.
                // Reject opaque providers for single images too, before exposing a bad card.
                providerSupported = tree.authority == "com.android.externalstorage.documents")
            descriptors.problems.forEach { problem ->
                val reason = when (problem.reason) {
                    DiscImport.Reason.MALFORMED -> context.getString(R.string.disc_invalid)
                    DiscImport.Reason.MISSING -> context.getString(R.string.disc_missing, problem.reference)
                    DiscImport.Reason.CASE_MISMATCH -> context.getString(R.string.disc_case_mismatch, problem.reference)
                    DiscImport.Reason.OUTSIDE_ROOT -> context.getString(R.string.disc_path_invalid, problem.reference)
                    DiscImport.Reason.AMBIGUOUS -> context.getString(R.string.disc_ambiguous, problem.reference)
                    DiscImport.Reason.NESTED_PLAYLIST -> context.getString(R.string.disc_nested)
                    DiscImport.Reason.CYCLE -> context.getString(R.string.disc_cycle)
                    DiscImport.Reason.TOO_LARGE -> context.getString(R.string.disc_too_large)
                    DiscImport.Reason.UNSUPPORTED -> context.getString(R.string.disc_unsupported)
                }
                onWarning(context.getString(R.string.disc_import_rejected, problem.path, reason))
            }
            descriptors.entries
        } else indexed
        val result = entries.mapNotNull { file ->
            scanContext.ensureActive()
            val child = file.id
            val name = file.path.substringAfterLast('/')
            val raw = if (tree.authority == "com.android.externalstorage.documents" && child.startsWith("primary:")) "/storage/emulated/0/" + child.removePrefix("primary:") else Docs.buildDocumentUriUsingTree(tree, child).toString()
            val documentUri = Docs.buildDocumentUriUsingTree(tree, child)
            if (!admitArchiveCandidate(name, libraryFileKey(raw) in previousFiles)) {
                val key = Triple(root.platform, root.uri, child)
                val nowMs = System.nanoTime() / 1_000_000
                val skipped = archiveFailures.skip(key, nowMs)
                val verified = name.endsWith(".zip", ignoreCase = true) && platform.accepts(name) &&
                    !skipped && verifyZip(context, documentUri, platform, scanContext, key)
                if (!verified) {
                    if (!skipped && name.endsWith(".zip", ignoreCase = true)) archiveFailures.record(key, System.nanoTime() / 1_000_000)
                    unverifiedArchives++
                    return@mapNotNull null
                }
                archiveFailures.remove(key)
            }
            gameForFile(context, root, bundled, known, previousByFile, child, name, documentUri, raw, installed)
        }
        if (showHints && unverifiedArchives > 0) onWarning(context.getString(R.string.import_archive_extract_hint))
        scanContext.ensureActive()
        if (showHints && threeDs && result.isEmpty() && ciaPackages == 0) onWarning(context.getString(if (installed) R.string.three_ds_installed_empty else R.string.three_ds_roms_empty))
        return mergeImportedVariants(result).sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    /** A document grant authorizes precisely one file; never infer permission to siblings. */
    private suspend fun scanSingle(context: Context, root: Root, bundled: List<Game>, previous: List<Game>): List<Game> {
        val scanContext = currentCoroutineContext()
        scanContext.ensureActive()
        val platform = PlatformRegistry.find(root.platform) ?: error(context.getString(R.string.platform_unknown))
        val uri = Uri.parse(root.uri)
        val cursor = context.contentResolver.query(uri, arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME, Docs.Document.COLUMN_MIME_TYPE), null, null, null)
            ?: error(context.getString(R.string.import_file_unavailable))
        val document = cursor.use { c ->
            // An empty, accessible provider response means the selected document was removed.
            if (!c.moveToNext()) return emptyList()
            Triple(c.getString(0), c.getString(1), c.getString(2))
        }
        scanContext.ensureActive()
        val (id, name, mime) = document
        require(!id.isNullOrBlank() && !name.isNullOrBlank() && mime != Docs.Document.MIME_TYPE_DIR) { context.getString(R.string.import_file_unavailable) }
        require(name !in setOf(".", "..") && '/' !in name && '\\' !in name) { context.getString(R.string.import_file_unavailable) }
        require(!(root.platform == "Nintendo 3DS" && name.endsWith(".cia", ignoreCase = true))) { context.getString(R.string.import_file_cia_hint) }
        val alreadyImported = previous.any { game ->
            game.platform == root.platform && game.files.any { libraryFileKey(it) == libraryFileKey(uri.toString()) }
        }
        if (!admitArchiveCandidate(name, alreadyImported)) {
            val verified = name.endsWith(".zip", ignoreCase = true) && platform.accepts(name) &&
                verifyZip(context, uri, platform, scanContext, Triple(root.platform, root.uri, id))
            require(verified) { context.getString(R.string.import_archive_extract_hint) }
        }
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(extension !in setOf("cue", "m3u", "gdi")) { context.getString(R.string.import_file_companions_hint) }
        require(platform.accepts(name)) { context.getString(R.string.import_file_wrong_type, name, PlatformRegistry.title(context, root.platform)) }
        require(platform.launchKind != LaunchKind.DUCKSTATION || uri.authority == "com.android.externalstorage.documents") { context.getString(R.string.disc_unsupported) }
        // Existence metadata alone is not proof that a cloud/offline document is readable.
        val readable = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error(context.getString(R.string.import_file_unavailable))
        readable.use { scanContext.ensureActive() }
        val game = gameForFile(context, root, bundled, catalogFiles(bundled, root.platform), previousIndex(context, previous, root), id, name, uri, uri.toString(), false)
            ?: error(context.getString(R.string.import_file_not_game))
        scanContext.ensureActive()
        return listOf(game)
    }

    private fun archiveStamp(context: Context, uri: Uri, scanContext: kotlin.coroutines.CoroutineContext): ArchiveFileStamp? = try {
        scanContext.ensureActive()
        context.contentResolver.query(uri, arrayOf(Docs.Document.COLUMN_SIZE, Docs.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0) || cursor.isNull(1)) null else {
                val size = cursor.getLong(0); val modified = cursor.getLong(1)
                if (size > 0 && modified > 0) ArchiveFileStamp(size, modified) else null
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (_: Exception) { null }

    private fun verifyZip(context: Context, uri: Uri, platform: PlatformSpec, scanContext: kotlin.coroutines.CoroutineContext,
                          key: Triple<String, String, String>): Boolean { return try {
        val before = archiveStamp(context, uri, scanContext)
        scanContext.ensureActive()
        if (archiveSuccesses.reuse(key, before, System.nanoTime() / 1_000_000)) return true
        val verified = context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            verifySelectedZip(java.io.File("/proc/self/fd/${descriptor.fd}"),
                platform.extensions - setOf("zip", "7z", "rar")) { scanContext.ensureActive() }
        } ?: false
        if (!verified) return false
        val after = archiveStamp(context, uri, scanContext)
        scanContext.ensureActive()
        if (before != null && after != null && before != after) return false
        archiveSuccesses.remember(key, before, after, System.nanoTime() / 1_000_000)
        true
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (_: Exception) { false } }

    private fun catalogFiles(bundled: List<Game>, platform: String): Map<String, Game> =
        bundled.filter { it.platform == platform }.flatMap { game -> game.files.map { libraryFileKey(it) to game } }.toMap()

    private fun previousIndex(context: Context, previous: List<Game>, root: Root): Map<String, Game> =
        try { previousImportIndex(previous, root.uri, root.platform) }
        catch (e: IllegalStateException) { throw IllegalStateException(context.getString(R.string.folder_unavailable), e) }

    private suspend fun gameForFile(context: Context, root: Root, bundled: List<Game>, known: Map<String, Game>, previous: Map<String, Game>, documentId: String, name: String, uri: Uri, raw: String, installed: Boolean): Game? {
        val platform = PlatformRegistry.find(root.platform) ?: error(context.getString(R.string.platform_unknown))
        val threeDs = root.platform == "Nintendo 3DS"
        val metadata = if (threeDs) readThreeDsMetadata(context, uri, installed || name.endsWith(".app", ignoreCase = true)) else null
        // Installed numeric data-only contents must never become launch cards.
        if (threeDs && name.endsWith(".app", ignoreCase = true) && metadata == null) return null
        // The installed snapshot is authoritative for this source's existing cards.
        // Do not invent missing files: each variant must still be observed in this scan.
        previous[libraryFileKey(raw)]?.let { return it.copy(files = listOf(raw), tree = root.uri) }
        // Preserve the catalog identity and user cover/preferences when the same ROM is
        // selected as a document. Existing folder identities are unchanged.
        known[libraryFileKey(raw)]?.let { return if (root.source == LibrarySource.SINGLE_FILE) it.copy(files = listOf(raw), tree = root.uri)
            else catalogImportEntry(it, raw, root.uri) }
        val match = GameMetadataMatcher.find(context, root.platform, metadata?.title ?: name, bundled)
        val title = metadata?.title ?: match?.title ?: name.substringBeforeLast('.').replace('_', ' ')
        val hash = MessageDigest.getInstance("SHA-256").digest(("${root.platform}|${uri.authority}|$documentId").toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
        return Game("import-$hash", title, root.platform, platform.emulator, platform.component, listOf(raw), root.uri, match?.cover.orEmpty(), match?.scene.orEmpty(), match?.genre.orEmpty(), match?.year.orEmpty(), metadata?.publisher ?: match?.developer.orEmpty(), match?.players.orEmpty(), description = match?.description.orEmpty())
    }

    /** Only bounded header/icon reads; never copy or decode the entire ROM. */
    private suspend fun readThreeDsMetadata(context: Context, uri: Uri, required: Boolean): ThreeDsGameMetadata? {
        val scanContext = currentCoroutineContext()
        return try {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: throw java.io.IOException(context.getString(R.string.folder_unavailable))
            descriptor.use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                    val channel = stream.channel
                    ThreeDsMetadata.read(channel.size(), context.resources.configuration.locales[0].language) { offset, length ->
                        scanContext.ensureActive()
                        val buffer = java.nio.ByteBuffer.allocate(length)
                        var position = offset
                        while (buffer.hasRemaining()) {
                            scanContext.ensureActive()
                            val count = channel.read(buffer, position)
                            if (count <= 0) break
                            position += count
                        }
                        if (buffer.hasRemaining()) null else buffer.array()
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: java.io.IOException) { if (required) throw e else null }
        catch (e: SecurityException) { if (required) throw e else null }
    }
}
