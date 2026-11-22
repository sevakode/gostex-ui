"""Exercise the ZIP recovery record and narrow grants with JVM Android boundaries."""

import os
from pathlib import Path
import subprocess
import tempfile

from kotlin_toolchain import COMPILER, JAVA, ROOT


sources = {
    "Content.kt": r'''
package android.content

import android.net.Uri
import java.io.File

class Context(val noBackupFilesDir: File, val contentResolver: ContentResolver)
data class UriPermission(val uri: Uri, val isReadPermission: Boolean, val isWritePermission: Boolean)
class ContentResolver {
    val persistedUriPermissions = mutableListOf<UriPermission>()
    fun query(uri: Uri, columns: Array<String>, selection: String?, args: Array<String>?, order: String?) =
        Cursor(uri.pathSegments.last())
    class Cursor(private val id: String) : AutoCloseable {
        fun moveToFirst() = true
        fun getString(index: Int) = id
        fun moveToNext() = false
        override fun close() {}
    }
    val requests = mutableListOf<Pair<Uri, Int>>()
    val offered = mutableSetOf<Uri>()
    fun takePersistableUriPermission(uri: Uri, flags: Int) {
        requests += uri to flags
        if (uri !in offered) throw SecurityException()
        val old = persistedUriPermissions.firstOrNull { it.uri == uri }
        persistedUriPermissions.remove(old)
        persistedUriPermissions += UriPermission(uri, flags and 1 != 0 || old?.isReadPermission == true,
            flags and 2 != 0 || old?.isWritePermission == true)
    }
    fun releasePersistableUriPermission(uri: Uri, flags: Int) {
        val old = persistedUriPermissions.single { it.uri == uri }
        persistedUriPermissions.remove(old)
        val read = old.isReadPermission && flags and 1 == 0
        val write = old.isWritePermission && flags and 2 == 0
        if (read || write) persistedUriPermissions += UriPermission(uri, read, write)
    }
    companion object { const val SCHEME_CONTENT = "content" }
}
object Intent {
    const val FLAG_GRANT_READ_URI_PERMISSION = 1
    const val FLAG_GRANT_WRITE_URI_PERMISSION = 2
}
''',
    "Uri.kt": r'''
package android.net

import java.net.URI
import java.net.URLDecoder

data class Uri(private val raw: String) {
    private val parsed = URI(raw)
    val scheme: String? get() = parsed.scheme
    val authority: String? get() = parsed.authority
    val query: String? get() = parsed.query
    val fragment: String? get() = parsed.fragment
    val pathSegments: List<String> get() = parsed.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        .map { URLDecoder.decode(it, Charsets.UTF_8) }
    override fun toString() = raw
    companion object { fun parse(raw: String) = Uri(raw) }
}
''',
    "DocumentsContract.kt": r'''
package android.provider

import android.net.Uri

object DocumentsContract {
    object Document { const val COLUMN_DOCUMENT_ID = "document_id" }
    fun getDocumentId(uri: Uri) = uri.pathSegments.last()
    fun buildDocumentUriUsingTree(uri: Uri, id: String): Uri =
        Uri.parse(uri.toString() + "/document/" + java.net.URLEncoder.encode(id, Charsets.UTF_8).replace("+", "%20"))
    fun isTreeUri(uri: Uri) = uri.pathSegments.firstOrNull() == "tree"
    fun getTreeDocumentId(uri: Uri) = uri.pathSegments[1]
    fun buildTreeDocumentUri(authority: String, id: String): Uri =
        Uri.parse("content://$authority/tree/" + java.net.URLEncoder.encode(id, Charsets.UTF_8).replace("+", "%20"))
    fun buildDocumentUri(authority: String, id: String): Uri =
        Uri.parse("content://$authority/document/" + java.net.URLEncoder.encode(id, Charsets.UTF_8).replace("+", "%20"))
}
''',
    "Os.kt": r'''
package android.system

import java.io.File

object Os {
    fun open(path: String, flags: Int, mode: Int): File = File(path).also {
        check(it.isDirectory && flags == OsConstants.O_RDONLY && mode == 0)
    }
    fun fsync(file: File) { check(file.isDirectory) }
    fun close(file: File) { check(file.isDirectory) }
}
object OsConstants { const val O_RDONLY = 0 }
''',
    "AtomicFile.kt": r'''
package android.util

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AtomicFile(private val base: File) {
    private val next = File(base.path + ".new")
    private val backup = File(base.path + ".bak")
    fun openRead() = base.also {
        if (backup.exists()) Files.move(backup.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING)
        next.delete()
    }.inputStream()
    fun startWrite() = next.outputStream()
    fun finishWrite(out: FileOutputStream) {
        out.close()
        if (!dropCommit) Files.move(next.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    fun failWrite(out: FileOutputStream) { out.close(); next.delete() }
    fun delete() { base.delete(); next.delete(); backup.delete() }
    companion object { var dropCommit = false }
}
''',
    "Checks.kt": r'''
package tv.orbit.shield

import android.content.ContentResolver
import android.content.Context
import android.content.UriPermission
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

object PlatformRegistry { fun find(id: String) = id.takeIf { it == "PlayStation" } }

fun main() {
    val root = Files.createTempDirectory("gostex-zip-journal").toFile()
    val resolver = ContentResolver()
    val context = Context(root, resolver)
    val journal = GameArchiveRecovery
    val base = File(root, "game-archive-recovery")
    val source = Uri.parse("content://downloads/document/archive%2Fgame.zip")
    val tree = Uri.parse("content://external/tree/usb%3Agames")
    val job = GameArchiveRecovery.Job("c57e7d9a-d59d-44e9-a6b7-189c86bcae4f", "PlayStation",
        source, tree, "Игра.zip", "1".repeat(64), "Игра-c57e7d9a", 100)
    val staged = job.copy(stagingId = "usb:games/stage")
    val renaming = staged.copy(phase = GameArchiveRecovery.Phase.RENAMING)
    val ready = renaming.copy(phase = GameArchiveRecovery.Phase.READY, finalId = "usb:games/finished")
    var count = 0
    fun expect(condition: Boolean) { check(condition) { "Assertion ${count + 1} failed" }; count++ }
    fun rejected(action: () -> Unit) { expect(runCatching(action).exceptionOrNull() is IOException) }
    try {
        expect(journal.read(context) == null)
        File(base.path + ".new").writeText("Interrupted before a job could start")
        expect(journal.read(context) == null)
        rejected { journal.begin(context, job.copy(totalBytes = -1)) }
        rejected { journal.begin(context, job.copy(totalBytes = (64L shl 30) + 1)) }
        rejected { journal.begin(context, job.copy(platform = "Unknown")) }
        rejected { journal.begin(context, job.copy(tree = Uri.parse("file:///games"))) }
        rejected { journal.begin(context, job.copy(finalName = "subfolder/game")) }
        rejected { journal.begin(context, staged) }
        journal.begin(context, job)
        expect(journal.read(Context(root, resolver)) == job)
        expect(job.stagingName == ".gostex-extract-${job.id}.partial" && !job.toString().contains(job.title))
        rejected { journal.begin(context, job) }
        rejected { journal.update(context, job, staged.copy(fingerprint = "2".repeat(64))) }
        rejected { journal.update(context, job, renaming) }
        rejected { journal.update(context, job, staged.copy(stagingId = "usb:games")) }
        journal.update(context, job, staged)
        rejected { journal.clear(context, job) }
        AtomicFile.dropCommit = true
        rejected { journal.update(context, staged, renaming) }
        AtomicFile.dropCommit = false
        expect(journal.read(context) == staged)
        journal.update(context, staged, renaming)
        rejected { journal.update(context, renaming, staged) }
        journal.update(context, renaming, ready)
        expect(journal.read(context) == ready)
        val good = base.readBytes()
        File(base.path + ".bak").writeBytes(good)
        base.writeBytes(byteArrayOf(4))
        expect(journal.read(context) == ready)
        base.appendBytes(byteArrayOf(0))
        rejected { journal.read(context) }
        rejected { journal.begin(context, job) }
        expect(base.length() == good.size + 1L)
        base.writeBytes(good)
        journal.clear(context, ready)
        expect(journal.read(context) == null)

        val start = CountDownLatch(1)
        val saved = AtomicInteger()
        val writers = (1..6).map { thread {
            start.await()
            if (runCatching { journal.begin(context, job) }.isSuccess) saved.incrementAndGet()
        } }
        start.countDown()
        writers.forEach(Thread::join)
        expect(saved.get() == 1 && journal.read(context) == job)

        expect(!journal.hasAccess(context, job) && !journal.hasDestinationAccess(context, job))
        resolver.offered += source
        resolver.offered += tree
        expect(journal.retainAccess(context, job) && journal.hasAccess(context, job))
        expect(resolver.requests == listOf(source to 1, tree to 3))
        expect(journal.retainAccess(context, job) && resolver.requests.size == 2)
        val sourceTree = Uri.parse("content://external/tree/downloads")
        val contained = Uri.parse("content://external/tree/downloads/document/downloads%2Fgame.zip")
        resolver.persistedUriPermissions += UriPermission(sourceTree, true, false)
        expect(journal.retainAccess(context, job.copy(source = contained)) && resolver.requests.size == 2)
        val otherRoot = Uri.parse("content://external/tree/downloads2/document/downloads2%2Fgame.zip")
        expect(!journal.retainAccess(context, job.copy(source = otherRoot)) && resolver.requests.last() == (otherRoot to 1))
        resolver.persistedUriPermissions.removeAll { it.uri == source }
        expect(!journal.hasAccess(context, job) && journal.hasDestinationAccess(context, job))

        val downloads = "content://com.android.providers.downloads.documents/document/"
        val raw = Uri.parse(downloads + "raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Fgame.zip")
        val indexed = Uri.parse(downloads + "msf%3A32")
        val original = job.copy(source = raw, title = "game.zip")
        val partial = original.copy(stagingId = staged.stagingId)
        journal.clear(context, job)
        journal.begin(context, original)
        journal.update(context, original, partial)
        val savedBeforeAlias = base.readBytes()
        expect(journal.canReconnectIndexedDownload(partial, indexed))
        expect(!journal.canReconnectIndexedDownload(partial.copy(title = "unrelated.zip"), indexed))
        expect(!journal.canReconnectIndexedDownload(ready.copy(source = raw, title = "game.zip"), indexed))
        expect(!journal.canReconnectIndexedDownload(partial, Uri.parse(downloads + "42")))
        val nestedDownload = Uri.parse(downloads + "raw%3A%2Fstorage%2Femulated%2F0%2FOther%2FDownload%2Fgame.zip")
        expect(!journal.canReconnectIndexedDownload(partial.copy(source = nestedDownload), indexed))
        expect(!journal.canReconnectIndexedDownload(partial, Uri.parse(indexed.toString() + "?other=1")))
        expect(!journal.canReconnectIndexedDownload(partial, Uri.parse("content://other/document/msf%3A32")))
        expect(!journal.reconnectVerifiedDownload(context, original, indexed) { error("Stale selection") })
        expect(!journal.reconnectVerifiedDownload(context, partial, indexed) {})
        expect(base.readBytes().contentEquals(savedBeforeAlias))
        resolver.offered += indexed
        rejected { journal.reconnectVerifiedDownload(context, partial, indexed) { throw IOException("Cancelled") } }
        expect(resolver.persistedUriPermissions.none { it.uri == indexed })
        var checks = 0
        rejected { journal.reconnectVerifiedDownload(context, partial, indexed) {
            if (++checks == 2) throw IOException("Cancelled after grant")
        } }
        expect(resolver.persistedUriPermissions.none { it.uri == indexed } && base.readBytes().contentEquals(savedBeforeAlias))
        AtomicFile.dropCommit = true
        rejected { journal.reconnectVerifiedDownload(context, partial, indexed) {} }
        AtomicFile.dropCommit = false
        expect(resolver.persistedUriPermissions.none { it.uri == indexed } && base.readBytes().contentEquals(savedBeforeAlias))
        expect(journal.reconnectVerifiedDownload(context, partial, indexed) {})
        expect(journal.read(context) == partial.copy(source = indexed) && resolver.requests.last() == (indexed to 1))
        expect(resolver.persistedUriPermissions.any { it.uri == indexed && it.isReadPermission && !it.isWritePermission })
        println("PASS ZIP recovery journal: $count checks")
    } finally {
        root.deleteRecursively()
    }
}
''',
}

with tempfile.TemporaryDirectory(prefix="gostex-archive-recovery-") as tmp:
    folder = Path(tmp)
    for filename, source in sources.items():
        (folder / filename).write_text(source)
    classes = folder / "classes"
    production = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield"
    subprocess.run([
        str(JAVA), "-cp", os.pathsep.join(map(str, COMPILER)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-classpath", str(COMPILER[1]), "-d", str(classes),
        str(production / "GameArchiveRecovery.kt"), str(production / "TorrDownloadPaths.kt"),
        *map(str, sorted(folder.glob("*.kt"))),
    ], check=True, timeout=60)
    subprocess.run([str(JAVA), "-cp", os.pathsep.join(map(str, [classes, COMPILER[1]])), "tv.orbit.shield.ChecksKt"],
                   check=True, timeout=15)
