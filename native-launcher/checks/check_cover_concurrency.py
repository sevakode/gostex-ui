"""Exercise cover coordination with suspended shared-folder IO; Android SAF needs device QA."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import ROOT, JAVA, COMPILER

root = ROOT
source_dir = root / 'native-launcher/app/src/main/java/tv/orbit/shield'
compiler = COMPILER
java = JAVA
model = (source_dir / 'ManagedCoverModel.kt').read_text()
cover = model[model.index('internal data class ManagedCover('):model.index('internal enum class CoverChoice')]
checks = '''package tv.orbit.shield
import kotlinx.coroutines.*
fun main() = runBlocking {
 val gate = CoverOperationGate()
 var index = ManagedCover(original = "old")
 val ioStarted = CompletableDeferred<Unit>()
 val releaseIo = CompletableDeferred<Unit>()
 val sync = async(Dispatchers.Default) {
  gate.synchronize {
   val snapshot = gate.mutate { index }
   check(snapshot.original == "old")
   ioStarted.complete(Unit)
   releaseIo.await() // represents a slow provider read; no index lock is held
   gate.mutate { index = mergeSharedCover(index, "new", "wide") }
  }
 }
 ioStarted.await()
 withTimeout(2000) { gate.mutate { index = index.copy(manual = "chosen", sourceName = "user") } }
 check(index.manual == "chosen" && index.original == "old")
 releaseIo.complete(Unit); sync.await()
 check(index.manual == "chosen" && index.sourceName == "user" && index.original == "new" && index.wide == "wide")
 check(mergeSharedCover(index, null, null) == index)
 val locked = CompletableDeferred<Unit>()
 val never = CompletableDeferred<Unit>()
 val blockedSync = launch(Dispatchers.Default) { gate.synchronize { locked.complete(Unit); never.await() } }
 locked.await()
 val nextStarted = CompletableDeferred<Unit>()
 val nextSync = launch(Dispatchers.Default) { gate.synchronize { nextStarted.complete(Unit) } }
 check(withTimeoutOrNull(100) { nextStarted.await() } == null)
 blockedSync.cancelAndJoin()
 withTimeout(2000) { nextStarted.await() }; nextSync.join()
 println("PASS: manual edit during slow sync, safe merge, serialized syncs, cancellation")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-cover-concurrency-') as temp:
    folder = Path(temp)
    (folder / 'Model.kt').write_text('package tv.orbit.shield\n' + cover)
    (folder / 'Checks.kt').write_text(checks)
    cp = ':'.join(map(str, compiler))
    runtime = ':'.join(map(str, [compiler[1], compiler[4], compiler[5]]))
    subprocess.run([str(java), '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', runtime, '-d', str(folder / 'classes'), str(source_dir / 'CoverOperationGate.kt'), str(folder / 'Model.kt'), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(java), '-cp', str(folder / 'classes') + ':' + runtime, 'tv.orbit.shield.ChecksKt'], check=True)
