"""Exercise the real screensaver cursor with a fake Android preference boundary."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / '.tools/native/gradle-cache/caches/modules-2/files-2.1'
JAVA = ROOT / '.tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java'
SOURCE = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/ArtProgramCursor.kt'

def jar(group, artifact, version):
    return next((CACHE / group / artifact / version).glob('*/*.jar'))

compiler = [jar('org.jetbrains.kotlin', a, v) for a, v in [
    ('kotlin-compiler-embeddable', '2.4.21'), ('kotlin-stdlib', '2.4.21'),
    ('kotlin-script-runtime', '2.4.21'), ('kotlin-reflect', '1.6.10'),
]] + [jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.9.0'), jar('org.jetbrains', 'annotations', '13.0')]
stub = '''package android.content
interface SharedPreferences {
    fun getLong(key: String, defaultValue: Long): Long
    fun edit(): Editor
    interface Editor { fun putLong(key: String, value: Long): Editor; fun apply() }
}
'''
checks = '''package tv.orbit.shield
import android.content.SharedPreferences
class FakePrefs : SharedPreferences {
    var value: Any? = null
    var writes = 0
    override fun getLong(key: String, defaultValue: Long): Long = when (val v = value) {
        null -> defaultValue
        is Long -> v
        else -> throw ClassCastException()
    }
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { this@FakePrefs.value = value; writes++; return this }
        override fun apply() {}
    }
}
fun main() {
    val p = FakePrefs(); val first = ArtProgramCursor(p)
    val start = first.nextScene(); check(start in 0..19); check(p.writes == 0)
    first.shown(start); check(p.writes == 1)
    val restarted = ArtProgramCursor(p); check(restarted.nextScene() == start + 1)
    restarted.shown(start + 1); check(ArtProgramCursor(p).nextScene() == start + 2)
    first.shown(start); check(ArtProgramCursor(p).nextScene() == start + 2)
    p.value = "bad"; check(ArtProgramCursor(p).nextScene() in 0..19)
    p.value = -5L; check(ArtProgramCursor(p).nextScene() in 0..19)
    p.value = Long.MAX_VALUE; check(ArtProgramCursor(p).nextScene() in 0..19)
    p.value = 1_000_000L; ArtProgramCursor(p).shown(1_000_000L); check(ArtProgramCursor(p).nextScene() == 0L)
    println("PASS: resume, bounded fallback, stale completion and wrap")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-art-resume-') as td:
    folder = Path(td)
    (folder / 'Prefs.kt').write_text(stub)
    (folder / 'Checks.kt').write_text(checks)
    cp = str(compiler[1])
    result = subprocess.run([str(JAVA), '-cp', ':'.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(folder / 'classes'),
        str(SOURCE), str(folder / 'Prefs.kt'), str(folder / 'Checks.kt')], capture_output=True, text=True)
    if result.returncode:
        raise SystemExit(result.stderr)
    result = subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{cp}', 'tv.orbit.shield.ChecksKt'], capture_output=True, text=True)
    print(result.stdout, end='')
    if result.returncode:
        raise SystemExit(result.stderr)
