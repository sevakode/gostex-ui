"""Exercise real Android export orchestration with an isolated fake Android boundary."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import ROOT, JAVA, COMPILER

sources = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
files = {
    'Context.kt': 'package android.content\nclass Context { val contentResolver = Any() }',
    'Uri.kt': '''package android.net
class Uri private constructor(private val value: String) {
    val lastPathSegment: String? get() = java.net.URLDecoder.decode(value.substringBefore('?').substringAfterLast('/'), "UTF-8")
    override fun toString() = value
    companion object { fun parse(value: String) = Uri(value) }
}''',
    'Documents.kt': '''package android.provider
object DocumentsContract { fun isTreeUri(uri: android.net.Uri) = uri.toString().contains("/tree/") }''',
    'Boundary.kt': '''package tv.orbit.shield
import android.content.Context
import android.net.Uri
internal data class FakeGame(val platform: String, val files: List<String>, val tree: String)
internal data class FakeLive(val games: List<FakeGame>)
internal enum class LibrarySource { FILES, SINGLE_FILE }
internal data class Root(val uri: String, val platform: String, val source: LibrarySource)
internal object State { var live = FakeLive(emptyList()); var roots = emptyList<Root>(); var reads = 0; var changed = false; var sources = 0; var destinations = 0; var committed = false }
internal object PortableLiveCapture { fun capture(context: Context, check: () -> Unit): FakeLive { check(); State.reads++; return State.live } }
internal object LibraryImport { fun roots(context: Context) = State.roots }
internal interface PortablePlanningSource
internal class PortableSafPlanningSource(resolver: Any, uri: Uri): PortablePlanningSource { init { State.sources++ } }
internal class PortableSafSingleSource(context: Context, uri: Uri): PortablePlanningSource { init { State.sources++ } }
internal class PortablePrivateCover(context: Context, key: String)
internal class PortableSafWritableTree(resolver: Any, uri: Uri) { init { State.destinations++ } }
internal class PortableSafStaging(resolver: Any, uri: Uri) { init { State.destinations++ } }
internal class PortableExportResult
internal object PortableExportWorkflow {
    fun export(live: FakeLive, recapture: () -> FakeLive, sources: Map<String, PortablePlanningSource>, cover: (String) -> PortablePrivateCover,
               dest: PortableSafWritableTree, staging: PortableSafStaging, check: () -> Unit,
               onSourceVerification: (Int, Int) -> Unit, onFile: (String, Int, Int) -> Unit): PortableExportResult {
        check(); if (State.changed) State.roots = emptyList(); check(recapture() == live)
        State.committed = true; return PortableExportResult()
    }
}''',
    'Checks.kt': '''package tv.orbit.shield
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.runBlocking
fun main() = runBlocking {
    val c = Context(); val dst = Uri.parse("content://dst/tree/folder")
    State.live = FakeLive(listOf(FakeGame("Nintendo 3DS", listOf("content://source/document/00000000%2EAPP"), "content://source/tree/root")))
    val failure = runCatching { PortableAndroidExport.export(c, dst) }.exceptionOrNull()
    check(failure is PortableInstalled3dsUnsupported && failure.gameCount == 1 && State.sources == 0 && State.destinations == 0 && !State.committed)
    val uri = "content://source/document/game.iso"
    State.live = FakeLive(listOf(FakeGame("PSP", listOf(uri), uri)))
    State.roots = listOf(Root(uri, "PSP", LibrarySource.SINGLE_FILE))
    PortableAndroidExport.export(c, dst)
    check(State.sources == 1 && State.committed && State.reads == 3)
    State.committed = false; State.changed = true
    check(runCatching { PortableAndroidExport.export(c, dst) }.isFailure && !State.committed)
    State.changed = false; State.roots = listOf(Root(uri, "PSP", LibrarySource.FILES)); State.committed = false
    check(runCatching { PortableAndroidExport.export(c, dst) }.isFailure && !State.committed)
    println("PASS: installed 3DS rejected before provider access, single file handled, changing sources blocks commit")
}'''
}
with tempfile.TemporaryDirectory() as temp:
    folder = Path(temp)
    for name, text in files.items(): (folder / name).write_text(text)
    runtime = ':'.join(map(str, (COMPILER[1], COMPILER[4])))
    output = folder / 'classes'
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                    '-classpath', runtime, '-d', str(output), str(sources/'PortableExportPreflight.kt'), str(sources/'PortableAndroidExport.kt'),
                    *map(str, (folder/name for name in files))], check=True)
    subprocess.run([str(JAVA), '-cp', str(output)+':'+runtime, 'tv.orbit.shield.ChecksKt'], check=True)
