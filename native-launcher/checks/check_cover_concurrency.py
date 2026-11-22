"""Exercise cover coordination with slow provider I/O and image validation."""
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

private suspend fun checkSharedImagePreparation(cancel: Boolean) = coroutineScope {
    val gate = CoverOperationGate()
    val validating = CompletableDeferred<Unit>()
    val releaseValidation = CountDownLatch(1)
    val MAX_IMAGE_BYTES = 1024
    val context = Unit
    val payload = byteArrayOf(1, 2, 3)
    val tree = object {
        fun read(path: String, max: Int): ByteArray? {
            check(path == "incoming/cover.png" && max >= payload.size)
            return payload
        }
    }
    var imagesWritten = false
    var entry = ManagedCover()
    fun validateImage(image: ByteArray, expected: String?, wide: Boolean): String {
        check(image === payload && expected == "expected-sha" && wide)
        validating.complete(Unit)
        check(releaseValidation.await(10, TimeUnit.SECONDS))
        return "validated-sha"
    }
    fun storeImage(context: Unit, image: ByteArray, sha: String, onWritten: () -> Unit): String {
        check(context == Unit && image === payload && sha == "validated-sha")
        onWritten()
        return "stored"
    }
    // Exercise the previous locking behavior if load calls the combined operation.
    fun putImage(context: Unit, image: ByteArray, expected: String, wide: Boolean, onWritten: () -> Unit): String =
        storeImage(context, image, validateImage(image, expected, wide), onWritten)

    SHARED_IMAGE_LOAD

    val pending = async(Dispatchers.Default) { load("incoming/cover.png", "expected-sha", wide = true) }
    try {
        withTimeout(2000) { validating.await() }
        withTimeoutOrNull(1000) { gate.mutate { entry = entry.copy(manual = "chosen") } }
        check(entry.manual == "chosen") { "Manual selection waited for shared image validation" }
        if (cancel) pending.cancel()
    } finally {
        releaseValidation.countDown()
    }
    if (cancel) {
        pending.join()
        check(!imagesWritten)
    } else {
        check(pending.await() == "stored" && imagesWritten)
    }
}

fun main() = runBlocking {
 val gate = CoverOperationGate()
 var index = ManagedCover(original = "old")
 val ioStarted = CompletableDeferred<Unit>()
 val releaseIo = CompletableDeferred<Unit>()
 val sync = async(Dispatchers.Default) {
  gate.synchronize {
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
 checkSharedImagePreparation(cancel = false)
 checkSharedImagePreparation(cancel = true)
 println("PASS: manual edit during provider I/O and image validation, safe merge, serialized syncs, cancellation")
}
'''
sync = (source_dir / 'ManagedCovers.kt').read_text().split('suspend fun sync(', 1)[1]
before_provider_read = sync.split('tree.read("updates.json"', 1)[0]
assert 'read(context)' not in before_provider_read, "Sync reads the index before provider I/O and discards that snapshot"
shared_image_load = sync[sync.index('suspend fun load('):sync.index('prepared += PreparedUpdate')].rstrip()

with tempfile.TemporaryDirectory(prefix='gostex-cover-concurrency-') as temp:
    folder = Path(temp)
    (folder / 'Model.kt').write_text('package tv.orbit.shield\n' + cover)
    (folder / 'Checks.kt').write_text(checks.replace('SHARED_IMAGE_LOAD', shared_image_load))
    cp = ':'.join(map(str, compiler))
    runtime = ':'.join(map(str, [compiler[1], compiler[4], compiler[5]]))
    subprocess.run([str(java), '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', runtime, '-d', str(folder / 'classes'), str(source_dir / 'CoverOperationGate.kt'), str(folder / 'Model.kt'), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(java), '-cp', str(folder / 'classes') + ':' + runtime, 'tv.orbit.shield.ChecksKt'], check=True)
