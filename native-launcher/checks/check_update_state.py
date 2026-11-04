"""Run the production Android cached-release codec without Android/network/ADB.

APK signing, lifecycle cancellation, offline recovery and install UI still need device QA.
"""
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield/LauncherUpdates.kt"
RELEASE = ROOT / "native-launcher/release-runtime/src/main/kotlin/dev/gostex/release/SignedReleaseClient.kt"
local_gradle = ROOT / ".tools/native/gradle-cache"
gradle_home = Path(os.environ["GRADLE_USER_HOME"]) if os.environ.get("GRADLE_USER_HOME") else local_gradle if local_gradle.exists() else Path.home() / ".gradle"
CACHE = gradle_home / "caches/modules-2/files-2.1"
JAVA = Path(os.environ.get("JAVA_HOME", str(ROOT / ".tools/native/jdk/jdk-21.0.13+9/Contents/Home"))) / "bin/java"
text = SOURCE.read_text()
production = text[text.index("internal object AndroidUpdateCache"):text.index("/** App-owned bounded diagnostics")]

CHECKS = r'''package tv.orbit.shield
import dev.gostex.release.Release
fun main() {
    fun pass(name: String, value: Boolean) { check(value) { name }; println("PASS|$name") }
    val source = "https://releases.example/android-arm64.properties"
    val release = Release("android-arm64", 42, "0.34.0", "https://releases.example/GOSTEX.apk", "ab".repeat(32), 12000)
    val values = AndroidUpdateCache.encode(source, release)
    fun changed(key: String, value: String) = values + (key to value)
    fun rejected(values: Map<String, String>) = AndroidUpdateCache.decode(values, source, 41) == null
    pass("authenticated metadata round-trip", AndroidUpdateCache.decode(values, source, 41) == release)
    pass("cache keys do not include user options", values.keys == AndroidUpdateCache.fields.toSet() && values.size == 7)
    pass("cache is bound to exact selected source", AndroidUpdateCache.decode(values, source + "/different", 41) == null)
    pass("cleared source cannot restore old update", AndroidUpdateCache.decode(values, "", 41) == null)
    pass("already installed version is discarded", AndroidUpdateCache.decode(values, source, 42) == null)
    pass("older than installed version is discarded", AndroidUpdateCache.decode(values, source, 43) == null)
    pass("missing and unknown fields rejected", rejected(values - "url") && rejected(values + ("extra" to "value")))
    pass("wrong platform rejected", rejected(changed("platform", "linux-x64")))
    pass("invalid version rejected", listOf("0", "-1", "future", "9223372036854775808").all { rejected(changed("code", it)) })
    pass("invalid artifact size rejected", listOf("0", "-1", "1073741825", "many").all { rejected(changed("size", it)) })
    pass("invalid digest rejected", rejected(changed("sha256", "xx".repeat(32))) && rejected(changed("sha256", "ab".repeat(31))))
    pass("invalid version label rejected", rejected(changed("name", "")) && rejected(changed("name", "v\n42")) && rejected(changed("name", "a".repeat(129))))
    pass("artifact transport and credentials rejected", listOf("http://releases.example/file.apk", "file:///tmp/file.apk", "https://user:password@releases.example/file.apk", "https://releases.example/file.apk#fragment").all { rejected(changed("url", it)) })
    pass("feed must be HTTPS without query", listOf("http://releases.example/feed", "https://releases.example/feed?token=example").all { AndroidUpdateCache.decode(values + ("feed" to it), it, 41) == null })
    pass("signed artifact query remains supported", AndroidUpdateCache.decode(changed("url", release.url + "?download=1"), source, 41)?.url == release.url + "?download=1")
    pass("oversized artifact address rejected", rejected(changed("url", release.url + "a".repeat(8192))))
    pass("decoding leaves persisted metadata unchanged", values == AndroidUpdateCache.encode(source, release))
}
'''

def jar(group, name, version):
    return next((CACHE / group / name / version).glob("*/*.jar"))

compiler = [jar("org.jetbrains.kotlin", artifact, version) for artifact, version in [
    ("kotlin-compiler-embeddable", "2.4.21"), ("kotlin-stdlib", "2.4.21"),
    ("kotlin-script-runtime", "2.4.21"), ("kotlin-reflect", "1.6.10")]]
compiler += [jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.9.0"), jar("org.jetbrains", "annotations", "13.0")]
report = {"timestamp": datetime.now(timezone.utc).isoformat(), "scope": __doc__,
          "sourceSha256": hashlib.sha256((production + RELEASE.read_text()).encode()).hexdigest(), "passed": False}
with tempfile.TemporaryDirectory(prefix="gostex-update-cache-") as temporary:
    folder = Path(temporary)
    (folder / "Cache.kt").write_text("package tv.orbit.shield\nimport java.net.URI\nimport dev.gostex.release.Release\n" + production)
    (folder / "Checks.kt").write_text(CHECKS)
    compiled = subprocess.run([str(JAVA), "-cp", ":".join(map(str, compiler)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-classpath", str(compiler[1]), "-d", str(folder / "classes"),
        str(RELEASE), str(folder / "Cache.kt"), str(folder / "Checks.kt")], capture_output=True, text=True)
    report["compile"] = {"code": compiled.returncode, "stderr": compiled.stderr}
    if compiled.returncode == 0:
        result = subprocess.run([str(JAVA), "-cp", f"{folder / 'classes'}:{compiler[1]}", "tv.orbit.shield.ChecksKt"], capture_output=True, text=True)
        report["run"] = {"code": result.returncode, "stdout": result.stdout, "stderr": result.stderr}
        report["passed"] = result.returncode == 0
output = ROOT / ".reports/update-cache-checks.json"
output.parent.mkdir(exist_ok=True)
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
print(json.dumps({"passed": report["passed"], "checks": report.get("run", {}).get("stdout", "").count("PASS|"),
                  "errors": report.get("run", report["compile"]).get("stderr", "")}))
raise SystemExit(0 if report["passed"] else 1)
