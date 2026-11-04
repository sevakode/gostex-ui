"""Exercise the production standalone Snake composition, looping geometry and food timing."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield/SnakeActPlan.kt"
CACHE = ROOT / ".tools/native/gradle-cache/caches/modules-2/files-2.1"
JAVA = ROOT / ".tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java"

CHECKS = r'''
package tv.orbit.shield
import kotlin.math.abs
import kotlin.math.floor

private var checked = 0
private fun verify(name: String, condition: Boolean) {
    check(condition) { name }; checked++; println("PASS|$name")
}
private fun comparable(track: SnakeTrack) = listOf(track.region, track.columns, track.rows,
    track.path.toList(), track.color, track.initialStep, track.cellsPerSecond, track.baseLength, track.foodStride)
private fun wrapped(distance: Double, size: Int) = (distance % size + size) % size
private fun point(track: SnakeTrack, distance: Double): Pair<Double, Double> {
    val wrapped = wrapped(distance, track.path.size)
    val before = wrapped.toInt(); val after = (before + 1) % track.path.size
    val fraction = wrapped - before
    val a = track.path[before]; val b = track.path[after]
    return (a % track.columns + (b % track.columns - a % track.columns) * fraction) to
        (a / track.columns + (b / track.columns - a / track.columns) * fraction)
}

fun main() {
    val seeds = (-128L..128L).toList() + listOf(Long.MIN_VALUE, Long.MAX_VALUE)
    var coverage = true; var adjacency = true; var containment = true; var distinct = true
    var emphasis = true; var determinism = true; var boundedLength = true; var finite = true
    var foodAhead = true; var eats = true; var grows = true; var continuity = true; var coordinates = true
    val signatures = mutableSetOf<List<List<Any>>>()
    for (seed in seeds) {
        val plan = SnakeActPlan.create(seed)
        determinism = determinism && plan.snakes.map(::comparable) == SnakeActPlan.create(seed).snakes.map(::comparable)
        signatures.add(plan.snakes.map(::comparable))
        emphasis = emphasis && plan.snakes.size == 3 && plan.snakes.first().region.width > plan.snakes.drop(1).maxOf { it.region.width } * 2
        for (a in plan.snakes.indices) for (b in a + 1 until plan.snakes.size) {
            val x = plan.snakes[a].region; val y = plan.snakes[b].region
            distinct = distinct && (x.left + x.width <= y.left || y.left + y.width <= x.left ||
                x.top + x.height <= y.top || y.top + y.height <= x.top)
        }
        for (track in plan.snakes) {
            val r = track.region
            containment = containment && r.left >= 0 && r.top >= 0 && r.left + r.width <= 1 && r.top + r.height <= 1
            coverage = coverage && track.path.sorted() == (0 until track.columns * track.rows).toList()
            for (index in track.path.indices) {
                val a = track.path[index]; val b = track.path[(index + 1) % track.path.size]
                adjacency = adjacency && abs(a % track.columns - b % track.columns) + abs(a / track.columns - b / track.columns) == 1
            }
            for (t in listOf(-1.0, 0.0, .1, 4.0, 28.0, 70.0, 1e8, Double.MAX_VALUE, Double.NaN, Double.POSITIVE_INFINITY)) {
                val length = track.bodyLength(t)
                boundedLength = boundedLength && length in track.baseLength..(track.baseLength + track.growthLimit) && length < track.path.size
                finite = finite && track.travel(t).isFinite() && track.headDistance(t).isFinite() && track.foodStep(t) in track.path.indices
            }
            for (step in 0..560) {
                val t = step / 20.0
                val distance = wrapped(track.foodStep(t) - wrapped(track.headDistance(t), track.path.size), track.path.size)
                foodAhead = foodAhead && distance <= track.foodStride + .00001
                val (x, y) = point(track, track.headDistance(t))
                coordinates = coordinates && x >= 0 && y >= 0 && x <= track.columns - 1 && y <= track.rows - 1
            }
            for (meal in 1..5) {
                val before = (meal * track.foodStride - .0001) / track.cellsPerSecond
                val after = (meal * track.foodStride + .0001) / track.cellsPerSecond
                eats = eats && track.foodStep(before) == (track.initialStep + meal * track.foodStride) % track.path.size &&
                    track.foodStep(after) == (track.initialStep + (meal + 1) * track.foodStride) % track.path.size
                grows = grows && track.bodyLength(after) == track.baseLength + minOf(meal * 2, track.growthLimit) &&
                    track.bodyLength(before) == track.baseLength + minOf((meal - 1) * 2, track.growthLimit)
            }
            val epsilon = .00001
            val (beforeX, beforeY) = point(track, track.headDistance(track.periodSeconds - epsilon))
            val (afterX, afterY) = point(track, track.headDistance(track.periodSeconds + epsilon))
            continuity = continuity && abs(beforeX - afterX) + abs(beforeY - afterY) < .001 &&
                track.foodStep(track.periodSeconds + epsilon) == track.foodStep(epsilon)
        }
    }
    verify("Each classic Snake loop visits the whole board exactly once", coverage)
    verify("Every movement including last-to-first is cardinal-adjacent without teleportation", adjacency)
    verify("Every board stays inside the normalized composition", containment)
    verify("The three boards never overlap, including reflected layouts", distinct)
    verify("Composition retains a large protagonist and two smaller secondary snakes", emphasis)
    verify("The same seed reproduces all layout, palette, route and starting decisions", determinism)
    verify("Different seeds produce varied route and layout compositions", signatures.size >= 128)
    verify("Bodies remain bounded and shorter than their loops at every tested time", boundedLength)
    verify("Invalid and extremely large times cannot produce nonfinite or out-of-bounds geometry", finite)
    verify("Exactly one food target remains ahead of each snake", foodAhead)
    verify("Food moves to the next target only when the head reaches the current meal", eats)
    verify("Each meal grows the body by two cells until its fixed technical cap", grows)
    verify("Long-running time loops preserve head position continuity and food phase", continuity)
    verify("Interpolated heads stay within their own board throughout a 28-second act", coordinates)
    println("COUNT|$checked")
    println("SEEDS|${seeds.size}")
}
'''


def jar(group, artifact, version):
    return next((CACHE / group / artifact / version).glob("*/*.jar"))


compiler = [jar("org.jetbrains.kotlin", artifact, version) for artifact, version in [
    ("kotlin-compiler-embeddable", "2.4.21"), ("kotlin-stdlib", "2.4.21"),
    ("kotlin-script-runtime", "2.4.21"), ("kotlin-reflect", "1.6.10"),
]] + [jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.9.0"),
      jar("org.jetbrains", "annotations", "13.0")]
report = {"timestampUtc": datetime.now(timezone.utc).isoformat(),
    "scope": "Actual pure SnakeActPlan compiled and executed on the JVM, including loop and food boundaries.",
    "source": str(SOURCE), "sourceSha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
    "notCovered": ["Android Canvas output", "On-device frame rate", "Visual composition acceptance"], "passed": False}
with tempfile.TemporaryDirectory(prefix="gostex-snake-act-") as folder:
    temporary = Path(folder)
    checks = temporary / "SnakeActChecks.kt"
    checks.write_text(CHECKS)
    classes = temporary / "classes"
    compiled = subprocess.run([str(JAVA), "-cp", ":".join(map(str, compiler)),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
        "-classpath", str(compiler[1]), "-d", str(classes), str(SOURCE), str(checks)], capture_output=True, text=True)
    report["compile"] = {"returncode": compiled.returncode, "stderr": compiled.stderr}
    if compiled.returncode == 0:
        run = subprocess.run([str(JAVA), "-cp", f"{classes}:{compiler[1]}", "tv.orbit.shield.SnakeActChecksKt"], capture_output=True, text=True)
        report["run"] = {"returncode": run.returncode, "stdout": run.stdout, "stderr": run.stderr}
        report["checks"] = [line.removeprefix("PASS|") for line in run.stdout.splitlines() if line.startswith("PASS|")]
        report["passed"] = run.returncode == 0 and len(report["checks"]) == 14
output = ROOT / ".reports/snake-act-checks.json"
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
print(json.dumps({"passed": report["passed"], "checks": len(report.get("checks", [])), "report": str(output)}))
if not report["passed"]:
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(1)
