"""Exercise the production transfer engine against controlled local HTTP responses."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
from urllib.parse import parse_qs, urlparse
import subprocess
import tempfile
import time
from kotlin_toolchain import JAVA, COMPILER, ROOT

compiler = COMPILER
DATA = bytes(range(256)) * 4096
PLAY_DATA = bytes((i ^ (i >> 9) ^ (i >> 16)) & 255 for i in range(len(DATA)))
LARGE_SIZE = 40 * 1024 * 1024 + 12345
BROKEN_SIZE = 18 * 1024 * 1024 + 123
PLAY_TAIL = len(PLAY_DATA) - 64 * 1024
RETRY_CUT = 256 * 1024
play_requests = []
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_GET(self):
        query = parse_qs(urlparse(self.path).query)
        assert urlparse(self.path).path == '/stream'
        mode = {'2': 'g', '3': 'h', '4': 'i'}.get(query['index'][0], query['link'][0][-1])
        if mode in 'cdefghi0':
            range_header = self.headers.get('Range')
            if mode != '0' or range_header and not range_header.endswith('-'):
                self.growing_response(mode, range_header)
                return
        if mode in ('7', '8'):
            self.send_response(401 if mode == '7' else 403); self.send_header('Content-Length', '0'); self.end_headers(); return
        if mode == '4':
            self.send_response(302); self.send_header('Location', 'http://example.com/'); self.end_headers(); return
        size = LARGE_SIZE if mode in ('a', 'b') else len(DATA)
        start = int(self.headers.get('Range', 'bytes=0-')[6:-1])
        if start and mode == '1':
            self.send_response(200)  # server ignored range
        else:
            self.send_response(206 if start else 200)
        tag = '"' + 'a' * 310 + '"' if mode == '9' else ('"changed"' if start and mode == '2' else '"original"')
        self.send_header('ETag', tag)
        self.send_header('Content-Length', str(size - start))
        if start:
            self.send_header('Content-Range', f'bytes {start + (1 if mode == "3" else 0)}-{size-1}/{size}')
        self.end_headers()
        if mode == '5': time.sleep(0.6)
        try:
            end = BROKEN_SIZE if mode == 'b' else size
            if mode == '6': end = min(size, start + 128)
            position = start
            while position < end:
                chunk = DATA[position % len(DATA):min(len(DATA), position % len(DATA) + end - position)]
                self.wfile.write(chunk)
                position += len(chunk)
        except (BrokenPipeError, ConnectionResetError): pass

    def growing_response(self, mode, range_header):
        size = len(PLAY_DATA)
        start, end = 0, size - 1
        status = 200
        validator = self.headers.get('If-Range')
        play_requests.append((mode, range_header, validator))
        if range_header and mode != 'c':
            lower, upper = range_header.removeprefix('bytes=').split('-')
            start, end = int(lower), int(upper) if upper else end
            status = 206
        if mode in ('g', 'h', 'i') and start == PLAY_TAIL and validator is not None:
            self.send_response(503)
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        self.send_response(status)
        changed = mode == 'd' and start < PLAY_TAIL or mode == 'h' and 0 < start < PLAY_TAIL
        self.send_header('ETag', '"changed"' if changed else '"original"')
        self.send_header('Content-Length', str(end - start + 1))
        if status == 206:
            self.send_header('Content-Range', f'bytes {start + (mode == "e")}-{end}/{size}')
        self.end_headers()
        if mode == 'f':
            end = min(end, start + 127)
        if mode in ('g', 'h', 'i') and start == 0:
            end = RETRY_CUT - 1
        try: self.wfile.write(PLAY_DATA[start:end + 1])
        except (BrokenPipeError, ConnectionResetError): pass

checks = r'''package tv.orbit.shield
import java.io.RandomAccessFile
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
private class ObservedTarget(private val file: RandomAccessFile) : TorrDownloadTarget {
    var syncs = 0
    var failSyncAt = Int.MAX_VALUE
    var lastSynced = -1L
    var beforeSync: () -> Unit = {}
    var beforeWrite: (Long) -> Unit = {}
    var matchChecks = 0
    val syncOffsets = mutableListOf<Long>()
    val truncates = mutableListOf<Long>()
    override fun size() = file.length()
    override fun truncate(size: Long) { truncates += size; file.setLength(size) }
    override fun seek(offset: Long) = file.seek(offset)
    override fun write(buffer: ByteArray, count: Int) {
        beforeWrite(file.filePointer)
        file.write(buffer, 0, count)
    }
    override fun matchesAt(offset: Long, expected: ByteArray): Boolean {
        matchChecks++
        val buffer = ByteBuffer.allocate(expected.size)
        while (buffer.hasRemaining()) {
            if (file.channel.read(buffer, offset + buffer.position()) <= 0) return false
        }
        return buffer.array().contentEquals(expected)
    }
    override fun sync() {
        syncs++
        beforeSync()
        if (syncs == failSyncAt) throw IOException("controlled sync failure")
        file.fd.sync()
        lastSynced = file.filePointer
        syncOffsets += lastSynced
    }
}
fun main(args: Array<String>) {
    val port = args[0].toInt()
    val path = Files.createTempFile("gostex-test", ".part")
    try {
      RandomAccessFile(path.toFile(), "rw").use { file ->
        val target = TorrFileTarget(file)
        fun src(n: Int) = TorrDownloadSource("0".repeat(39) + n, 1, 1048576)
        var checkpoint = TorrDownloadCheckpoint(0, null)
        var checks = 0
        val paused = TorrDownloadEngine.transfer(src(0), target, checkpoint, port,
            cancelled = { ++checks > 4 }, saveCheckpoint = { checkpoint = it })
        check(paused.offset in 1 until 1048576 && paused == checkpoint)
        // Simulate bytes written after the last durable checkpoint before a crash.
        file.seek(file.length()); file.write(ByteArray(7) { 77 })
        for (mode in 1..4) {
            val before = file.length()
            val error = runCatching { TorrDownloadEngine.transfer(src(mode), target, checkpoint, port, saveCheckpoint = {}) }.exceptionOrNull()
            check(error is TorrSourceFailure && error.reason == if (mode == 2) "source_changed" else "source_response")
            check(file.length() == before) // rejected response must not touch destination
        }
        val timeout = runCatching { TorrDownloadEngine.transfer(src(5), target, checkpoint, port, saveCheckpoint = {}, readTimeoutMs = 150) }.exceptionOrNull()
        check(timeout is TorrSourceFailure && timeout.reason == "source_timeout")
        val incomplete = runCatching { TorrDownloadEngine.transfer(src(6), target, checkpoint, port, saveCheckpoint = {}) }.exceptionOrNull()
        check(incomplete is TorrSourceFailure && incomplete.reason in setOf("source_incomplete", "source_io"))
        for (mode in 7..8) {
            val before = file.length()
            val denied = runCatching { TorrDownloadEngine.transfer(src(mode), target, checkpoint, port, saveCheckpoint = {}) }.exceptionOrNull()
            check(denied is TorrSourceFailure && denied.reason == "source_auth" && file.length() == before)
        }
        val done = TorrDownloadEngine.transfer(src(0), target, checkpoint, port, saveCheckpoint = { checkpoint = it })
        check(done.offset == 1048576L && file.length() == 1048576L)
        file.seek(0); val actual = ByteArray(1048576); file.readFully(actual)
        check(actual.indices.all { actual[it] == (it % 256).toByte() })
        check(TorrDownloadEngine.transfer(src(0), target, checkpoint, port, saveCheckpoint = {}) == done)
        // Even a full durable checkpoint cannot make a truncated or extended
        // destination complete. The validation must not alter either file.
        file.setLength(1048575)
        check(runCatching { TorrDownloadEngine.transfer(src(0), target, done, port, saveCheckpoint = {}) }.isFailure)
        check(file.length() == 1048575L)
        file.setLength(1048577)
        check(runCatching { TorrDownloadEngine.transfer(src(0), target, done, port, saveCheckpoint = {}) }.isFailure)
        check(file.length() == 1048577L)
        file.setLength(1048576)
        check(runCatching { TorrDownloadEngine.transfer(src(0), target, TorrDownloadCheckpoint(1048576, null), port, saveCheckpoint = {}) }.isFailure)
        file.setLength(0)
        var throttleCheckpoint = TorrDownloadCheckpoint(0, null)
        var blocks = 0
        val throttled = TorrDownloadEngine.transfer(src(0), target, throttleCheckpoint, port,
            saveCheckpoint = { throttleCheckpoint = it }, beforeWrite = { ++blocks <= 2 })
        check(throttled.offset in 1 until 1048576 && throttled == throttleCheckpoint && file.length() == throttled.offset)
        val resumedAfterThrottle = TorrDownloadEngine.transfer(src(0), target, throttled, port, saveCheckpoint = { throttleCheckpoint = it })
        check(resumedAfterThrottle.offset == 1048576L)
        file.seek(0); val throttleData = ByteArray(1048576); file.readFully(throttleData)
        check(throttleData.indices.all { throttleData[it] == (it % 256).toByte() })
        // Real TorrServer encodes paths in ETags; a 312-character strong tag must resume.
        file.setLength(0)
        var longTag = TorrDownloadCheckpoint(0, null)
        var longBlocks = 0
        val longPause = TorrDownloadEngine.transfer(src(9), target, longTag, port,
            saveCheckpoint = { longTag = it }, beforeWrite = { ++longBlocks <= 2 })
        check(longPause.offset in 1 until 1048576 && longPause.etag?.length == 312)
        val longDone = TorrDownloadEngine.transfer(src(9), target, longPause, port, saveCheckpoint = { longTag = it })
        check(longDone.offset == 1048576L && longDone.etag == longPause.etag)
        file.setLength(0)
        check(TorrDownloadEngine.transfer(TorrDownloadSource("0".repeat(40), 1, 0), target,
            TorrDownloadCheckpoint(0, null), port, saveCheckpoint = {}) == TorrDownloadCheckpoint(0, null))
        var limited = TorrDownloadCheckpoint(0, null)
        var calls = 0
        val stopped = runCatching { TorrDownloadEngine.transfer(src(0), target, limited, port,
            saveCheckpoint = { limited = it }, spaceAllowance = { if (calls++ == 0) 131072L else 0L }) }.exceptionOrNull()
        check(stopped is TorrDownloadLowSpace && limited.offset == 131072L && file.length() == limited.offset)
        val afterSpace = TorrDownloadEngine.transfer(src(0), target, limited, port, saveCheckpoint = { limited = it }, spaceAllowance = { 1048576L })
        check(afterSpace.offset == 1048576L)
        file.seek(0); file.readFully(actual); check(actual.indices.all { actual[it] == (it % 256).toByte() })
        val largeSize = 40L * 1024 * 1024 + 12345
        val brokenSize = 18L * 1024 * 1024 + 123
        val hardBytes = 16L * 1024 * 1024
        val spaceBytes = 4L * 1024 * 1024
        fun large(mode: Char = 'a') = TorrDownloadSource("0".repeat(39) + mode, 1, largeSize)
        // Fast input must never sync for every space check. A hard boundary remains
        // even if no virtual time elapses; every persisted offset follows a real sync.
        file.setLength(0)
        val fast = ObservedTarget(file)
        val fastSaved = mutableListOf<Long>()
        val fastProgress = mutableListOf<Long>()
        var spaceChecks = 0
        val fastDone = TorrDownloadEngine.transfer(large(), fast, TorrDownloadCheckpoint(0, null), port,
            saveCheckpoint = {
                check(fast.lastSynced == it.offset)
                fastSaved += it.offset
            }, spaceAllowance = { spaceChecks++; Long.MAX_VALUE },
            nowNanos = { 0 }, reportProgress = { fastProgress += it })
        check(fastDone.offset == largeSize)
        check(fastSaved == listOf(hardBytes, hardBytes * 2, largeSize))
        check(fast.syncOffsets == fastSaved && spaceChecks == 11)
        check(fastProgress == listOf(0L, largeSize))
        // Slower input reaches the soft byte+time boundary; progress is independent
        // and bounded to 500ms, except the initial/final reports.
        file.setLength(0)
        val timed = ObservedTarget(file)
        var clock = 0L
        val timedSaved = mutableListOf<Pair<Long, Long>>()
        val timedProgress = mutableListOf<Pair<Long, Long>>()
        TorrDownloadEngine.transfer(large(), timed, TorrDownloadCheckpoint(0, null), port,
            saveCheckpoint = {
                check(timed.lastSynced == it.offset)
                timedSaved += it.offset to clock
            }, beforeWrite = { count -> clock += count * 500L; true }, nowNanos = { clock },
            reportProgress = { timedProgress += it to clock })
        check(timedSaved.last().first == largeSize && timedSaved.size >= 10)
        var previousOffset = 0L
        var previousTime = 0L
        for ((offsetAtSave, timeAtSave) in timedSaved.dropLast(1)) {
            val delta = offsetAtSave - previousOffset
            check(delta in spaceBytes..hardBytes)
            check(delta == hardBytes || timeAtSave - previousTime >= 2_000_000_000L)
            previousOffset = offsetAtSave; previousTime = timeAtSave
        }
        check(timedProgress.first().first == 0L && timedProgress.last().first == largeSize)
        check(timedProgress.size > timedSaved.size && timedProgress.size <= 45)
        check(timedProgress.dropLast(1).zipWithNext().all { (a, b) -> b.second - a.second >= 500_000_000L })
        // An explicit pause saves a non-aligned tail below the first checkpoint.
        file.setLength(0)
        val pauseTarget = ObservedTarget(file)
        var pauseBytes = 0L
        var pauseSaved = TorrDownloadCheckpoint(0, null)
        val largePaused = TorrDownloadEngine.transfer(large(), pauseTarget, pauseSaved, port,
            cancelled = { pauseBytes >= 3L * 1024 * 1024 },
            saveCheckpoint = { check(pauseTarget.lastSynced == it.offset); pauseSaved = it },
            beforeWrite = { count -> pauseBytes += count; true }, nowNanos = { 0 })
        check(largePaused == pauseSaved && largePaused.offset in 1 until spaceBytes)
        check(pauseTarget.syncs == 1)
        // The low-space path still saves the tail even when its allowance is much
        // smaller than the checkpoint threshold.
        file.setLength(0)
        val spaceTarget = ObservedTarget(file)
        var smallAllowance = 0
        var spaceSaved = TorrDownloadCheckpoint(0, null)
        val lowSpace = runCatching {
            TorrDownloadEngine.transfer(large(), spaceTarget, spaceSaved, port,
                saveCheckpoint = { check(spaceTarget.lastSynced == it.offset); spaceSaved = it },
                spaceAllowance = { if (smallAllowance++ == 0) 1234567L else 0L }, nowNanos = { 0 })
        }.exceptionOrNull()
        check(lowSpace is TorrDownloadLowSpace && spaceSaved.offset == 1234567L && spaceTarget.syncs == 1)
        // A validated response ending early persists its received tail before retry;
        // the successful Range request then continues from exactly that checkpoint.
        file.setLength(0)
        val brokenTarget = ObservedTarget(file)
        var brokenSaved = TorrDownloadCheckpoint(0, null)
        val broken = runCatching {
            TorrDownloadEngine.transfer(large('b'), brokenTarget, brokenSaved, port,
                saveCheckpoint = { check(brokenTarget.lastSynced == it.offset); brokenSaved = it }, nowNanos = { 0 })
        }.exceptionOrNull()
        check(broken is TorrSourceFailure && broken.reason in setOf("source_incomplete", "source_io"))
        check(brokenSaved.offset == brokenSize && brokenTarget.syncOffsets == listOf(hardBytes, brokenSize))
        val resumedOffsets = mutableListOf(brokenSize)
        val resumedLarge = TorrDownloadEngine.transfer(large(), brokenTarget, brokenSaved, port,
            saveCheckpoint = { check(brokenTarget.lastSynced == it.offset); resumedOffsets += it.offset; brokenSaved = it }, nowNanos = { 0 })
        check(resumedLarge.offset == largeSize && file.length() == largeSize)
        check(resumedOffsets[1] == brokenSize + hardBytes && resumedOffsets.zipWithNext().all { (a, b) -> b - a in 1..hardBytes })
        file.seek(0)
        val verify = ByteArray(65536)
        var verified = 0L
        while (verified < largeSize) {
            val count = file.read(verify, 0, minOf(verify.size.toLong(), largeSize - verified).toInt())
            check(count > 0 && (0 until count).all { verify[it] == ((verified + it) % 256).toByte() })
            verified += count
        }
        // Neither a failed target sync nor a failed journal write can advance the
        // durable checkpoint; source-error tail flushing must not swallow either.
        file.setLength(0)
        val syncFailureTarget = ObservedTarget(file).apply { failSyncAt = 1 }
        var savedAfterSyncFailure = false
        val syncFailure = runCatching {
            TorrDownloadEngine.transfer(large(), syncFailureTarget, TorrDownloadCheckpoint(0, null), port,
                saveCheckpoint = { savedAfterSyncFailure = true }, nowNanos = { 0 })
        }.exceptionOrNull()
        check(syncFailure is IOException && syncFailure !is TorrSourceFailure && !savedAfterSyncFailure)
        check(file.length() == hardBytes)
        file.setLength(0)
        val saveFailureTarget = ObservedTarget(file)
        var saveAttempts = 0
        val saveFailure = runCatching {
            TorrDownloadEngine.transfer(large(), saveFailureTarget, TorrDownloadCheckpoint(0, null), port,
                saveCheckpoint = { check(saveFailureTarget.lastSynced == it.offset); saveAttempts++; throw IOException("controlled journal failure") },
                nowNanos = { 0 })
        }.exceptionOrNull()
        check(saveFailure is IOException && saveFailure !is TorrSourceFailure && saveAttempts == 1 && saveFailureTarget.syncs == 1)
        file.setLength(0)
        val brokenFlushTarget = ObservedTarget(file).apply { failSyncAt = 2 }
        var beforeBrokenFlush = TorrDownloadCheckpoint(0, null)
        val brokenFlush = runCatching {
            TorrDownloadEngine.transfer(large('b'), brokenFlushTarget, beforeBrokenFlush, port,
                saveCheckpoint = { beforeBrokenFlush = it }, nowNanos = { 0 })
        }.exceptionOrNull()
        check(brokenFlush is IOException && brokenFlush !is TorrSourceFailure && beforeBrokenFlush.offset == hardBytes)
        file.setLength(0)
        val brokenSaveTarget = ObservedTarget(file)
        var beforeBrokenSave = TorrDownloadCheckpoint(0, null)
        var brokenSaveAttempts = 0
        val brokenSave = runCatching {
            TorrDownloadEngine.transfer(large('b'), brokenSaveTarget, beforeBrokenSave, port,
                saveCheckpoint = {
                    check(brokenSaveTarget.lastSynced == it.offset)
                    if (++brokenSaveAttempts == 2) throw IOException("controlled tail journal failure")
                    beforeBrokenSave = it
                }, nowNanos = { 0 })
        }.exceptionOrNull()
        check(brokenSave is IOException && brokenSave !is TorrSourceFailure && beforeBrokenSave.offset == hardBytes && brokenSaveAttempts == 2)
        check(runCatching { TorrDownloadSource("invalid", 1, 1) }.isFailure)
        checkGrowing(path, file, port)
        println("PASS: pause/resume, range/identity, crash tail, bounded checkpoint cadence, independent progress/space checks, source-tail recovery and sync/save failures")
      }
    } finally { Files.deleteIfExists(path) }
}

private fun checkGrowing(path: java.nio.file.Path, file: RandomAccessFile, port: Int) {
    val size = 1048576L
    val tail = size - 65536L
    val source = TorrDownloadSource("0".repeat(40), 1, size)
    fun content(offset: Long, length: Int): Boolean = RandomAccessFile(path.toFile(), "r").use { input ->
        input.seek(offset)
        val actual = ByteArray(length)
        input.readFully(actual)
        actual.indices.all { index ->
            val position = offset + index
            actual[index] == (position xor (position shr 9) xor (position shr 16)).toByte()
        }
    }
    file.setLength(0)
    val target = ObservedTarget(file)
    val empty = TorrDownloadCheckpoint(0, null)
    var saved = empty
    var prefix = 0L
    var tailVisible = false
    val paused = TorrDownloadEngine.transfer(source, target, saved, port, cancelled = { prefix > 0 },
        saveCheckpoint = { saved = it }, readable = { offset, tailStart ->
            prefix = offset
            if (tailStart == tail && !tailVisible) {
                check(offset == 0L && target.syncs == 1 && target.size() == size)
                check(content(tail, (size - tail).toInt()))
                tailVisible = true
            }
        })
    check(tailVisible && paused == saved && paused.offset in 1 until tail && file.length() == size)
    file.seek(tail); file.write(0b1010101)
    val corrupted = Files.readAllBytes(path)
    for ((mode, expectedReason) in listOf('d' to "source_changed", 'e' to "source_response", 'f' to "source_incomplete")) {
        val error = runCatching {
            TorrDownloadEngine.transfer(source.copy(hash = "0".repeat(39) + mode), target, paused, port,
                saveCheckpoint = { error("unexpected checkpoint") }, readable = { _, _ -> error("unexpected coverage") })
        }.exceptionOrNull()
        check(error is TorrSourceFailure && (error.reason == expectedReason || mode == 'f' && error.reason == "source_io"))
        check(Files.readAllBytes(path).contentEquals(corrupted))
    }
    var reset = false
    var fullReadable = false
    val finalTarget = ObservedTarget(file)
    finalTarget.beforeSync = {
        if (finalTarget.syncs == 2) {
            check(content(0L, size.toInt()))
            check(fullReadable && saved == paused) { "Final staged bytes are written but unavailable before sync" }
        }
    }
    val done = TorrDownloadEngine.transfer(source, finalTarget, paused, port, saveCheckpoint = {
        check(fullReadable && finalTarget.syncs == 2 && saved == paused)
        saved = it
    },
        readable = { offset, tailStart ->
            if (!reset) {
                check(offset == paused.offset && tailStart == size && file.length() == size)
                reset = true
            }
            if (offset == size) fullReadable = true
        })
    check(done == saved && done.offset == size && content(0L, size.toInt()))

    file.setLength(0)
    fullReadable = false
    saved = empty
    val fallbackTarget = ObservedTarget(file)
    fallbackTarget.beforeSync = {
        check(content(0L, size.toInt()))
        check(fullReadable && saved == empty) { "Final sequential bytes are written but unavailable before sync" }
    }
    val compatible = TorrDownloadEngine.transfer(source.copy(hash = "0".repeat(39) + 'c'), fallbackTarget, empty, port,
        saveCheckpoint = { check(fullReadable && fallbackTarget.syncs == 1); saved = it },
        readable = { offset, tailStart -> check(tailStart == size); if (offset == size) fullReadable = true })
    check(compatible == saved && compatible.offset == size && file.length() == size && content(0L, size.toInt()))

    file.setLength(0)
    var spaceChecks = 0
    saved = empty
    val lowSpace = runCatching {
        TorrDownloadEngine.transfer(source, target, empty, port, saveCheckpoint = { saved = it },
            readable = { _, _ -> }, spaceAllowance = { when (spaceChecks++) { 0 -> size; 1 -> 65536L; else -> 0L } })
    }.exceptionOrNull()
    check(lowSpace is TorrDownloadLowSpace && saved.offset == 65536L && file.length() == size)

    for (failAt in 1..3) {
        file.setLength(0)
        val failing = ObservedTarget(file).apply { failSyncAt = failAt }
        tailVisible = false
        fullReadable = false
        var saveAttempted = false
        val error = runCatching {
            TorrDownloadEngine.transfer(source, failing, empty, port, saveCheckpoint = {
                saveAttempted = true
                throw IOException("controlled checkpoint failure")
            },
                readable = { offset, tailStart ->
                    fullReadable = fullReadable || offset == size
                    tailVisible = tailVisible || tailStart == tail
                })
        }.exceptionOrNull()
        check(error is IOException && error !is TorrSourceFailure)
        check(saveAttempted == (failAt == 3) && tailVisible == (failAt >= 2) && fullReadable == (failAt >= 2))
    }
    println("PASS: final staged and sequential writes are readable before sync; sync and checkpoint failures still fail the transfer")
    println("PASS: growing-file staging, coverage, resume identity, malformed ranges, normal fallback, reserve and failed sync")
    for ((episode, mode) in listOf('g', 'h', 'i').withIndex()) {
        file.setLength(0)
        saved = empty
        val retrySource = source.copy(index = episode + 2)
        val retryTarget = ObservedTarget(file)
        val retry = TorrDownloadEngine.PlaybackRetry()
        val interrupted = runCatching {
            TorrDownloadEngine.transfer(retrySource, retryTarget, saved, port, saveCheckpoint = { saved = it },
                readable = { _, _ -> }, playbackRetry = retry)
        }.exceptionOrNull()
        check(interrupted is TorrSourceFailure && interrupted.reason in setOf("source_incomplete", "source_io"))
        val durable = TorrDownloadCheckpoint(256 * 1024L, "\"original\"")
        check(saved == durable && retryTarget.syncs == 2 && content(0, durable.offset.toInt()))
        check(content(tail, (size - tail).toInt()))
        val beforeRetry = Files.readAllBytes(path)
        if (mode == 'g') {
            // A new worker must prove the tail itself. Its unavailable tail does
            // not mutate the original worker's durable checkpoint or bytes.
            val noCache = runCatching {
                TorrDownloadEngine.transfer(retrySource, retryTarget, durable, port,
                    saveCheckpoint = { error("new worker unexpectedly committed") },
                    readable = { _, _ -> error("new worker unexpectedly changed coverage") },
                    playbackRetry = TorrDownloadEngine.PlaybackRetry())
            }.exceptionOrNull()
            check(noCache is TorrSourceFailure && noCache.reason == "source_response")
            check(retryTarget.syncs == 2 && Files.readAllBytes(path).contentEquals(beforeRetry))
        }
        if (mode == 'i') {
            file.seek(tail)
            val originalByte = file.readUnsignedByte()
            file.seek(tail)
            file.write(originalByte xor 0xff)
        }
        val resumeCoverage = mutableListOf<Pair<Long, Long>>()
        var beforeMiddle = false
        retryTarget.beforeWrite = { offset ->
            if (offset == durable.offset && !beforeMiddle) {
                beforeMiddle = true
                val retained = durable.offset to tail
                if (mode == 'g') {
                    check(retryTarget.truncates.isEmpty() && retryTarget.syncs == 2 && resumeCoverage == listOf(retained)) {
                        "Matching retry unnecessarily restaged the readable tail"
                    }
                } else {
                    check(mode == 'i' && retryTarget.truncates == listOf(durable.offset) && retryTarget.syncs == 3)
                    check(resumeCoverage == listOf(durable.offset to size, retained))
                }
                check(content(tail, (size - tail).toInt()))
            }
        }
        val resumed = runCatching {
            TorrDownloadEngine.transfer(retrySource, retryTarget, durable, port, saveCheckpoint = { saved = it },
                readable = { prefix, tailStart ->
                    check(mode != 'h') { "changed response must not alter coverage" }
                    resumeCoverage += prefix to tailStart
                },
                playbackRetry = retry)
        }
        if (mode == 'h') {
            val error = resumed.exceptionOrNull()
            check(error is TorrSourceFailure && error.reason == "source_changed")
            check(saved == durable && retryTarget.matchChecks == 0 && retryTarget.syncs == 2 &&
                Files.readAllBytes(path).contentEquals(beforeRetry))
        } else {
            check(resumed.getOrThrow() == saved && saved.offset == size && content(0, size.toInt()))
            check(beforeMiddle && retryTarget.matchChecks == 1)
        }
    }
    println("PASS: transient disconnect reuses one validated tail; different worker and changed validator fail safely")
    println("PASS: matching on-disk tail stays readable; one changed byte uses protected restaging")
}
'''
server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
thread = Thread(target=server.serve_forever, daemon=True); thread.start()
try:
    with tempfile.TemporaryDirectory(prefix='gostex-transfer-test-') as td:
        folder = Path(td); (folder / 'Checks.kt').write_text(checks)
        cp = f'{compiler[1]}:{compiler[4]}'
        source = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/TorrDownloadEngine.kt'
        result = subprocess.run([str(JAVA), '-cp', ':'.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
            '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(folder / 'classes'), str(source),
            str(source.with_name('HttpDownloadAdmission.kt')), str(folder / 'Checks.kt')], capture_output=True, text=True)
        if result.returncode: raise SystemExit(result.stderr)
        result = subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{cp}', 'tv.orbit.shield.ChecksKt', str(server.server_port)], capture_output=True, text=True)
        print(result.stdout, end='')
        if result.returncode: raise SystemExit(result.stderr)
        tail = f'bytes={PLAY_TAIL}-{len(PLAY_DATA)-1}'
        prefix = f'bytes=0-{PLAY_TAIL-1}'
        staged = [(range_header, tag) for mode, range_header, tag in play_requests if mode == '0']
        assert len(staged) == 12 and staged[:3] == [(tail, None), (prefix, '"original"'), (tail, '"original"')]
        resumed_prefix, resumed_tag = staged[3]
        start, end = resumed_prefix.removeprefix('bytes=').split('-')
        assert 0 < int(start) < PLAY_TAIL and int(end) == PLAY_TAIL-1 and resumed_tag == '"original"'
        assert staged[4:] == [(tail, None), (prefix, '"original"')] * 4
        assert [(range_header, tag) for mode, range_header, tag in play_requests if mode == 'c'] == [(tail, None), (None, None)]
        resumed_middle = f'bytes={RETRY_CUT}-{PLAY_TAIL - 1}'
        assert [(header, tag) for mode, header, tag in play_requests if mode == 'g'] == [
            (tail, None), (prefix, '"original"'), (tail, '"original"'), (resumed_middle, '"original"'),
        ]
        for retry_mode in ('h', 'i'):
            assert [(header, tag) for mode, header, tag in play_requests if mode == retry_mode] == [
                (tail, None), (prefix, '"original"'), (resumed_middle, '"original"'),
            ]
finally:
    server.shutdown(); server.server_close(); thread.join()
