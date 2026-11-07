"""Check linked media counting without touching a user's library or TorrServer."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import JAVA, COMPILER, ROOT
src = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
model = (src / 'MovieLibraryStore.kt').read_text()
model = model[model.index('internal data class MovieSource('):model.index('/** User collection')]
logic = (src / 'MovieTorrentLinks.kt').read_text()
checks = '''
fun main() {
    val hash = "a".repeat(40)
    val direct = MovieSource("one", "torrserver://$hash")
    val same = MovieSource("two", "TORRSERVER://${hash.uppercase()}")
    val other = MovieSource("other", "torrserver://${"b".repeat(40)}")
    val magnet = MovieSource("magnet", "magnet:?xt=urn:btih:$hash")
    val movies = listOf(
        SavedMovie("a", "A", "", null, listOf(direct, same)),
        SavedMovie("b", "B", "", null, listOf(same, other)),
        SavedMovie("c", "C", "", null, listOf(magnet, other)),
        SavedMovie("d", "D", "", null))
    check(linkedTorrentHashes(movies) == setOf(hash, "b".repeat(40)))
    val independent = listOf(SavedMovie("x", "X", "", null, listOf(
        MovieSource("encoded", "magnet:?xt=urn%3Abtih%3A${"c".repeat(40)}"),
        MovieSource("base32", "magnet:?xt=urn:btih:${"A".repeat(32)}"),
        MovieSource("multiple", "magnet:?xt=urn:btih:bad&xt=urn:btih:${"d".repeat(40)}"),
        MovieSource("invalid", "magnet:?xt=urn:btih:bad"))))
    check(linkedTorrentHashes(independent) == setOf("c".repeat(40), "0".repeat(40), "d".repeat(40)))
    check(linkedTorrentHashes(emptyList()).isEmpty())
    check(linkedTorrentMovieCount(movies, hash) == 2)
    check(linkedTorrentMovieCount(movies, "b".repeat(40)) == 2)
    check(linkedTorrentMovieCount(movies, "c".repeat(40)) == 0)
    check(runCatching { linkedTorrentMovieCount(movies, "bad") }.isFailure)
    println("PASS: exact hash, case, unique cards and independent magnet sources")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-movie-links-') as td:
    folder = Path(td); source = folder / 'Links.kt'; source.write_text(logic + '\n' + model + checks)
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', str(COMPILER[1]), '-d', str(folder / 'classes'), str(source)], check=True)
    subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{COMPILER[1]}', 'tv.orbit.shield.LinksKt'], check=True)
