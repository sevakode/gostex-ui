"""Exercise the production bounded retry policy."""
import os
from pathlib import Path
import subprocess
import tempfile

from kotlin_toolchain import COMPILER, JAVA, ROOT

checks = '''package tv.orbit.shield
fun main() {
  for (reason in listOf("source_timeout", "source_connect", "source_io", "source_incomplete")) {
    val retry = TorrDownloadRetry(100)
    check(retry.delayAfterFailure(reason, 100, enabled = false) == null)
    check(retry.delayAfterFailure(reason, 100, enabled = true) == 2000L)
    check(retry.delayAfterFailure(reason, 100, enabled = true) == 5000L)
    check(retry.delayAfterFailure(reason, 100, enabled = true) == null)
    check(retry.delayAfterFailure(reason, 99, enabled = true) == null)
  }
  val recovered = TorrDownloadRetry(initialOffset = 100)
  for (offset in listOf(200L, 300L, 400L, 500L)) {
    check(recovered.delayAfterFailure("source_timeout", offset, enabled = true) == 2000L)
    check(recovered.delayAfterFailure("source_incomplete", offset, enabled = true) == 5000L)
  }
  check(recovered.delayAfterFailure("source_timeout", 500, enabled = true) == null)
  for (reason in listOf("source_changed", "source_response", "source_auth", "storage", "space", "finalize"))
    check(recovered.delayAfterFailure(reason, 600, enabled = true) == null)
  println("PASS: durable progress renews retry budget; stalls, disabled retries and permanent errors stay bounded")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-recovery-test-') as td:
    folder = Path(td); (folder / 'Checks.kt').write_text(checks)
    cp = str(COMPILER[1]); src = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/TorrDownloadRetry.kt'
    result = subprocess.run([str(JAVA), '-cp', os.pathsep.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(folder / 'classes'), str(src), str(folder / 'Checks.kt')], capture_output=True, text=True)
    if result.returncode: raise SystemExit(result.stderr)
    result = subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{cp}', 'tv.orbit.shield.ChecksKt'], capture_output=True, text=True)
    print(result.stdout, end='')
    if result.returncode: raise SystemExit(result.stderr)
