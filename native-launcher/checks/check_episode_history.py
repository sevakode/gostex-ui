"""Validate that episode history can never resolve to a different series."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import JAVA, COMPILER, ROOT
folder_src = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
model = (folder_src / 'MovieCatalogClient.kt').read_text()
model = model[model.index('internal data class MovieEpisode('):model.index('/** Metadata only;')]
logic = (folder_src / 'MovieEpisodeHistory.kt').read_text()
checks = '''
fun main() {
    check(decodeEpisodeHistory("tt123", "tt123:2:3") == MovieEpisode("tt123:2:3", 2, 3, ""))
    check(decodeEpisodeHistory("tt123", "tt999:2:3") == null)
    check(decodeEpisodeHistory("tt123", "tt123:02:3") == null)
    check(decodeEpisodeHistory("tt123", "tt123:2:3/extra") == null)
    check(decodeEpisodeHistory("tt123", "tt123:-1:3") == null)
    check(decodeEpisodeHistory("tt123", "tt123:2:999999") == null)
    check(decodeEpisodeHistory("tt123", null) == null)
    check(decodeEpisodeHistory("invalid", "invalid:2:3") == null)
    check(episodeHistoryKey("tt123") != episodeHistoryKey("tt124"))
    println("PASS: episode identity, corrupted values and series isolation")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-episode-history-') as td:
    folder = Path(td); path = folder / 'History.kt'; path.write_text(logic + '\n' + model + checks)
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', str(COMPILER[1]), '-d', str(folder / 'classes'), str(path)], check=True)
    subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{COMPILER[1]}', 'tv.orbit.shield.HistoryKt'], check=True)
