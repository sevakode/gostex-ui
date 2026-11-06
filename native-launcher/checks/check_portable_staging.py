"""Exercise staging boundaries against a synthetic, controllable document provider."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import ROOT, JAVA, COMPILER

sources = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
checks = r'''package tv.orbit.shield
import java.io.*

private const val NAME = "gostex-stage-0123456789abcdef0123456789abcdef.tmp"
private val payload = "synthetic ROM content".toByteArray()
private class Fake : PortableStagingDirectory {
    val existing = PortableStagingEntry("source", "game.iso", "application/octet-stream")
    val items = mutableListOf(existing)
    var returned = PortableStagingEntry("new", NAME, "application/octet-stream")
    var child = true
    var addCreated = true
    var duplicateName = false
    var writes = 0
    var verifyCorrupt = false
    private val bytes = ByteArrayOutputStream()
    override fun children() = items.toList()
    override fun create(name: String, mime: String): PortableStagingEntry {
        if (addCreated) items += returned
        if (duplicateName) items += PortableStagingEntry("other", name, mime)
        return returned
    }
    override fun isChild(id: String) = child
    override fun openWrite(id: String): OutputStream { writes++; check(id == "new"); return bytes }
    override fun openRead(id: String): InputStream = ByteArrayInputStream(
        bytes.toByteArray().let { if (verifyCorrupt) it + byteArrayOf(0) else it })
}
fun main() {
    var checks = 0
    fun ok(b: Boolean, label: String) { check(b) { label }; checks++ }
    fun attempt(f: Fake, protected: Set<String> = setOf("source")) = runCatching {
        portableStageFile(f, { ByteArrayInputStream(payload) }, Long.MAX_VALUE, protected, newName = { NAME })
    }
    val good = Fake(); val created = attempt(good).getOrThrow()
    ok(created.documentId == "new" && created.name == NAME && created.receipt.bytes == payload.size.toLong(), "verified new stage")
    ok(good.items.first() == good.existing && good.writes == 1, "existing entry remains unchanged")
    val sameName = Fake().apply { items += PortableStagingEntry("old", NAME, "application/octet-stream") }
    ok(attempt(sameName).isFailure && sameName.writes == 0, "existing name rejected")
    val reused = Fake().apply { returned = existing; addCreated = false }
    ok(attempt(reused).isFailure && reused.writes == 0, "source ID rejected")
    val protected = Fake().apply { returned = PortableStagingEntry("reserved", NAME, "application/octet-stream") }
    ok(attempt(protected, setOf("reserved")).isFailure && protected.writes == 0, "protected ID rejected")
    val outside = Fake().apply { child = false }
    ok(attempt(outside).isFailure && outside.writes == 0, "outside document rejected")
    val renamed = Fake().apply { returned = PortableStagingEntry("new", "different.tmp", "application/octet-stream") }
    ok(attempt(renamed).isFailure && renamed.writes == 0, "unexpected name rejected")
    val folder = Fake().apply { returned = PortableStagingEntry("new", NAME, "vnd.android.document/directory") }
    ok(attempt(folder).isFailure && folder.writes == 0, "directory rejected")
    val missing = Fake().apply { addCreated = false }
    ok(attempt(missing).isFailure && missing.writes == 0, "unlisted new document rejected")
    val ambiguous = Fake().apply { duplicateName = true }
    ok(attempt(ambiguous).isFailure && ambiguous.writes == 0, "duplicate name rejected")
    val corrupt = Fake().apply { verifyCorrupt = true }
    ok(attempt(corrupt).isFailure && corrupt.writes == 1 && corrupt.items.first() == corrupt.existing, "corruption fails without touching source")
    val invalid = Fake()
    ok(runCatching { portableStageFile(invalid, { ByteArrayInputStream(payload) }, 100, emptySet(), newName = { "../bad" }) }.isFailure && invalid.writes == 0,
        "unsafe generated name rejected")
    var calls = 0
    val cancelled = Fake()
    ok(runCatching { portableStageFile(cancelled, { ByteArrayInputStream(payload) }, 100, emptySet(),
        checkActive = { calls++; if (calls == 3) throw IOException("cancelled") }, newName = { NAME }) }.isFailure && cancelled.writes == 0,
        "cancellation before write")
    println("PASS: $checks staging checks")
}
'''
with tempfile.TemporaryDirectory() as tmp:
    folder = Path(tmp)
    (folder / 'Checks.kt').write_text(checks)
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)),
                    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                    '-classpath', str(COMPILER[1]), '-d', str(folder / 'classes'),
                    str(sources / 'PortableVerifiedCopy.kt'), str(sources / 'PortableStaging.kt'),
                    str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', str(folder / 'classes') + ':' + str(COMPILER[1]),
                    'tv.orbit.shield.ChecksKt'], check=True)
