"""Check the server playlist against the exact torrent videos and sidecars."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import JAVA, COMPILER, ROOT
source = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/TorrPlaylistSafety.kt'
checks = r'''package tv.orbit.shield
fun main() {
    val hash = "ab".repeat(20)
    val files = setOf(1, 2, 3, 4, 5)
    fun url(id: Int) = "http://127.0.0.1:8090/stream/file$id.mkv?link=$hash&index=$id&play"
    fun playlist(vararg lines: String) = "#EXTM3U\n" + lines.joinToString("\n") + "\n"
    fun safe(text: String) = isSafeTorrentPlaylist(text, hash, listOf(1, 2), files, setOf(3, 4))
    val normal = playlist("#EXTINF:0,one", url(1), "#EXTINF:0,two", url(2))
    check(safe(normal))
    check(safe(playlist("#EXTINF:0,one", "#EXTVLCOPT:input-slave=${url(3)}#${url(4)}#", url(1), "#EXTINF:0,two", url(2))))
    check(!safe(playlist("#EXTINF:0,one", url(1), url(3), url(2)))) // an audio or extra media entry
    check(!safe(playlist(url(2), url(1))))
    check(!safe(playlist(url(1), url(1), url(2))))
    check(!safe(playlist(url(1).replace("127.0.0.1", "example.com"), url(2))))
    check(!safe(playlist(url(1).replace(hash, "cd".repeat(20)), url(2))))
    check(!safe(playlist(url(1).replace("index=1", "index=1&index=2"), url(2))))
    check(!safe(playlist("#EXTVLCOPT:input-slave=https://example.com/track#", url(1), url(2))))
    check(!safe(playlist("#EXTVLCOPT:input-slave=${url(2)}#", url(1), url(2))))
    check(!safe(playlist("#EXTVLCOPT:input-slave=${url(5)}#", url(1), url(2)))) // image, not a subtitle or audio
    check(!safe(playlist("#EXTVLCOPT:network-caching=1", url(1), url(2))))
    check(!safe(playlist("#EXTINF:0,one\u0000bad", url(1), url(2))))
    check(!isSafeTorrentPlaylist(normal, hash, listOf(1), files, setOf(3, 4)))
    println("PASS: local ordered video playlist and validated sidecars")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-torr-playlist-') as td:
    folder = Path(td)
    (folder / 'Checks.kt').write_text(checks)
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', str(COMPILER[1]), '-d', str(folder / 'classes'),
                    str(source), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{COMPILER[1]}', 'tv.orbit.shield.ChecksKt'], check=True)
