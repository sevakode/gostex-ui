"""Exercise production Apps-tab preferences with a typed Android preference boundary.

Android XML persistence, reactive Compose updates and focus are device checks.
"""
from datetime import datetime, timezone
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import ROOT, JAVA, COMPILER as compiler, jar as gradle_jar

SOURCE = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/AppsOptions.kt'
source = SOURCE.read_text()
production = source[source.index('internal data class AppsOptions'):source.index('internal val LocalAppsOptions')]

BOUNDARY = '''package android.content
interface SharedPreferences {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getString(key: String, default: String?): String?
    fun getStringSet(key: String, default: Set<String>?): Set<String>?
    fun edit(): Editor
    interface Editor {
        fun putBoolean(key: String, value: Boolean): Editor
        fun putString(key: String, value: String): Editor
        fun putStringSet(key: String, value: Set<String>): Editor
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
    override fun getString(key: String, default: String?) = (values[key] ?: default) as String?
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, default: Set<String>?) = (values[key] ?: default) as Set<String>?
    override fun edit() = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putString(key: String, value: String): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putStringSet(key: String, value: Set<String>): SharedPreferences.Editor { pending[key] = value.toSet(); return this }
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


    val known = InstallableAppCatalog.packageNames
    check(known == OfficialApkPolicy.sources.keys.toList() && known.size == 5)
    check(AppsPreferences.read(context).catalogOrder == known)
    check(InstallableAppCatalog.visible(AppsPreferences.read(context)) == InstallableAppCatalog.all)
    pass("Catalog metadata matches existing installer sources in their default order")

    known.forEach { AppsPreferences.setCatalogVisible(context, it, false) }
    check(InstallableAppCatalog.visible(AppsPreferences.read(TestContext(prefs))).isEmpty())
    check(AppsPreferences.read(context).tabVisible)
    check(prefs.values.filterKeys { it !in setOf("apps.tabVisible", "apps.catalogHidden") } == original)
    known.forEach { AppsPreferences.setCatalogVisible(context, it, true) }
    check(InstallableAppCatalog.visible(AppsPreferences.read(context)).size == known.size)
    pass("Hiding every catalog card survives fresh readers without changing Home or the installed-app tab")

    prefs.values["apps.catalogHidden"] = setOf("future.hidden", known.first())
    AppsPreferences.setCatalogVisible(context, known.first(), true)
    check(prefs.values["apps.catalogHidden"] == setOf("future.hidden"))
    check(AppsPreferences.read(context).catalogHidden.isEmpty())
    pass("An older catalog preserves hidden IDs belonging to a future version")

    val reverse = known.reversed()
    prefs.values["apps.catalogOrder"] = AppCatalogPreferencesCodec.encodeOrder(listOf("future.order") + reverse + reverse.first())
    check(AppsPreferences.read(TestContext(prefs)).catalogOrder == reverse)
    AppsPreferences.moveCatalog(context, reverse[1], -1)
    val expected = listOf(reverse[1], reverse[0]) + reverse.drop(2)
    check(AppsPreferences.read(TestContext(prefs)).catalogOrder == expected)
    check(AppCatalogPreferencesCodec.decodeOrder(prefs.values.getValue("apps.catalogOrder") as String) == listOf("future.order") + expected)
    pass("Saved order survives restart and a move preserves unknown package slots")

    val beforeInvalid = prefs.values.toMap()
    AppsPreferences.moveCatalog(context, expected.first(), -1)
    AppsPreferences.moveCatalog(context, "missing.package", 1)
    AppsPreferences.moveCatalog(context, expected.first(), 99)
    AppsPreferences.setCatalogVisible(context, "missing.package", false)
    check(prefs.values == beforeInvalid)
    pass("Boundary moves and unknown actions leave stored preferences unchanged")

    val malformedOrders = listOf<Any>("not-json", "[true]", "[[]]", "[\"bad/id\"]", "x".repeat(8193), 7, emptySet<String>())
    for (bad in malformedOrders) {
        prefs.values["apps.catalogOrder"] = bad
        val before = prefs.values.toMap()
        check(AppsPreferences.read(context).catalogOrder == known)
        check(prefs.values == before)
    }
    for (bad in listOf<Any>("bad", setOf(7), setOf("bad/id"), (0..128).map { "future.p$it" }.toSet())) {
        prefs.values["apps.catalogHidden"] = bad
        val before = prefs.values.toMap()
        check(AppsPreferences.read(context).catalogHidden.isEmpty())
        check(prefs.values == before)
    }
    pass("Malformed and oversized values use defaults without rewriting stored preferences")

    prefs.values["apps.catalogOrder"] = AppCatalogPreferencesCodec.encodeOrder(listOf(known.last()))
    check(AppsPreferences.read(context).catalogOrder == listOf(known.last()) + known.dropLast(1))
    prefs.values["apps.catalogHidden"] = setOf(known.last())
    val visible = InstallableAppCatalog.visible(AppsPreferences.read(context)).map { it.packageName }
    check(visible == known.dropLast(1))
    pass("New catalog entries append after the saved selection while hidden entries stay hidden")

    check(AppsPreferences.changesOptions("apps.catalogOrder"))
    check(AppsPreferences.changesOptions("apps.catalogHidden"))
    check(!AppsPreferences.changesOptions("apps.catalogHiddenExtra"))
    pass("The existing listener invalidates both catalog preferences only by exact key")
}
'''


local_json = ROOT / '.tools/native/test-json/json-20240303.jar'
json_jar = (Path(os.environ['PORTABLE_TEST_JSON_JAR']) if os.environ.get('PORTABLE_TEST_JSON_JAR')
            else local_json if local_json.is_file() else gradle_jar('org.json', 'json', '20180813'))
verified_json = {
    '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed': '20240303',
    '518080049ba83181914419d11a25d9bc9833a2d729b6a6e7469fa52851356da8': '20180813',
}
json_digest = hashlib.sha256(json_jar.read_bytes()).hexdigest() if json_jar.is_file() else ''
if json_digest not in verified_json:
    raise SystemExit('Provide a verified org.json 20180813 or 20240303 test jar in PORTABLE_TEST_JSON_JAR')
classpath = ':'.join(map(str, [compiler[1], compiler[-1], json_jar]))
source_dir = SOURCE.parent
catalog = source_dir / 'InstallableAppCatalog.kt'
resource_names = sorted(set(re.findall(r'R\.string\.(\w+)', catalog.read_text())))
resource_stub = 'package tv.orbit.shield\nobject R { object string {\n' + '\n'.join(
    f' const val {name} = {index + 1}' for index, name in enumerate(resource_names)) + '\n} }\n'
production_files = [source_dir / name for name in ['PortableJsonSafety.kt', 'AppCatalogPreferencesCodec.kt', 'InstallableAppCatalog.kt', 'OfficialApkPolicy.kt']]
report = {'timestampUtc': datetime.now(timezone.utc).isoformat(), 'scope': __doc__,
          'jsonVersion': verified_json[json_digest], 'jsonSha256': json_digest,
          'sourceSha256': hashlib.sha256(SOURCE.read_bytes()).hexdigest(), 'passed': False,
          'notCovered': ['Android XML persistence across process restarts', 'Compose listener registration/disposal and live updates', 'TV focus and Back']}
with tempfile.TemporaryDirectory(prefix='gostex-apps-options-') as folder:
    temporary = Path(folder)
    contents = {
        'Boundary.kt': BOUNDARY,
        'AppsOptions.kt': 'package tv.orbit.shield\nimport android.content.Context\nimport android.content.SharedPreferences\n' + production,
        'Resources.kt': resource_stub,
        'AppsChecks.kt': CHECKS,
    }
    for name, value in contents.items(): (temporary / name).write_text(value)
    classes = temporary / 'classes'
    compiled = subprocess.run([str(JAVA), '-cp', ':'.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', str(classes), *[str(temporary / name) for name in contents], *map(str, production_files)], capture_output=True, text=True)
    report['compile'] = {'returncode': compiled.returncode, 'stderr': compiled.stderr}
    if compiled.returncode == 0:
        run = subprocess.run([str(JAVA), '-cp', f'{classes}:{classpath}', 'tv.orbit.shield.AppsChecksKt'], capture_output=True, text=True)
        report['run'] = {'returncode': run.returncode, 'stdout': run.stdout, 'stderr': run.stderr}
        report['checks'] = [line.removeprefix('PASS|') for line in run.stdout.splitlines() if line.startswith('PASS|')]
        report['passed'] = run.returncode == 0 and len(report['checks']) == 13
output = ROOT / '.reports/apps-options-checks.json'
output.parent.mkdir(exist_ok=True)
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({'passed': report['passed'], 'checks': len(report.get('checks', [])), 'report': str(output)}))
if not report['passed']:
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(1)
