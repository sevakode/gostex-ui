"""Test conservative episode hints without changing source file identity."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import JAVA, COMPILER, ROOT
source = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/EpisodeFileSuggestion.kt'
checks = '''package tv.orbit.shield
fun main() {
    fun hint(vararg paths: String) = suggestEpisodeFile(paths.toList(), 2, 3)
    check(hint("Season 2/Show.S02E01.mkv", "Season 2/Show.S02E03.1080p.mkv") == 1)
    check(hint("Show.2x03.720p.mp4") == 0)
    check(hint("S02E03.mkv", "s2e3.mp4") == null)
    check(hint("S02E03E04.mkv") == null)
    check(hint("S02E03-E04.mkv") == null)
    check(hint("S02E03-S02E04.mkv") == null)
    check(hint("S02E03-04.mkv") == null)
    check(hint("dir/S02E03/unrelated.mkv") == null)
    check(hint("XS02E03.mkv", "S02E030.mkv") == null)
    check(hint("S02E03-1080p.mkv") == 0)
    check(suggestEpisodeFile(listOf("S00E01.mkv"), 0, 1) == 0)
    check(suggestEpisodeFile(listOf("S02E03.mkv"), -1, 3) == null)
    check(suggestEpisodeFile(emptyList(), 2, 3) == null)
    val season = listOf("Show.S02E03.mkv", "Show.S02E04.mkv")
    check(episodeFileMatchesHistory(season, 2, 3, 0))
    check(!episodeFileMatchesHistory(season, 2, 3, 1))
    check(!episodeFileMatchesHistory(listOf("S02E03.mkv", "s2e3.mp4"), 2, 3, 0))
    check(!episodeFileMatchesHistory(listOf("unknown-1.mkv", "unknown-2.mkv"), 2, 3, 0))
    check(episodeFileMatchesHistory(listOf("unknown.mkv"), 2, 3, 0))
    check(!episodeFileMatchesHistory(listOf("Show.S02E04.mkv"), 2, 3, 0))
    check(!episodeFileMatchesHistory(listOf("Show.S02E03E04.mkv"), 2, 3, 0))
    check(!episodeFileMatchesHistory(season, 2, 3, 2))
    check(!episodeFileMatchesHistory(listOf("unknown.mkv"), -1, 3, 0))
    println("PASS: unique hints and reliable episode history for selected files")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-episode-hint-') as td:
    folder = Path(td)
    (folder / 'Checks.kt').write_text(checks)
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', str(COMPILER[1]), '-d', str(folder / 'classes'),
                    str(source), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{COMPILER[1]}', 'tv.orbit.shield.ChecksKt'], check=True)
