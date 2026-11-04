"""Exercise production Home shoulder handling against press/repeat/release sequences.

Android's KeyEvent is the only host boundary. Compose focus and modal scoping require
the separate device checks; this verifies that one physical press advances once.
"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/HomeInput.kt'
CACHE = ROOT / '.tools/native/gradle-cache/caches/modules-2/files-2.1'
JAVA = ROOT / '.tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java'

BOUNDARY = '''package android.view
class KeyEvent(val action: Int, val keyCode: Int, val repeatCount: Int = 0, val isCanceled: Boolean = false) {
    companion object {
        const val ACTION_DOWN = 0; const val ACTION_UP = 1; const val ACTION_MULTIPLE = 2
        const val KEYCODE_BUTTON_L1 = 102; const val KEYCODE_BUTTON_R1 = 103
    }
}
'''
CHECKS = '''package tv.orbit.shield
import android.view.KeyEvent
fun main() {
    var checks = 0
    fun pass(name: String) { println("PASS|$name"); checks++ }
    for ((key, delta) in listOf(KeyEvent.KEYCODE_BUTTON_L1 to -1, KeyEvent.KEYCODE_BUTTON_R1 to 1)) {
        val sequence = listOf(KeyEvent(0, key), KeyEvent(0, key, 1), KeyEvent(0, key, 2), KeyEvent(1, key))
        check(sequence.all(::isHomeShelfKey))
        check(sequence.mapNotNull(::homeShelfStep) == listOf(delta))
        check(homeShelfStep(KeyEvent(0, key)) == delta) // releasing permits the next press
    }
    pass("Each shoulder hold advances once; repeats and release stay consumable without movement")
    for (key in 0..304) {
        if (key == 102 || key == 103) continue
        for (action in 0..2) {
            val event = KeyEvent(action, key)
            check(!isHomeShelfKey(event) && homeShelfStep(event) == null)
        }
    }
    pass("D-pad, confirm, Back, triggers and unrelated keys are never captured as shoulders")
    for (key in listOf(102, 103)) {
        check(homeShelfStep(KeyEvent(0, key, isCanceled = true)) == null)
        check(homeShelfStep(KeyEvent(1, key, isCanceled = true)) == null)
        check(homeShelfStep(KeyEvent(2, key)) == null)
    }
    pass("Canceled and multiple-action events cannot advance shelves")
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
          'notCovered': ['Compose focus', 'Modal panel input scope', 'Physical device mapping'], 'passed': False}
with tempfile.TemporaryDirectory(prefix='gostex-home-input-') as folder:
    temporary = Path(folder)
    (temporary / 'Boundary.kt').write_text(BOUNDARY)
    (temporary / 'HomeInputChecks.kt').write_text(CHECKS)
    classes = temporary / 'classes'
    compiled = subprocess.run([str(JAVA), '-cp', ':'.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                               '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', str(classes),
                               str(SOURCE), str(temporary / 'Boundary.kt'), str(temporary / 'HomeInputChecks.kt')],
                              capture_output=True, text=True)
    report['compile'] = {'returncode': compiled.returncode, 'stderr': compiled.stderr}
    if compiled.returncode == 0:
        run = subprocess.run([str(JAVA), '-cp', f'{classes}:{classpath}', 'tv.orbit.shield.HomeInputChecksKt'],
                             capture_output=True, text=True)
        report['run'] = {'returncode': run.returncode, 'stdout': run.stdout, 'stderr': run.stderr}
        report['checks'] = [line.removeprefix('PASS|') for line in run.stdout.splitlines() if line.startswith('PASS|')]
        report['passed'] = run.returncode == 0 and len(report['checks']) == 3
output = ROOT / '.reports/home-input-checks.json'
output.parent.mkdir(exist_ok=True)
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({'passed': report['passed'], 'checks': len(report.get('checks', [])), 'report': str(output)}))
if not report['passed']:
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(1)
