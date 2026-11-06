"""Test exact external-storage source mapping with a synthetic document tree."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import ROOT, JAVA, COMPILER

sources = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
checks = r'''package tv.orbit.shield
import java.io.*
import java.util.concurrent.CancellationException
private class Tree : PortableContentTree {
    override val rootId = "ABCD-1234:Игры"
    val rows = mutableMapOf(rootId to mutableListOf(PortableContentEntry("ABCD-1234:Игры/PS1", "PS1", true)),
        "ABCD-1234:Игры/PS1" to mutableListOf(PortableContentEntry("ABCD-1234:Игры/PS1/Game 01.cue", "Game 01.cue", false)))
    var allowed = true
    override fun children(parentId: String): List<PortableContentEntry> = rows[parentId] ?: throw IOException("missing")
    override fun isChild(parentId: String, childId: String) = allowed
    override fun openRead(id: String): InputStream = error("lookup must not read file contents")
}
fun main() {
    var n=0
    fun ok(value: Boolean, name: String) { check(value) { name }; n++ }
    val id = "ABCD-1234:Игры/PS1/Game 01.cue"
    val t = Tree()
    ok(PortableSourceLookup.locate(t, id) == PortableSourceFile(listOf("PS1", "Game 01.cue"), id), "exact nested source")
    ok(PortableExternalDocumentIds.relative("primary:", "primary:Игры/100%/Game.bin").components == listOf("Игры", "100%", "Game.bin"), "volume root and literal percent")
    ok(PortableExternalDocumentIds.primaryDocumentId("/storage/emulated/0/Игры/Game.bin") == "primary:Игры/Game.bin", "primary filesystem path")
    fun invalid(root: String, file: String) = runCatching { PortableExternalDocumentIds.relative(root, file) }.isFailure
    ok(invalid("ABCD-1234:Игры", "ABCD-1234:Игры-old/PS1/Game.cue"), "sibling prefix is not a child")
    ok(invalid("ABCD-1234:Игры", "OTHER:Игры/PS1/Game.cue"), "different volume rejected")
    ok(invalid("ABCD-1234:Игры", "ABCD-1234:Игры"), "root is not a file")
    ok(invalid("ABCD-1234:Игры", "ABCD-1234:Игры/../private"), "traversal rejected")
    ok(runCatching { PortableExternalDocumentIds.primaryDocumentId("/storage/emulated/01/game") }.isFailure, "similar primary prefix rejected")
    ok(runCatching { PortableExternalDocumentIds.primaryDocumentId("/storage/emulated/0/../game") }.isFailure, "primary traversal rejected")
    ok(runCatching { PortableSourceLookup.locate(Tree().apply { allowed=false }, id) }.isFailure, "unverified child rejected")
    ok(runCatching { PortableSourceLookup.locate(Tree().apply { rows[rootId]!!.add(PortableContentEntry("other", "PS1", true)) }, id) }.isFailure, "ambiguous display name rejected")
    ok(runCatching { PortableSourceLookup.locate(Tree().apply { rows[rootId]!![0] = PortableContentEntry("other", "ps1", true) }, id) }.isFailure, "case mismatch rejected")
    ok(runCatching { PortableSourceLookup.locate(Tree().apply { rows[rootId]!![0] = PortableContentEntry("other", "PS1", false) }, id) }.isFailure, "file where directory expected rejected")
    ok(runCatching { PortableSourceLookup.locate(Tree().apply { rows["ABCD-1234:Игры/PS1"]!![0] = PortableContentEntry("other", "Game 01.cue", false) }, id) }.isFailure, "different document identity rejected")
    ok(runCatching { PortableSourceLookup.locate(t, id) { throw CancellationException() } }.exceptionOrNull() is CancellationException, "cancellation propagates")
    println("PASS: $n source lookup checks")
}'''
with tempfile.TemporaryDirectory() as tmp:
    folder = Path(tmp)
    (folder / 'Checks.kt').write_text(checks)
    (folder / 'Tree.kt').write_text('''package tv.orbit.shield
import java.io.InputStream
data class PortableContentEntry(val id: String, val name: String, val directory: Boolean)
interface PortableContentTree { val rootId: String; fun children(parentId: String): List<PortableContentEntry>; fun isChild(parentId: String, childId: String): Boolean; fun openRead(id: String): InputStream }
''')
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', str(COMPILER[1]), '-d', str(folder / 'classes'),
                    str(sources / 'PortablePaths.kt'), str(sources / 'PortableSourceLookup.kt'), str(folder / 'Tree.kt'), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', str(folder / 'classes') + ':' + str(COMPILER[1]), 'tv.orbit.shield.ChecksKt'], check=True)
