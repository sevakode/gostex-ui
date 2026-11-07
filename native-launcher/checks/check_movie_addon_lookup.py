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
    val job = launch { lookupAddons(listOf(1, 2, 3)) { delay(10_000); it } }
    delay(50); job.cancelAndJoin(); check(job.isCancelled)
    println("PASS: at most two requests, stable order, partial failure and cancellation")
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
