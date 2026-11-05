"""Exercise bridge behavior with fake Android storage; real AtomicFile needs device QA."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import ROOT, JAVA, COMPILER

root = ROOT
compiler = COMPILER
java = JAVA
source = root / 'native-launcher/app/src/main/java/tv/orbit/shield/LegacyCatalogBridge.kt'
context = '''package android.content
import java.io.File
import java.io.InputStream
abstract class Context {
 companion object { const val MODE_PRIVATE = 0 }
 abstract val filesDir: File
 abstract val assets: Assets
 abstract fun getSharedPreferences(name: String, mode: Int): Preferences
}
interface Preferences { val all: Map<String, *> }
interface Assets { fun open(name: String): InputStream }
'''
atomic = '''package android.util
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
class AtomicFile(val baseFile: File) {
 fun openRead(): FileInputStream {
  val pending = File(baseFile.path + ".new")
  if (pending.exists() && baseFile.exists()) pending.delete() // Android 11 AtomicFile behavior
  return FileInputStream(baseFile)
 }
 fun startWrite(): FileOutputStream = FileOutputStream(File(baseFile.path + ".new"))
 fun finishWrite(out: FileOutputStream) { out.close(); check(File(baseFile.path + ".new").renameTo(baseFile)) }
 fun failWrite(out: FileOutputStream) { out.close(); File(baseFile.path + ".new").delete() }
}
'''
checks = '''package tv.orbit.shield
import android.content.*
import java.io.File
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking

data class Game(val id: String, val tree: String, val files: List<String>)
object LibraryImport {
 fun decode(text: String): List<Game> = when (text) {
  "legacy" -> listOf(Game("old-id", "primary:Games", listOf("game.bin")))
  "legacy-other" -> listOf(Game("other-id", "primary:Other", listOf("other.bin")))
  "public" -> listOf(Game("metadata", "", emptyList()))
  "empty" -> emptyList()
  else -> error("invalid")
 }
}
class TestContext(override val filesDir: File, var text: String, var state: Boolean): Context() {
 var signals: Map<String, *>? = null
 override val assets = object: Assets { override fun open(name: String) = ByteArrayInputStream(text.toByteArray()) }
 override fun getSharedPreferences(name: String, mode: Int) = object: Preferences {
  override val all = signals ?: if (state) mapOf("libraryRoots" to "[existing]") else mapOf("libraryRoots" to "[]", "home.sections.v1" to "no games")
 }
}
fun reset() { LegacyCatalogBridge::class.java.getDeclaredField("captured").apply { isAccessible = true; setBoolean(null, false) } }
fun main() = runBlocking {
 val dir = java.nio.file.Files.createTempDirectory("catalog-bridge-").toFile()
 try {
  val ctx = TestContext(dir, "legacy", false)
  LegacyCatalogBridge.capture(ctx)
  check(!File(dir, "legacy-catalog.json").exists())
  ctx.state = true
  LegacyCatalogBridge.capture(ctx)
  check(File(dir, "legacy-catalog.json").readText() == "legacy")
  ctx.text = "public"
  check(LegacyCatalogBridge.load(ctx).single().id == "old-id")
  File(dir, "legacy-catalog.json.new").writeText("partial")
  check(LegacyCatalogBridge.load(ctx).single().id == "old-id")
  check(runCatching { LegacyCatalogBridge.adoptVerified(ctx, "legacy-other") }.isFailure)
  check(File(dir, "legacy-catalog.json.new").readText() == "partial")
  File(dir, "legacy-catalog.json.new").delete()
  LegacyCatalogBridge.adoptVerified(ctx, "legacy-other")
  check(File(dir, "legacy-catalog.json").readText() == "legacy")
  check(runCatching { LegacyCatalogBridge.adoptVerified(ctx, "public") }.isFailure)
  reset()
  LegacyCatalogBridge.capture(ctx)
  check(File(dir, "legacy-catalog.json").readText() == "legacy")
  File(dir, "legacy-catalog.json").writeText("damaged")
  reset(); ctx.text = "legacy"
  check(runCatching { LegacyCatalogBridge.capture(ctx) }.isFailure)
  check(runCatching { LegacyCatalogBridge.adoptVerified(ctx, "legacy") }.isFailure)
  check(File(dir, "legacy-catalog.json").readText() == "damaged")
  check(LegacyCatalogBridge.load(ctx).single().id == "old-id")
  File(dir, "legacy-catalog.json").delete()
  File(dir, "legacy-catalog.json.new").writeText("partial")
  reset()
  check(runCatching { LegacyCatalogBridge.capture(ctx) }.isFailure)
  check(File(dir, "legacy-catalog.json.new").readText() == "partial")
  File(dir, "legacy-catalog.json.new").delete()
  reset(); ctx.text = "public"
  LegacyCatalogBridge.capture(ctx)
  check(!File(dir, "legacy-catalog.json").exists())
  check(LegacyCatalogBridge.load(ctx).single().id == "metadata")
  ctx.text = "legacy"
  val indicators = listOf(
   mapOf("favorites" to setOf("old-id")),
   mapOf("home.sections.v1" to "game:old-id"),
   mapOf("home.selectedGame" to "old-id"),
   mapOf("played:old-id" to 1L),
   mapOf("covers.choice.old-id" to "manual")
  )
  for (indicator in indicators) {
   File(dir, "legacy-catalog.json").delete(); reset(); ctx.signals = indicator
   LegacyCatalogBridge.capture(ctx)
   check(File(dir, "legacy-catalog.json").readText() == "legacy")
  }
  File(dir, "legacy-catalog.json").delete(); reset(); ctx.signals = emptyMap<String, Any>()
  File(dir, "imported-games.json").writeText("[saved]")
  LegacyCatalogBridge.capture(ctx)
  check(File(dir, "legacy-catalog.json").readText() == "legacy")
  println("PASS: private catalog preservation, clean install and interrupted/corrupt state")
 } finally { dir.deleteRecursively() }
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-catalog-check-') as temp:
    folder = Path(temp)
    for name, text in [('Context.kt', context), ('AtomicFile.kt', atomic), ('Checks.kt', checks)]:
        (folder / name).write_text(text)
    cp = ':'.join(map(str, compiler))
    runtime = ':'.join(map(str, [compiler[1], compiler[4], compiler[5]]))
    subprocess.run([str(java), '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', runtime, '-d', str(folder / 'classes'), str(source), *map(str, folder.glob('*.kt'))], check=True)
    subprocess.run([str(java), '-cp', str(folder / 'classes') + ':' + runtime, 'tv.orbit.shield.ChecksKt'], check=True)
