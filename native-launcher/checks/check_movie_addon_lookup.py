"""Check bounded add-on lookup, errors, ordering and cancellation."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import JAVA, COMPILER, ROOT

source = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/MovieAddonLookup.kt'
checks = '''package tv.orbit.shield
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
fun main() = runBlocking {
    val inFlight = AtomicInteger()
    val maximum = AtomicInteger()
    val found = lookupAddons((0..5).toList()) { n ->
        val count = inFlight.incrementAndGet()
        maximum.updateAndGet { old -> maxOf(old, count) }
        try { delay(if (n == 0) 40 else 10); if (n == 3) error("unavailable"); n * 10 }
        finally { inFlight.decrementAndGet() }
    }
    check(maximum.get() == 2)
    check(found.map { it.getOrNull() } == listOf(0, 10, 20, null, 40, 50))
    check(found[3].isFailure && inFlight.get() == 0)
    val releaseSlow = CompletableDeferred<Unit>()
    val firstVisible = CompletableDeferred<Int>()
    val progressive = async {
        lookupAddons(listOf(0, 1), onResult = { result ->
            if (result.isSuccess) firstVisible.complete(result.getOrThrow())
        }) { n -> if (n == 0) releaseSlow.await(); n }
    }
    check(withTimeout(1000) { firstVisible.await() } == 1)
    check(!progressive.isCompleted)
    releaseSlow.complete(Unit)
    check(progressive.await().map { it.getOrNull() } == listOf(0, 1))
    val job = launch { lookupAddons(listOf(1, 2, 3)) { delay(10_000); it } }
    delay(50); job.cancelAndJoin(); check(job.isCancelled)
    val current = AtomicInteger()
    val maxAcross = AtomicInteger()
    val started = CompletableDeferred<Unit>()
    val callbacks = AtomicInteger()
    val blocked = launch(Dispatchers.IO) {
        lookupAddons(listOf(1, 2), onResult = { callbacks.incrementAndGet() }) {
            val count = current.incrementAndGet(); maxAcross.updateAndGet { old -> maxOf(old, count) }
            started.complete(Unit)
            try { Thread.sleep(200); it } finally { current.decrementAndGet() }
        }
    }
    started.await(); delay(30); blocked.cancel()
    val next = async(Dispatchers.IO) {
        lookupAddons(listOf(3, 4)) {
            val count = current.incrementAndGet(); maxAcross.updateAndGet { old -> maxOf(old, count) }
            try { Thread.sleep(20); it } finally { current.decrementAndGet() }
        }
    }
    next.await(); blocked.join()
    check(maxAcross.get() <= 2 && callbacks.get() == 0)
    println("PASS: progressive results, stable final order, partial failure, cancellation and shared concurrency bound")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-addon-lookup-') as td:
    folder = Path(td)
    (folder / 'Checks.kt').write_text(checks)
    classpath = ':'.join(map(str, (COMPILER[1], COMPILER[4])))
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', str(folder / 'classes'),
                    str(source), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{classpath}', 'tv.orbit.shield.ChecksKt'], check=True)
