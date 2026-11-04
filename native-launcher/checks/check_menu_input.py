"""Exercise the production guard against releasing the gesture that opened a tile menu.

The installed TV Material 1.1.0 Surface clicks on confirm UP without requiring a DOWN;
this gate prevents the newly focused first menu action from launching unintentionally.
Android delivery and Compose window focus still require separate device checks.
"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/MenuInput.kt'
CACHE = ROOT / '.tools/native/gradle-cache/caches/modules-2/files-2.1'
JAVA = ROOT / '.tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java'

BOUNDARY = '''package android.view
class KeyEvent {
    companion object {
        const val ACTION_DOWN = 0; const val ACTION_UP = 1; const val ACTION_MULTIPLE = 2
        const val KEYCODE_DPAD_CENTER = 23; const val KEYCODE_ENTER = 66
        const val KEYCODE_NUMPAD_ENTER = 160; const val KEYCODE_BUTTON_A = 96
    }
}
'''
CHECKS = '''package tv.orbit.shield
fun main() {
    var checks = 0
    fun pass(name: String) { println("PASS|$name"); checks++ }
    val confirmKeys = listOf(23, 66, 160, 96)
    for (key in confirmKeys) {
        val gate = MenuConfirmKeyGate()
        for (repeat in 1..8) check(gate.consume(key, 0, repeat))
        check(gate.consume(key, 1, 0))
        check(gate.consume(key, 1, 0))
    }
    pass("Opening hold repeats and release cannot activate the first menu action")
    for (key in confirmKeys) {
        val gate = MenuConfirmKeyGate()
        repeat(3) {
            check(!gate.consume(key, 0, 0))
            check(!gate.consume(key, 1, 0))
            check(gate.consume(key, 1, 0))
        }
    }
    pass("Fresh deliberate confirm presses activate once and allow subsequent selections")
    for (key in confirmKeys) {
        val gate = MenuConfirmKeyGate()
        check(!gate.consume(key, 0, 0))
        check(gate.consume(key, 1, 0, canceled = true))
        check(gate.consume(key, 1, 0))
        check(!gate.consume(key, 0, 0))
        check(gate.consume(key, 0, 1, canceled = true))
        check(gate.consume(key, 1, 0))
    }
    pass("Canceled down or up cannot leave an armed confirm gesture")
    val translated = MenuConfirmKeyGate()
    check(!translated.consume(96, 0, 0))
    check(!translated.consume(23, 0, 0))
    check(!translated.consume(96, 1, 0))
    check(!translated.consume(23, 1, 0))
    check(translated.consume(96, 1, 0) && translated.consume(23, 1, 0))
    val mixed = MenuConfirmKeyGate()
    check(!mixed.consume(23, 0, 0))
    check(mixed.consume(66, 1, 0))
    check(!mixed.consume(23, 1, 0))
    pass("Android A-to-center fallback and different confirm keys retain separate down/up pairs")
    val other = MenuConfirmKeyGate()
    for (key in 0..304) if (key !in confirmKeys) {
        for (action in 0..2) {
            check(!other.consume(key, action, 0))
            check(!other.consume(key, action, 1, canceled = true))
        }
    }
    pass("D-pad navigation, Back, and shoulder events pass through unchanged")
    println("TOTAL|$checks")
}
'''


def jar(group, artifact, version):
    return next((CACHE / group / artifact / version).glob('*/*.jar'))


compiler = [jar('org.jetbrains.kotlin', artifact, version) for artifact, version in [
    ('kotlin-compiler-embeddable', '2.4.21'), ('kotlin-stdlib', '2.4.21'),
    ('kotlin-script-runtime', '2.4.21'), ('kotlin-reflect', '1.6.10'),
]] + [jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.9.0'), jar('org.jetbrains', 'annotations', '13.0')]
classpath = str(compiler[1])
report = {'timestampUtc': datetime.now(timezone.utc).isoformat(),
          'sourceSha256': hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
          'notCovered': ['Compose window event delivery and focus', 'Physical gamepad mapping'], 'passed': False}
with tempfile.TemporaryDirectory(prefix='gostex-menu-input-') as folder:
    temporary = Path(folder)
    (temporary / 'Boundary.kt').write_text(BOUNDARY)
    (temporary / 'MenuInputChecks.kt').write_text(CHECKS)
    classes = temporary / 'classes'
    compiled = subprocess.run([str(JAVA), '-cp', ':'.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                               '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', str(classes),
                               str(SOURCE), str(temporary / 'Boundary.kt'), str(temporary / 'MenuInputChecks.kt')],
                              capture_output=True, text=True)
    report['compile'] = {'returncode': compiled.returncode, 'stderr': compiled.stderr}
    if compiled.returncode == 0:
        run = subprocess.run([str(JAVA), '-cp', f'{classes}:{classpath}', 'tv.orbit.shield.MenuInputChecksKt'],
                             capture_output=True, text=True)
        report['run'] = {'returncode': run.returncode, 'stdout': run.stdout, 'stderr': run.stderr}
        report['checks'] = [line.removeprefix('PASS|') for line in run.stdout.splitlines() if line.startswith('PASS|')]
        report['passed'] = run.returncode == 0 and len(report['checks']) == 5
output = ROOT / '.reports/menu-input-checks.json'
output.parent.mkdir(exist_ok=True)
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({'passed': report['passed'], 'checks': len(report.get('checks', [])), 'report': str(output)}))
if not report['passed']:
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(1)
