"""Exercise the conservative archive admission rule without Android or device claims."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import ROOT, JAVA, COMPILER

root = ROOT
compiler = COMPILER
java = JAVA
source = root / 'native-launcher/app/src/main/java/tv/orbit/shield/ArchiveAdmission.kt'
checks = '''package tv.orbit.shield
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
fun main() {
 for (name in listOf("a.zip", "b.7Z", "c.RAR")) {
   check(!admitArchiveCandidate(name, false)); check(admitArchiveCandidate(name, true))
 }
 for (name in listOf("game.nds", "game.cue", "game.chd", "game.zip.nds")) {
   check(admitArchiveCandidate(name, false))
 }
 val file = File.createTempFile("gostex-archive", ".zip")
 fun create(vararg names: String) {
   ZipOutputStream(file.outputStream()).use { out -> names.forEach { name ->
     val bytes = "synthetic rom payload".toByteArray()
     val crc = CRC32().apply { update(bytes) }
     val entry = ZipEntry(name).apply { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size; this.crc = crc.value }
     out.putNextEntry(entry); out.write(bytes); out.closeEntry()
   } }
 }
 create("Game.NDS", "readme.txt")
 check(verifySelectedZip(file, setOf("nds")))
 check(!verifySelectedZip(file, setOf("gba")))
 ZipOutputStream(file.outputStream()).use { out -> out.putNextEntry(ZipEntry("compressed.nds")); out.write(ByteArray(20000) { (it % 241).toByte() }); out.closeEntry() }
 check(verifySelectedZip(file, setOf("nds")))
 create("one.nds", "two.nds")
 check(!verifySelectedZip(file, setOf("nds")))
 create("../one.nds")
 check(!verifySelectedZip(file, setOf("nds")))
 create("one.nds")
 val good = file.readBytes()
 val payload = "synthetic rom payload".toByteArray()
 val index = good.indices.first { i -> i + payload.size <= good.size && good.copyOfRange(i, i + payload.size).contentEquals(payload) }
 good[index] = (good[index].toInt() xor 1).toByte(); file.writeBytes(good)
 check(!verifySelectedZip(file, setOf("nds")))
 create("one.nds"); file.writeBytes(file.readBytes().dropLast(20).toByteArray())
 check(runCatching { verifySelectedZip(file, setOf("nds")) }.getOrDefault(false).not())
 create("one.nds")
 check(runCatching { verifySelectedZip(file, setOf("nds")) { error("cancel") } }.isFailure)
 val failures = ArchiveFailureCache<String>(ttlMs = 100, capacity = 2)
 check(!failures.skip("bad", 10)); failures.record("bad", 10)
 check(failures.skip("bad", 109)); check(!failures.skip("bad", 110))
 failures.record("one", 200); failures.record("two", 200); failures.record("three", 200)
 check(failures.skip("three", 201)); check(!failures.skip("one", 201))
 failures.remove("three"); check(!failures.skip("three", 201))
 val successes = ArchiveSuccessCache<String>(ttlMs = 100, capacity = 2)
 val stamp = ArchiveFileStamp(100, 200)
 successes.remember("bad", null, stamp, 10); check(!successes.reuse("bad", stamp, 11))
 successes.remember("bad", stamp, ArchiveFileStamp(101, 200), 10); check(!successes.reuse("bad", stamp, 11))
 successes.remember("good", stamp, stamp, 10); check(successes.reuse("good", stamp, 109))
 check(!successes.reuse("good", stamp, 110))
 successes.remember("good", stamp, stamp, 110); check(!successes.reuse("good", ArchiveFileStamp(100, 201), 111))
 successes.remember("a", stamp, stamp, 200); successes.remember("b", stamp, stamp, 200)
 check(successes.reuse("a", stamp, 201)); successes.remember("c", stamp, stamp, 201)
 check(!successes.reuse("b", stamp, 202)); check(successes.reuse("a", stamp, 202))
 file.delete()
 println("archive admission checks passed")
}
'''
with tempfile.TemporaryDirectory() as d:
    folder = Path(d); (folder / 'Checks.kt').write_text(checks)
    subprocess.run([str(java), '-cp', ':'.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', str(compiler[1]), '-d', str(folder / 'classes'), str(source), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(java), '-cp', str(folder / 'classes') + ':' + str(compiler[1]), 'tv.orbit.shield.ChecksKt'], check=True)
