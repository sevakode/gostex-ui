"""Test immutable state generations with an in-memory document provider."""
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
source = (sources / 'HomeSections.kt').read_text()
model = source[source.index('internal enum class HomeSectionKind'):source.index('internal fun homeGameKey')]
checks = r'''package tv.orbit.shield
import java.io.*
private class Store: PortableStagingDirectory {
    val entries = mutableListOf<PortableStagingEntry>()
    val data = mutableMapOf<String, ByteArray>()
    var failWriteName: String? = null
    var corruptName: String? = null
    var writes = 0
    override fun children() = entries.toList()
    override fun create(name: String, mime: String): PortableStagingEntry {
        val entry = PortableStagingEntry("id${entries.size}", name, mime); entries += entry; return entry
    }
    override fun isChild(id: String) = entries.any { it.id == id }
    override fun openWrite(id: String): OutputStream {
        writes++
        val name = entries.single { it.id == id }.name
        return object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (failWriteName != null && name.contains(failWriteName!!)) { super.write(b, off, len / 2); throw IOException("interrupted") }
                super.write(b, off, len)
            }
            override fun close() { data[id] = toByteArray() }
        }
    }
    override fun openRead(id: String): InputStream {
        val name = entries.single { it.id == id }.name
        val bytes = data[id] ?: byteArrayOf()
        return ByteArrayInputStream(if (corruptName != null && name.contains(corruptName!!)) bytes + 0 else bytes)
    }
}
fun main() {
    var n = 0
    fun ok(b: Boolean, msg: String) { check(b) { msg }; n++ }
    fun snap(id: String = PortableSnapshotCodec.newId()) = PortableSnapshot(id, PortableGameIndex(emptyList(), emptyList()),
        HomeSectionsOptions(emptyList(), listOf("app:missing")), mapOf("language" to "ru"))
    val store = Store(); val first = snap(); val marker = PortableSnapshotStore.write(store, first)
    ok(PortableSnapshotStore.readCommitted(store, marker.documentId).snapshot == first, "written generation restored")
    val savedEntries = store.entries.toList(); val saved = store.data.mapValues { it.value.copyOf() }; val writes = store.writes
    ok(runCatching { PortableSnapshotStore.write(store, first) }.isFailure && store.writes == writes, "duplicate generation never overwrites")
    store.failWriteName = "state-"
    ok(runCatching { PortableSnapshotStore.write(store, snap()) }.isFailure, "interrupted state rejected")
    ok(store.entries.count { it.name.startsWith("gostex-commit-") } == 1, "no commit for failed state")
    ok(savedEntries.all { saved[it.id]!!.contentEquals(store.data[it.id]!!) }, "old generation preserved on state failure")
    store.failWriteName = "commit-"
    ok(runCatching { PortableSnapshotStore.write(store, snap()) }.isFailure, "interrupted commit rejected")
    val incomplete = store.entries.last()
    ok(runCatching { PortableSnapshotStore.readCommitted(store, incomplete.id) }.isFailure, "partial commit never accepted")
    ok(PortableSnapshotStore.readCommitted(store, marker.documentId).snapshot == first, "previous generation still usable")
    store.failWriteName = null
    store.corruptName = "state-"
    ok(runCatching { PortableSnapshotStore.write(store, snap()) }.isFailure, "readback mismatch blocks commit")
    ok(runCatching { PortableSnapshotStore.readCommitted(store, marker.documentId) }.isFailure, "corrupt state rejected")
    store.corruptName = null
    val second = snap(); val marker2 = PortableSnapshotStore.write(store, second)
    ok(PortableSnapshotStore.readCommitted(store, marker2.documentId).snapshot == second, "next generation usable")
    ok(PortableSnapshotStore.readCommitted(store, marker.documentId).snapshot == first, "both generations retained")
    val payload = "game".toByteArray()
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it.toInt() and 255) }
    val withFile = snap().copy(index = PortableGameIndex(listOf(PortableIndexedFile("rom", listOf("game.bin"), payload.size.toLong(), digest)), emptyList()))
    val marker3 = PortableSnapshotStore.write(store, withFile)
    var treeCalls = 0
    var available = true
    val tree = object : PortableContentTree {
        override val rootId = "root"
        override fun children(parentId: String): List<PortableContentEntry> { treeCalls++; return if (available) listOf(PortableContentEntry("f", "game.bin", false)) else emptyList() }
        override fun isChild(parentId: String, childId: String) = true
        override fun openRead(id: String): InputStream = ByteArrayInputStream(payload)
    }
    val checked = inspectPortableBackup(store, tree, marker3.documentId)
    ok(checked.committed.snapshot == withFile && checked.content.verified["rom"] == "f", "inspect saved generation and its file")
    available = false
    ok(inspectPortableBackup(store, tree, marker3.documentId).content.issues.single().problem == PortableContentProblem.MISSING, "missing file reported without modifying metadata")
    val beforeCalls = treeCalls
    ok(runCatching { inspectPortableBackup(store, tree, incomplete.id) }.isFailure && treeCalls == beforeCalls, "invalid metadata rejected before opening content")
    val dup = store.entries.first { it.name.startsWith("gostex-state-") }
    store.entries += PortableStagingEntry("duplicate", dup.name, dup.mime)
    ok(runCatching { PortableSnapshotStore.readCommitted(store, marker.documentId) }.isFailure, "ambiguous state rejected")
    println("PASS: $n portable store checks")
}'''
with tempfile.TemporaryDirectory() as tmp:
    folder = Path(tmp)
    (folder / 'Model.kt').write_text('package tv.orbit.shield\n' + model)
    (folder / 'Checks.kt').write_text(checks)
    runtime_cp = str(COMPILER[1]) + ':' + str(jar)
    files = ['PortableJsonSafety.kt', 'PortablePaths.kt', 'PortableGameIndex.kt', 'PortableHomeState.kt', 'PortableSettings.kt',
             'PortableSnapshot.kt', 'PortableVerifiedCopy.kt', 'PortableStaging.kt', 'PortableSnapshotStore.kt',
             'PortableContentVerification.kt', 'PortableBackupVerification.kt']
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', runtime_cp, '-d', str(folder / 'classes'),
                    *map(str, (sources / name for name in files)), str(folder / 'Model.kt'), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', str(folder / 'classes') + ':' + runtime_cp, 'tv.orbit.shield.ChecksKt'], check=True)
