"""Exercise production video preferences/order with typed Android and JSON boundaries.

The host JSON adapter uses Gradle's existing Groovy parser, not Android's JSON
implementation. Android persistence, dialog interaction and focus require device QA.
"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield/VideoOptions.kt"
DEFAULTS = ROOT / "native-launcher/app/src/main/res/values/app_categories.xml"
CACHE = ROOT / ".tools/native/gradle-cache/caches/modules-2/files-2.1"
JAVA = ROOT / ".tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java"
GRADLE = ROOT / ".tools/native/gradle/gradle-9.8.2/lib"
source = SOURCE.read_text()
production = source[source.index("internal data class VideoOptions"):source.index("@Composable internal fun rememberVideoOptions")]
production += source[source.index("internal fun orderedVideoApps"):source.index("@Composable internal fun VideoSettings")]
defaults = [entry.text for entry in ET.parse(DEFAULTS).findall(".//string-array[@name='video_app_packages']/item")]

BOUNDARY = r'''package android.content
interface SharedPreferences {
    fun getString(key: String, default: String?): String?
    fun getStringSet(key: String, default: Set<String>?): Set<String>?
    fun edit(): Editor
    interface Editor {
        fun putString(key: String, value: String): Editor
        fun putStringSet(key: String, value: Set<String>): Editor
        fun apply()
    }
}
abstract class Resources { abstract fun getStringArray(id: Int): Array<String> }
abstract class Context {
    companion object { const val MODE_PRIVATE = 0 }
    abstract val resources: Resources
    abstract fun getSharedPreferences(name: String, mode: Int): SharedPreferences
}
'''
JSON_BOUNDARY = r'''package org.json
class JSONArray {
    private val values: List<*>
    constructor(text: String?) {
        values = groovy.json.JsonSlurper().parseText(text) as List<*>
    }
    constructor(items: Collection<*>) { values = items.toList() }
    fun length() = values.size
    fun opt(index: Int) = values.getOrNull(index)
    override fun toString(): String = groovy.json.JsonOutput.toJson(values)
}
'''
CHECKS = r'''package tv.orbit.shield
import android.content.Context
import android.content.Resources
import android.content.SharedPreferences
import java.util.Locale

internal object R { object array { const val video_app_packages = 1 } }
internal data class TvApp(val name: String, val packageName: String)
private val defaults = DEFAULT_ARRAY
private class TypedPreferences(val values: MutableMap<String, Any?> = mutableMapOf()) : SharedPreferences {
    override fun getString(key: String, default: String?) = (values[key] ?: default) as String?
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, default: Set<String>?) = (values[key] ?: default) as Set<String>?
    override fun edit() = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        override fun putString(key: String, value: String): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putStringSet(key: String, value: Set<String>): SharedPreferences.Editor { pending[key] = value; return this }
        override fun apply() { values.putAll(pending) }
    }
}
private class TestContext(val prefs: TypedPreferences) : Context() {
    override val resources = object : Resources() {
        override fun getStringArray(id: Int): Array<String> { check(id == R.array.video_app_packages); return defaults.copyOf() }
    }
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        check(name == "orbit" && mode == Context.MODE_PRIVATE); return prefs
    }
}
fun main() {
    var count = 0
    fun pass(name: String) { println("PASS|$name"); count++ }
    val prefs = TypedPreferences(); val context = TestContext(prefs)
    val original = VideoPreferences.read(context)
    check(original.visible == defaults.toSet() && original.order.isEmpty())
    check(prefs.values.isEmpty())
    check(setOf("mobi.zona", "org.smarttube.stable", "org.smarttube.beta").all { it in original.visible })
    pass("Missing values use current resource classification, including Zona and both SmartTube channels, without writes")

    for (bad in listOf(true, 7, 12L, "[]", listOf("mobi.zona"))) {
        prefs.values["video.visible"] = bad
        check(VideoPreferences.read(context).visible == defaults.toSet())
        check(prefs.values["video.visible"] == bad)
    }
    pass("Wrong visibility storage types fall back without rewriting corrupt values")

    for (bad in listOf(true, 7, 12L, setOf("mobi.zona"), "{", "{}", "null", "\"word\"")) {
        prefs.values["video.order"] = bad
        check(VideoPreferences.read(context).order.isEmpty())
        check(prefs.values["video.order"] == bad)
    }
    pass("Wrong order types and non-array or malformed JSON fall back without modifying storage")

    prefs.values.clear()
    prefs.values["video.order"] = "[\"mobi.zona\",null,12,true,\"\",\"  \",\"mobi.zona\",\"future.package\"]"
    check(VideoPreferences.read(context).order == listOf("mobi.zona", "future.package"))
    pass("Order ignores nonstrings, empty items and duplicates while preserving unknown package ids")

    val backing = linkedSetOf("mobi.zona", "future.package")
    prefs.values["video.visible"] = backing
    val snapshot = VideoPreferences.read(context).visible
    check(snapshot == backing && snapshot !== backing)
    backing.add("org.videolan.vlc")
    check("org.videolan.vlc" !in snapshot)
    pass("Visibility reads return snapshots detached from the stored mutable set")

    val selection = linkedSetOf("mobi.zona", "org.smarttube.stable")
    VideoPreferences.setVisible(context, selection); selection.clear()
    check(VideoPreferences.read(TestContext(prefs)).visible == setOf("mobi.zona", "org.smarttube.stable"))
    VideoPreferences.setVisible(context, emptySet())
    check(VideoPreferences.read(TestContext(prefs)).visible.isEmpty())
    pass("Selections round-trip through orbit preferences, copy mutable input and retain an intentional empty shelf")

    prefs.values.clear()
    val preserved = mapOf("language" to "ru", "favorites" to setOf("aladdin"), "art.speed" to "slow")
    prefs.values.putAll(preserved)
    VideoPreferences.setVisible(context, setOf("mobi.zona", "org.smarttube.stable", "org.videolan.vlc"))
    VideoPreferences.prioritize(context, "org.smarttube.stable")
    VideoPreferences.prioritize(context, "mobi.zona")
    VideoPreferences.prioritize(context, "mobi.zona")
    val ordered = VideoPreferences.read(TestContext(prefs))
    check(ordered.order == listOf("mobi.zona", "org.smarttube.stable"))
    check(prefs.values.filterKeys { !it.startsWith("video.") } == preserved)
    pass("Prioritizing composes a stable unique order and preserves visibility plus unrelated preferences")

    val before = prefs.values.toMap()
    VideoPreferences.prioritize(context, "hidden.package")
    check(prefs.values == before)
    pass("A hidden application cannot be prioritized or mutate the saved order")

    val apps = listOf(TvApp("VLC", "org.videolan.vlc"), TvApp("Hidden", "hidden.package"),
        TvApp("Zona", "mobi.zona"), TvApp("SmartTube", "org.smarttube.stable"))
    check(orderedVideoApps(apps, ordered).map { it.packageName } == listOf("mobi.zona", "org.smarttube.stable", "org.videolan.vlc"))
    check(orderedVideoApps(apps, VideoOptions(emptySet(), ordered.order)).isEmpty())
    check(orderedVideoApps(emptyList(), ordered).isEmpty())
    pass("Saved priorities lead the shelf; hidden apps and all empty-library cases remain absent")

    val gone = orderedVideoApps(apps.filterNot { it.packageName == "mobi.zona" }, ordered)
    check(gone.first().packageName == "org.smarttube.stable")
    check(VideoPreferences.read(context).order.first() == "mobi.zona")
    check(orderedVideoApps(apps, VideoPreferences.read(context)).first().packageName == "mobi.zona")
    pass("Uninstalled apps are omitted without deleting preferences, so reinstall restores the prior position")

    val fallbackApps = listOf(TvApp("zeta", "z"), TvApp("ALPHA", "b"), TvApp("alpha", "a"), TvApp("İstanbul", "i"))
    val fallbackOptions = VideoOptions(fallbackApps.map { it.packageName }.toSet(), listOf("gone"))
    val first = orderedVideoApps(fallbackApps, fallbackOptions)
    check(first.take(2).map { it.packageName } == listOf("a", "b"))
    val savedLocale = Locale.getDefault()
    try { Locale.setDefault(Locale.forLanguageTag("tr-TR")); check(orderedVideoApps(fallbackApps.reversed(), fallbackOptions) == first) }
    finally { Locale.setDefault(savedLocale) }
    pass("Unprioritized apps use deterministic case-insensitive name/package sorting independent of input order and locale")

    check(listOf(null, "video.visible", "video.order").all(VideoPreferences::changes))
    check(listOf("language", "favorites", "art.speed", "video.unknown").none(VideoPreferences::changes))
    pass("Option invalidation listens to both owned keys and clear events, ignoring unrelated preferences")
    println("TOTAL|$count")
}
'''.replace("DEFAULT_ARRAY", "arrayOf(" + ", ".join(json.dumps(item) for item in defaults) + ")")


def jar(group, artifact, version):
    return next((CACHE / group / artifact / version).glob("*/*.jar"))


compiler = [jar("org.jetbrains.kotlin", artifact, version) for artifact, version in [
    ("kotlin-compiler-embeddable", "2.4.21"), ("kotlin-stdlib", "2.4.21"),
    ("kotlin-script-runtime", "2.4.21"), ("kotlin-reflect", "1.6.10"),
]] + [jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.9.0"), jar("org.jetbrains", "annotations", "13.0")]
libraries = [compiler[1], GRADLE / "groovy-4.0.33.jar", GRADLE / "groovy-json-4.0.33.jar"]
classpath = ":".join(map(str, libraries))
report = {
    "timestampUtc": datetime.now(timezone.utc).isoformat(),
    "scope": __doc__,
    "sourceSha256": {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in [SOURCE, DEFAULTS]},
    "notCovered": ["Android JSON parser differences", "Android XML persistence and cold process lifecycle", "Native dialog Apply/Cancel/Back and Compose focus after reorder"],
    "passed": False,
}
with tempfile.TemporaryDirectory(prefix="gostex-video-options-") as folder:
    temporary = Path(folder)
    contents = {
        "Boundary.kt": BOUNDARY,
        "JsonBoundary.kt": JSON_BOUNDARY,
        "VideoOptions.kt": "package tv.orbit.shield\nimport android.content.Context\nimport org.json.JSONArray\n" + production,
        "VideoChecks.kt": CHECKS,
    }
    for name, value in contents.items():
        (temporary / name).write_text(value)
    classes = temporary / "classes"
    compiled = subprocess.run([str(JAVA), "-cp", ":".join(map(str, compiler)),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-classpath", classpath,
        "-d", str(classes), *[str(temporary / name) for name in contents]], capture_output=True, text=True)
    report["compile"] = {"returncode": compiled.returncode, "stderr": compiled.stderr}
    if compiled.returncode == 0:
        run = subprocess.run([str(JAVA), "-cp", f"{classes}:{classpath}", "tv.orbit.shield.VideoChecksKt"], capture_output=True, text=True)
        report["run"] = {"returncode": run.returncode, "stdout": run.stdout, "stderr": run.stderr}
        report["checks"] = [line.removeprefix("PASS|") for line in run.stdout.splitlines() if line.startswith("PASS|")]
        report["passed"] = run.returncode == 0 and len(report["checks"]) == 12

output = ROOT / ".reports/video-options-checks.json"
output.parent.mkdir(exist_ok=True)
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
print(json.dumps({"passed": report["passed"], "checks": len(report.get("checks", [])), "report": str(output)}))
if not report["passed"]:
    print(report)
    raise SystemExit(1)
