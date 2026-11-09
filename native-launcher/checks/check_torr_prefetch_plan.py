"""Check prefetch order, deduplication and storage limits without touching media."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import JAVA, COMPILER, ROOT

base = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
checks = '''package tv.orbit.shield
fun main() {
    val episodes = (1..4).map { PrefetchEpisode(it, "Show.S01E0$it.mkv", 100) }
    fun plan(files: List<PrefetchEpisode> = episodes, current: Int = 1, count: Int = 2,
             existing: Set<Int> = emptySet(), free: Long? = 500, reserve: Long = 100,
             quota: Long = 500, used: Long = 0) =
        planEpisodePrefetch(files, current, count, existing, free, reserve, quota, used).map { it.id }
    check(plan() == listOf(2, 3))
    check(planEpisodePrefetch(episodes, 1, 1, emptySet(), 500, 100, 500, 0, true).map { it.id } == listOf(1, 2))
    check(planEpisodePrefetch(episodes, 1, 1, setOf(1), 500, 100, 500, 0, true).map { it.id } == listOf(2))
    check(plan(existing = setOf(2)) == listOf(3))
    // Rolling queue advances after completion, but stops at the quota.
    check(plan(current = 2, count = 1, existing = setOf(2), used = 100) == listOf(3))
    check(plan(current = 3, count = 1, existing = setOf(2, 3), quota = 200, used = 200).isEmpty())
    check(plan(free = 250) == listOf(2))
    check(plan(quota = 250, used = 100) == listOf(2))
    check(plan(free = null).isEmpty())
    check(plan(free = 99).isEmpty())
    check(plan(quota = 100, used = 101).isEmpty())
    check(plan(current = 4).isEmpty())
    check(plan(current = 99).isEmpty())
    check(plan(files = episodes.reversed()).isEmpty())
    check(plan(files = episodes.filter { it.id != 2 }).isEmpty())
    check(plan(files = episodes.map { it.copy(id = 1) }).isEmpty())
    check(plan(files = episodes.map { it.copy(bytes = -1) }).isEmpty())
    check(plan(free = Long.MAX_VALUE, quota = Long.MAX_VALUE, used = Long.MAX_VALUE - 50).isEmpty())
    println("PASS: safe episode prefetch planning")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-prefetch-') as td:
    folder = Path(td)
    (folder / 'Checks.kt').write_text(checks)
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', str(COMPILER[1]), '-d', str(folder / 'classes'),
                    str(base / 'EpisodeFileSuggestion.kt'), str(base / 'TorrPrefetchPlan.kt'), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{COMPILER[1]}', 'tv.orbit.shield.ChecksKt'], check=True)
