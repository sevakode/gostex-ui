"""Test production download indicator selection and bounds on the JVM."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import JAVA, COMPILER, ROOT

src = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
store = (src / 'TorrDownloadStore.kt').read_text()
model = store[store.index('internal data class TorrDownloadJob('):store.index('/** Download records')]
nav = (src / 'DownloadsNavigation.kt').read_text()
logic = nav[nav.index('internal data class DownloadIndicator('):nav.index('internal object DownloadsNavigation')]
check = r'''
fun main() {
    fun job(state: String, offset: Long = 0, total: Long = 100) =
        TorrDownloadJob("id", "hash", 0, total, "name", "tree", "doc", "name", offset = offset, state = state)
    check(downloadIndicator(emptyList()) == DownloadIndicator(false, false, null))
    check(downloadIndicator(listOf(job("paused"), job("error"), job("done"))) == DownloadIndicator(false, false, null))
    check(downloadIndicator(listOf(job("queued"))) == DownloadIndicator(false, true, null))
    check(downloadIndicator(listOf(job("queued"), job("running", 50))) == DownloadIndicator(true, true, .5f))
    check(downloadIndicator(listOf(job("running", 5, 0))) == DownloadIndicator(true, false, null))
    check(downloadIndicator(listOf(job("running", 200))) == DownloadIndicator(true, false, 1f))
    check(downloadIndicator(listOf(job("running", -1))) == DownloadIndicator(true, false, 0f))
    println("PASS: idle, queued, running, unknown and bounded progress")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-indicator-') as td:
    folder = Path(td)
    source = folder / 'Indicator.kt'
    source.write_text('package tv.orbit.shield\n' + model + logic + check)
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', str(COMPILER[1]), '-d', str(folder / 'classes'), str(source)], check=True)
    subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{COMPILER[1]}', 'tv.orbit.shield.IndicatorKt'], check=True)
