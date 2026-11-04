"""Check authored painting treatments and independent program acts on the JVM."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield/ArtEffectSequence.kt"
OPTIONS = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield/ArtOptions.kt"
DIRECTION = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield/ArtDirection.kt"
CATALOG = ROOT / "native-launcher/app/src/main/assets/art/catalog.json"
CACHE = ROOT / ".tools/native/gradle-cache/caches/modules-2/files-2.1"
JAVA = ROOT / ".tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java"

options = OPTIONS.read_text()
enum_source = options[options.index("internal enum class RevealStyle("):options.index("internal data class ArtOptions(")]
resource_ids = sorted(set(re.findall(r"R\.string\.(\w+)", enum_source)))
resource_stub = "internal object R { object string {\n" + "\n".join(
    f"const val {name} = {index}" for index, name in enumerate(resource_ids)
) + "\n} }\n"

catalog = json.loads(CATALOG.read_text())["artworks"]
profiles = {"painting": [], "snakes": []}
for artwork in catalog:
    direction = artwork["direction"]
    kind = direction["kind"]
    treatments = []
    for treatment in direction["treatments"]:
        treatments.append("ArtTreatment(RevealStyle." + treatment["style"].upper() + ", " +
            str(treatment.get("columns", 160)) + ", " + str(treatment.get("rows", 90)) + ", " +
            str(treatment.get("retainPixelGrid", False)).lower() + ")")
    profiles[kind].append(json.dumps(artwork["id"]) + " to ArtDirection(ArtActKind." + kind.upper() +
        ", listOf(" + ", ".join(treatments) + "))")

CHECKS = r'''
package tv.orbit.shield

private var checked = 0
private fun verify(name: String, condition: Boolean) {
    check(condition) { name }; checked++; println("PASS|$name")
}

fun main() {
    val paintings = listOf(CATALOG_PAINTINGS)
    val acts = listOf(CATALOG_ACTS)
    val calm = setOf(RevealStyle.SCENE, RevealStyle.INK, RevealStyle.TILES, RevealStyle.FOCUS, RevealStyle.STILL)
    val seeds = (-32L..32L).toList() + listOf(Long.MIN_VALUE, Long.MAX_VALUE)
    val scenes = (-32L..127L).toList() + listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE)
    var allowed = true; var calmAllowed = true; var complete = true; var deterministic = true
    var varied = true; var randomAccess = true
    for ((_, direction) in paintings) {
        val seen = mutableSetOf<ArtTreatment>()
        val orders = mutableSetOf<List<ArtTreatment>>()
        val calmTreatments = direction.treatments.filter { it.style in calm }.ifEmpty { listOf(ArtTreatment()) }
        for (seed in seeds) {
            val sequence = ArtEffectSequence(seed)
            val duplicate = ArtEffectSequence(seed)
            orders += (0L..15L).map { sequence.at(RevealStyle.MIXED, it, direction) }
            for (scene in scenes) {
                val chosen = sequence.at(RevealStyle.MIXED, scene, direction)
                seen += chosen
                allowed = allowed && chosen in direction.treatments
                calmAllowed = calmAllowed && sequence.at(RevealStyle.CALM, scene, direction) in calmTreatments
                deterministic = deterministic && chosen == duplicate.at(RevealStyle.MIXED, scene, direction) &&
                    sequence.at(RevealStyle.CALM, scene, direction) == duplicate.at(RevealStyle.CALM, scene, direction)
            }
            val remembered = sequence.at(RevealStyle.MIXED, 5, direction)
            sequence.at(RevealStyle.CALM, Long.MAX_VALUE, direction)
            paintings.reversed().forEach { (_, other) -> sequence.at(RevealStyle.MIXED, 19, other) }
            randomAccess = randomAccess && sequence.at(RevealStyle.MIXED, 5, direction) == remembered
        }
        complete = complete && seen == direction.treatments.toSet()
        varied = varied && (direction.treatments.size == 1 || orders.size >= 16)
    }
    verify("Auto returns only full authored treatments from each real catalog painting, including grid and retention", allowed)
    verify("Every authored treatment is reachable rather than one fixed presentation per painting", complete)
    verify("Calm selects only calm or still treatments authored for that painting", calmAllowed)
    verify("Same seed, scene and direction reproduce the same treatment, including extreme Long indices", deterministic)
    verify("Different session seeds produce varied treatment orders for every multi-treatment painting", varied)
    verify("Out-of-order requests and visits to other paintings do not consume a shared cursor", randomAccess)

    val sequence = ArtEffectSequence(47)
    val movingOnly = ArtDirection(treatments = listOf(
        ArtTreatment(RevealStyle.BLOCKS, 32, 18, true), ArtTreatment(RevealStyle.WEAVE, 64, 36)))
    verify("A moving-only painting gets the calm scene fallback when Calm is selected",
        scenes.all { sequence.at(RevealStyle.CALM, it, movingOnly) == ArtTreatment() })
    val still = ArtTreatment(RevealStyle.STILL, 20, 12, true)
    val stillOnly = ArtDirection(treatments = listOf(still))
    verify("A still-only authored painting keeps its exact pixel treatment in both automatic programs",
        scenes.all { sequence.at(RevealStyle.MIXED, it, stillOnly) == still && sequence.at(RevealStyle.CALM, it, stillOnly) == still })
    val sizeVariants = ArtDirection(treatments = listOf(
        ArtTreatment(RevealStyle.TILES, 20, 12, true), ArtTreatment(RevealStyle.TILES, 160, 90, false)))
    verify("Pixel scale and retained grid remain authored treatment data rather than a global style setting",
        (0L..1023L).map { sequence.at(RevealStyle.MIXED, it, sizeVariants) }.toSet() == sizeVariants.treatments.toSet())
    verify("Legacy concrete painting choices retain their selected style",
        ArtTreatment.PAINTING_STYLES.all { style -> scenes.all { sequence.at(style, it, movingOnly).style == style } })
    verify("Legacy styles reuse authored pixel scale and retained-grid intent when a matching treatment exists",
        paintings.all { (_, direction) -> direction.treatments.map { it.style }.distinct().all { style ->
            scenes.all { scene -> sequence.at(style, scene, direction) == direction.treatments.first { it.style == style } }
        } } && scenes.all { sequence.at(RevealStyle.TILES, it, sizeVariants) == sizeVariants.treatments.first() })
    val noMotionProfile = ArtDirection(treatments = listOf(ArtTreatment(RevealStyle.SCENE)))
    verify("Legacy focus and moving styles without an authored match use a bounded 32 by 18 grid",
        listOf(RevealStyle.FOCUS, RevealStyle.BLOCKS, RevealStyle.WEAVE).all { style -> scenes.all {
            val treatment = sequence.at(style, it, noMotionProfile)
            treatment.style == style && treatment.columns == 32 && treatment.rows == 18 &&
                treatment.columns * treatment.rows <= 576 && !treatment.retainPixelGrid
        } })
    verify("Legacy snake preference never turns a painting into a snake reveal",
        scenes.all { sequence.at(RevealStyle.SNAKES, it, movingOnly) == ArtTreatment(RevealStyle.SCENE) })
    verify("Standalone catalog acts reject every painting-reveal request",
        acts.isNotEmpty() && acts.all { (_, direction) -> RevealStyle.entries.all {
            runCatching { sequence.at(it, 0, direction) }.isFailure
        } })

    verify("Group styles and snake acts cannot be constructed as painting treatments",
        listOf(RevealStyle.MIXED, RevealStyle.CALM, RevealStyle.SNAKES).all { runCatching { ArtTreatment(it) }.isFailure })
    verify("Art grids reject invalid dimensions and excess cells while accepting budget boundaries",
        listOf(0 to 18, 1 to 18, 32 to 1, 257 to 2, 2 to 257, 160 to 91, Int.MAX_VALUE to Int.MAX_VALUE).all {
            runCatching { ArtTreatment(columns = it.first, rows = it.second) }.isFailure
        } && listOf(2 to 2, 160 to 90, 256 to 2, 2 to 256).all {
            runCatching { ArtTreatment(columns = it.first, rows = it.second) }.isSuccess
        })
    verify("Directions reject empty or duplicate painting profiles and painting data inside an independent act",
        runCatching { ArtDirection(treatments = emptyList()) }.isFailure &&
            runCatching { ArtDirection(treatments = listOf(ArtTreatment(), ArtTreatment())) }.isFailure &&
            runCatching { ArtDirection(treatments = (2..10).map { ArtTreatment(columns = it, rows = 2) }) }.isFailure &&
            runCatching { ArtDirection(ArtActKind.SNAKES) }.isFailure &&
            runCatching { ArtDirection(ArtActKind.SNAKES, emptyList()) }.isSuccess)

    val firstSlots = (0L..7L).map { ArtProgramPosition.at(it, true) }
    verify("Independent acts occur after three paintings instead of replacing a painting reveal",
        firstSlots == listOf(ArtProgramPosition(0, null), ArtProgramPosition(1, null), ArtProgramPosition(2, null), ArtProgramPosition(2, 0),
            ArtProgramPosition(3, null), ArtProgramPosition(4, null), ArtProgramPosition(5, null), ArtProgramPosition(5, 1)))
    val enabled = (0L until 10000L).map { ArtProgramPosition.at(it, true) }
    val disabled = (0L until 7500L).map { ArtProgramPosition.at(it, false) }
    verify("Interleaving acts preserves every painting index without duplicates or omissions",
        enabled.filter { it.interlude == null }.map { it.painting } == disabled.map { it.painting } &&
            disabled.all { it.interlude == null } && disabled.map { it.painting } == (0L until 7500L).toList())
    verify("Standalone act indices remain contiguous and independent of painting indices",
        enabled.mapNotNull { it.interlude } == (0L until 2500L).toList())
    val lateScenes = (Long.MAX_VALUE - 1000)..Long.MAX_VALUE
    verify("Program positions stay nonnegative and monotonic near Long.MAX_VALUE without overflow",
        lateScenes.map { ArtProgramPosition.at(it, true) }.zipWithNext().all { (a, b) ->
            a.painting >= 0 && b.painting >= a.painting && b.painting - a.painting <= 1 &&
                (a.interlude == null || a.interlude >= 0) && (b.interlude == null || b.interlude >= 0)
        } && lateScenes.all { ArtProgramPosition.at(it, false) == ArtProgramPosition(it, null) })
    verify("Negative program positions are rejected explicitly in both interlude settings",
        listOf(-1L, -4L, Long.MIN_VALUE).all { scene -> listOf(false, true).all {
            runCatching { ArtProgramPosition.at(scene, it) }.isFailure
        } })

    verify("All current saved style ids retain their exact identity before program selection",
        RevealStyle.entries.all { RevealStyle.fromSavedId(it.id) == it })
    verify("Retired ids remain readable without restoring snake painting reveals",
        RevealStyle.fromSavedId("flow") == RevealStyle.SNAKES &&
            RevealStyle.fromSavedId("rain") == RevealStyle.BLOCKS &&
            RevealStyle.fromSavedId("vortex") == RevealStyle.WEAVE &&
            sequence.at(RevealStyle.fromSavedId("flow"), 0, movingOnly).style == RevealStyle.SCENE)
    verify("Missing and malformed saved ids still fall back to Auto",
        listOf(null, "", " ", "unknown", "MIXED", "snakes ", "null").all { RevealStyle.fromSavedId(it) == RevealStyle.MIXED })
    println("COUNT|$checked")
    println("SEEDS|${seeds.size}")
    println("PAINTINGS|${paintings.size}")
    println("ACTS|${acts.size}")
}
'''.replace("CATALOG_PAINTINGS", ",\n".join(profiles["painting"])).replace("CATALOG_ACTS", ",\n".join(profiles["snakes"]))


def jar(group, artifact, version):
    return next((CACHE / group / artifact / version).glob("*/*.jar"))


compiler = [jar("org.jetbrains.kotlin", artifact, version) for artifact, version in [
    ("kotlin-compiler-embeddable", "2.4.21"), ("kotlin-stdlib", "2.4.21"),
    ("kotlin-script-runtime", "2.4.21"), ("kotlin-reflect", "1.6.10"),
]] + [jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.9.0"),
      jar("org.jetbrains", "annotations", "13.0")]
report = {
    "timestampUtc": datetime.now(timezone.utc).isoformat(),
    "scope": "Unmodified production ArtEffectSequence.kt and ArtDirection.kt with production RevealStyle enum and authored profiles from the actual art catalog. Tests cover treatment selection and independent act scheduling, not the earlier global eight-effect cycle.",
    "source": str(SOURCE),
    "sourceSha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
    "enumSha256": hashlib.sha256(enum_source.encode()).hexdigest(),
    "directionSha256": hashlib.sha256(DIRECTION.read_bytes()).hexdigest(),
    "catalogSha256": hashlib.sha256(CATALOG.read_bytes()).hexdigest(),
    "notCovered": ["Android catalog parsing", "Renderer integration or visual quality", "Preference storage and UI focus", "Android lifecycle or frame timing"],
    "passed": False,
}
with tempfile.TemporaryDirectory(prefix="gostex-art-effect-sequence-") as folder:
    temporary = Path(folder)
    model = temporary / "RevealStyle.kt"
    model.write_text("package tv.orbit.shield\n" + resource_stub + enum_source)
    checks = temporary / "ArtEffectSequenceChecks.kt"
    checks.write_text(CHECKS)
    classes = temporary / "classes"
    compiled = subprocess.run([str(JAVA), "-cp", ":".join(map(str, compiler)),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
        "-classpath", str(compiler[1]), "-d", str(classes), str(SOURCE), str(DIRECTION), str(model), str(checks)],
        capture_output=True, text=True)
    report["compile"] = {"returncode": compiled.returncode, "stderr": compiled.stderr}
    if compiled.returncode == 0:
        run = subprocess.run([str(JAVA), "-cp", f"{classes}:{compiler[1]}",
            "tv.orbit.shield.ArtEffectSequenceChecksKt"], capture_output=True, text=True)
        report["run"] = {"returncode": run.returncode, "stdout": run.stdout, "stderr": run.stderr}
        report["checks"] = [line.removeprefix("PASS|") for line in run.stdout.splitlines() if line.startswith("PASS|")]
        for label, key in [("SEEDS|", "seeds"), ("PAINTINGS|", "paintings"), ("ACTS|", "acts")]:
            report[key] = next((int(line.removeprefix(label)) for line in run.stdout.splitlines() if line.startswith(label)), 0)
        report["passed"] = run.returncode == 0 and len(report["checks"]) == 25

output = ROOT / ".reports/art-effect-sequence-checks.json"
output.parent.mkdir(exist_ok=True)
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
print(json.dumps({"passed": report["passed"], "checks": len(report.get("checks", [])),
    "seeds": report.get("seeds", 0), "paintings": report.get("paintings", 0),
    "acts": report.get("acts", 0), "report": str(output)}))
if not report["passed"]:
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(1)
