"""Check production content-direction invariants and reject malformed gallery metadata."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

from check_art_catalog import WORLDS, validate

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield/ArtDirection.kt"
OPTIONS = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield/ArtOptions.kt"
CATALOG = ROOT / "native-launcher/app/src/main/assets/art/catalog.json"
CACHE = ROOT / ".tools/native/gradle-cache/caches/modules-2/files-2.1"
JAVA = ROOT / ".tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java"

options = OPTIONS.read_text()
enum_source = options[options.index("internal enum class RevealStyle("):options.index("internal data class ArtOptions(")]
resource_ids = sorted(set(re.findall(r"R\.string\.(\w+)", enum_source)))
resource_stub = "internal object R { object string {\n" + "\n".join(
    f"const val {name} = {index}" for index, name in enumerate(resource_ids)
) + "\n} }\n"

CHECKS = r'''
package tv.orbit.shield

private var checked = 0
private fun verify(name: String, condition: Boolean) {
    check(condition) { name }; checked++; println("PASS|$name")
}
private fun rejected(block: () -> Unit): Boolean = runCatching(block).isFailure

fun main() {
    verify("Default direction remains a painting with a valid authored treatment",
        ArtDirection().kind == ArtActKind.PAINTING && ArtDirection().treatments == listOf(ArtTreatment()))
    verify("Act ids round-trip and unknown ids are rejected without guessing",
        ArtActKind.entries.all { ArtActKind.fromId(it.id) == it } &&
            listOf(null, "", "SNAKES", "unknown").all { ArtActKind.fromId(it) == null })
    verify("Snake is an independent act without a painting reveal",
        ArtDirection(ArtActKind.SNAKES, emptyList()).isInterlude && !ArtDirection().isInterlude)
    verify("Painting requires a presentation; snake cannot borrow one",
        rejected { ArtDirection(treatments = emptyList()) } &&
            rejected { ArtDirection(ArtActKind.SNAKES, listOf(ArtTreatment())) })
    verify("Group preferences and snake acts cannot appear in painting treatments",
        listOf(RevealStyle.MIXED, RevealStyle.CALM, RevealStyle.SNAKES).all {
            rejected { ArtTreatment(it) }
        })
    verify("All concrete painting styles including static art remain valid",
        ArtTreatment.PAINTING_STYLES.all { runCatching { ArtTreatment(it) }.isSuccess })
    verify("Malformed or enormous grids are rejected before allocation",
        listOf(0 to 18, 1 to 18, -1 to 90, 32 to 0, 32 to 257,
            257 to 32, 256 to 256, Int.MAX_VALUE to Int.MAX_VALUE).all { (columns, rows) ->
            rejected { ArtTreatment(columns = columns, rows = rows) }
        })
    verify("Small grids and the existing detailed scene grid fit the rendering budget",
        listOf(2 to 2, 20 to 12, 24 to 14, 32 to 18, 64 to 36, 160 to 90, 256 to 2).all { (columns, rows) ->
            runCatching { ArtTreatment(columns = columns, rows = rows) }.isSuccess
        })
    verify("A treatment may preserve deliberately coarse artwork through its hold phase",
        ArtTreatment(RevealStyle.BLOCKS, 64, 36, true).retainPixelGrid && !ArtTreatment().retainPixelGrid)
    verify("Duplicate treatments cannot accidentally weight one presentation twice",
        rejected { ArtDirection(treatments = listOf(ArtTreatment(), ArtTreatment())) })
    verify("Distinct pixel compositions using the same technique are allowed",
        ArtDirection(treatments = listOf(ArtTreatment(RevealStyle.FOCUS, 20, 12),
            ArtTreatment(RevealStyle.FOCUS, 32, 18))).treatments.size == 2)
    verify("Authored treatment lists have a finite budget",
        rejected { ArtDirection(treatments = (2..10).map { ArtTreatment(columns = it, rows = 2) }) })
    println("COUNT|$checked")
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
    "scope": "Production ArtDirection.kt JVM invariants, shipped content profiles, and invalid catalog fixtures.",
    "sourceSha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
    "catalogSha256": hashlib.sha256(CATALOG.read_bytes()).hexdigest(),
    "notCovered": ["Android JSONObject parser runtime", "Animation appearance or frame timing", "Device preferences"],
    "checks": [], "passed": False,
}
with tempfile.TemporaryDirectory(prefix="gostex-art-direction-") as folder:
    temporary = Path(folder)
    model = temporary / "RevealStyle.kt"
    model.write_text("package tv.orbit.shield\n" + resource_stub + enum_source)
    checks = temporary / "ArtDirectionChecks.kt"
    checks.write_text(CHECKS)
    classes = temporary / "classes"
    compiled = subprocess.run([str(JAVA), "-cp", ":".join(map(str, compiler)),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
        "-classpath", str(compiler[1]), "-d", str(classes), str(SOURCE), str(model), str(checks)],
        capture_output=True, text=True)
    report["compile"] = {"returncode": compiled.returncode, "stderr": compiled.stderr}
    if compiled.returncode == 0:
        run = subprocess.run([str(JAVA), "-cp", f"{classes}:{compiler[1]}",
            "tv.orbit.shield.ArtDirectionChecksKt"], capture_output=True, text=True)
        report["run"] = {"returncode": run.returncode, "stdout": run.stdout, "stderr": run.stderr}
        report["checks"] += [line.removeprefix("PASS|") for line in run.stdout.splitlines() if line.startswith("PASS|")]
        if run.returncode == 0:
            catalog = json.loads(CATALOG.read_text())
            errors, count = validate(CATALOG)
            assert not errors, errors
            report["checks"].append("Shipped catalog has valid artwork identities, sources, languages and presentation data")
            paintings = [entry for entry in catalog["artworks"] if entry["direction"]["kind"] == "painting"]
            snakes = [entry for entry in catalog["artworks"] if entry["direction"]["kind"] == "snakes"]
            assert len(paintings) >= 8 and len(snakes) == 1
            assert {entry["world"] for entry in catalog["artworks"]} == WORLDS
            assert snakes[0]["world"] == "snake" and snakes[0]["direction"]["treatments"] == []
            report["checks"].append("The catalog contains a separate Snake act alongside eight distinct paintings")
            assert all(any(t["style"] == "still" for t in entry["direction"]["treatments"]) for entry in paintings)
            report["checks"].append("Every painting can appear as a ready static picture")
            assert len({json.dumps(entry["direction"], sort_keys=True) for entry in paintings}) >= 5
            assert any(t.get("retainPixelGrid") for entry in paintings for t in entry["direction"]["treatments"])
            report["checks"].append("Pictures have different curated presentations including intentional coarse artwork")
            fixtures = [
                ("missing direction", lambda entry: entry.pop("direction")),
                ("unknown act", lambda entry: entry["direction"].update(kind="particles")),
                ("no painting treatments", lambda entry: entry["direction"].update(treatments=[])),
                ("snake as reveal", lambda entry: entry["direction"]["treatments"][0].update(style="snakes")),
                ("group as reveal", lambda entry: entry["direction"]["treatments"][0].update(style="mixed")),
                ("decimal grid", lambda entry: entry["direction"]["treatments"][0].update(columns=32.5)),
                ("boolean grid", lambda entry: entry["direction"]["treatments"][0].update(columns=True)),
                ("over budget grid", lambda entry: entry["direction"]["treatments"][0].update(columns=256, rows=256)),
                ("nonboolean retained grid", lambda entry: entry["direction"]["treatments"][0].update(retainPixelGrid="true")),
                ("duplicate treatment", lambda entry: entry["direction"]["treatments"].append(entry["direction"]["treatments"][0].copy())),
                ("wrong snake world", lambda entry: entry.update(direction={"kind":"snakes", "treatments":[]})),
            ]
            for name, mutate in fixtures:
                damaged = json.loads(CATALOG.read_text())
                mutate(damaged["artworks"][0])
                fixture = temporary / "invalid.json"
                fixture.write_text(json.dumps(damaged))
                assert validate(fixture)[0], f"Invalid metadata accepted: {name}"
                report["checks"].append(f"Catalog rejects {name}")
            all_acts = {"schemaVersion": 1, "artworks": snakes}
            fixture.write_text(json.dumps(all_acts))
            assert "catalog must contain at least one painting" in validate(fixture)[0]
            report["checks"].append("An all-interlude catalog is rejected before playlist selection")
            report["entries"] = count
            report["invalidFixtures"] = len(fixtures)
            report["passed"] = True

output = ROOT / ".reports/art-direction-checks.json"
output.parent.mkdir(exist_ok=True)
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
print(json.dumps({"passed": report["passed"], "checks": len(report["checks"]),
    "entries": report.get("entries"), "report": str(output)}))
if not report["passed"]:
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(1)
