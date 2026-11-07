"""Check bandwidth estimates without treating file size as a hardware capability."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / '.tools/native/gradle-cache/caches/modules-2/files-2.1'
JAVA = ROOT / '.tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java'
SOURCE = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/MoviePlaybackEstimate.kt'

def jar(group, artifact, version):
    return next((CACHE / group / artifact / version).glob('*/*.jar'))

compiler = [jar('org.jetbrains.kotlin', a, v) for a, v in [
    ('kotlin-compiler-embeddable', '2.4.21'), ('kotlin-stdlib', '2.4.21'),
    ('kotlin-script-runtime', '2.4.21'), ('kotlin-reflect', '1.6.10'),
]] + [jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.9.0'), jar('org.jetbrains', 'annotations', '13.0')]
checks = '''package tv.orbit.shield
fun main() {
    check(MoviePlaybackEstimator.estimate(0, 10, null) == null)
    check(MoviePlaybackEstimator.estimate(1, 0, null) == null)
    val bytes = 4_500_000_000L; val duration = 7200L
    val avg = MoviePlaybackEstimator.estimate(bytes, duration, null)!!
    check(avg.averageBitsPerSecond == 5_000_000.0 && avg.assessment == PeerSpeedAssessment.UNKNOWN)
    check(MoviePlaybackEstimator.estimate(bytes, duration, 4_000_000.0)!!.assessment == PeerSpeedAssessment.BELOW_AVERAGE)
    check(MoviePlaybackEstimator.estimate(bytes, duration, 6_000_000.0)!!.assessment == PeerSpeedAssessment.LOW_MARGIN)
    check(MoviePlaybackEstimator.estimate(bytes, duration, 8_000_000.0)!!.assessment == PeerSpeedAssessment.HEADROOM)
    check(MoviePlaybackEstimator.estimate(bytes, duration, Double.NaN)!!.assessment == PeerSpeedAssessment.UNKNOWN)
    check(MoviePlaybackEstimator.estimate(Long.MAX_VALUE, 1, 1.0)!!.averageBitsPerSecond > 0)
    println("PASS: bitrate units, unknown/slow/headroom and large file boundaries")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-movie-estimate-') as td:
    folder = Path(td)
    (folder / 'Checks.kt').write_text(checks)
    cp = str(compiler[1])
    result = subprocess.run([str(JAVA), '-cp', ':'.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(folder / 'classes'),
        str(SOURCE), str(folder / 'Checks.kt')], capture_output=True, text=True)
    if result.returncode:
        raise SystemExit(result.stderr)
    result = subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{cp}', 'tv.orbit.shield.ChecksKt'], capture_output=True, text=True)
    print(result.stdout, end='')
    if result.returncode:
        raise SystemExit(result.stderr)
