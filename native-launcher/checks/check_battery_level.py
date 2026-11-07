"""Check the production battery percentage calculation on the JVM, without Android UI."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / '.tools/native/gradle-cache/caches/modules-2/files-2.1'
JAVA = ROOT / '.tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java'
SOURCE = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/BatteryStatus.kt'

def jar(group, artifact, version):
    return next((CACHE / group / artifact / version).glob('*/*.jar'))

compiler = [jar('org.jetbrains.kotlin', artifact, version) for artifact, version in [
    ('kotlin-compiler-embeddable', '2.4.21'), ('kotlin-stdlib', '2.4.21'),
    ('kotlin-script-runtime', '2.4.21'), ('kotlin-reflect', '1.6.10'),
]] + [jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.9.0'), jar('org.jetbrains', 'annotations', '13.0')]
source = SOURCE.read_text().split('internal fun batteryPercent', 1)[1].split('\n\n', 1)[0]
checks = '''
package tv.orbit.shield
fun main() {
    check(batteryPercent(0, 100) == 0)
    check(batteryPercent(50, 100) == 50)
    check(batteryPercent(100, 100) == 100)
    check(batteryPercent(1, 3) == 33)
    check(batteryPercent(Int.MAX_VALUE, Int.MAX_VALUE) == 100)
    check(batteryPercent(-1, 100) == null)
    check(batteryPercent(1, 0) == null)
    check(batteryPercent(101, 100) == null)
    println("PASS: valid, invalid, scaled and large battery levels")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-battery-') as td:
    folder = Path(td)
    (folder / 'Battery.kt').write_text('package tv.orbit.shield\ninternal fun batteryPercent' + source)
    (folder / 'Checks.kt').write_text(checks)
    cp = str(compiler[1])
    result = subprocess.run([str(JAVA), '-cp', ':'.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(folder / 'classes'),
        str(folder / 'Battery.kt'), str(folder / 'Checks.kt')], capture_output=True, text=True)
    if result.returncode:
        raise SystemExit(result.stderr)
    result = subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{cp}', 'tv.orbit.shield.ChecksKt'], capture_output=True, text=True)
    print(result.stdout, end='')
    if result.returncode:
        raise SystemExit(result.stderr)
