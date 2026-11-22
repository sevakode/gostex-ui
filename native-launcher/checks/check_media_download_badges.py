"""Check the shared media card projection with a counted journal fixture on the JVM."""
from pathlib import Path
import subprocess
import tempfile

from kotlin_toolchain import COMPILER, JAVA, ROOT

src = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
store = (src / 'TorrDownloadStore.kt').read_text()
model = store[store.index('internal data class TorrDownloadJob('):store.index('/** Download records')]
check = r'''
internal object TorrDownloadStore {
    val jobs = MutableStateFlow<List<TorrDownloadJob>>(emptyList())
}

private class CountedJobs(private val rows: List<TorrDownloadJob>, private val ui: Thread,
                          private val beforeScan: () -> Unit = {}) : List<TorrDownloadJob> by rows {
    val scans = AtomicInteger()
    val rowsRead = AtomicInteger()
    val uiReads = AtomicInteger()

    override fun iterator(): Iterator<TorrDownloadJob> {
        scans.incrementAndGet()
        beforeScan()
        return object : Iterator<TorrDownloadJob> {
            private val entries = rows.iterator()
            override fun hasNext(): Boolean = entries.hasNext()
            override fun next(): TorrDownloadJob {
                rowsRead.incrementAndGet()
                if (Thread.currentThread() == ui) uiReads.incrementAndGet()
                return entries.next()
            }
        }
    }
}

private fun entry(id: String, movieId: String, state: String, offset: Long = 0, total: Long = 1_000) =
    TorrDownloadJob(id, "hash", 0, total, "name", "tree", "doc", "name", offset = offset, state = state,
        prefetchMovieId = movieId)

fun main() = runBlocking {
    val journal = TorrDownloadStore.jobs
    val ui = Thread.currentThread()
    val events = Channel<MediaDownloadBadge?>(Channel.UNLIMITED)
    val other = Channel<MediaDownloadBadge?>(Channel.UNLIMITED)
    fun collect(movieId: String, output: Channel<MediaDownloadBadge?>) = launch(start = CoroutineStart.UNDISPATCHED) {
        MediaDownloadBadges.observe(movieId).collect { output.send(it) }
    }
    suspend fun next(output: Channel<MediaDownloadBadge?>) = withTimeout(5_000) { output.receive() }
    suspend fun subscriptions(count: Int) = withTimeout(5_000) { journal.subscriptionCount.first { it == count } }

    val first = collect("featured", events)
    val second = collect("other", other)
    subscriptions(1)
    check(next(events) == null && next(other) == null)

    val unrelated = List(1_995) { entry("archive$it", "separate$it", "done") }
    val featured = entry("active", "featured", "running", 207)
    val another = entry("queued", "featured", "running", 993)
    val saved = entry("saved", "featured", "done")
    val savedOther = entry("other-video", "other", "done")
    val ignored = entry("unattached", "", "done")
    val initial = CountedJobs(unrelated + listOf(saved, featured, another, savedOther, ignored), ui)
    journal.emit(initial)
    check(next(events) == MediaDownloadBadge.Running(20))
    check(next(other) == MediaDownloadBadge.Completed(1))
    check(initial.scans.get() == 1 && initial.rowsRead.get() == initial.size && initial.uiReads.get() == 0)
    check(journal.subscriptionCount.value == 1)

    val savedAgain = saved.copy(id = "saved-again")
    val otherAgain = savedOther.copy(id = "other-again")
    val samePercent = featured.copy(offset = 209)
    journal.emit(unrelated + listOf(saved, samePercent, another, savedAgain, savedOther, otherAgain))
    check(next(other) == MediaDownloadBadge.Completed(2))
    yield()
    check(events.tryReceive().isFailure)

    val clipped = featured.copy(offset = 2_000)
    journal.emit(listOf(clipped, another))
    check(next(events) == MediaDownloadBadge.Running(100) && next(other) == null)
    journal.emit(listOf(featured.copy(total = 0), saved))
    check(next(events) == MediaDownloadBadge.Running(0))
    journal.emit(listOf(featured.copy(state = "queued"), saved, savedAgain))
    check(next(events) == MediaDownloadBadge.Completed(2))

    val started = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val current = CountedJobs(listOf(featured.copy(offset = 420)), ui) {
        check(Thread.currentThread() != ui) { "Projection must not run on Main" }
        started.complete(Unit)
        check(release.await(10, TimeUnit.SECONDS)) { "Projection gate was not released" }
    }
    val newEvents = Channel<MediaDownloadBadge?>(Channel.UNLIMITED)
    journal.emit(current)
    val newObserver = try {
        withTimeout(5_000) { started.await() }
        // Existing cards keep replay alive. UNDISPATCHED consumes any replay
        // synchronously, while the new scan cannot run past the gate.
        collect("featured", newEvents).also {
            check(newEvents.tryReceive().isFailure) { "New card received stale replay from the prior journal" }
        }
    } finally {
        release.countDown()
    }
    check(next(newEvents) == MediaDownloadBadge.Running(42))
    check(next(events) == MediaDownloadBadge.Running(42))
    check(current.scans.get() == 1 && current.uiReads.get() == 0)
    newObserver.cancelAndJoin()

    first.cancelAndJoin()
    check(journal.subscriptionCount.value == 1)
    second.cancelAndJoin()
    subscriptions(0)
    val whileIdle = CountedJobs(listOf(featured.copy(offset = 516)), ui)
    journal.emit(whileIdle)
    check(whileIdle.scans.get() == 0)

    val restart = collect("featured", events)
    check(next(events) == MediaDownloadBadge.Running(51))
    check(whileIdle.scans.get() == 1 && whileIdle.uiReads.get() == 0)
    restart.cancelAndJoin()
    subscriptions(0)
    println("PASS: one background scan of ${initial.size} rows, unchanged badge suppression, priority, bounds, idle unsubscription and stale replay rejection")
}
'''
imports = '''package tv.orbit.shield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
'''
with tempfile.TemporaryDirectory(prefix='gostex-media-badges-') as td:
    folder = Path(td)
    fixture = folder / 'Checks.kt'
    fixture.write_text(imports + model + check)
    runtime = ':'.join(map(str, [COMPILER[1], COMPILER[4]]))
    classes = folder / 'classes'
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', runtime, '-d', str(classes), str(fixture),
                    str(src / 'HttpDownloadAdmission.kt'), str(src / 'MediaDownloadBadges.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', f'{classes}:{runtime}', 'tv.orbit.shield.ChecksKt'], check=True)
