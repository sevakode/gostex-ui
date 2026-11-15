"""Check import-result counts against committed cards and normalized document identity.

This executes production Kotlin. Android dialog focus and installer return need UI QA.
"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

from kotlin_toolchain import COMPILER, JAVA, ROOT

sources = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield"
main = (sources / "MainActivity.kt").read_text()
start = main.index("data class Game(")
end = main.index("\n)", start) + len("\n)")
model = "package tv.orbit.shield\n" + main[start:end]
checks = r'''package tv.orbit.shield
private fun game(id: String, vararg files: String, platform: String = "SNES") = Game(
    id, id, platform, "emulator", "component", files.toList(), "root", "", "", "", "", "", "")
fun main() {
    var count = 0
    fun verify(name: String, condition: Boolean) { check(condition) { name }; count++; println("PASS|$name") }
    val old = game("old", "content://provider/document/old.sfc")
    val fresh = game("new", "content://provider/document/new.sfc")
    val both = listOf(old, fresh)
    fun result(scanned: List<Game>?, previous: List<Game> = listOf(old), committed: List<Game> = both,
               warnings: List<String> = emptyList()) = summarizeGameImport("SNES", scanned, previous, committed, warnings)
    verify("Only selected source cards are counted", result(listOf(fresh)) == GameImportSummary("SNES", 1, 1, false))
    verify("Existing cards remain available without being added again", result(both) == GameImportSummary("SNES", 2, 1, false))
    val overlap = old.copy(id = "other-grant", files = listOf("content://provider/tree/library/document/old.sfc"))
    verify("A second grant matches the committed card without making a new card",
        result(listOf(overlap)) == GameImportSummary("SNES", 1, 0, false))
    val variant = old.copy(files = old.files + "content://provider/document/translation.sfc")
    verify("A newly found variant of an existing card is not a new card",
        result(listOf(variant), committed = listOf(variant)) == GameImportSummary("SNES", 1, 0, false))
    verify("Uncommitted scan candidates are not reported as available", result(listOf(fresh), committed = listOf(old)).available == 0)
    verify("A failed scan does not present preserved cards as fresh verification",
        result(null, warnings = listOf("unavailable")) == GameImportSummary("SNES", 0, 0, true, listOf("unavailable")))
    verify("An empty successful scan is distinguished from failure",
        result(emptyList()) == GameImportSummary("SNES", 0, 0, false))
    val otherSystem = fresh.copy(id = "other-system", platform = "PSP")
    verify("The same document on another platform is outside this import",
        result(listOf(otherSystem), committed = listOf(otherSystem)).available == 0)
    verify("Another platform's prior document does not suppress a new card",
        result(listOf(fresh), previous = listOf(otherSystem)).added == 1)
    val literal = game("literal", "content://provider/document/Game%2BOne.sfc")
    val space = game("space", "content://provider/document/Game%20One.sfc")
    verify("Literal plus and spaces remain different documents",
        result(listOf(literal), previous = listOf(space), committed = listOf(literal)).added == 1)
    verify("Document identity remains case-sensitive",
        result(listOf(fresh), previous = listOf(fresh.copy(files = listOf("content://provider/document/New.sfc")))).added == 1)
    verify("A merged card is counted once", result(listOf(fresh, fresh), committed = listOf(fresh, fresh)).available == 1)
    val warnings = mutableListOf("a file was skipped")
    val frozen = result(listOf(fresh), warnings = warnings)
    warnings.clear()
    verify("Result owns its warning snapshot", frozen.warnings == listOf("a file was skipped"))
    println("COUNT|$count")
}
'''

production = [sources / "GameImportSummary.kt", sources / "LibrarySnapshot.kt"]
report = {
    "timestamp": datetime.now(timezone.utc).isoformat(),
    "scope": __doc__,
    "sourceSha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in production},
    "passed": False,
}
with tempfile.TemporaryDirectory(prefix="gostex-import-summary-") as folder:
    folder = Path(folder)
    (folder / "Model.kt").write_text(model)
    (folder / "Checks.kt").write_text(checks)
    compiled = subprocess.run([
        str(JAVA), "-cp", ":".join(map(str, COMPILER)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-classpath", str(COMPILER[1]), "-d", str(folder / "classes"),
        *map(str, production), str(folder / "Model.kt"), str(folder / "Checks.kt"),
    ], capture_output=True, text=True)
    report["compile"] = {"code": compiled.returncode, "stderr": compiled.stderr}
    if compiled.returncode == 0:
        run = subprocess.run([str(JAVA), "-cp", f"{folder / 'classes'}:{COMPILER[1]}", "tv.orbit.shield.ChecksKt"],
                             capture_output=True, text=True)
        report["run"] = {"code": run.returncode, "stdout": run.stdout, "stderr": run.stderr}
        report["passed"] = run.returncode == 0
out = ROOT / ".reports/game-import-summary-2026-11-14"
out.mkdir(parents=True, exist_ok=True)
(out / "model-check.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
print(json.dumps({"passed": report["passed"], "checks": report.get("run", {}).get("stdout", "").count("PASS|"),
                  "errors": report.get("run", report["compile"]).get("stderr", "")}))
raise SystemExit(0 if report["passed"] else 1)
