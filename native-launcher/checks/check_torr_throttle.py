"""Exercise the production write throttle with a deterministic clock."""
from pathlib import Path
import subprocess
import tempfile

from kotlin_toolchain import COMPILER, JAVA, ROOT

checks = '''package tv.orbit.shield

private fun verifySourcePacing() {
  var time = 0L
  val limiter = TorrDownloadThrottle({ 1024 }, { time }, { ms -> time += ms * 1_000_000L })
  fun admit() { check(limiter.beforeWrite(65536) { false }) }

  admit()
  check(time in 61_500_000L..62_500_000L)
  repeat(8) {
    time += 80_000_000L
    val arrival = time
    admit()
    check(time == arrival) { "Slow source paid again: ${time - arrival}ns after arrival" }
  }

  time += 30_000_000L
  val arrival = time
  admit()
  check(time - arrival in 31_500_000L..32_500_000L) { "Partial source delay was not credited" }

  time += 10_000_000_000L
  val idleEnd = time
  admit()
  check(time == idleEnd) { "First chunk after idle should pass immediately" }
  admit()
  check(time - idleEnd in 61_500_000L..62_500_000L) { "Idle time banked a burst" }
}

private fun verifySleepOvershoot() {
  var time = 0L
  var overshoot = 30_000_000L
  val limiter = TorrDownloadThrottle({ 1024 }, { time }, { ms ->
    time += ms * 1_000_000L + overshoot
    overshoot = 0L
  })
  check(limiter.beforeWrite(65536) { false })
  val firstAdmission = time
  check(limiter.beforeWrite(65536) { false })
  check(time - firstAdmission in 61_500_000L..62_500_000L) { "Late sleep banked a burst" }
}

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
  check(sleeps > 0)
  verifySourcePacing()
  verifySleepOvershoot()
  println("PASS: rate cap, slow and partial source delay, no burst after idle or oversleep, live rate changes and cancellation")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-throttle-test-') as td:
    folder = Path(td); (folder / 'Checks.kt').write_text(checks)
    cp = str(COMPILER[1]); src = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/TorrDownloadThrottle.kt'
    result = subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(folder / 'classes'), str(src), str(folder / 'Checks.kt')], capture_output=True, text=True)
    if result.returncode: raise SystemExit(result.stderr)
    result = subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{cp}', 'tv.orbit.shield.ChecksKt'], capture_output=True, text=True)
    print(result.stdout, end='')
    if result.returncode: raise SystemExit(result.stderr)
