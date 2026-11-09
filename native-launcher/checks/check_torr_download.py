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
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_GET(self):
        query = parse_qs(urlparse(self.path).query)
        assert urlparse(self.path).path == '/stream'
        mode = query['link'][0][-1]
        if mode in ('7', '8'):
            self.send_response(401 if mode == '7' else 403); self.send_header('Content-Length', '0'); self.end_headers(); return
        if mode == '4':
            self.send_response(302); self.send_header('Location', 'http://example.com/'); self.end_headers(); return
        start = int(self.headers.get('Range', 'bytes=0-')[6:-1])
        if start and mode == '1':
            self.send_response(200)  # server ignored range
        else:
            self.send_response(206 if start else 200)
        tag = '"' + 'a' * 310 + '"' if mode == '9' else ('"changed"' if start and mode == '2' else '"original"')
        self.send_header('ETag', tag)
        self.send_header('Content-Length', str(len(DATA) - start))
        if start:
            self.send_header('Content-Range', f'bytes {start + (1 if mode == "3" else 0)}-{len(DATA)-1}/{len(DATA)}')
        self.end_headers()
        if mode == '5': time.sleep(0.6)
        try: self.wfile.write(DATA[start:] if mode != '6' else DATA[start:start+128])
        except (BrokenPipeError, ConnectionResetError): pass

checks = r'''package tv.orbit.shield
import java.io.RandomAccessFile
import java.io.IOException
import java.nio.file.Files
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
        check(runCatching { TorrDownloadSource("invalid", 1, 1) }.isFailure)
        println("PASS: pause, resume, crash tail, range/identity, completion size, empty file and space reserve")
      }
    } finally { Files.deleteIfExists(path) }
}
'''
server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
thread = Thread(target=server.serve_forever, daemon=True); thread.start()
try:
    with tempfile.TemporaryDirectory(prefix='gostex-transfer-test-') as td:
        folder = Path(td); (folder / 'Checks.kt').write_text(checks)
        cp = str(compiler[1])
        source = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/TorrDownloadEngine.kt'
        result = subprocess.run([str(JAVA), '-cp', ':'.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
            '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(folder / 'classes'), str(source), str(folder / 'Checks.kt')], capture_output=True, text=True)
        if result.returncode: raise SystemExit(result.stderr)
        result = subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{cp}', 'tv.orbit.shield.ChecksKt', str(server.server_port)], capture_output=True, text=True)
        print(result.stdout, end='')
        if result.returncode: raise SystemExit(result.stderr)
finally:
    server.shutdown(); server.server_close(); thread.join()
