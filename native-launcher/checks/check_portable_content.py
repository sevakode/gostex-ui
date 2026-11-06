"""Test read-only portable content verification with synthetic documents."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import ROOT, JAVA, COMPILER

jar = Path(os.environ.get('PORTABLE_TEST_JSON_JAR', ROOT / '.tools/native/test-json/json-20240303.jar'))
if not jar.is_file() or hashlib.sha256(jar.read_bytes()).hexdigest() != '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed':
    raise SystemExit('Provide the verified org.json 20240303 test jar in PORTABLE_TEST_JSON_JAR')
sources = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
checks = r'''package tv.orbit.shield
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.CancellationException
private class Tree : PortableContentTree {
    override val rootId = "root"
    val dirs = mutableMapOf("root" to mutableListOf(PortableContentEntry("d", "Игры", true)),
        "d" to mutableListOf(PortableContentEntry("f", "Game.bin", false)))
    var bytes = "hello".toByteArray()
    var child = true
    var deny = false
    override fun children(parentId: String): List<PortableContentEntry> = dirs[parentId] ?: throw IOException("missing")
    override fun isChild(parentId: String, childId: String) = child
    override fun openRead(id: String): InputStream { if (deny) throw SecurityException(); return ByteArrayInputStream(bytes) }
}
fun main() {
    var n = 0
    fun ok(v: Boolean, msg: String) { check(v) { msg }; n++ }
    val hash = MessageDigest.getInstance("SHA-256").digest("hello".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    val file = PortableIndexedFile("rom", listOf("Игры", "Game.bin"), 5, hash)
    val index = PortableGameIndex(listOf(file), emptyList())
    fun run(tree: Tree = Tree(), item: PortableIndexedFile = file) = PortableContentVerifier.verify(tree, PortableGameIndex(listOf(item), emptyList()))
    val tree = Tree(); var progress = 0L
    val good = PortableContentVerifier.verify(tree, index, onProgress = { _, current, _ -> progress = current })
    ok(good.verified == mapOf("rom" to "f") && good.issues.isEmpty(), "exact path and digest")
    ok(progress == 5L, "progress reports streamed bytes")
    fun problem(tree: Tree, type: PortableContentProblem, item: PortableIndexedFile = file) = run(tree, item).let { it.verified.isEmpty() && it.issues.single().problem == type }
    ok(problem(Tree(), PortableContentProblem.MISSING, file.copy(path = listOf("Игры", "game.bin"))), "case is not guessed")
    ok(problem(Tree().apply { dirs["d"]!!.clear() }, PortableContentProblem.MISSING), "missing file")
    ok(problem(Tree().apply { dirs["d"]!!.add(PortableContentEntry("f2", "Game.bin", false)) }, PortableContentProblem.AMBIGUOUS), "duplicate name")
    ok(problem(Tree().apply { dirs["d"]!!.add(PortableContentEntry("f", "other.bin", false)) }, PortableContentProblem.AMBIGUOUS), "duplicate identity")
    ok(problem(Tree().apply { dirs["d"]!![0] = PortableContentEntry("f", "Game.bin", true) }, PortableContentProblem.WRONG_TYPE), "directory in place of file")
    ok(problem(Tree().apply { child = false }, PortableContentProblem.OUTSIDE_TREE), "unconfirmed containment rejected")
    ok(problem(Tree().apply { dirs["root"]!![0] = PortableContentEntry("root", "Игры", true) }, PortableContentProblem.OUTSIDE_TREE), "cycle rejected")
    ok(problem(Tree().apply { bytes = "other".toByteArray() }, PortableContentProblem.CORRUPT), "digest mismatch")
    ok(problem(Tree().apply { bytes = "hell".toByteArray() }, PortableContentProblem.CORRUPT), "truncated file")
    ok(problem(Tree().apply { bytes = "hello!".toByteArray() }, PortableContentProblem.CORRUPT), "extra bytes")
    ok(problem(Tree().apply { deny = true }, PortableContentProblem.UNAVAILABLE), "permission failure")
    var ticks = 0
    ok(runCatching { PortableContentVerifier.verify(Tree(), index, checkActive = { if (++ticks == 4) throw CancellationException() }) }.exceptionOrNull() is CancellationException, "cancellation propagates")
    ok(runCatching { run(Tree(), file.copy(path = listOf("..", "Game.bin"))) }.isFailure, "untrusted paths rejected")
    println("PASS: $n portable content checks")
}'''
with tempfile.TemporaryDirectory() as tmp:
    folder = Path(tmp)
    (folder / 'Checks.kt').write_text(checks)
    runtime_cp = str(COMPILER[1]) + ':' + str(jar)
    files = ['PortableJsonSafety.kt', 'PortablePaths.kt', 'PortableGameIndex.kt', 'PortableContentVerification.kt']
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', runtime_cp, '-d', str(folder / 'classes'),
                    *map(str, (sources / name for name in files)), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', str(folder / 'classes') + ':' + runtime_cp, 'tv.orbit.shield.ChecksKt'], check=True)
