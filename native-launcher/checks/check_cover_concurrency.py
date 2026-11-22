"""Exercise shared cover listing, slow provider I/O and export recovery."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import ROOT, JAVA, COMPILER

root = ROOT
source_dir = root / 'native-launcher/app/src/main/java/tv/orbit/shield'
compiler = COMPILER
java = JAVA
model = (source_dir / 'ManagedCoverModel.kt').read_text()
cover = model[model.index('internal data class ManagedCover('):model.index('internal enum class CoverChoice')]
checks = '''package tv.orbit.shield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.*

private typealias Uri = String
private object Docs {
    const val EXTRA_LOADING = "loading"
    const val EXTRA_ERROR = "error"
    object Document {
        const val COLUMN_DOCUMENT_ID = "id"
        const val COLUMN_DISPLAY_NAME = "name"
        const val COLUMN_MIME_TYPE = "mime"
        const val MIME_TYPE_DIR = "dir"
    }
    fun getTreeDocumentId(root: Uri) = root
    fun getDocumentId(uri: Uri) = uri
    fun buildDocumentUriUsingTree(root: Uri, id: String) = id.also { check(it.startsWith(root)) }
    fun buildChildDocumentsUriUsingTree(root: Uri, id: String) = buildDocumentUriUsingTree(root, id)
    fun createDocument(resolver: Provider, parent: Uri, mime: String, name: String): Uri? =
        resolver.create(parent, mime, name)
}
private object R {
    object string { const val folder_unavailable = "Folder unavailable" }
}
private object ManagedCoverManifest {
    PRODUCTION_IMAGE_PATH
}
private class Context(val contentResolver: Provider) {
    val cacheDir = java.nio.file.Files.createTempDirectory("cover-cache").toFile()
    fun getString(message: String) = message
    val assets = Assets(contentResolver)
}
private class Assets(private val provider: Provider) {
    fun open(path: String) = provider.assets.getValue(path).inputStream()
}
private class Cursor(private val rows: List<Document>, private val incomplete: Boolean) : AutoCloseable {
    private var row = -1
    val extras get() = Extras(incomplete && row == rows.size)
    fun moveToNext() = ++row < rows.size
    fun getString(column: Int): String = rows[row].let {
        when (column) {
            0 -> it.uri
            1 -> it.name
            else -> it.mime
        }
    }
    override fun close() = Unit
}
private class Extras(private val loading: Boolean) {
    fun getBoolean(key: String) = key == Docs.EXTRA_LOADING && loading
    fun getString(key: String): String? = null.also { check(key == Docs.EXTRA_ERROR) }
}
private class Provider {
    val folders = mutableMapOf<Uri, List<Document>>()
    val files = mutableMapOf<Uri, ByteArray>()
    val assets = mutableMapOf<String, ByteArray>()
    val queries = mutableListOf<Uri>()
    val opened = mutableListOf<Uri>()
    var incomplete: Uri? = null
    var cancelled: Uri? = null
    var failNextWrite = false
    var truncateNextWrite = false
    var returnOnCreate: Uri? = null

    fun query(uri: Uri, columns: Array<String>, selection: String?, args: Array<String>?, order: String?): Cursor? {
        check(columns.contentEquals(arrayOf("id", "name", "mime")))
        check(selection == null && args == null && order == null)
        queries += uri
        if (uri == cancelled) throw CancellationException("Cancelled provider query")
        return folders[uri]?.let { Cursor(it, uri == incomplete) }
    }
    fun openInputStream(uri: Uri): InputStream? {
        opened += uri
        return files[uri]?.inputStream()
    }
    fun create(parent: Uri, mime: String, name: String): Uri {
        returnOnCreate?.let { return it }
        val uri = "$parent/$name"
        folders[parent] = folders.getValue(parent) + Document(uri, name, mime)
        return uri
    }
    fun openOutputStream(uri: Uri, mode: String): ByteArrayOutputStream? {
        check(mode == "wt")
        if (folders.values.none { rows -> rows.any { it.uri == uri } }) return null
        return object : ByteArrayOutputStream() {
            override fun close() {
                val written = toByteArray()
                if (failNextWrite) {
                    failNextWrite = false
                    files[uri] = written.copyOf(1)
                    throw IOException("Provider write interrupted")
                }
                files[uri] = if (truncateNextWrite) written.copyOf(1) else written
                truncateNextWrite = false
            }
        }
    }
}

PRODUCTION_BOUNDED
PRODUCTION_TREE_READER
PRODUCTION_DIGEST

private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
private const val MAX_TREE_CHILDREN = 4096
private fun imageFile(context: Context, key: String): File? {
    check(key in context.contentResolver.assets)
    return null
}

private class ExportFixture(private val context: Context, private val root: Uri) {
    private var lastExport = ""

    suspend fun export(force: Boolean) {
        val tree = Tree(context, root)
        val fingerprint = "same-fingerprint"
        PRODUCTION_EXPORT_HEAD
        PRODUCTION_EXPORT_SETUP
        PRODUCTION_EXPORT_IMAGE
        val payload = exportImage("cover.jpg").orEmpty().encodeToByteArray()
        PRODUCTION_EXPORT_TAIL
    }
}

private suspend fun exportFixture(context: Context, root: Uri): String? {
    ExportFixture(context, root).export(force = true)
    return context.contentResolver.files["$root/library.json"]?.decodeToString()
}

private suspend fun checkManifestRetry() {
    val provider = Provider()
    val context = Context(provider)
    val root = "root"
    val originals = "$root/originals"
    val library = "$root/library.json"
    provider.folders[root] = listOf(Document(originals, "originals", "dir"))
    provider.folders[originals] = emptyList()
    provider.assets["cover.jpg"] = byteArrayOf(1, 2, 3)

    val exporter = ExportFixture(context, root)
    exporter.export(force = false)
    val complete = provider.files.getValue(library)
    provider.opened.clear()
    exporter.export(force = false)
    check(provider.opened.isEmpty()) { "Unchanged export should not reopen image or manifest bytes" }
    for (restart in listOf(false, true)) {
        provider.failNextWrite = true
        check(runCatching { exporter.export(force = true) }.exceptionOrNull() is IOException)
        check(provider.files.getValue(library).size == 1)
        val retry = if (restart) ExportFixture(context, root) else exporter
        retry.export(force = false)
        check(provider.files.getValue(library).contentEquals(complete)) {
            "Failed forced export was skipped on automatic retry (restart=$restart)"
        }
    }

    provider.truncateNextWrite = true
    check(runCatching { exporter.export(force = true) }.exceptionOrNull()?.message == "Folder unavailable")
    check(provider.files.getValue(library).size == 1)
    ExportFixture(context, root).export(force = false)
    check(provider.files.getValue(library).contentEquals(complete))
    val cache = File(context.cacheDir, "cover-export-fingerprint")
    check(cache.delete() && cache.mkdir())
    File(cache, "blocked").writeText("fixture")
    provider.failNextWrite = true
    check(runCatching { exporter.export(force = true) }.exceptionOrNull()?.message == "Folder unavailable")
    check(provider.failNextWrite && provider.files.getValue(library).contentEquals(complete))
}

private suspend fun checkExportAfterInterruptedWrite() {
    val provider = Provider()
    val context = Context(provider)
    val root = "root"
    val originals = "$root/originals"
    val library = "$root/library.json"
    val bytes = byteArrayOf(1, 2, 3)
    val canonical = "$originals/${digest(bytes)}.jpg"
    provider.folders[root] = listOf(Document(originals, "originals", "dir"))
    provider.folders[originals] = emptyList()
    provider.assets["cover.jpg"] = bytes
    provider.failNextWrite = true
    check(runCatching { exportFixture(context, root) }.exceptionOrNull() is IOException)
    check(library !in provider.files && provider.files.getValue(canonical).contentEquals(byteArrayOf(1)))
    val image = checkNotNull(exportFixture(context, root))
    check(provider.files.getValue("$root/$image").contentEquals(bytes)) { "Export published an incomplete image" }
    check(provider.files.getValue(canonical).contentEquals(byteArrayOf(1)))
    check(provider.files.getValue(library).decodeToString() == image)
    check(exportFixture(context, root) == image)
    check(provider.folders.getValue(originals).size == 2)

    val badBytes = byteArrayOf(1, 9, 3)
    provider.files["$root/$image"] = badBytes
    val replacement = checkNotNull(exportFixture(context, root))
    check(replacement != image && provider.files.getValue("$root/$replacement").contentEquals(bytes))
    check(provider.files.getValue("$root/$image").contentEquals(badBytes))
    check(exportFixture(context, root) == replacement)

    provider.assets["cover.jpg"] = byteArrayOf(4, 5, 6)
    provider.returnOnCreate = canonical
    check(runCatching { exportFixture(context, root) }.exceptionOrNull()?.message ==
        "Provider reused an existing export document")
    check(provider.files.getValue(canonical).contentEquals(byteArrayOf(1)))
    check(provider.files.getValue(library).decodeToString() == replacement)

    provider.returnOnCreate = null
    val full = List(MAX_TREE_CHILDREN) { Document("$originals/item$it.png", "item$it.png", "image/png") }
    provider.folders[originals] = full
    check(runCatching { exportFixture(context, root) }.exceptionOrNull()?.message == "Folder contains too many files")
    check(provider.folders.getValue(originals) == full)
}

private fun checkImportListings() {
    val provider = Provider()
    val root = "root"
    val incoming = "$root/incoming"
    val other = "$root/other"
    val manifest = "$root/updates"
    val paths = (1..100).map { "cover$it.png" }
    val images = paths.map { Document("$incoming/$it", it, "image/png") }
    provider.folders[root] = listOf(
        Document(incoming, "incoming", "dir"), Document(other, "other", "dir"),
        Document(manifest, "updates.json", "application/json")
    )
    provider.folders[incoming] = images
    provider.folders[other] = emptyList()
    provider.files[manifest] = byteArrayOf(0)
    images.forEachIndexed { index, document -> provider.files[document.uri] = byteArrayOf(index.toByte()) }
    val tree = Tree(Context(provider), root)
    val read = IMPORT_READER
    check(read("updates.json", 1)?.single() == 0.toByte())
    paths.forEachIndexed { index, path -> check(read("incoming/$path", 1)?.single() == index.toByte()) }
    val counts = provider.queries.groupingBy { it }.eachCount()
    println("Cover import, 100 images: root=${counts[root]}, incoming=${counts[incoming]}, total=${provider.queries.size}")
    check(provider.queries == listOf(root, incoming)) { "Import should list root and incoming once each" }
    check(provider.opened == listOf(manifest) + images.map { it.uri })

    val later = images.first()
    provider.files.remove(later.uri)
    check(read("incoming/${later.name}", 1) == null)
    check(read("other/missing.png", 1) == null)
    check(read("incoming/${later.name}", 1) == null)
    check(provider.queries == listOf(root, incoming, other, incoming))

    provider.folders[incoming] = images.drop(1)
    val retry = IMPORT_READER
    check(retry("incoming/${later.name}", 1) == null)
    provider.folders[incoming] = images + later.copy(uri = "$incoming/duplicate")
    val duplicate = IMPORT_READER
    check(runCatching { duplicate("incoming/${later.name}", 1) }.exceptionOrNull()?.message == "Ambiguous file name")
    provider.folders[incoming] = images
    provider.files[later.uri] = byteArrayOf(0)
    val partial = IMPORT_READER
    provider.incomplete = incoming
    check(runCatching { partial("incoming/${later.name}", 1) }.exceptionOrNull()?.message == "Folder unavailable")
    provider.incomplete = null
    check(partial("incoming/${later.name}", 1)?.single() == 0.toByte())
    check(runCatching { partial("incoming/../cover1.png", 1) }.isFailure)

    val interrupted = IMPORT_READER
    provider.cancelled = incoming
    check(runCatching { interrupted("incoming/${later.name}", 1) }.exceptionOrNull() is CancellationException)
    provider.cancelled = null
    val afterCancellation = IMPORT_READER
    check(afterCancellation("incoming/${later.name}", 1)?.single() == 0.toByte())
}

private suspend fun checkSharedImagePreparation(cancel: Boolean) = coroutineScope {
    val gate = CoverOperationGate()
    val validating = CompletableDeferred<Unit>()
    val releaseValidation = CountDownLatch(1)
    val MAX_IMAGE_BYTES = 1024
    val context = Unit
    val payload = byteArrayOf(1, 2, 3)
    val tree = object {
        fun read(path: String, max: Int): ByteArray? {
            check(path == "incoming/cover.png" && max >= payload.size)
            return payload
        }
    }
    val readShared = tree::read
    var imagesWritten = false
    var entry = ManagedCover()
    fun validateImage(image: ByteArray, expected: String?, wide: Boolean): String {
        check(image === payload && expected == "expected-sha" && wide)
        validating.complete(Unit)
        check(releaseValidation.await(10, TimeUnit.SECONDS))
        return "validated-sha"
    }
    fun storeImage(context: Unit, image: ByteArray, sha: String, onWritten: () -> Unit): String {
        check(context == Unit && image === payload && sha == "validated-sha")
        onWritten()
        return "stored"
    }
    // Exercise the previous locking behavior if load calls the combined operation.
    fun putImage(context: Unit, image: ByteArray, expected: String, wide: Boolean, onWritten: () -> Unit): String =
        storeImage(context, image, validateImage(image, expected, wide), onWritten)

    SHARED_IMAGE_LOAD

    val pending = async(Dispatchers.Default) { load("incoming/cover.png", "expected-sha", wide = true) }
    try {
        withTimeout(2000) { validating.await() }
        withTimeoutOrNull(1000) { gate.mutate { entry = entry.copy(manual = "chosen") } }
        check(entry.manual == "chosen") { "Manual selection waited for shared image validation" }
        if (cancel) pending.cancel()
    } finally {
        releaseValidation.countDown()
    }
    if (cancel) {
        pending.join()
        check(!imagesWritten)
    } else {
        check(pending.await() == "stored" && imagesWritten)
    }
}

fun main() = runBlocking {
 val gate = CoverOperationGate()
 var index = ManagedCover(original = "old")
 val ioStarted = CompletableDeferred<Unit>()
 val releaseIo = CompletableDeferred<Unit>()
 val sync = async(Dispatchers.Default) {
  gate.synchronize {
   ioStarted.complete(Unit)
   releaseIo.await() // represents a slow provider read; no index lock is held
   gate.mutate { index = mergeSharedCover(index, "new", "wide") }
  }
 }
 ioStarted.await()
 withTimeout(2000) { gate.mutate { index = index.copy(manual = "chosen", sourceName = "user") } }
 check(index.manual == "chosen" && index.original == "old")
 releaseIo.complete(Unit); sync.await()
 check(index.manual == "chosen" && index.sourceName == "user" && index.original == "new" && index.wide == "wide")
 check(mergeSharedCover(index, null, null) == index)
 val locked = CompletableDeferred<Unit>()
 val never = CompletableDeferred<Unit>()
 val blockedSync = launch(Dispatchers.Default) { gate.synchronize { locked.complete(Unit); never.await() } }
 locked.await()
 val nextStarted = CompletableDeferred<Unit>()
 val nextSync = launch(Dispatchers.Default) { gate.synchronize { nextStarted.complete(Unit) } }
 check(withTimeoutOrNull(100) { nextStarted.await() } == null)
 blockedSync.cancelAndJoin()
 withTimeout(2000) { nextStarted.await() }; nextSync.join()
 checkSharedImagePreparation(cancel = false)
 checkSharedImagePreparation(cancel = true)
 checkImportListings()
 checkExportAfterInterruptedWrite()
 checkManifestRetry()
 println("PASS: bounded listings, cover coordination, cancellation and verified export/manifest recovery")
}
'''
store = (source_dir / 'ManagedCovers.kt').read_text()
sync = store.split('suspend fun sync(', 1)[1]
before_provider_read = sync.split('("updates.json"', 1)[0]
assert 'read(context)' not in before_provider_read, "Sync reads the index before provider I/O and discards that snapshot"
shared_image_load = sync[sync.index('suspend fun load('):sync.index('prepared += PreparedUpdate')].rstrip()
image_path = model[model.index('fun imagePath('):model.index('fun parse(')].rstrip()
bounded = store[store.index('private fun bounded('):store.index('private fun validateImage(')].rstrip()
tree_reader = store[store.index('private data class Document('):store.index('suspend fun images(')].rstrip()
digest = next(line.strip() for line in store.splitlines() if 'private fun digest(' in line)
export = store.split('private suspend fun export(', 1)[1]
export_head = export[export.index('val cache = File('):export.index('val originals =')].rstrip()
export_tail = export[export.index('currentCoroutineContext().ensureActive()', export.index('val payload =')):export.rindex('    }')].rstrip()
export_setup = export[export.index('val originals ='):export.index('val rows = JSONArray()')].rstrip()
export_image = export[export.index('suspend fun exportImage('):export.index('val original = exportImage(base)')].rstrip()
# Run the same provider fixture on the previous implementation for the baseline count.
import_reader = 'tree.importReader()' if 'fun importReader(' in tree_reader else 'tree::read'

with tempfile.TemporaryDirectory(prefix='gostex-cover-concurrency-') as temp:
    folder = Path(temp)
    (folder / 'Model.kt').write_text('package tv.orbit.shield\n' + cover)
    (folder / 'Checks.kt').write_text(checks.replace('SHARED_IMAGE_LOAD', shared_image_load)
        .replace('PRODUCTION_IMAGE_PATH', image_path).replace('PRODUCTION_BOUNDED', bounded)
        .replace('PRODUCTION_TREE_READER', tree_reader).replace('IMPORT_READER', import_reader)
        .replace('PRODUCTION_DIGEST', digest).replace('PRODUCTION_EXPORT_SETUP', export_setup)
        .replace('PRODUCTION_EXPORT_IMAGE', export_image)
        .replace('PRODUCTION_EXPORT_HEAD', export_head).replace('PRODUCTION_EXPORT_TAIL', export_tail))
    cp = ':'.join(map(str, compiler))
    runtime = ':'.join(map(str, [compiler[1], compiler[4], compiler[5]]))
    subprocess.run([str(java), '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', runtime, '-d', str(folder / 'classes'), str(source_dir / 'CoverOperationGate.kt'), str(folder / 'Model.kt'), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(java), f'-Djava.io.tmpdir={folder}', '-cp', str(folder / 'classes') + ':' + runtime, 'tv.orbit.shield.ChecksKt'], check=True)
