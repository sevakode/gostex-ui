"""Exercise production Grafana URL/persistence code against a typed Android preference boundary.

UI focus, WebView authentication and Android lifecycle remain device checks.
"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/GrafanaOptions.kt'
CACHE = ROOT / '.tools/native/gradle-cache/caches/modules-2/files-2.1'
JAVA = ROOT / '.tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java'
source = SOURCE.read_text()
production = source[source.index('internal enum class ScreensaverMode'):source.index('/** The drawer owns')]
ids = [item.attrib['name'] for item in ET.parse(ROOT / 'native-launcher/app/src/main/res/values/grafana.xml').findall('string')]
BOUNDARY = '''package android.content
interface SharedPreferences {
    fun getString(key: String, default: String?): String?
    fun getInt(key: String, default: Int): Int
    fun edit(): Editor
    interface Editor {
        fun putString(key: String, value: String): Editor
        fun putInt(key: String, value: Int): Editor
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
    override fun getString(key: String, default: String?) = (values[key] ?: default) as String?
    override fun getInt(key: String, default: Int) = (values[key] ?: default) as Int
    override fun edit() = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        override fun putString(key: String, value: String): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { pending[key] = value; return this }
        override fun apply() { values.putAll(pending) }
    }
}
private class TestContext(val prefs: TypedPreferences) : Context() {
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        check(name == "orbit" && mode == Context.MODE_PRIVATE); return prefs
    }
}
fun main() {
    var count = 0
    fun pass(name: String) { println("PASS|$name"); count++ }
    val original = mapOf<String, Any?>("art.program" to "mixed", "home.sections.v1" to "favorite order", "appearance.tileSize" to "balanced")
    val prefs = TypedPreferences(original.toMutableMap()); val context = TestContext(prefs)
    check(GrafanaPreferences.read(context) == GrafanaOptions(ScreensaverMode.ART, "", 0))
    check(prefs.values == original)
    pass("Existing artwork remains selected; no URL or reload is invented and reads never migrate preferences")

    val urls = listOf("https://grafana.example/d/uid/server?orgId=1&kiosk&var-region=South%20America&refresh=10s",
        "http://192.168.1.4:3000/playlists/play/uid?kiosk", "http://localhost:3000/", "https://[2001:db8::1]:8443/d/example", "HTTPS://grafana.example/d/uid#panel")
    for (url in urls) {
        check(GrafanaPreferences.normalizeUrl(" $url ") == url)
        check(GrafanaPreferences.setUrl(context, url))
        check(GrafanaPreferences.read(TestContext(prefs)).url == url)
        check(GrafanaPreferences.read(context).mode == ScreensaverMode.ART)
    }
    pass("Dashboard and playlist links retain query, kiosk, fragments, local addresses and encoded variables without changing mode")

    val beforeInvalid = prefs.values.toMap()
    val invalid = listOf("file:///sdcard/private", "javascript:alert(1)", "intent://grafana", "data:text/html,hi", "//grafana.example/d/one", "/d/one",
        "https://user:password@grafana.example", "https://user%40email@grafana.example", "https://grafana.example@another.example",
        "http://", "https://#fragment", "https://grafana.example:0/", "https://grafana.example:65536/", "https://grafana.example:-2/",
        "https://grafana.example/a b", "https://grafana.example/\\other", "https://grafana.example/%", "https://grafana.example/\n", "https://grafana.example/\u0000",
        "https://grafana.example/" + "a".repeat(GrafanaPreferences.MAX_URL_LENGTH))
    for (url in invalid) {
        check(GrafanaPreferences.normalizeUrl(url) == null) { "Invalid URL accepted" }
        check(!GrafanaPreferences.setUrl(context, url))
        check(prefs.values == beforeInvalid)
    }
    pass("Non-web, malformed, credential-bearing, control-containing and oversized URLs cannot replace a saved address")

    GrafanaPreferences.setMode(context, ScreensaverMode.GRAFANA)
    check(GrafanaPreferences.setUrl(context, "   "))
    check(GrafanaPreferences.read(context).url.isEmpty())
    check(GrafanaPreferences.read(context).mode == ScreensaverMode.GRAFANA)
    check(prefs.values.filterKeys { !it.startsWith(GrafanaPreferences.KEY_PREFIX) } == original)
    pass("Clearing the address removes only its content and preserves the selected mode and all unrelated settings")

    for (bad in listOf(true, 1, "unknown", "", emptySet<String>())) {
        prefs.values["grafana.mode"] = bad
        prefs.values["grafana.url"] = bad
        prefs.values["grafana.refreshSeconds"] = bad
        prefs.values["grafana.scrollMode"] = bad
        val before = prefs.values.toMap()
        check(GrafanaPreferences.read(context) == GrafanaOptions(ScreensaverMode.ART, "", 0))
        check(prefs.values == before)
    }
    prefs.values["grafana.url"] = "javascript:alert(1)"
    prefs.values["grafana.refreshSeconds"] = -1
    check(GrafanaPreferences.read(context).url.isEmpty() && GrafanaPreferences.read(context).refreshSeconds == 0)
    pass("Stale types, unknown modes, invalid saved links and unsupported reload intervals use safe defaults without destructive repairs")

    for (mode in ScreensaverMode.entries) {
        GrafanaPreferences.setMode(context, mode)
        check(GrafanaPreferences.setUrl(context, urls.first()))
        for (seconds in GrafanaPreferences.refreshChoices) {
            GrafanaPreferences.setRefreshSeconds(context, seconds)
            check(GrafanaPreferences.read(TestContext(prefs)) == GrafanaOptions(mode, urls.first(), seconds))
            check(prefs.values.filterKeys { !it.startsWith(GrafanaPreferences.KEY_PREFIX) } == original)
        }
    }
    pass("Every mode and reload choice persists between readers without resetting artwork, favorite order or card size")

    val beforeUnsupported = prefs.values.toMap()
    for (seconds in listOf(-1, 1, 59, 61, 301, Int.MAX_VALUE)) GrafanaPreferences.setRefreshSeconds(context, seconds)
    check(prefs.values == beforeUnsupported)
    pass("Invalid reload writes leave an existing valid choice intact")
    for (scroll in GrafanaScrollMode.entries) {
        GrafanaPreferences.setScrollMode(context, scroll)
        check(GrafanaPreferences.read(TestContext(prefs)).scrollMode == scroll)
        check(prefs.values.filterKeys { !it.startsWith(GrafanaPreferences.KEY_PREFIX) } == original)
    }
    pass("All scrolling choices survive a fresh preferences reader without changing unrelated settings")
    for (bad in listOf(true, 1, "legacy", "", emptySet<String>())) {
        prefs.values["grafana.scrollMode"] = bad
        val before = prefs.values.toMap()
        check(GrafanaPreferences.read(context).scrollMode == GrafanaScrollMode.OFF)
        check(prefs.values == before)
    }
    pass("Missing, stale and unknown scrolling choices stay off without overwriting the stored value")

    val legacyValues = original + mapOf<String, Any?>(
        "grafana.mode" to "grafana", "grafana.url" to urls.first(), "grafana.scrollMode" to "slow",
        "grafana.refreshSeconds" to 300, "grafana.catalog.saved-server.favorites" to setOf("finance", "payments"),
        "grafana.catalog.saved-server.favoritesOnly" to true
    )
    val upgradedPrefs = TypedPreferences(legacyValues.toMutableMap())
    val upgradedContext = TestContext(upgradedPrefs)
    val legacyOptions = GrafanaPreferences.read(upgradedContext)
    check(legacyOptions.rotationMode == GrafanaRotationMode.SINGLE && legacyOptions.rotationSeconds == 120)
    check(legacyOptions.mode == ScreensaverMode.GRAFANA && legacyOptions.url == urls.first())
    check(legacyOptions.scrollMode == GrafanaScrollMode.SLOW && legacyOptions.refreshSeconds == 300)
    check(upgradedPrefs.values == legacyValues)
    pass("Existing dashboard, scrolling, reload and scoped favorites survive an upgrade without automatically enabling rotation")

    val rotationKeys = setOf("grafana.rotationMode", "grafana.rotationSeconds")
    for (rotation in GrafanaRotationMode.entries) {
        GrafanaPreferences.setRotationMode(upgradedContext, rotation)
        for (seconds in GrafanaPreferences.rotationChoices) {
            GrafanaPreferences.setRotationSeconds(upgradedContext, seconds)
            val fresh = GrafanaPreferences.read(TestContext(upgradedPrefs))
            check(fresh == legacyOptions.copy(rotationMode = rotation, rotationSeconds = seconds))
            check(upgradedPrefs.values.filterKeys { it !in rotationKeys } == legacyValues)
        }
    }
    pass("Every rotation mode and interval survives a new reader without changing the selected link, stars or scrolling")

    for (bad in listOf<Any?>(null, true, 1, "unknown", "", emptySet<String>())) {
        upgradedPrefs.values["grafana.rotationMode"] = bad
        val before = upgradedPrefs.values.toMap()
        val read = GrafanaPreferences.read(upgradedContext)
        check(read.rotationMode == GrafanaRotationMode.SINGLE)
        check(read.rotationSeconds == 300 && read.url == legacyOptions.url)
        check(upgradedPrefs.values == before)
    }
    GrafanaPreferences.setRotationMode(upgradedContext, GrafanaRotationMode.FAVORITES)
    for (bad in listOf<Any?>(null, true, "120", "old", emptySet<String>(), -1, 0, 1, 31, 240, Int.MAX_VALUE)) {
        upgradedPrefs.values["grafana.rotationSeconds"] = bad
        val before = upgradedPrefs.values.toMap()
        val read = GrafanaPreferences.read(upgradedContext)
        check(read.rotationMode == GrafanaRotationMode.FAVORITES && read.rotationSeconds == 120)
        check(upgradedPrefs.values == before)
    }
    pass("Missing, unsupported and stale rotation settings use independent safe defaults without repairing or resetting saved choices")

    GrafanaPreferences.setRotationSeconds(upgradedContext, 60)
    val validRotation = upgradedPrefs.values.toMap()
    for (bad in listOf(-1, 0, 1, 29, 31, 59, 61, 121, 240, 301, Int.MAX_VALUE))
        GrafanaPreferences.setRotationSeconds(upgradedContext, bad)
    check(upgradedPrefs.values == validRotation)
    GrafanaPreferences.setMode(upgradedContext, ScreensaverMode.ART)
    GrafanaPreferences.setRotationMode(upgradedContext, GrafanaRotationMode.ALL)
    val art = GrafanaPreferences.read(upgradedContext)
    check(art.mode == ScreensaverMode.ART && art.rotationMode == GrafanaRotationMode.ALL && art.rotationSeconds == 60)
    check(upgradedPrefs.values.filterKeys { it !in rotationKeys && it != "grafana.mode" } == legacyValues.filterKeys { it != "grafana.mode" })
    pass("Invalid interval writes leave the last valid interval intact, and choosing a rotation never changes the screensaver type")
    println("TOTAL|$count")
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
          'notCovered': ['Android WebView and login sessions', 'Live drawer updates', 'Native URL editor focus and Back']}
with tempfile.TemporaryDirectory(prefix='gostex-grafana-preferences-') as folder:
    temporary = Path(folder)
    contents = {
        'Boundary.kt': BOUNDARY,
        'GrafanaOptions.kt': 'package tv.orbit.shield\nimport android.content.Context\nimport android.content.SharedPreferences\nimport java.net.URI\n' + production,
        'Resources.kt': 'package tv.orbit.shield\ninternal object R { object string { ' + ' '.join(f'const val {name} = {i};' for i, name in enumerate(ids)) + ' } }',
        'GrafanaChecks.kt': CHECKS,
    }
    for name, value in contents.items(): (temporary / name).write_text(value)
    classes = temporary / 'classes'
    compile_result = subprocess.run([str(JAVA), '-cp', ':'.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', str(classes), *[str(temporary / name) for name in contents]], capture_output=True, text=True)
    report['compile'] = {'returncode': compile_result.returncode, 'stderr': compile_result.stderr}
    if compile_result.returncode == 0:
        run = subprocess.run([str(JAVA), '-cp', f'{classes}:{classpath}', 'tv.orbit.shield.GrafanaChecksKt'], capture_output=True, text=True)
        report['run'] = {'returncode': run.returncode, 'stdout': run.stdout, 'stderr': run.stderr}
        report['checks'] = [line.removeprefix('PASS|') for line in run.stdout.splitlines() if line.startswith('PASS|')]
        report['passed'] = run.returncode == 0 and len(report['checks']) == 13
output = ROOT / '.reports/grafana-preferences-checks.json'
output.parent.mkdir(exist_ok=True)
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({'passed': report['passed'], 'checks': len(report.get('checks', [])), 'report': str(output)}))
if not report['passed']:
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(1)
