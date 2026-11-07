"""Exercise the production identity policy for interrupted SAF renames."""
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
  check(torrCanRecoverRenamed("id-1", "id-1", "game.bin", "game.bin", false))
  check(!torrCanRecoverRenamed("id-1", "id-2", "game.bin", "game.bin", false))
  check(!torrCanRecoverRenamed("id-1", "id-1", "game.bin", "GAME.BIN", false))
  check(!torrCanRecoverRenamed("id-1", "id-1", "game.bin", "game.bin", true))
  check(!torrCanRecoverRenamed("", "", "game.bin", "game.bin", false))
  println("PASS: rename recovery requires the same nonempty document ID, exact name and absent partial")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-recovery-test-') as td:
    folder = Path(td); (folder / 'Checks.kt').write_text(checks)
    cp = str(compiler[1]); src = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/TorrFinalizeRecovery.kt'
    result = subprocess.run([str(JAVA), '-cp', ':'.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(folder / 'classes'), str(src), str(folder / 'Checks.kt')], capture_output=True, text=True)
    if result.returncode: raise SystemExit(result.stderr)
    result = subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{cp}', 'tv.orbit.shield.ChecksKt'], capture_output=True, text=True)
    print(result.stdout, end='')
    if result.returncode: raise SystemExit(result.stderr)
