"""Check title cleanup and avoid applying an ambiguous automatic cover."""
from pathlib import Path
import subprocess
import tempfile
ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / '.tools/native/gradle-cache/caches/modules-2/files-2.1'
JAVA = ROOT / '.tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java'
def jar(group, artifact, version): return next((CACHE / group / artifact / version).glob('*/*.jar'))
compiler = [jar('org.jetbrains.kotlin', a, v) for a, v in [
    ('kotlin-compiler-embeddable', '2.4.21'), ('kotlin-stdlib', '2.4.21'),
    ('kotlin-script-runtime', '2.4.21'), ('kotlin-reflect', '1.6.10')]] + [
    jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.9.0'), jar('org.jetbrains', 'annotations', '13.0')]
checks = r'''package tv.orbit.shield
fun main() {
    check(TorrentArtworkPolicy.query("The.Matrix.1999.1080p") == "The Matrix")
    check(TorrentArtworkPolicy.query("Cosmos.Possible.Worlds.S01.1080p") == "Cosmos Possible Worlds")
    check(TorrentArtworkPolicy.query("Cosmos Possible Worlds S01E02 WEB-DL") == "Cosmos Possible Worlds")
    check(TorrentArtworkPolicy.query("Batman [HD] (2022)") == "Batman")
    check(TorrentArtworkPolicy.query("a".repeat(40)).isEmpty())
    check(TorrentArtworkPolicy.query("A".repeat(40)).isEmpty())
    check(TorrentArtworkPolicy.query("a".repeat(39)).isNotEmpty())
    check(TorrentArtworkPolicy.normalized("The-Matrix!") == TorrentArtworkPolicy.normalized("THE MATRIX"))
    check(TorrentArtworkPolicy.normalized("Космос") != TorrentArtworkPolicy.normalized("Готэм"))
    check(TorrentArtworkPolicy.uniqueSource(1, 0) == "movie")
    check(TorrentArtworkPolicy.uniqueSource(0, 1) == "game")
    check(TorrentArtworkPolicy.uniqueSource(1, 1) == null)
    check(TorrentArtworkPolicy.uniqueSource(2, 0) == null)
    check(TorrentArtworkPolicy.uniqueSource(0, 0) == null)
    println("PASS: title normalization and ambiguous covers")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-torrent-cover-') as td:
    folder=Path(td); (folder/'Checks.kt').write_text(checks)
    cp=str(compiler[1])
    source=ROOT/'native-launcher/app/src/main/java/tv/orbit/shield/TorrentArtworkPolicy.kt'
    result=subprocess.run([str(JAVA),'-cp',':'.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(folder/'classes'),str(source),str(folder/'Checks.kt')],capture_output=True,text=True)
    if result.returncode: raise SystemExit(result.stderr)
    result=subprocess.run([str(JAVA),'-cp',f'{folder / "classes"}:{cp}','tv.orbit.shield.ChecksKt'],capture_output=True,text=True)
    print(result.stdout,end='')
    if result.returncode: raise SystemExit(result.stderr)
