"""Exercise the production bounded retry policy."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / '.tools/native/gradle-cache/caches/modules-2/files-2.1'
JAVA = ROOT / '.tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java'
def jar(group, artifact, version):
    return next((CACHE / group / artifact / version).glob('*/*.jar'))
compiler = [jar('org.jetbrains.kotlin', a, v) for a, v in [
    ('kotlin-compiler-embeddable', '2.4.21'), ('kotlin-stdlib', '2.4.21'),
    ('kotlin-script-runtime', '2.4.21'), ('kotlin-reflect', '1.6.10'),
]] + [jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.9.0'), jar('org.jetbrains', 'annotations', '13.0')]
checks = '''package tv.orbit.shield
fun main() {
  var time = 0L; var rate = 512; var sleeps = 0
  val limiter = TorrDownloadThrottle({ rate }, { time }, { ms -> sleeps++; time += ms * 1_000_000L })
  check(limiter.beforeWrite(65536) { false }); check(time in 124_000_000L..125_000_000L)
  check(limiter.beforeWrite(65536) { false }); check(time in 249_000_000L..250_000_000L)
  rate = 1024; check(limiter.beforeWrite(65536) { false }); check(time in 310_000_000L..313_000_000L)
  rate = 0; val before = time; check(limiter.beforeWrite(65536) { false }); check(time == before)
  rate = 512; check(!limiter.beforeWrite(65536) { true }); check(time == before)
  var changing = 512; var calls = 0; var other = 0L
  val flexible = TorrDownloadThrottle({ changing }, { other }, { ms -> other += ms*1_000_000L; if (++calls == 1) changing = 0 })
  check(flexible.beforeWrite(65536) { false }); check(other == 100_000_000L)
  var tinyTime = 0L; var tinySleeps = 0
  val tiny = TorrDownloadThrottle({ 512 }, { tinyTime }, { ms -> tinySleeps++; tinyTime += ms * 1_000_000L })
  repeat(4096) { check(tiny.beforeWrite(1) { false }) }
  check(tinyTime in 6_000_000L..9_000_000L && tinySleeps < 20)
  check(sleeps > 0); println("PASS: rate, no limit, change while waiting, and cancellation")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-throttle-test-') as td:
    folder = Path(td); (folder / 'Checks.kt').write_text(checks)
    cp = str(compiler[1]); src = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/TorrDownloadThrottle.kt'
    result = subprocess.run([str(JAVA), '-cp', ':'.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(folder / 'classes'), str(src), str(folder / 'Checks.kt')], capture_output=True, text=True)
    if result.returncode: raise SystemExit(result.stderr)
    result = subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{cp}', 'tv.orbit.shield.ChecksKt'], capture_output=True, text=True)
    print(result.stdout, end='')
    if result.returncode: raise SystemExit(result.stderr)
