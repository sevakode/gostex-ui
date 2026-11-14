"""Exercise the production HTTP admission and Engine against disposable local servers."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import re
import subprocess
import tempfile
from threading import Event, Thread
from urllib.parse import urlsplit

from kotlin_toolchain import COMPILER, JAVA, ROOT

PAYLOAD = bytes(i % 251 for i in range(1024 * 1024))
ETAG = '"private-validator"'


class Fixture(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        route = urlsplit(self.path).path
        range_ = self.headers.get('Range')
        self.server.requests.append((self.path, range_, self.headers.get('If-Match'), self.headers.get('If-Range')))
        assert self.headers.get('Accept-Encoding') == 'identity'
        assert self.headers.get('Connection') == 'close'
        if route in ('/redirect', '/moved', '/cross'):
            if route == '/cross' and range_ != 'bytes=0-0':
                return self.reply(302, [('Location', self.server.second_origin + '/file')])
            path = '/file' if route != '/moved' or range_ == 'bytes=0-0' else '/different'
            return self.reply(302, [('Location', path + '?private=effective')])
        size = len(PAYLOAD)
        if route.startswith('/restart'):
            assert self.headers.get('If-Match') is None and self.headers.get('If-Range') is None
            if range_ == 'bytes=0-0':
                return self.reply(200, declared=size)  # No data before the real whole-file request.
            assert range_ is None, 'Restart-only stream must never send Range'
            if route == '/restart-changed':
                return self.reply(200, declared=size + 1)
            if route == '/restart-framing':
                return self.reply(200, [('Content-Range', f'bytes 0-{size - 1}/{size}')], declared=size)
            if route == '/restart-partial':
                return self.reply(206, [('Content-Range', f'bytes 0-{size - 1}/{size}')], declared=size)
            return self.reply(200, body=PAYLOAD)
        if range_ == 'bytes=0-0':
            return self.reply(206, [('Content-Range', f'bytes 0-0/{size}'), ('ETag', ETAG)], PAYLOAD[:1])
        match = re.fullmatch(r'bytes=(\d+)-', range_ or '')
        assert match
        offset = int(match[1])
        assert (self.headers.get('If-Range'), self.headers.get('If-Match')) == ((ETAG, None) if offset else (None, ETAG))
        if route in ('/auth', '/precondition'):
            return self.reply({'/auth': 403, '/precondition': 412}[route])
        if route == '/ignored':
            return self.reply(200, [('ETag', ETAG)], PAYLOAD)
        headers = [('Content-Range', f'bytes {offset}-{size - 1}/{size}'), ('ETag', ETAG)]
        if route == '/changed':
            headers[-1] = ('ETag', '"changed"')
        elif route == '/range':
            headers[0] = ('Content-Range', f'bytes {offset}-{size - 1}/{size + 1}')
        elif route == '/duplicate':
            headers.append(('ETag', ETAG))
        elif route == '/compressed':
            headers.append(('Content-Encoding', 'gzip'))
        body = PAYLOAD[offset:]
        if route == '/truncated':
            return self.reply(206, headers, body[:8192], declared=len(body))
        if route == '/slow':
            self.reply(206, headers, body[:8192], declared=len(body))
            self.server.release.wait(3)
            return
        self.reply(206, headers, body)

    def reply(self, status, headers=(), body=b'', declared=None):
        self.send_response(status)
        self.send_header('Content-Length', str(len(body) if declared is None else declared))
        for name, value in headers:
            self.send_header(name, value)
        self.end_headers()
        try:
            self.wfile.write(body)
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass


CHECKS = r'''package tv.orbit.shield

import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val base = args.single()
    val file = Files.createTempFile("gostex-http-partial", ".bin")
    val initial = TorrDownloadCheckpoint(0, null)
    val bytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
    fun origin(route: String): Pair<HttpDownloadOrigin, Long> {
        val checked = runBlocking { HttpDownloadAdmission.probeCancellable("$base/$route?private=fixture", true) }
        return HttpDownloadOrigin(checked.url, checked.resourceKey, checked.strongEtag) to checked.size
    }
    try {
        RandomAccessFile(file.toFile(), "rw").use { raw ->
            val target = TorrFileTarget(raw)
            val (source, size) = origin("redirect")
            check(!source.toString().contains("private") && source.url == HttpDownloadUrl.parse(source.url!!.serialized))
            var durable = initial
            val paused = TorrDownloadEngine.transfer(source, size, target, initial,
                cancelled = { raw.length() >= 256 * 1024 }, saveCheckpoint = { durable = it })
            check(paused == durable && paused.offset in 256 * 1024L..(320 * 1024L) && paused.etag == source.expectedEtag)
            check(Files.readAllBytes(file).contentEquals(bytes.copyOf(paused.offset.toInt())))
            raw.seek(raw.length()); raw.write(byteArrayOf(11, 22, 33)) // Simulate bytes after the durable checkpoint.
            val done = TorrDownloadEngine.transfer(source, size, target, durable, saveCheckpoint = { durable = it })
            check(done == durable && done.offset == size)
            check(Files.readAllBytes(file).contentEquals(bytes))
            TorrDownloadEngine.verifyCompleted(size, target, done)
            check(runCatching { TorrDownloadEngine.verifyCompleted(size, target, paused) }.isFailure)

            for ((route, reason) in listOf("changed" to "source_changed", "moved" to "source_changed", "ignored" to "source_changed",
                "precondition" to "source_changed", "range" to "source_response", "cross" to "source_response",
                "duplicate" to "source_response", "compressed" to "source_response", "auth" to "source_auth")) {
                val (candidate, count) = origin(route)
                for (checkpoint in listOf(initial, paused)) {
                    // If the wrong server response slips through, this noncommitted tail is truncated.
                    val rejected = runCatching {
                        TorrDownloadEngine.transfer(candidate, count, target, checkpoint, saveCheckpoint = { error("Unexpected checkpoint") })
                    }.exceptionOrNull()
                    check(rejected is TorrSourceFailure && rejected.reason == reason && rejected.cause == null) { "Wrong result for $route: $rejected" }
                    check(Files.readAllBytes(file).contentEquals(bytes))
                }
            }

            val (short, count) = origin("truncated")
            durable = initial
            val incomplete = runCatching { TorrDownloadEngine.transfer(short, count, target, initial, saveCheckpoint = { durable = it }) }.exceptionOrNull()
            check(incomplete is TorrSourceFailure && incomplete.reason in setOf("source_incomplete", "source_io"))
            check(durable.offset == 8192L && raw.length() == durable.offset)
            check(Files.readAllBytes(file).contentEquals(bytes.copyOf(8192)))

            val (slow, slowSize) = origin("slow")
            val stopped = HttpDownloadAbort()
            stopped.cancel()
            check(TorrDownloadEngine.transfer(slow, slowSize, target, durable, abort = stopped,
                saveCheckpoint = { error("Pre-start abort changed state") }) == durable)
            check(raw.length() == 8192L)
            val handle = HttpDownloadAbort()
            val acceptedBody = CountDownLatch(1)
            val saved = AtomicReference(initial)
            val outcome = AtomicReference<Result<TorrDownloadCheckpoint>>()
            val worker = Thread {
                outcome.set(runCatching {
                    TorrDownloadEngine.transfer(slow, slowSize, target, initial, abort = handle, readTimeoutMs = 400,
                        beforeWrite = { acceptedBody.countDown(); true }, saveCheckpoint = saved::set)
                })
            }
            worker.start()
            check(acceptedBody.await(2, TimeUnit.SECONDS))
            handle.cancel() // Must not block on a transport's body-read monitor on this caller.
            worker.join(1800)
            check(!worker.isAlive && outcome.get().getOrThrow() == saved.get())
            check(raw.length() == saved.get().offset && raw.length() <= 8192L)

            val (wholeFile, wholeSize) = origin("restart")
            check(wholeFile.expectedEtag == null && wholeSize == bytes.size.toLong())
            raw.setLength(0)
            durable = initial
            val restartPaused = TorrDownloadEngine.transfer(wholeFile, wholeSize, target, initial,
                cancelled = { raw.length() >= 192 * 1024 }, saveCheckpoint = { durable = it })
            check(restartPaused == durable && durable.offset in 192 * 1024L..(256 * 1024L) && durable.etag == null)
            // Mark the whole old partial, including an uncommitted tail, so appending or
            // truncating before the journal authorizes a restart is observable.
            val oldPartial = ByteArray(durable.offset.toInt() + 19) { 17 }
            raw.seek(0); raw.write(oldPartial)
            val unauthorized = runCatching {
                TorrDownloadEngine.transfer(wholeFile, wholeSize, target, restartPaused,
                    saveCheckpoint = { error("Unexpected checkpoint") })
            }.exceptionOrNull()
            check(unauthorized is TorrSourceFailure && unauthorized.reason == "source_restart_required")
            check(Files.readAllBytes(file).contentEquals(oldPartial))

            for ((route, reason) in listOf("restart-changed" to "source_changed", "restart-framing" to "source_response",
                "restart-partial" to "source_response")) {
                val (candidate, admittedSize) = origin(route)
                val failure = runCatching {
                    TorrDownloadEngine.transfer(candidate, admittedSize, target, restartPaused,
                        resetForRestart = { error("Reset before whole response validation") },
                        saveCheckpoint = { error("Unexpected checkpoint") })
                }.exceptionOrNull()
                check(failure is TorrSourceFailure && failure.reason == reason)
                check(Files.readAllBytes(file).contentEquals(oldPartial))
            }

            val failedCommit = IllegalStateException("Synthetic journal write failure")
            check(runCatching {
                TorrDownloadEngine.transfer(wholeFile, wholeSize, target, restartPaused,
                    resetForRestart = {
                        check(Files.readAllBytes(file).contentEquals(oldPartial))
                        throw failedCommit
                    }, saveCheckpoint = { error("Unexpected checkpoint") })
            }.exceptionOrNull() === failedCommit)
            check(Files.readAllBytes(file).contentEquals(oldPartial) && durable == restartPaused)

            var cancelledAfterReset = false
            check(TorrDownloadEngine.transfer(wholeFile, wholeSize, target, restartPaused,
                cancelled = { cancelledAfterReset }, resetForRestart = { cancelledAfterReset = true },
                saveCheckpoint = { error("Unexpected checkpoint") }) == initial)
            check(Files.readAllBytes(file).contentEquals(oldPartial))

            var resetCount = 0
            val finished = TorrDownloadEngine.transfer(wholeFile, wholeSize, target, restartPaused,
                resetForRestart = {
                    check(durable == restartPaused && Files.readAllBytes(file).contentEquals(oldPartial))
                    durable = initial
                    resetCount++
                }, saveCheckpoint = {
                    check(resetCount == 1)
                    durable = it
                })
            check(resetCount == 1 && finished == durable && finished.offset == wholeSize && finished.etag == null)
            check(Files.readAllBytes(file).contentEquals(bytes))
            check(TorrDownloadEngine.transfer(wholeFile.copy(url = null), wholeSize, target, finished,
                saveCheckpoint = { error("Offline completion changed state") }) == finished)
            TorrDownloadEngine.verifyCompleted(wholeSize, target, finished, requireStrongEtag = false)
            check(runCatching { TorrDownloadEngine.verifyCompleted(wholeSize, target, finished) }.isFailure)
        }
    } finally { Files.deleteIfExists(file) }
    println("PASS: resumable and restart-only HTTP, validated journal reset before replacing bytes, durable pause and offline completion")
}
'''

with ThreadingHTTPServer(('127.0.0.1', 0), Fixture) as main, ThreadingHTTPServer(('127.0.0.1', 0), Fixture) as other:
    workers = []
    for server in (main, other):
        server.requests = []
        server.release = Event()
        worker = Thread(target=server.serve_forever, daemon=True)
        worker.start()
        workers.append(worker)
    main.second_origin = f'http://127.0.0.1:{other.server_port}'
    try:
        with tempfile.TemporaryDirectory(prefix='gostex-http-transfer-') as directory:
            base = Path(directory)
            code = base / 'Checks.kt'
            code.write_text(CHECKS, encoding='utf-8')
            app = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
            cp = f'{COMPILER[1]}:{COMPILER[4]}'
            subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                            '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(base / 'classes'),
                            str(app / 'HttpDownloadAdmission.kt'), str(app / 'TorrDownloadEngine.kt'), str(code)], check=True, timeout=60)
            subprocess.run([str(JAVA), '-cp', f'{base / "classes"}:{cp}', 'tv.orbit.shield.ChecksKt',
                            f'http://127.0.0.1:{main.server_port}'], check=True, timeout=20)
        assert ('/redirect?private=fixture', 'bytes=0-', ETAG, None) in main.requests
        assert any(path == '/file?private=effective' and if_range == ETAG for path, _, _, if_range in main.requests)
        assert len([req for req in main.requests if req[0].startswith('/slow')]) == 2, main.requests
        assert [req[1:] for req in main.requests if req[0] == '/restart?private=fixture'] == [
            ('bytes=0-0', None, None), (None, None, None), (None, None, None), (None, None, None), (None, None, None)]
        assert not other.requests
    finally:
        for server in (main, other):
            server.release.set()
            server.shutdown()
        for worker in workers:
            worker.join(timeout=2)
