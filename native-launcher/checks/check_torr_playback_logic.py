"""Check conservative automatic startup and last-file selection."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import JAVA, COMPILER, ROOT
source = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/TorrPlaybackLogic.kt'
checks = '''package tv.orbit.shield
fun main() {
    check(!shouldAutoOpenPrepared(false, true, true))
    check(!shouldAutoOpenPrepared(true, false, true))
    check(!shouldAutoOpenPrepared(true, true, false))
    check(shouldAutoOpenPrepared(true, true, true))
    check(torrentPreloadReady(3, 32, 32))
    check(torrentPreloadReady(3, 64, 32))
    check(!torrentPreloadReady(2, 64, 32))
    check(!torrentPreloadReady(4, 64, 32))
    check(!torrentPreloadReady(3, 31, 32))
    check(!torrentPreloadReady(3, null, 32))
    check(!torrentPreloadReady(3, 64, 0))
    check(!torrentPreloadReady(3, 64, null))
    check(torrentPreloadReady(3, 8, 32, 8))
    check(!torrentPreloadReady(3, 7, 32, 8))
    check(!torrentPreloadReady(3, 7, 32, null))
    val files = listOf("season/one.mkv", "season/two.mkv")
    check(lastTorrentFileIndex(files, "season/two.mkv") == 1)
    check(lastTorrentFileIndex(files.reversed(), "season/two.mkv") == 0)
    check(lastTorrentFileIndex(files, "elsewhere/two.mkv") == null)
    check(lastTorrentFileIndex(listOf("two.mkv", "two.mkv"), "two.mkv") == null)
    check(lastTorrentFileIndex(files, null) == null)
    println("PASS: preload readiness and conservative last-file suggestion")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-torrent-playback-') as td:
    folder = Path(td)
    (folder / 'Checks.kt').write_text(checks)
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', str(COMPILER[1]), '-d', str(folder / 'classes'),
                    str(source), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{COMPILER[1]}', 'tv.orbit.shield.ChecksKt'], check=True)
