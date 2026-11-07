"""Exercise production episode labels without altering TorrServer file identities."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import JAVA, COMPILER, ROOT

src = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
model = (src / 'TorrServerCatalog.kt').read_text()
model = model[model.index('internal data class TorrCatalogFile('):model.index('internal class TorrMetadataTimeout')]
logic = (src / 'MediaFileLabels.kt').read_text()
checks = '''
fun main() {
    fun file(id: Int, path: String) = TorrCatalogFile(id, path, 100)
    val entries = listOf(
        file(1, "Cosmos/Season 1/Episode 01.mkv"),
        file(2, "Cosmos/Season 1/Episode 02.mkv"),
        file(3, "Series/Season 1/Opening.mkv"),
        file(4, "Series/Season 2/Opening.mkv"),
        file(5, "duplicate/File.mkv"),
        file(6, "duplicate/File.mkv"),
        file(7, "upper/TEST.mkv"),
        file(8, "UPPER/test.mkv"),
        file(9, "one-video.mp4")
    )
    val labels = mediaFileLabels(entries)
    check(labels == listOf("Episode 01.mkv", "Episode 02.mkv", "Series/Season 1/Opening.mkv",
        "Series/Season 2/Opening.mkv", "duplicate/File.mkv · 5", "duplicate/File.mkv · 6",
        "upper/TEST.mkv · 7", "UPPER/test.mkv · 8", "one-video.mp4"))
    check(mediaFileLabels(emptyList()).isEmpty())
    check(entries[0].path == "Cosmos/Season 1/Episode 01.mkv" && entries.map { it.id } == (1..9).toList())
    val large = (1..5000).map { file(it, "folder/Episode $it.mkv") }
    check(mediaFileLabels(large).size == 5000 && mediaFileLabels(large).last() == "Episode 5000.mkv")
    println("PASS: basenames, duplicate paths, case collisions, identity and 5000 entries")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-media-labels-') as td:
    folder = Path(td)
    source = folder / 'Labels.kt'
    source.write_text(logic + '\n' + model + checks)
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', str(COMPILER[1]), '-d', str(folder / 'classes'), str(source)], check=True)
    subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{COMPILER[1]}', 'tv.orbit.shield.LabelsKt'], check=True)
