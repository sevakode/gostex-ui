"""Exercise safe generation copy on a synthetic writable document tree."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import ROOT, JAVA, COMPILER

sources = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
checks = r'''package tv.orbit.shield
import java.io.*
import java.util.concurrent.CancellationException
private const val GEN = "gostex-content-0123456789abcdef0123456789abcdef"
private class Tree : PortableWritableTree {
    override val rootId = "root"
    val rows = mutableMapOf("root" to mutableListOf(PortableWritableEntry("old", "important.bin", false)))
    val data = mutableMapOf("old" to "original".toByteArray())
    var writes = 0; var createCount = 0; var corrupt = false; var renamed = false; var outside = false; var reuseId: String? = null
    override fun children(parentId: String) = rows[parentId]?.toList() ?: error("not a directory")
    override fun create(parentId: String, name: String, directory: Boolean): PortableWritableEntry {
        createCount++
        val id = reuseId ?: "n$createCount"
        val entry = PortableWritableEntry(id, if (renamed) "renamed" else name, directory)
        rows.getValue(parentId).add(entry)
        if (directory && id !in rows) rows[id] = mutableListOf()
        return entry
    }
    override fun isChild(parentId: String, childId: String) = !outside
    override fun openWrite(id: String): OutputStream { writes++; return object : ByteArrayOutputStream() { override fun close() { data[id] = toByteArray() } } }
    override fun openRead(id: String): InputStream = ByteArrayInputStream(data.getValue(id).let { if (corrupt) it + 0 else it })
}
fun main() {
    var n = 0
    fun ok(v: Boolean, s: String) { check(v) { s }; n++ }
    fun input(id: String, path: List<String>, content: String = "data", declared: Long = content.length.toLong()) =
        PortableCopyInput(id, path, declared) { ByteArrayInputStream(content.toByteArray()) }
    val entries = listOf(input("cue", listOf("PS1", "Disc", "game.cue"), "cue"),
        input("track", listOf("PS1", "Disc", "track.bin"), "track"), input("art", listOf("covers", "обложка.png")))
    fun run(tree: Tree, sources: List<PortableCopyInput> = entries, check: (PortableCopyInput) -> Unit = {}) =
        PortableGenerationWriter.copy(tree, sources, setOf("old"), check, newGenerationName = { GEN })
    val tree = Tree(); var checks = 0
    val result = run(tree, check = { checks++ })
    ok(result.files.map { it.path } == entries.map { listOf(GEN) + it.path }, "exact nested paths retained")
    ok(result.files.map { it.receipt.bytes } == listOf(3L, 5L, 4L) && checks == 6, "readback and source checked twice")
    ok(tree.data.getValue("old").decodeToString() == "original", "pre-existing data untouched")
    ok(tree.createCount == 7 && tree.writes == 3, "shared directories created once")
    val duplicate = Tree(); ok(runCatching { run(duplicate, listOf(entries[0], entries[0].copy(id = "duplicate"))) }.isFailure && duplicate.createCount == 0, "duplicate path preflight")
    val prefix = Tree(); ok(runCatching { run(prefix, listOf(input("a", listOf("a")), input("b", listOf("a", "b")))) }.isFailure && prefix.createCount == 0, "file and directory conflict preflight")
    val unsafe = Tree(); ok(runCatching { run(unsafe, listOf(input("x", listOf("..", "file")))) }.isFailure && unsafe.createCount == 0, "unsafe path preflight")
    val existing = Tree().apply { rows.getValue("root").add(PortableWritableEntry("prior", GEN, true)) }
    ok(runCatching { run(existing) }.isFailure && existing.writes == 0, "existing generation untouched")
    val renamed = Tree().apply { renamed = true }
    ok(runCatching { run(renamed) }.isFailure && renamed.writes == 0, "provider renamed document")
    val protected = Tree().apply { reuseId = "old" }
    ok(runCatching { run(protected) }.isFailure && protected.writes == 0 && protected.data.getValue("old").decodeToString() == "original", "protected identity never written")
    val outside = Tree().apply { outside = true }
    ok(runCatching { run(outside) }.isFailure && outside.writes == 0, "outside destination rejected")
    val corrupt = Tree().apply { corrupt = true }
    ok(runCatching { run(corrupt) }.isFailure && corrupt.data.getValue("old").decodeToString() == "original", "corrupt copy not accepted")
    val shorter = Tree(); ok(runCatching { run(shorter, listOf(input("x", listOf("x"), "ab", 3))) }.isFailure, "short source rejected")
    val longer = Tree(); ok(runCatching { run(longer, listOf(input("x", listOf("x"), "abcd", 3))) }.isFailure, "long source rejected")
    val changed = Tree(); var calls = 0
    ok(runCatching { run(changed, listOf(entries[0]), check = { if (++calls == 2) error("changed") }) }.isFailure, "changed source does not yield result")
    val cancelled = Tree()
    ok(runCatching { PortableGenerationWriter.copy(cancelled, entries, emptySet(), {}, { throw CancellationException() }, { GEN }) }.exceptionOrNull() is CancellationException && cancelled.writes == 0, "cancellation propagated")
    println("PASS: $n portable generation checks")
}'''
with tempfile.TemporaryDirectory() as tmp:
    folder = Path(tmp); (folder / 'Checks.kt').write_text(checks)
    files = ['PortablePaths.kt', 'PortableVerifiedCopy.kt', 'PortableGenerationWriter.kt']
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', str(COMPILER[1]), '-d', str(folder / 'classes'),
                    *map(str, (sources / name for name in files)), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', str(folder / 'classes') + ':' + str(COMPILER[1]), 'tv.orbit.shield.ChecksKt'], check=True)
