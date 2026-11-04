"""Run the production preference readers against a narrow typed Android boundary."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield/PreferenceReads.kt"
CACHE = ROOT / ".tools/native/gradle-cache/caches/modules-2/files-2.1"
JAVA = ROOT / ".tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java"

BOUNDARY = '''package android.content
interface SharedPreferences {
    fun getString(key: String, default: String?): String?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getStringSet(key: String, default: Set<String>?): Set<String>?
}
'''
CHECKS = '''package tv.orbit.shield
import android.content.SharedPreferences

private class TypedPreferences(val values: MutableMap<String, Any?> = mutableMapOf()) : SharedPreferences {
    override fun getString(key: String, default: String?) = (values[key] ?: default) as String?
    override fun getBoolean(key: String, default: Boolean) = (values[key] ?: default) as Boolean
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, default: Set<String>?) = (values[key] ?: default) as Set<String>?
}

fun main() {
    fun pass(name: String) = println("PASS|$name")
    val prefs = TypedPreferences()
    check(prefs.readStringOrDefault("language") == null)
    check(prefs.readStringOrDefault("controllerIcons", "auto") == "auto")
    check(prefs.readBooleanOrDefault("remoteAutostart", true))
    check(!prefs.readBooleanOrDefault("remoteAutostart", false))
    check(prefs.readStringSetOrDefault("favorites").isEmpty())
    check(prefs.values.isEmpty())
    pass("Missing values use the caller default without writing preferences")

    for (value in listOf(true, 12, 45L, setOf("ru"))) {
        prefs.values["language"] = value
        check(prefs.readStringOrDefault("language") == null)
        prefs.values["controllerIcons"] = value
        check(prefs.readStringOrDefault("controllerIcons", "auto") == "auto")
        check(prefs.values["language"] == value && prefs.values["controllerIcons"] == value)
    }
    pass("Malformed string types fall back and leave the original stored value untouched")

    for (value in listOf("ru", "en", "es", "playstation", "nintendo", "future-value", "")) {
        prefs.values["key"] = value
        check(prefs.readStringOrDefault("key", "auto") == value)
    }
    pass("Valid strings retain their exact value for existing domain validation")

    for (value in listOf("false", 0, 1L, setOf("true"))) {
        prefs.values["remoteAutostart"] = value
        check(prefs.readBooleanOrDefault("remoteAutostart", true))
        check(!prefs.readBooleanOrDefault("remoteAutostart", false))
        check(prefs.values["remoteAutostart"] == value)
    }
    pass("Malformed boolean types use the supplied default without coercion or rewriting")

    for (value in listOf(false, true)) {
        prefs.values["remoteAutostart"] = value
        check(prefs.readBooleanOrDefault("remoteAutostart", !value) == value)
    }
    pass("Explicit Remote off and on both survive reads")

    for (value in listOf("Aladdin", true, 12, 45L)) {
        prefs.values["favorites"] = value
        check(prefs.readStringSetOrDefault("favorites").isEmpty())
        check(prefs.readStringSetOrDefault("favorites", setOf("fallback")) == setOf("fallback"))
        check(prefs.values["favorites"] == value)
    }
    pass("Malformed favorite storage types fall back without overwriting data")

    val backing = linkedSetOf("aladdin", "zelda", "Пример")
    prefs.values["favorites"] = backing
    val snapshot = prefs.readStringSetOrDefault("favorites")
    check(snapshot == backing && snapshot !== backing)
    backing.add("new")
    check("new" !in snapshot)
    prefs.values["favorites"] = emptySet<String>()
    check(prefs.readStringSetOrDefault("favorites", setOf("fallback")).isEmpty())
    pass("Valid favorite sets are detached snapshots and deliberate empty sets stay empty")

    prefs.values["favorites"] = null
    val fallback = linkedSetOf("one", "two")
    val copy = prefs.readStringSetOrDefault("favorites", fallback)
    fallback.add("three")
    check(copy == setOf("one", "two"))
    prefs.values["language"] = null
    check(prefs.readStringOrDefault("language") == null)
    check(prefs.readStringOrDefault("language", "auto") == "auto")
    pass("Null boundary values use defaults and do not share mutable fallback sets")
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
    "scope": "Unchanged production PreferenceReads.kt compiled on the host; typed in-memory adapter throws for wrong stored types.",
    "source": str(SOURCE),
    "sourceSha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
    "notCovered": ["Android XML persistence and process lifecycle", "Compose focus and UI", "Language/controller domain selection, which is unchanged"],
    "passed": False,
}
with tempfile.TemporaryDirectory(prefix="gostex-preference-reads-") as folder:
    temporary = Path(folder)
    boundary = temporary / "SharedPreferences.kt"
    checks = temporary / "PreferenceReadChecks.kt"
    boundary.write_text(BOUNDARY)
    checks.write_text(CHECKS)
    classes = temporary / "classes"
    compile_command = [str(JAVA), "-cp", ":".join(map(str, compiler)),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
        "-classpath", str(compiler[1]), "-d", str(classes), str(SOURCE), str(boundary), str(checks)]
    compiled = subprocess.run(compile_command, capture_output=True, text=True)
    report["compile"] = {"returncode": compiled.returncode, "stderr": compiled.stderr}
    if compiled.returncode == 0:
        run = subprocess.run([str(JAVA), "-cp", f"{classes}:{compiler[1]}",
            "tv.orbit.shield.PreferenceReadChecksKt"], capture_output=True, text=True)
        report["run"] = {"returncode": run.returncode, "stdout": run.stdout, "stderr": run.stderr}
        report["checks"] = [line.removeprefix("PASS|") for line in run.stdout.splitlines() if line.startswith("PASS|")]
        report["passed"] = run.returncode == 0 and len(report["checks"]) == 8

output = ROOT / ".reports/preference-read-checks.json"
output.parent.mkdir(exist_ok=True)
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
print(json.dumps({"passed": report["passed"], "checks": len(report.get("checks", [])), "report": str(output)}))
if not report["passed"]:
    print(report)
    raise SystemExit(1)
