"""Exercise production install recovery/consent gates without Android, Gradle or ADB.

System PackageInstaller behavior and physical remote/focus still require device QA.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield/LauncherUpdates.kt"
local_gradle = ROOT / ".tools/native/gradle-cache"
gradle_home = Path(os.environ["GRADLE_USER_HOME"]) if os.environ.get("GRADLE_USER_HOME") else local_gradle if local_gradle.exists() else Path.home() / ".gradle"
CACHE = gradle_home / "caches/modules-2/files-2.1"
JAVA = Path(os.environ.get("JAVA_HOME", str(ROOT / ".tools/native/jdk/jdk-21.0.13+9/Contents/Home"))) / "bin/java"
text = SOURCE.read_text()
production = text[text.index("internal object AndroidInstallRecovery"):text.index("/** Pure cache codec")]
preferences = text[text.index("internal data class UpdateOptions"):text.index("/** Install recovery")]
channels = (ROOT / "native-launcher/release-runtime/src/main/kotlin/dev/gostex/release/ReleaseChannels.kt").read_text()

# Android-free adapters for running the unchanged production preference reader/setters.
STUBS = r'''
internal class Context(val prefs: SharedPreferences = SharedPreferences()) {
    companion object { const val MODE_PRIVATE = 0 }
    fun getSharedPreferences(name: String, mode: Int) = prefs
}
internal class SharedPreferences(val values: MutableMap<String, Any?> = mutableMapOf()) {
    fun contains(key: String) = values.containsKey(key)
    fun getString(key: String, fallback: String?): String? = if (contains(key)) values[key] as String? else fallback
    fun getInt(key: String, fallback: Int): Int = if (contains(key)) values[key] as Int else fallback
    fun getBoolean(key: String, fallback: Boolean): Boolean = if (contains(key)) values[key] as Boolean else fallback
    fun edit() = Editor()
    inner class Editor {
        fun putString(key: String, value: String) = apply { values[key] = value }
        fun putInt(key: String, value: Int) = apply { values[key] = value }
        fun putBoolean(key: String, value: Boolean) = apply { values[key] = value }
        fun remove(key: String) = apply { values.remove(key) }
        fun apply() {}
    }
}
internal fun SharedPreferences.readStringOrDefault(key: String, fallback: String? = null): String? =
    runCatching { getString(key, fallback) }.getOrDefault(fallback)
internal fun SharedPreferences.readBooleanOrDefault(key: String, fallback: Boolean): Boolean =
    runCatching { getBoolean(key, fallback) }.getOrDefault(fallback)
internal object AndroidUpdateCache { val fields = listOf("feed", "platform", "code", "name", "url", "sha256", "size") }
'''

CHECKS = r'''package tv.orbit.shield
fun main() {
    fun pass(name: String, value: Boolean) { check(value) { name }; println("PASS|$name") }
    val record = AndroidInstallRecovery.Record(17, "consent-a", true, 42)
    fun decide(value: AndroidInstallRecovery.Record = record, exists: Boolean = true,
        installed: Long = 41, explicit: Boolean = false) =
        AndroidInstallRecovery.decide(value, exists, installed, explicit)
    val clear = AndroidInstallRecovery.Action.CLEAR
    val keep = AndroidInstallRecovery.Action.KEEP
    val recreate = AndroidInstallRecovery.Action.RECREATE
    pass("pending session stays dormant on resume", decide() == keep)
    pass("explicit retry replaces pending or uncommitted session", decide(explicit = true) == recreate)
    pass("legacy marker without version remains retryable", decide(record.copy(versionCode = 0), explicit = true) == recreate)
    pass("session removed by Android clears stale marker", decide(exists = false) == clear)
    pass("successful self-update clears its marker", decide(installed = 42) == clear)
    pass("a later installed release clears old marker", decide(installed = 43) == clear)
    pass("invalid or incomplete markers cannot authorize recovery", listOf(
        record.copy(id = -1), record.copy(token = ""), record.copy(token = "  "),
        record.copy(requested = false), record.copy(versionCode = -1)
    ).all { decide(it, explicit = true) == clear })
    pass("automatic resume never requests installation", (listOf(-1L, 0L, 41L, 42L, 43L).flatMap { code ->
        listOf(false, true).map { exists -> decide(record.copy(versionCode = code), exists = exists) }
    }).none { it == recreate })

    val gate = AndroidInstallGate()
    val first = Any()
    val replacement = Any()
    pass("background launcher cannot start or arm install", !gate.canStart(first) && !gate.arm(first, "a"))
    gate.resume(first)
    pass("visible explicit action can arm consent", gate.canStart(first) && gate.arm(first, "a"))
    pass("rapid repeated Install cannot double commit", !gate.canStart(first) && !gate.arm(first, "b"))
    pass("unrelated callback cannot open consent", !gate.takeConfirmation("old"))
    pass("matching callback opens consent once", gate.takeConfirmation("a") && !gate.takeConfirmation("a"))
    pass("repeat click remains blocked until installer takes focus", !gate.canStart(first))
    gate.stop(first)
    pass("Home cancels the old activity consent permit", !gate.takeConfirmation("a") && !gate.canStart(first))
    gate.resume(first)
    pass("returning Home enables explicit retry without reopening old consent", gate.canStart(first) && !gate.takeConfirmation("a"))
    check(gate.arm(first, "retry"))
    gate.resume(replacement)
    pass("recreated activity cannot inherit old consent permit", gate.canStart(replacement) && !gate.takeConfirmation("retry"))
    gate.stop(first)
    pass("late old onStop cannot hide the replacement", gate.canStart(replacement))
    check(gate.arm(replacement, "new"))
    gate.finish("retry")
    pass("old completion cannot unlock a newer attempt", !gate.canStart(replacement) && gate.takeConfirmation("new"))
    gate.finish("new")
    pass("terminal success or failure permits a new explicit action", gate.canStart(replacement))
    check(gate.arm(replacement, "cancelled"))
    gate.cancel(replacement)
    pass("feed or operation cancellation rejects delayed consent", gate.canStart(replacement) && !gate.takeConfirmation("cancelled"))
    pass("empty callback token cannot be armed", !gate.arm(replacement, ""))

    val context = Context()
    val key = "updates.feedUrl"
    val defaultFeed = dev.gostex.release.ReleaseChannels.ANDROID_ARM64_FEED
    pass("absent feed gets the editable public default", UpdatePreferences.read(context).feedUrl == defaultFeed && UpdateOptions().feedUrl == defaultFeed)
    context.prefs.values[key] = ""
    pass("explicitly disconnected feed stays empty", UpdatePreferences.read(context).feedUrl.isEmpty())
    context.prefs.values[key] = "https://owner.example/custom.properties"
    pass("custom channel survives update defaults", UpdatePreferences.read(context).feedUrl == "https://owner.example/custom.properties")
    context.prefs.values[key] = "http://invalid.example/feed"
    pass("invalid stored URL is disconnected", UpdatePreferences.read(context).feedUrl.isEmpty())
    context.prefs.values[key] = true
    pass("wrong preference type cannot activate default network channel", UpdatePreferences.read(context).feedUrl.isEmpty())
    context.prefs.values.remove(key)
    check(UpdatePreferences.setFeed(context, ""))
    pass("saving an empty feed persists explicit opt-out", context.prefs.contains(key) && UpdatePreferences.read(context).feedUrl.isEmpty())
    check(UpdatePreferences.setFeed(context, "https://owner.example/custom.properties"))
    pass("rejected edit preserves selected channel", !UpdatePreferences.setFeed(context, "http://invalid.example/feed") && UpdatePreferences.read(context).feedUrl == "https://owner.example/custom.properties")
}
'''


def jar(group, name, version):
    return next((CACHE / group / name / version).glob("*/*.jar"))


compiler = [jar("org.jetbrains.kotlin", artifact, version) for artifact, version in [
    ("kotlin-compiler-embeddable", "2.4.21"), ("kotlin-stdlib", "2.4.21"),
    ("kotlin-script-runtime", "2.4.21"), ("kotlin-reflect", "1.6.10")]]
compiler += [jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.9.0"),
             jar("org.jetbrains", "annotations", "13.0")]
report = {"sourceSha256": hashlib.sha256((production + preferences + channels).encode()).hexdigest(), "passed": False}
with tempfile.TemporaryDirectory(prefix="gostex-install-session-") as temporary:
    folder = Path(temporary)
    (folder / "Recovery.kt").write_text("package tv.orbit.shield\nimport java.net.URI\nimport dev.gostex.release.ReleaseChannels\n" + production + preferences + STUBS)
    (folder / "Channels.kt").write_text(channels)
    (folder / "Checks.kt").write_text(CHECKS)
    compiled = subprocess.run([str(JAVA), "-cp", ":".join(map(str, compiler)),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
        "-classpath", str(compiler[1]), "-d", str(folder / "classes"),
        str(folder / "Recovery.kt"), str(folder / "Channels.kt"), str(folder / "Checks.kt")], capture_output=True, text=True)
    report["compile"] = {"code": compiled.returncode, "stderr": compiled.stderr}
    if compiled.returncode == 0:
        result = subprocess.run([str(JAVA), "-cp", f"{folder / 'classes'}:{compiler[1]}",
            "tv.orbit.shield.ChecksKt"], capture_output=True, text=True)
        report["run"] = {"code": result.returncode, "stdout": result.stdout, "stderr": result.stderr}
        report["passed"] = result.returncode == 0
report["checks"] = report.get("run", {}).get("stdout", "").count("PASS|")
print(json.dumps(report, ensure_ascii=False))
raise SystemExit(0 if report["passed"] else 1)
