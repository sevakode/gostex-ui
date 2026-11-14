"""Test production durable/live download progress and indicator selection on the JVM."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import JAVA, COMPILER, ROOT

src = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
store = (src / 'TorrDownloadStore.kt').read_text()
model = store[store.index('internal data class TorrDownloadJob('):store.index('/** Download records')]
nav = (src / 'DownloadsNavigation.kt').read_text()
logic = nav[nav.index('internal data class DownloadIndicator('):nav.index('internal object DownloadsNavigation')]
live = (src / 'TorrDownloadLiveProgress.kt').read_text().removeprefix('package tv.orbit.shield\n')
check = r'''
fun main() = kotlinx.coroutines.runBlocking {
    fun job(state: String, offset: Long = 0, total: Long = 100) =
        TorrDownloadJob("id", "hash", 0, total, "name", "tree", "doc", "name", offset = offset, state = state)
    check(downloadIndicator(emptyList()) == DownloadIndicator(false, false, null))
    check(downloadIndicator(listOf(job("paused"), job("error"), job("done"))) == DownloadIndicator(false, false, null))
    check(downloadIndicator(listOf(job("queued"))) == DownloadIndicator(false, true, null))
    check(downloadIndicator(listOf(job("queued"), job("running", 50))) == DownloadIndicator(true, true, .5f))
    check(downloadIndicator(listOf(job("running", 5, 0))) == DownloadIndicator(true, false, null))
    check(downloadIndicator(listOf(job("running", 200))) == DownloadIndicator(true, false, 1f))
    check(downloadIndicator(listOf(job("running", -1))) == DownloadIndicator(true, false, 0f))
    val running = job("running", 20)
    val live = TorrDownloadProgress("id", 70)
    check(downloadIndicator(listOf(running), live) == DownloadIndicator(true, false, .7f))
    check(downloadDisplayOffset(running, null) == 20L)
    check(downloadDisplayOffset(running, TorrDownloadProgress("other", 90)) == 20L)
    check(downloadDisplayOffset(running, TorrDownloadProgress("id", 10)) == 20L)
    check(downloadDisplayOffset(running, TorrDownloadProgress("id", -1)) == 20L)
    check(downloadDisplayOffset(running, TorrDownloadProgress("id", 200)) == 100L)
    check(downloadDisplayOffset(job("running", -1), TorrDownloadProgress("id", -1)) == 0L)
    check(downloadDisplayOffset(job("running", 5, 0), live) == 0L)
    check(downloadDisplayOffset(job("running", 5, -1), live) == 0L)
    listOf("paused", "error", "queued", "done", "finalizing").forEach { state ->
        check(downloadDisplayOffset(job(state, 20), live) == 20L)
    }

    // The singleton publishes without disk state or a permanent coroutine;
    // identical reports do not wake UI collectors, and clearing an old id is scoped.
    val observed = mutableListOf<TorrDownloadProgress?>()
    val observer = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
        TorrDownloadLiveProgress.current.collect { observed += it }
    }
    TorrDownloadLiveProgress.report("id", 70); kotlinx.coroutines.yield()
    check(observed == listOf(null, live))
    TorrDownloadLiveProgress.report("id", 70); kotlinx.coroutines.yield()
    check(observed.size == 2)
    TorrDownloadLiveProgress.clear("other"); kotlinx.coroutines.yield()
    check(TorrDownloadLiveProgress.current.value == live && observed.size == 2)
    TorrDownloadLiveProgress.report("next", -1); kotlinx.coroutines.yield()
    check(TorrDownloadLiveProgress.current.value == TorrDownloadProgress("next", 0))
    TorrDownloadLiveProgress.clear("id"); kotlinx.coroutines.yield()
    check(TorrDownloadLiveProgress.current.value?.id == "next")
    TorrDownloadLiveProgress.clear("next"); kotlinx.coroutines.yield()
    check(TorrDownloadLiveProgress.current.value == null && observed.size == 4)
    observer.cancel(); observer.join()
    println("PASS: indicator, matching running-only progress, stale/bounded offsets, scoped clear and equal-report suppression")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-indicator-') as td:
    folder = Path(td)
    source = folder / 'Indicator.kt'
    source.write_text('package tv.orbit.shield\nimport kotlinx.coroutines.launch\n' + live + model + logic + check)
    runtime = ':'.join(map(str, [COMPILER[1], COMPILER[4]]))
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', runtime, '-d', str(folder / 'classes'), str(source)], check=True)
    subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{runtime}', 'tv.orbit.shield.IndicatorKt'], check=True)
