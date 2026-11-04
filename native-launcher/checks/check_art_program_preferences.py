"""Check art program compatibility and the new opt-out against a typed preference boundary."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield/ArtOptions.kt"
CACHE = ROOT / ".tools/native/gradle-cache/caches/modules-2/files-2.1"
JAVA = ROOT / ".tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java"
source = SOURCE.read_text()
production = source[source.index("internal enum class ArtTheme("):source.index("@Composable internal fun ArtSettings(")]
resource_ids = sorted(set(re.findall(r"R\.string\.(\w+)", production)))
resource_stub = "internal object R { object string {\n" + "\n".join(
    f"const val {name} = {index}" for index, name in enumerate(resource_ids)
) + "\n} }\n"

BOUNDARY = r'''
package android.content
interface SharedPreferences {
    fun getString(key: String, default: String?): String?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getInt(key: String, default: Int): Int
    fun edit(): Editor
    interface Editor {
        fun putString(key: String, value: String): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putInt(key: String, value: Int): Editor
        fun apply()
    }
}
abstract class Context {
    companion object { const val MODE_PRIVATE = 0 }
    abstract fun getSharedPreferences(name: String, mode: Int): SharedPreferences
}
'''

CHECKS = r'''
package tv.orbit.shield
import android.content.Context
import android.content.SharedPreferences

private class TypedPreferences(val values: MutableMap<String, Any?> = mutableMapOf()) : SharedPreferences {
    override fun getString(key: String, default: String?) = (values[key] ?: default) as String?
    override fun getBoolean(key: String, default: Boolean) = (values[key] ?: default) as Boolean
    override fun getInt(key: String, default: Int) = (values[key] ?: default) as Int
    override fun edit() = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        override fun putString(key: String, value: String): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { pending[key] = value; return this }
        override fun apply() { values.putAll(pending) }
    }
}
private class TestContext(val prefs: TypedPreferences) : Context() {
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        check(name == "orbit" && mode == Context.MODE_PRIVATE)
        return prefs
    }
}
fun main() {
    fun pass(name: String) = println("PASS|$name")
    val prefs = TypedPreferences()
    val context = TestContext(prefs)
    check(ArtPreferences.read(context) == ArtOptions())
    check(ArtPreferences.read(context).interludes)
    check(prefs.values.isEmpty())
    pass("Missing interlude preference defaults on without writing or migrating data")

    for (enabled in listOf(false, true, false)) {
        ArtPreferences.setInterludes(context, enabled)
        check(ArtPreferences.read(TestContext(prefs)).interludes == enabled)
        check(prefs.values == mapOf("art.interludes" to enabled))
    }
    pass("Explicit interlude opt-out and opt-in round-trip through the shared orbit preference key")

    for (value in listOf("false", "true", 0, 1, 1L, setOf("false"))) {
        prefs.values["art.interludes"] = value
        check(ArtPreferences.read(context).interludes)
        check(prefs.values["art.interludes"] == value)
    }
    pass("Malformed boolean types safely use the default without coercion or rewriting")

    val preserved = mapOf("language" to "ru", "favorites" to setOf("aladdin"),
        "art.revealStyle" to "scene", "art.holdSeconds" to 20, "art.speed" to "slow",
        "art.captionMode" to "title", "art.priorities" to "history,games,comics,media,books")
    prefs.values.clear(); prefs.values.putAll(preserved)
    ArtPreferences.setInterludes(context, false)
    check(prefs.values.filterKeys { it != "art.interludes" } == preserved)
    val selected = ArtPreferences.read(context)
    check(selected.revealStyle == RevealStyle.SCENE && selected.holdSeconds == 20 && selected.speed == ArtSpeed.SLOW)
    check(selected.captionMode == CaptionMode.TITLE && selected.priorities.first() == ArtTheme.HISTORY)
    pass("Changing interludes preserves the previous art choices and unrelated user data")

    check(ArtPreferences.programs == listOf(RevealStyle.MIXED, RevealStyle.CALM, RevealStyle.STILL))
    check(ArtPreferences.programs.distinct().size == 3)
    pass("The program selector exposes only Auto, Calm and Still")

    for (style in RevealStyle.entries) {
        ArtPreferences.setRevealStyle(context, style)
        check(ArtPreferences.read(context).revealStyle == style)
        check(!ArtPreferences.read(context).interludes)
    }
    pass("Every saved concrete style still loads unchanged and selecting a program preserves interlude opt-out")

    for ((id, style) in mapOf("flow" to RevealStyle.SNAKES, "rain" to RevealStyle.BLOCKS, "vortex" to RevealStyle.WEAVE)) {
        prefs.values["art.revealStyle"] = id
        check(ArtPreferences.read(context).revealStyle == style)
        check(prefs.values["art.revealStyle"] == id)
    }
    pass("Retired style ids remain readable without silently overwriting the stored choice")

    prefs.values["art.revealStyle"] = "future-mode"
    prefs.values["art.holdSeconds"] = 999
    prefs.values["art.speed"] = "unknown"
    prefs.values["art.captionMode"] = 17
    prefs.values["art.priorities"] = "books,books,unknown,games"
    val fallback = ArtPreferences.read(context)
    check(fallback.revealStyle == RevealStyle.MIXED && fallback.holdSeconds == 10)
    check(fallback.speed == ArtSpeed.NORMAL && fallback.captionMode == CaptionMode.DETAILS)
    check(fallback.priorities == listOf(ArtTheme.BOOKS, ArtTheme.GAMES, ArtTheme.MEDIA, ArtTheme.COMICS, ArtTheme.HISTORY))
    check(!fallback.interludes)
    pass("Invalid neighboring settings fall back independently without losing the valid interlude opt-out")
}
'''


def jar(group, artifact, version):
    return next((CACHE / group / artifact / version).glob("*/*.jar"))


compiler = [jar("org.jetbrains.kotlin", artifact, version) for artifact, version in [
    ("kotlin-compiler-embeddable", "2.4.21"), ("kotlin-stdlib", "2.4.21"),
    ("kotlin-script-runtime", "2.4.21"), ("kotlin-reflect", "1.6.10"),
]] + [jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.9.0"),
      jar("org.jetbrains", "annotations", "13.0")]
report = {
    "timestampUtc": datetime.now(timezone.utc).isoformat(),
    "scope": "Unchanged non-Compose ArtOptions/ArtPreferences slice compiled with typed in-memory Context/SharedPreferences and resource stubs.",
    "source": str(SOURCE),
    "sourceSha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
    "notCovered": ["Android XML persistence and process lifecycle", "Compose focus and UI", "Rendering and choice of standalone interlude scenes"],
    "passed": False,
}
with tempfile.TemporaryDirectory(prefix="gostex-art-preferences-") as folder:
    temporary = Path(folder)
    boundary = temporary / "SharedPreferences.kt"
    checks = temporary / "ArtPreferenceChecks.kt"
    boundary.write_text(BOUNDARY)
    checks.write_text(CHECKS)
    model = temporary / "ArtOptions.kt"
    model.write_text("package tv.orbit.shield\nimport android.content.Context\nimport android.content.SharedPreferences\n" + resource_stub + production)
    classes = temporary / "classes"
    compile_command = [str(JAVA), "-cp", ":".join(map(str, compiler)),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
        "-classpath", str(compiler[1]), "-d", str(classes), str(model), str(boundary), str(checks)]
    compiled = subprocess.run(compile_command, capture_output=True, text=True)
    report["compile"] = {"returncode": compiled.returncode, "stderr": compiled.stderr}
    if compiled.returncode == 0:
        run = subprocess.run([str(JAVA), "-cp", f"{classes}:{compiler[1]}",
            "tv.orbit.shield.ArtPreferenceChecksKt"], capture_output=True, text=True)
        report["run"] = {"returncode": run.returncode, "stdout": run.stdout, "stderr": run.stderr}
        report["checks"] = [line.removeprefix("PASS|") for line in run.stdout.splitlines() if line.startswith("PASS|")]
        report["passed"] = run.returncode == 0 and len(report["checks"]) == 8

output = ROOT / ".reports/art-program-preference-checks.json"
output.parent.mkdir(exist_ok=True)
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
print(json.dumps({"passed": report["passed"], "checks": len(report.get("checks", [])), "report": str(output)}))
if not report["passed"]:
    print(report)
    raise SystemExit(1)
