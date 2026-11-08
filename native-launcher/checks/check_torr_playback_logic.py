"""Check conservative automatic startup and last-file selection."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import JAVA, COMPILER, ROOT
source = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/TorrPlaybackLogic.kt'
checks = '''package tv.orbit.shield
fun main() {
    val hash = "ab".repeat(20)
    val stream = "http://127.0.0.1:8090/stream?link=$hash&index=3&play=1"
    check(localTorrentStreamIdentity(stream) == (hash to 3))
    check(localTorrentStreamIdentity(stream.replace("index=3", "index=4")) == (hash to 4))
    check(localTorrentStreamIdentity(stream.replace("127.0.0.1", "example.com")) == null)
    check(localTorrentStreamIdentity(stream + "&index=4") == null)
    check(localTorrentStreamIdentity("S01E03.mkv") == null)
    check(vlcTorrentTitleIdentity("stream?link=$hash&index=3&play=1") == (hash to 3))
    check(vlcTorrentTitleIdentity("stream?link=$hash&index=3&index=4") == null)
    check(vlcTorrentTitleIdentity("https://example.com/stream?link=$hash&index=3") == null)
    check(torrentVideoTitleMatches("S01E03", setOf("S01E03.mkv")))
    check(torrentVideoTitleMatches("S01E03.mkv", setOf("S01E03.mkv")))
    check(torrentVideoTitleMatches("Show.S01E03.Rutracker", setOf("Show.S01E03.Rutracker.mkv")))
    check(!torrentVideoTitleMatches("S01E04", setOf("S01E03.mkv")))
    check(!torrentVideoTitleMatches(null, setOf("S01E03.mkv")))
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
    check(selectedTorrentPreloadReady(3, 32, 32, 100))
    check(!selectedTorrentPreloadReady(3, 32, 32, null))
    check(!selectedTorrentPreloadReady(3, 32, 32, 0))
    check(selectedTorrentPreloadReady(3, 1_228_800, 33_554_432, 1_250_184))
    check(!selectedTorrentPreloadReady(3, 1, 65_536, 65_536))
    check(!selectedTorrentPreloadReady(3, 58_981, 65_536, 65_536))
    check(selectedTorrentPreloadReady(3, 58_983, 65_536, 65_536))
    check(!selectedTorrentPreloadReady(3, 1_184_647, 33_554_432, 1_250_184))
    check(!selectedTorrentPreloadReady(2, 1_228_800, 33_554_432, 1_250_184))
    check(completedSelectedPreload(3, 100))
    check(!completedSelectedPreload(2, 100))
    check(!completedSelectedPreload(3, null))
    check(!completedSelectedPreload(3, 0))
    check(observedTorrentPreloadReady(true, true, 3, 32, 32, 100))
    check(!observedTorrentPreloadReady(false, true, 3, 32, 32, 100))
    check(!observedTorrentPreloadReady(true, false, 3, 32, 32, 100))
    check(!observedTorrentPreloadReady(true, true, 2, 32, 32, 100))
    check(!observedTorrentPreloadReady(true, true, 3, 31, 32, 100))
    check(!observedTorrentPreloadReady(true, true, 3, 32, 32, null))
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
