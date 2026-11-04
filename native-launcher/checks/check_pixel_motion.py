"""Compile the actual pure motion planner and verify geometry and timing invariants on the JVM."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield/PixelMotionPlan.kt"
CACHE = ROOT / ".tools/native/gradle-cache/caches/modules-2/files-2.1"
JAVA = ROOT / ".tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java"

CHECKS = r'''
package tv.orbit.shield
import kotlin.math.abs

private var checked = 0
private var plans = 0
private var sampledTiles = 0L
private fun verify(name: String, condition: Boolean) {
    check(condition) { name }; checked++; println("PASS|$name")
}
private fun equivalent(a: PixelMotionPlan, b: PixelMotionPlan) =
    a.kind == b.kind && a.columns == b.columns && a.rows == b.rows && a.tiles == b.tiles &&
        a.paths.size == b.paths.size && a.paths.indices.all { a.paths[it].contentEquals(b.paths[it]) }
private fun parameters(t: PixelMotionTile) =
    listOf(t.start, t.duration, t.sourceX, t.sourceY, t.arcX, t.arcY)

fun main() {
    val dimensions = listOf(2 to 2, 2 to 3, 3 to 2, 3 to 3, 7 to 5, 32 to 18, 31 to 17, 64 to 36)
    var coverage = true; var finite = true; var endpoints = true; var monotonic = true
    var adjacency = true; var snakePartition = true; var snakeTiming = true; var nonSnakePaths = true
    var bounded = true; var placement = true
    for (kind in PixelMotionKind.entries) for (seed in -16L..47L) for ((columns, rows) in dimensions) {
        val plan = PixelMotionPlan.create(kind, seed, columns, rows)
        plans++
        coverage = coverage && plan.tiles.size == columns * rows && plan.tiles.map { it.y * columns + it.x }.toSet().size == columns * rows
        coverage = coverage && plan.tiles.indices.all { plan.tiles[it].x == it % columns && plan.tiles[it].y == it / columns }
        for (tile in plan.tiles) {
            sampledTiles++
            finite = finite && parameters(tile).all { it.isFinite() } && tile.duration > 0.0
            bounded = bounded && tile.start in 0.0..0.78 && tile.start + tile.duration <= 1.0 &&
                abs(tile.sourceX) <= maxOf(8.0, columns * .28) && abs(tile.sourceY) <= maxOf(8.0, rows * .28) &&
                abs(tile.arcX) <= .9 && abs(tile.arcY) <= .9
            endpoints = endpoints && tile.phase(0.0) == 0.0 && tile.phase(1.0) == 1.0 &&
                tile.phase(-1.0) == 0.0 && tile.phase(2.0) == 1.0 && tile.phase(Double.NaN) == 0.0
            var last = 0.0
            for (step in 0..12) {
                val phase = tile.phase(step / 12.0)
                monotonic = monotonic && phase.isFinite() && phase in last..1.0
                last = phase
            }
            val finished = tile.phase(1.0)
            placement = placement && tile.sourceX * (1.0 - finished) + tile.arcX * 4 * finished * (1.0 - finished) == 0.0 &&
                tile.sourceY * (1.0 - finished) + tile.arcY * 4 * finished * (1.0 - finished) == 0.0
        }
        if (kind == PixelMotionKind.SNAKES) {
            snakePartition = snakePartition && plan.paths.size == 4 && plan.paths.all { it.isNotEmpty() } &&
                plan.paths.flatMap { it.asIterable() }.sorted() == plan.tiles.indices.toList()
            for ((pathIndex, path) in plan.paths.withIndex()) {
                for ((step, cell) in path.withIndex()) {
                    val tile = plan.tiles[cell]
                    snakePartition = snakePartition && tile.pathIndex == pathIndex && tile.step == step
                    if (step > 0) {
                        val before = plan.tiles[path[step - 1]]
                        adjacency = adjacency && abs(before.x - tile.x) + abs(before.y - tile.y) == 1
                        snakeTiming = snakeTiming && before.start < tile.start
                    }
                }
            }
        } else nonSnakePaths = nonSnakePaths && plan.paths.isEmpty() && plan.tiles.all { it.pathIndex == -1 && it.step == -1 }
    }
    verify("All dimensions and seeds have complete row-major coverage without duplicate tiles", coverage)
    verify("Every parameter and duration is finite and valid", finite)
    verify("Every tile starts empty and is fully settled by progress one, including clamped input", endpoints)
    verify("Coverage phases increase monotonically without blinking", monotonic)
    verify("Motion offsets and timing remain bounded", bounded)
    verify("Every position finishes at its exact image cell without a residual arc", placement)
    verify("Four snake paths partition the image exactly and expose correct step indices", snakePartition)
    verify("Every pair of consecutive snake cells is cardinal-adjacent, including thin rectangles", adjacency)
    verify("Snake progression follows its actual route with strictly increasing arrival times", snakeTiming)
    verify("Blocks and weave do not contain accidental snake paths", nonSnakePaths)

    verify("Seeded plans reproduce exact geometry and timing",
        PixelMotionKind.entries.all { kind -> (-32L..32L).all { seed ->
            equivalent(PixelMotionPlan.create(kind, seed), PixelMotionPlan.create(kind, seed))
        } })
    verify("Changing seed changes each family's composition rather than reusing one fixed plan",
        PixelMotionKind.entries.all { kind -> (0L..31L).map { seed ->
            PixelMotionPlan.create(kind, seed).tiles.hashCode()
        }.toSet().size >= 8 })

    var blocksCoherent = true; var clusterSizes = true
    for (seed in 0L..63L) {
        val plan = PixelMotionPlan.create(PixelMotionKind.BLOCKS, seed)
        val groups = plan.tiles.groupBy(::parameters)
        blocksCoherent = blocksCoherent && groups.size in 20..72
        for (cluster in groups.values) {
            val left = cluster.minOf { it.x }; val right = cluster.maxOf { it.x }
            val top = cluster.minOf { it.y }; val bottom = cluster.maxOf { it.y }
            blocksCoherent = blocksCoherent && cluster.size == (right - left + 1) * (bottom - top + 1)
            clusterSizes = clusterSizes && cluster.size in 2..24
        }
    }
    verify("Blocks move as contiguous complete rectangles with shared timing and trajectories", blocksCoherent)
    verify("Default block effect uses large connected pieces, including partial edge pieces", clusterSizes)

    var weaveCoherent = true; var horizontalSeen = false; var verticalSeen = false
    for (seed in 0L..63L) {
        val plan = PixelMotionPlan.create(PixelMotionKind.WEAVE, seed)
        val horizontal = plan.tiles.first().sourceX != 0.0
        horizontalSeen = horizontalSeen || horizontal; verticalSeen = verticalSeen || !horizontal
        val bands = plan.tiles.groupBy(::parameters).values.sortedBy { cluster -> cluster.minOf { if (horizontal) it.y else it.x } }
        var previousTravel = 0.0
        for (cluster in bands) {
            val left = cluster.minOf { it.x }; val right = cluster.maxOf { it.x }
            val top = cluster.minOf { it.y }; val bottom = cluster.maxOf { it.y }
            weaveCoherent = weaveCoherent && cluster.size == (right - left + 1) * (bottom - top + 1) &&
                (if (horizontal) left == 0 && right == plan.columns - 1 else top == 0 && bottom == plan.rows - 1)
            val travel = if (horizontal) cluster.first().sourceX else cluster.first().sourceY
            weaveCoherent = weaveCoherent && travel != 0.0 && (previousTravel == 0.0 || previousTravel * travel < 0.0)
            previousTravel = travel
        }
    }
    verify("Weave moves entire image-spanning ribbons from alternating sides", weaveCoherent)
    verify("Weave seed variations contain both horizontal and vertical arrangements", horizontalSeen && verticalSeen)

    verify("Technical dimension limits reject empty, degenerate and oversized grids",
        listOf(0 to 18, 1 to 18, 32 to 0, 32 to 1, -1 to 18, 257 to 18, 32 to 257).all { (w, h) ->
            runCatching { PixelMotionPlan.create(PixelMotionKind.SNAKES, 0, w, h) }.isFailure
        })
    val maximum = PixelMotionPlan.create(PixelMotionKind.SNAKES, Long.MIN_VALUE, 256, 256)
    verify("Largest supported grid and extreme seed remain bounded and completely covered",
        maximum.tiles.size == 65536 && maximum.paths.sumOf { it.size } == 65536 && maximum.tiles.all { it.phase(1.0) == 1.0 })
    println("COUNT|$checked")
    println("PLANS|$plans")
    println("TILES|$sampledTiles")
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
    "scope": "Unmodified production PixelMotionPlan.kt compiled and exercised on the JVM.",
    "source": str(SOURCE),
    "sourceSha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
    "notCovered": ["Android Canvas rendering", "On-device frame performance", "Visual composition or user comfort", "Preference persistence"],
    "passed": False,
}
with tempfile.TemporaryDirectory(prefix="gostex-pixel-motion-") as folder:
    temporary = Path(folder)
    checks = temporary / "PixelMotionChecks.kt"
    checks.write_text(CHECKS)
    classes = temporary / "classes"
    compiled = subprocess.run([str(JAVA), "-cp", ":".join(map(str, compiler)),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
        "-classpath", str(compiler[1]), "-d", str(classes), str(SOURCE), str(checks)],
        capture_output=True, text=True)
    report["compile"] = {"returncode": compiled.returncode, "stderr": compiled.stderr}
    if compiled.returncode == 0:
        run = subprocess.run([str(JAVA), "-cp", f"{classes}:{compiler[1]}",
            "tv.orbit.shield.PixelMotionChecksKt"], capture_output=True, text=True)
        report["run"] = {"returncode": run.returncode, "stdout": run.stdout, "stderr": run.stderr}
        report["checks"] = [line.removeprefix("PASS|") for line in run.stdout.splitlines() if line.startswith("PASS|")]
        for label, key in [("PLANS|", "plans"), ("TILES|", "sampledTiles")]:
            report[key] = next((int(line.removeprefix(label)) for line in run.stdout.splitlines() if line.startswith(label)), 0)
        report["passed"] = run.returncode == 0 and len(report["checks"]) == 18

output = ROOT / ".reports/pixel-motion-checks.json"
output.parent.mkdir(exist_ok=True)
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
print(json.dumps({"passed": report["passed"], "checks": len(report.get("checks", [])),
    "plans": report.get("plans", 0), "sampledTiles": report.get("sampledTiles", 0), "report": str(output)}))
if not report["passed"]:
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(1)
