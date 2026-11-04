"""Exercise the production rotation planner, selection and deadlines without Android rendering.

Request close/read cancellation is covered by check_grafana_catalog.py. Android lifecycle,
single-WebView ownership and real page loading remain device checks.
"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCES = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
CACHE = ROOT / '.tools/native/gradle-cache/caches/modules-2/files-2.1'
JAVA = ROOT / '.tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java'
catalog = (SOURCES / 'GrafanaCatalog.kt').read_text()
catalog_models = catalog[catalog.index('internal data class GrafanaDashboard'):catalog.index('internal enum class GrafanaCatalogFailure')]
options = (SOURCES / 'GrafanaOptions.kt').read_text()
enum = options[options.index('internal enum class GrafanaRotationMode'):options.index('internal data class GrafanaOptions')]
normalizer = options[options.index('    fun normalizeUrl('):options.index('\n    fun read(')]
choices = re.search(r'val rotationChoices = (listOf\([^\n]+\))', options).group(1)
rotation = (SOURCES / 'GrafanaRotation.kt').read_text()
CHECKS = r'''package tv.orbit.shield
fun main() {
    var count = 0
    fun pass(label: String) { count++; println("PASS|$label") }
    val anchor = "https://grafana.example/d/beta/finance?orgId=3&from=now-24h&to=now&refresh=1m&var-team=beta"
    val server = GrafanaCatalogServer.fromUrl(anchor)!!
    val alpha = GrafanaDashboard("alpha", "A", "", "https://grafana.example/d/alpha/flow")
    val beta = GrafanaDashboard("beta", "B", "", "https://grafana.example/d/beta/finance")
    val gamma = GrafanaDashboard("gamma", "C", "", "https://grafana.example/d/gamma/payments")
    val catalog = GrafanaCatalogResult(server, 3, listOf(alpha, beta, gamma), false)
    val all = grafanaRotationPages(catalog, GrafanaRotationMode.ALL, emptySet(), anchor)
    check(all.map { it.uid } == listOf("alpha", "beta", "gamma"))
    check(all.all { "from=now-24h" in it.url && "to=now" in it.url && "refresh=1m" in it.url && "kiosk=true" in it.url })
    check("var-team=beta" in all[1].url && all.filter { it.uid != "beta" }.none { "var-team" in it.url })
    val queue = GrafanaRotationQueue()
    queue.replace(all, grafanaRotationAnchorUid(server, anchor))
    check(queue.current!!.uid == "beta")
    check(queue.advance()!!.uid == "gamma" && queue.advance()!!.uid == "alpha")
    check(queue.advance()!!.url == all[1].url && "var-team=beta" in queue.current!!.url)
    pass("Stable catalog order wraps and the saved anchor restores only its own variables after a complete cycle")

    val filtered = grafanaRotationPages(catalog, GrafanaRotationMode.FAVORITES, setOf("gamma", "alpha", "stale"), anchor)
    check(filtered.map { it.uid } == listOf("alpha", "gamma"))
    check(grafanaRotationPages(catalog, GrafanaRotationMode.FAVORITES, emptySet(), anchor).isEmpty())
    check(grafanaRotationPages(catalog, GrafanaRotationMode.FAVORITES, setOf("removed"), anchor).isEmpty())
    check(grafanaRotationPages(catalog, GrafanaRotationMode.SINGLE, setOf("alpha"), anchor).isEmpty())
    pass("Favorites use only selected valid UIDs in catalog order; empty/stale favorites and SINGLE never fall back to ALL or the anchor")

    queue.replace(filtered, "beta")
    check(queue.current!!.uid == "alpha")
    queue.advance()
    check(queue.current!!.uid == "gamma")
    queue.replace(all, "beta")
    check(queue.current!!.uid == "gamma")
    queue.replace(all, "alpha")
    check(queue.current!!.uid == "gamma")
    pass("Filtering out the current dashboard chooses an available page; unchanged selections retain their current position")

    queue.replace(listOf(all[2]), "beta")
    check(!queue.canAdvance && queue.advance() == all[2])
    queue.replace(emptyList(), "beta")
    check(!queue.canAdvance && queue.current == null && queue.advance() == null)
    queue.replace(all, "beta")
    queue.clear()
    check(queue.pages.isEmpty() && queue.current == null && !queue.canAdvance)
    pass("One-item/empty queues never trigger pointless rotation; stop clears selection and pages")

    val invalid = listOf(alpha, alpha, beta.copy(url="https://other.example/d/beta/finance"),
        beta.copy(uid="invalid uid"), beta.copy(url="https://grafana.example/d/other/finance"),
        beta.copy(url="https://grafana.example/login"), gamma)
    check(grafanaRotationPages(catalog.copy(dashboards=invalid), GrafanaRotationMode.ALL, emptySet(), anchor).map { it.uid } == listOf("alpha", "gamma"))
    check(grafanaRotationAnchorUid(server, "https://other.example/d/beta/finance") == null)
    check(grafanaRotationAnchorUid(server, "https://grafana.example/dashboards") == null)
    pass("Malformed, cross-origin and wrong-UID targets are rejected, duplicate catalog rows never duplicate playback")

    val otherOrg = grafanaRotationPages(catalog.copy(orgId=4), GrafanaRotationMode.ALL, emptySet(), anchor)
    check(otherOrg.all { "orgId=4" in it.url && "var-team" !in it.url })
    pass("Same UID in another organization cannot inherit the saved dashboard's variables")

    for (seconds in GrafanaPreferences.rotationChoices) {
        check(grafanaRotationRemainingMillis(seconds, 50_000, 50_000) == seconds * 1000L)
        check(grafanaRotationRemainingMillis(seconds, 50_000, 60_000) == seconds * 1000L - 10_000L)
        check(grafanaRotationRemainingMillis(seconds, 50_000, 999_999) == 0L)
    }
    for (seconds in listOf(-1, 0, 1, 59, Int.MAX_VALUE)) check(grafanaRotationRemainingMillis(seconds, 0, 0) == 120_000L)
    check(grafanaRotationRemainingMillis(60, 50_000, 20_000) == 60_000L)
    check(grafanaRotationRemainingMillis(60, 50_000, 95_000) == 15_000L)
    check(grafanaRotationRemainingMillis(120, 50_000, 95_000) == 75_000L)
    pass("Display deadlines preserve elapsed visible time across refresh, apply new durations, and bound invalid intervals safely")

    for (bad in listOf(null, "", "random", "SINGLE", "ALL")) check(GrafanaRotationMode.fromSavedId(bad) == GrafanaRotationMode.SINGLE)
    for (mode in GrafanaRotationMode.entries) check(GrafanaRotationMode.fromSavedId(mode.id) == mode)
    pass("Unknown saved modes safely preserve single-dashboard behavior")
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
          'sourceSha256': hashlib.sha256(rotation.encode()).hexdigest(), 'passed': False,
          'notCovered': ['Android WebView lifecycle and page events', 'Dream screen preference updates', 'Actual HTTP request cancellation (separate catalog check)']}
with tempfile.TemporaryDirectory(prefix='gostex-grafana-rotation-') as folder:
    temporary = Path(folder)
    contents = {
        'CatalogModels.kt': 'package tv.orbit.shield\nimport java.net.URI\nimport java.net.URLDecoder\nimport java.util.Locale\n' + catalog_models,
        'GrafanaPreferences.kt': 'package tv.orbit.shield\nimport java.net.URI\ninternal object GrafanaPreferences { const val MAX_URL_LENGTH = 8192\nval rotationChoices = ' + choices + '\n' + normalizer + '\n}',
        'GrafanaRotation.kt': rotation,
        'Mode.kt': 'package tv.orbit.shield\n' + enum,
        'Resources.kt': 'package tv.orbit.shield\ninternal object R { object string { const val grafana_rotation_single=1; const val grafana_rotation_all=2; const val grafana_rotation_favorites=3; } }',
        'Checks.kt': CHECKS,
    }
    for name, value in contents.items(): (temporary / name).write_text(value)
    classes = temporary / 'classes'
    compiled = subprocess.run([str(JAVA), '-cp', ':'.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', str(classes), *[str(temporary / name) for name in contents]], capture_output=True, text=True)
    report['compile'] = {'returncode': compiled.returncode, 'stderr': compiled.stderr}
    if compiled.returncode == 0:
        run = subprocess.run([str(JAVA), '-cp', f'{classes}:{classpath}', 'tv.orbit.shield.ChecksKt'], capture_output=True, text=True)
        report['run'] = {'returncode': run.returncode, 'stdout': run.stdout, 'stderr': run.stderr}
        report['checks'] = [line.removeprefix('PASS|') for line in run.stdout.splitlines() if line.startswith('PASS|')]
        report['passed'] = run.returncode == 0 and len(report['checks']) == 8
output = ROOT / '.reports/grafana-rotation-checks.json'
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({'passed': report['passed'], 'checks': len(report.get('checks', [])), 'report': str(output)}))
if not report['passed']:
    print(json.dumps(report, ensure_ascii=False, indent=2)); raise SystemExit(1)
