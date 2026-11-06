"""Exercise live-capture validation with isolated synthetic preference and file stubs."""
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
android = r'''package android.content
import java.io.File
open class SharedPreferences(open val all: Map<String, *>)
open class Context(open val filesDir: File) {
    companion object { const val MODE_PRIVATE = 0 }
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences = error("override")
}'''
stubs = r'''package tv.orbit.shield
import android.content.Context
import org.json.JSONArray
data class Game(val id: String, val file: String)
object LibraryImport {
    fun decode(text: String): List<Game> { val a = JSONArray(text); return (0 until a.length()).map { a.getJSONObject(it).let { o -> Game(o.getString("id"), o.getJSONArray("files").getString(0)) } } }
}
internal object SectionPreferences {
    fun read(context: Context) = HomeSectionsOptions(listOf(HomeSection("apps", HomeSectionKind.APPS)), emptyList())
}'''
checks = r'''package tv.orbit.shield
import android.content.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
private class Fake(dir: File) : Context(dir) {
    var values = mutableMapOf<String, Any>()
    var reads = 0
    var mutate = false
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        reads++
        if (mutate && reads == 2) values["language"] = "es"
        return SharedPreferences(values.toMap())
    }
}
fun main() {
    var n = 0
    fun ok(v: Boolean, name: String) { check(v) { name }; n++ }
    val dir = Files.createTempDirectory("gostex-synthetic").toFile()
    try {
        val ctx = Fake(dir); val library = File(dir, "imported-games.json")
        val validLibrary = """[{"id":"g1","title":"Game","platform":"PS1","emulator":"emu","component":"app/Act","files":["content://private/game.cue"]}]"""
        library.writeText(validLibrary)
        ctx.values["language"] = "ru"; ctx.values["covers.automatic"] = false
        ctx.values["covers.steamgriddbKey"] = "secret"; ctx.values["covers.choice.g1"] = "manual"
        ctx.values["home.sections.v1"] = PortableHomeStateCodec.encode(HomeSectionsOptions(listOf(HomeSection("apps", HomeSectionKind.APPS)), listOf("game:g1")))
        val coverFile = File(dir, "covers/index.json"); coverFile.parentFile.mkdirs()
        val key = managedCoverKey("a".repeat(64))
        val covers = mapOf("g1" to ManagedCover(manual = key, sourceName = "source", author = "author"))
        coverFile.writeText(ManagedCoverManifest.encodeIndex(covers))
        val result = PortableLiveCapture.capture(ctx)
        ok(result.games.single().id == "g1" && result.games.single().file.endsWith(".cue"), "game metadata preserved")
        ok(result.home.favorites == listOf("game:g1"), "favorites preserved")
        ok(result.covers == covers && result.coverChoices["g1"] == "manual", "cover metadata and choice preserved")
        ok(result.settings["language"] == "ru" && result.settings["covers.automatic"] == false, "settings retained")
        ok(!result.settings.containsKey("covers.steamgriddbKey"), "secret excluded from settings")
        ok(library.readText().contains("game.cue"), "source file untouched")
        library.writeText(validLibrary.replace("\"title\":", "\"future\":true,\"title\":"))
        ok(runCatching { PortableLiveCapture.capture(ctx) }.isFailure, "unknown game field rejected")
        library.writeText(validLibrary)
        ctx.values["home.sections.v1"] = "{\"version\":2}"
        ok(runCatching { PortableLiveCapture.capture(ctx) }.isFailure, "future home document is not replaced by defaults")
        ctx.values.remove("home.sections.v1")
        ok(PortableLiveCapture.capture(ctx).home.sections.single().id == "apps", "legacy read when home document absent")
        ctx.values["covers.choice.g1"] = "unknown"
        ok(runCatching { PortableLiveCapture.capture(ctx) }.isFailure, "unknown choice is not discarded")
        ctx.values["covers.choice.g1"] = "manual"
        coverFile.writeText("{\"version\":1,\"games\":{\"g1\":{\"manual\":\"bad\"}}}")
        ok(runCatching { PortableLiveCapture.capture(ctx) }.isFailure, "invalid image key rejected")
        coverFile.writeText("{\"version\":1,\"games\":{\"g1\":{\"future\":\"x\"}}}")
        ok(runCatching { PortableLiveCapture.capture(ctx) }.isFailure, "unknown cover field rejected")
        val backup = File(coverFile.path + ".bak"); backup.writeText(ManagedCoverManifest.encodeIndex(covers))
        ok(PortableLiveCapture.capture(ctx).covers == covers, "committed backup takes precedence")
        ok(backup.exists() && coverFile.exists(), "reading does not recover or rewrite files")
        backup.delete(); coverFile.delete(); File(coverFile.path + ".new").writeText("partial")
        ok(runCatching { PortableLiveCapture.capture(ctx) }.isFailure, "uncommitted source not mistaken for empty")
        File(coverFile.path + ".new").delete(); ctx.reads = 0; ctx.mutate = true
        ok(runCatching { PortableLiveCapture.capture(ctx) }.isFailure, "concurrent settings change rejected")
        ctx.mutate = false
        ok(runCatching { PortableLiveCapture.capture(ctx) { throw CancellationException() } }.exceptionOrNull() is CancellationException, "cancellation propagates")
        println("PASS: $n live capture checks")
    } finally { dir.deleteRecursively() }
}'''
with tempfile.TemporaryDirectory() as tmp:
    folder = Path(tmp)
    for name, content in [('Android.kt', android), ('Model.kt', 'package tv.orbit.shield\n' + model), ('Stubs.kt', stubs), ('Checks.kt', checks)]:
        (folder / name).write_text(content)
    runtime_cp = str(COMPILER[1]) + ':' + str(jar)
    files = ['PortableJsonSafety.kt', 'PortableSettings.kt', 'PortableHomeState.kt', 'PortableLiveCapture.kt', 'ManagedCoverModel.kt']
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', runtime_cp, '-d', str(folder / 'classes'),
                    *map(str, (sources / name for name in files)), *map(str, (folder / name for name in ['Android.kt', 'Model.kt', 'Stubs.kt', 'Checks.kt']))], check=True)
    subprocess.run([str(JAVA), '-cp', str(folder / 'classes') + ':' + runtime_cp, 'tv.orbit.shield.ChecksKt'], check=True)
