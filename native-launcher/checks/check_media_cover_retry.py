"""Exercise the foreground cover retry policy without network or Android."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import JAVA, COMPILER, ROOT

source = (ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/MediaCoverRetry.kt').read_text()
checks = '''
fun main() {
    val gate = MediaCoverRetry(60_000)
    check(gate.begin("movie", "poster-a", 100))
    check(!gate.begin("movie", "poster-a", 101))
    gate.finish("movie", "poster-a", 200, true)
    check(!gate.begin("movie", "poster-a", 60_199))
    check(gate.begin("movie", "poster-b", 201))
    gate.finish("movie", "poster-b", 202, false)
    check(gate.begin("movie", "poster-a", 60_200))
    gate.finish("movie", "poster-a", 60_201, false)
    check(gate.begin("movie", "poster-a", 60_202))
    gate.finish("movie", "poster-a", 60_203, false)
    repeat(300) { check(gate.begin("item$it", "poster", 0)); gate.finish("item$it", "poster", 0, true) }
    check(gate.begin("item0", "poster", 1)) // old failures are bounded
    println("PASS: duplicate suppression, retry delay, changed poster, cancellation and bounded failures")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-media-cover-') as td:
    d = Path(td)
    (d / 'MediaCoverRetry.kt').write_text(source)
    (d / 'Checks.kt').write_text('package tv.orbit.shield\n' + checks)
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', str(COMPILER[1]), '-d', str(d / 'classes'), str(d / 'MediaCoverRetry.kt'), str(d / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', f'{d / "classes"}:{COMPILER[1]}', 'tv.orbit.shield.ChecksKt'], check=True)
