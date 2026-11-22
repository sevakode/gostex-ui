"""Exercise resume positions and automatic completion after the actual player launch."""
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

    var completionChecks = 0
    fun verify(name: String, expected: Boolean, success: Boolean = true, exactItem: Boolean = true,
               patched: Boolean = true, explicitEnd: Boolean = true,
               hasPosition: Boolean = true, hasDuration: Boolean = true,
               position: Long = duration, returnedDuration: Long = duration,
               launchedAt: Long? = 1_000_000L, returnedAt: Long = 1_002_000L,
               sourceComplete: Boolean = true) {
        val actual = videoShouldAdvanceAfterLaunch(success, exactItem, patched, explicitEnd,
            hasPosition, hasDuration, position, returnedDuration, launchedAt, returnedAt,
            sourceComplete = sourceComplete)
        check(actual == expected) { "$name: expected $expected, got $actual" }
        completionChecks++
    }
    // Real failure window: the corrupt episode returned as an end within 0.54–0.86 s.
    for (elapsed in listOf(0L, 540L, 560L, 860L, 1_499L)) {
        verify("early explicit end $elapsed", false, returnedAt = 1_000_000L + elapsed)
        verify("early legacy numeric end $elapsed", false, patched = false,
            returnedAt = 1_000_000L + elapsed)
        verify("stale near-end duration cannot bypass floor $elapsed", false,
            position = 400L, returnedDuration = 400L, returnedAt = 1_000_000L + elapsed)
    }
    verify("boundary explicit end", true, returnedAt = 1_001_500L)
    verify("normal explicit end", true)
    verify("incomplete growing file with explicit end", false, returnedAt = 1_008_000L,
        sourceComplete = false)
    verify("incomplete growing file near end in legacy player", false, patched = false,
        explicitEnd = false, position = duration - 1_000L, sourceComplete = false)
    verify("completed growing file with explicit end", true, returnedAt = 1_008_000L,
        hasPosition = false, hasDuration = false, position = -1L, returnedDuration = -1L,
        sourceComplete = true)
    verify("short clip after launch floor", true, position = 1_600L, returnedDuration = 1_600L)
    verify("legitimate near-end continuation after launch floor", true,
        position = duration - 250L)
    // Native VLC can clear its time/duration before returning a genuine EndReached result.
    verify("explicit end with absent numeric metadata", true, hasPosition = false,
        hasDuration = false, position = -1L, returnedDuration = -1L)
    verify("explicit end with cleared numeric metadata", true, position = 0L, returnedDuration = 0L)
    verify("patched Back exactly at end", false, explicitEnd = false)
    verify("patched Back near end", false, explicitEnd = false, position = duration - 1_000L)
    verify("failed player result", false, success = false)
    verify("unrelated URI", false, exactItem = false)
    verify("missing current launch", false, launchedAt = null)
    verify("negative current launch", false, launchedAt = -1L)
    verify("clock before current launch", false, returnedAt = 999_999L)
    verify("elapsed starts at zero", true, launchedAt = 0L, returnedAt = 1_500L)
    verify("large monotonic elapsed", true, launchedAt = 1L, returnedAt = Long.MAX_VALUE)
    verify("large timestamps do not overflow", true, launchedAt = Long.MAX_VALUE - 1_500L,
        returnedAt = Long.MAX_VALUE)
    verify("large early timestamps stay early", false, launchedAt = Long.MAX_VALUE - 860L,
        returnedAt = Long.MAX_VALUE)
    // Old external VLC does not know our explicit-end flag and retains its numeric contract.
    verify("legacy numeric end", true, patched = false, explicitEnd = false)
    verify("legacy near end", true, patched = false, explicitEnd = false, position = duration - 2_000L)
    verify("legacy incomplete", false, patched = false, position = duration - 2_001L)
    verify("legacy missing position", false, patched = false, hasPosition = false)
    verify("legacy missing duration", false, patched = false, hasDuration = false)
    verify("unrecognized explicit flag cannot bypass legacy metrics", false,
        patched = false, hasPosition = false, hasDuration = false, position = 0L, returnedDuration = 0L)
    // A minute spent choosing Resume is not part of the attempt's evidence.
    verify("resume dialog time excluded", false, launchedAt = 1_060_000L, returnedAt = 1_060_860L)
    verify("resumed attempt later completes", true, launchedAt = 1_060_000L, returnedAt = 1_062_000L)
    // Every result must have its own live timestamp; an old successful attempt cannot be reused.
    verify("second attempt starts its own floor", false, launchedAt = 1_062_000L, returnedAt = 1_062_540L)
    verify("consumed attempt evidence is absent", false, launchedAt = null, returnedAt = 1_070_000L)
    println("PASS: resume/checkpoint boundaries and $completionChecks automatic completion cases")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-video-resume-') as td:
    folder = Path(td)
    (folder / 'Checks.kt').write_text(checks)
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', str(COMPILER[1]), '-d', str(folder / 'classes'),
                    str(source), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{COMPILER[1]}', 'tv.orbit.shield.ChecksKt'], check=True)
