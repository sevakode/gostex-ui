"""Check the actual direct HTTP preflight against two synthetic loopback servers."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import subprocess
import tempfile
from threading import Event, Thread
from urllib.parse import urlsplit

from kotlin_toolchain import COMPILER, JAVA, ROOT


class Fixture(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        target = urlsplit(self.path)
        self.server.requests.append((self.path, self.headers.get('Range'), self.headers.get('Accept-Encoding')))
        if target.path in ('/redirect', '/cross-origin', '/fragment', '/loop'):
            destination = {
                '/redirect': '/file?private=other',
                '/cross-origin': self.server.second_origin + '/file',
                '/fragment': '/file#secret-fragment',
                '/loop': '/loop',
            }[target.path]
            return self.reply(302, [('Location', destination)])
        if target.path == '/query-redirect' and target.query == 'private=initial':
            return self.reply(307, [('Location', '?private=final')])
        if target.path in ('/auth', '/gone', '/invalid'):
            return self.reply({'/auth': 403, '/gone': 410, '/invalid': 500}[target.path])
        if target.path in ('/unranged', '/unranged-chunked', '/unranged-no-size', '/unranged-zero', '/unranged-partial'):
            headers = [('Content-Length', '1048576'), ('ETag', '"still-not-resumable"')]
            if target.path == '/unranged-chunked':
                headers.append(('Transfer-Encoding', 'chunked'))
            elif target.path == '/unranged-no-size':
                headers.pop(0)
            elif target.path == '/unranged-zero':
                headers[0] = ('Content-Length', '0')
            elif target.path == '/unranged-partial':
                headers.append(('Content-Range', 'bytes 0-0/1048576'))
            self.reply(200, headers)
            self.server.stall.wait(2)  # No body is sent; admitting the file must not wait for it.
            return
        if target.path == '/silent':
            self.server.stall.wait(2)
            return
        headers = [('Content-Length', '1'), ('Content-Range', 'bytes 0-0/1048576'), ('ETag', '"private-validator"')]
        if target.path == '/weak':
            headers[-1] = ('ETag', 'W/"private-validator"')
        elif target.path == '/no-validator':
            headers.pop()
        elif target.path == '/malformed-validator':
            headers[-1] = ('ETag', 'not-an-entity-tag')
        elif target.path == '/duplicate':
            headers.append(('ETag', '"different"'))
        elif target.path == '/unknown-length':
            headers[1] = ('Content-Range', 'bytes 0-0/*')
        elif target.path == '/range-mismatch':
            headers[1] = ('Content-Range', 'bytes 0-1/1048576')
        elif target.path == '/overflow':
            headers[1] = ('Content-Range', 'bytes 0-0/9223372036854775808')
        elif target.path == '/overlong':
            headers[0] = ('Content-Length', '20')
        elif target.path == '/encoded':
            headers.append(('Content-Encoding', 'gzip'))
        elif target.path == '/chunked':
            headers.append(('Transfer-Encoding', 'chunked'))
        self.reply(206, headers, b'' if target.path == '/truncated' else b'x')

    def reply(self, code, headers=(), body=b''):
        self.send_response(code)
        for key, data in headers:
            self.send_header(key, data)
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass


CHECKS = r'''package tv.orbit.shield

import java.util.concurrent.CancellationException

fun main(args: Array<String>) {
    val base = args.single()
    fun reject(url: String, expected: HttpDownloadAdmissionError, allowCleartext: Boolean = true, timeout: Int = 5000) {
        val failure = runCatching { HttpDownloadAdmission.probe(url, allowCleartext, timeout) }.exceptionOrNull()
        check(failure is HttpDownloadAdmissionException && failure.reason == expected) {
            "Expected safe $expected error, got ${failure?.javaClass?.simpleName}"
        }
        check(failure.cause == null && failure.message == expected.name && !failure.toString().contains("private"))
    }
    val raw = "$base/file?private=fixture&second=1"
    reject(raw, HttpDownloadAdmissionError.CLEARTEXT_CONFIRMATION, allowCleartext = false)
    for (invalid in listOf("file:///tmp/fixture", "//127.0.0.1/file", "http:/file", "$base/file#private",
        "$base/file\n?private=fixture", "http://user:private@127.0.0.1/file", "https://example.invalid:0/file",
        "https://example.invalid:65536/file", "https://example.invalid:/file", "https://example.invalid/%gg?private=fixture",
        "$base/?private=" + "x".repeat(8192))) reject(invalid, HttpDownloadAdmissionError.INVALID_LINK)
    check(runCatching { HttpDownloadAdmission.probe(raw, true, cancelled = { true }) }.exceptionOrNull() is CancellationException)
    val success = HttpDownloadAdmission.probe(raw, true)
    check(success.url.serialized == raw && success.size == 1048576L && success.strongEtag == "\"private-validator\"")
    check(success.resourceKey == success.url.resourceKey() && success.resourceKey.length == 64)
    check(!success.toString().contains("private") && !success.url.toString().contains(base))
    check(HttpDownloadUrl.parse("HTTPS://EXAMPLE.invalid:443/file?p=x").resourceKey() ==
        HttpDownloadUrl.parse("https://example.invalid/file?p=x").resourceKey())
    check(HttpDownloadUrl.parse("$base/file?a=1&b=2").resourceKey() != HttpDownloadUrl.parse("$base/file?b=2&a=1").resourceKey())
    val redirected = HttpDownloadAdmission.probe("$base/redirect?private=fixture", true)
    check(redirected.url.serialized == "$base/redirect?private=fixture" &&
        redirected.resourceKey == HttpDownloadUrl.parse("$base/file?private=other").resourceKey())
    val queryOnly = HttpDownloadAdmission.probe("$base/query-redirect?private=initial", true)
    check(queryOnly.resourceKey == HttpDownloadUrl.parse("$base/query-redirect?private=final").resourceKey())
    check(runCatching { HttpDownloadUrl.parse("https://example.invalid/file").redirect("http://example.invalid/file") }
        .exceptionOrNull() is HttpDownloadAdmissionException)
    for (unsafe in listOf("cross-origin", "fragment", "loop")) reject("$base/$unsafe?private=fixture", HttpDownloadAdmissionError.REDIRECT)
    for (unsupported in listOf("malformed-validator", "unknown-length", "range-mismatch", "overflow", "overlong", "encoded", "chunked",
        "unranged-no-size", "unranged-zero", "unranged-chunked", "unranged-partial")) {
        reject("$base/$unsupported?private=fixture", HttpDownloadAdmissionError.UNSUPPORTED)
    }
    for (restartOnly in listOf("unranged", "weak", "no-validator")) {
        val admitted = HttpDownloadAdmission.probe("$base/$restartOnly?private=fixture", true, timeoutMs = 500)
        check(admitted.size == 1048576L && admitted.strongEtag == null && admitted.resourceKey == admitted.url.resourceKey())
    }
    reject("$base/auth?private=fixture", HttpDownloadAdmissionError.AUTH)
    reject("$base/gone?private=fixture", HttpDownloadAdmissionError.NOT_FOUND)
    reject("$base/duplicate?private=fixture", HttpDownloadAdmissionError.RESPONSE)
    reject("$base/invalid?private=fixture", HttpDownloadAdmissionError.RESPONSE)
    reject("$base/truncated?private=fixture", HttpDownloadAdmissionError.RESPONSE)
    reject("$base/silent?private=fixture", HttpDownloadAdmissionError.TIMEOUT, timeout = 120)
    println("PASS: resumable and known-length restart-only admission, private URL/validator, redirect and explicit failures")
}
'''

with ThreadingHTTPServer(('127.0.0.1', 0), Fixture) as origin, ThreadingHTTPServer(('127.0.0.1', 0), Fixture) as other:
    for server in (origin, other):
        server.requests = []
        server.stall = Event()
    origin.second_origin = f'http://127.0.0.1:{other.server_port}'
    workers = [Thread(target=server.serve_forever, daemon=True) for server in (origin, other)]
    for worker in workers:
        worker.start()
    try:
        with tempfile.TemporaryDirectory(prefix='gostex-http-admission-') as temp:
            folder = Path(temp)
            check = folder / 'Checks.kt'
            check.write_text(CHECKS, encoding='utf-8')
            classes = folder / 'classes'
            source = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/HttpDownloadAdmission.kt'
            cp = f'{COMPILER[1]}:{COMPILER[4]}'
            subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                            '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(classes), str(source), str(check)],
                           check=True, timeout=60)
            subprocess.run([str(JAVA), '-cp', f'{classes}:{cp}', 'tv.orbit.shield.ChecksKt',
                            f'http://127.0.0.1:{origin.server_port}'], check=True, timeout=15)
        assert origin.requests[0] == ('/file?private=fixture&second=1', 'bytes=0-0', 'identity')
        assert sum(path == '/file?private=fixture&second=1' for path, *_ in origin.requests) == 1
        assert sum(path.startswith('/loop') for path, *_ in origin.requests) == 4
        assert all((byte_range, encoding) == ('bytes=0-0', 'identity') for _, byte_range, encoding in origin.requests)
        assert not other.requests, 'Cross-origin redirect contacted a second origin'
    finally:
        for server in (origin, other):
            server.stall.set()
            server.shutdown()
        for worker in workers:
            worker.join(timeout=2)
