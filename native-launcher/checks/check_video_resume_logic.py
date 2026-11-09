"""Exercise old and new resume positions at the end and in the middle of a video."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import JAVA, COMPILER, ROOT

source = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/VideoResumeLogic.kt'
checks = r'''package tv.orbit.shield
fun main() {
    val duration = 24 * 60_000L
    check(videoResumePosition(12 * 60_000L, duration) == 12 * 60_000L)
    check(videoResumePosition(23 * 60_000L + 2_000, duration) == 0L)
    check(videoResumePosition(23 * 60_000L + 2_000, duration, false) == 23 * 60_000L + 2_000)
    check(videoResumePosition(duration, duration, false) == 0L)
    check(videoResumePosition(23 * 60_000L + 2_000, 0) == 23 * 60_000L + 2_000)
    check(videoResumePosition(0, duration) == 0L)
    check(videoResumePosition(-1, duration) == 0L)
    check(videoResumePosition(19_000, 20_000) == 0L)
    check(videoResumePosition(10_000, 2_000_000) == 10_000L)
    check(videoResumePosition(Long.MAX_VALUE, Long.MAX_VALUE) == 0L)
    check(!videoActuallyCompleted(0, duration))
    check(!videoActuallyCompleted(duration - 2_001, duration))
    check(videoActuallyCompleted(duration - 2_000, duration))
    check(videoActuallyCompleted(duration, duration))
    check(videoActuallyCompleted(duration + 2_000, duration))
    check(!videoActuallyCompleted(duration + 2_001, duration))
    check(!videoActuallyCompleted(0, 0))
    check(!videoActuallyCompleted(Long.MAX_VALUE, 10_000))
    check(videoActuallyCompleted(Long.MAX_VALUE, Long.MAX_VALUE))
    check(videoShouldAdvance(true, true, true, true, duration, duration))
    check(!videoShouldAdvance(false, true, true, true, duration, duration))
    check(!videoShouldAdvance(true, false, true, true, duration, duration))
    check(!videoShouldAdvance(true, true, false, true, duration, duration))
    check(!videoShouldAdvance(true, true, true, false, duration, duration))
    check(!videoShouldAdvance(true, true, true, true, duration - 5_000, duration))
    check(videoCheckpoint(-1, 100_000) == null)
    check(videoCheckpoint(9_999, 100_000) == null)
    check(videoCheckpoint(15_000, -1) == null)
    check(videoCheckpoint(100_001, 100_000) == null)
    check(videoCheckpoint(20_000, 0) == 20_000L)
    check(videoCheckpoint(20_000, 100_000) == 20_000L)
    check(videoCheckpoint(96_000, 100_000) == 0L)
    check(videoCheckpoint(96_000, 100_000, false) == 96_000L)
    println("PASS: near-end restart and mid-video resume")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-video-resume-') as td:
    folder = Path(td)
    (folder / 'Checks.kt').write_text(checks)
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', str(COMPILER[1]), '-d', str(folder / 'classes'),
                    str(source), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{COMPILER[1]}', 'tv.orbit.shield.ChecksKt'], check=True)
