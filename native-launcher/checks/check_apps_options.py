"""Exercise production Apps-tab preferences with a typed Android preference boundary.

Android XML persistence, reactive Compose updates and focus are device checks.
"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/AppsOptions.kt'
CACHE = ROOT / '.tools/native/gradle-cache/caches/modules-2/files-2.1'
JAVA = ROOT / '.tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java'
source = SOURCE.read_text()
production = source[source.index('internal data class AppsOptions'):source.index('internal val LocalAppsOptions')]

BOUNDARY = '''package android.content
interface SharedPreferences {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun edit(): Editor
    interface Editor {
        fun putBoolean(key: String, value: Boolean): Editor
        fun apply()
    }
}
abstract class Context {
    companion object { const val MODE_PRIVATE = 0 }
    abstract fun getSharedPreferences(name: String, mode: Int): SharedPreferences
}
'''
CHECKS = r'''package tv.orbit.shield
import android.content.Context
import android.content.SharedPreferences
private class TypedPreferences(val values: MutableMap<String, Any?> = mutableMapOf()) : SharedPreferences {
    override fun getBoolean(key: String, default: Boolean) = (values[key] ?: default) as Boolean
    override fun edit() = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
        override fun apply() { values.putAll(pending) }
    }
}
private class TestContext(val prefs: TypedPreferences) : Context() {
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        check(name == "orbit" && mode == Context.MODE_PRIVATE); return prefs
    }
}
fun main() {
    fun pass(name: String) = println("PASS|$name")
    val original = mapOf<String, Any?>(
        "home.sections.v1" to "owner's row layout",
        "library.tabVisible" to false,
        "grafana.rotationMode" to "favorites",
        "favorites" to setOf("app:example.player")
    )
    val prefs = TypedPreferences(original.toMutableMap())
    val context = TestContext(prefs)
    check(AppsOptions() == AppsOptions(false))
    check(AppsPreferences.read(context) == AppsOptions(false))
    check(prefs.values == original)
    pass("An existing installation keeps its Home layout without automatically adding a tab or writing preferences")

    for (visible in listOf(true, false, true)) {
        AppsPreferences.setTabVisible(context, visible)
        check(prefs.values["apps.tabVisible"] == visible)
        check(AppsPreferences.read(TestContext(prefs)).tabVisible == visible)
        check(prefs.values.filterKeys { it != "apps.tabVisible" } == original)
    }
    pass("Both explicit choices survive fresh readers while preserving library visibility, Home sections, favorites and Grafana")

    for (bad in listOf<Any?>(null, "true", "false", "legacy", 0, 1, 1L, emptySet<String>())) {
        prefs.values["apps.tabVisible"] = bad
        val before = prefs.values.toMap()
        check(!AppsPreferences.read(context).tabVisible)
        check(prefs.values == before)
    }
    pass("Wrong saved types use the safe hidden default without coercing or silently repairing the owner's preferences")

    AppsPreferences.setTabVisible(context, true)
    check(AppsPreferences.read(context).tabVisible)
    check(prefs.values.filterKeys { it != "apps.tabVisible" } == original)
    pass("An explicit choice replaces a malformed tab value without resetting unrelated settings")

    check(AppsPreferences.changesOptions(null))
    check(AppsPreferences.changesOptions("apps.tabVisible"))
    for (unrelated in listOf("", "library.tabVisible", "apps.tabVisibleExtra", "apps", "home.sections.v1", "grafana.mode")) {
        check(!AppsPreferences.changesOptions(unrelated))
    }
    pass("The reactive reader invalidates only for its exact key or a full preferences clear")
}
'''


def jar(group, artifact, version):
    return next((CACHE / group / artifact / version).glob('*/*.jar'))


compiler = [jar('org.jetbrains.kotlin', artifact, version) for artifact, version in [
    ('kotlin-compiler-embeddable', '2.4.21'), ('kotlin-stdlib', '2.4.21'),
    ('kotlin-script-runtime', '2.4.21'), ('kotlin-reflect', '1.6.10'),
]] + [jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.9.0'), jar('org.jetbrains', 'annotations', '13.0')]
classpath = ':'.join(map(str, [compiler[1], compiler[-1]]))
report = {'timestampUtc': datetime.now(timezone.utc).isoformat(), 'scope': __doc__,
          'sourceSha256': hashlib.sha256(SOURCE.read_bytes()).hexdigest(), 'passed': False,
          'notCovered': ['Android XML persistence across process restarts', 'Compose listener registration/disposal and live updates', 'TV focus and Back']}
with tempfile.TemporaryDirectory(prefix='gostex-apps-options-') as folder:
    temporary = Path(folder)
    contents = {
        'Boundary.kt': BOUNDARY,
        'AppsOptions.kt': 'package tv.orbit.shield\nimport android.content.Context\n' + production,
        'AppsChecks.kt': CHECKS,
    }
    for name, value in contents.items(): (temporary / name).write_text(value)
    classes = temporary / 'classes'
    compiled = subprocess.run([str(JAVA), '-cp', ':'.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', str(classes), *[str(temporary / name) for name in contents]], capture_output=True, text=True)
    report['compile'] = {'returncode': compiled.returncode, 'stderr': compiled.stderr}
    if compiled.returncode == 0:
        run = subprocess.run([str(JAVA), '-cp', f'{classes}:{classpath}', 'tv.orbit.shield.AppsChecksKt'], capture_output=True, text=True)
        report['run'] = {'returncode': run.returncode, 'stdout': run.stdout, 'stderr': run.stderr}
        report['checks'] = [line.removeprefix('PASS|') for line in run.stdout.splitlines() if line.startswith('PASS|')]
        report['passed'] = run.returncode == 0 and len(report['checks']) == 5
output = ROOT / '.reports/apps-options-checks.json'
output.parent.mkdir(exist_ok=True)
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({'passed': report['passed'], 'checks': len(report.get('checks', [])), 'report': str(output)}))
if not report['passed']:
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(1)
