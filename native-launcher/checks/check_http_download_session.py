"""Exercise authenticated HTTPS admission/resume with a temporary loopback trust store."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import ssl
import subprocess
import tempfile
from threading import Thread
from urllib.parse import urlsplit

from kotlin_toolchain import COMPILER, JAVA, ROOT


FILE = b'file-fixture'
ETAG = '"fixture-content"'


class Fixture(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        path = urlsplit(self.path).path
        cookies = self.headers.get('Cookie')
        self.server.requests.append((path, self.headers.get('Range'), self.headers.get('If-Range')))
        if path == '/cross-origin':
            return self.reply(302, [('Location', self.server.other_origin + '/file')])
        if path == '/start' and cookies == 'fixture=entry':
            return self.reply(302, [('Location', '/file?fixture=private')])
        if path != '/file' or cookies not in ('fixture=attachment', 'fixture=expired', 'fixture=changed'):
            return self.reply(403)
        if cookies == 'fixture=expired':
            return self.reply(200, [('Content-Type', 'text/html')], b'<p>login</p>')
        begin, end = self.headers['Range'].removeprefix('bytes=').split('-', 1)
        lower, upper = int(begin), int(end) if end else len(FILE) - 1
        self.reply(206, [
            ('Content-Range', f'bytes {lower}-{upper}/{len(FILE)}'),
            ('ETag', ETAG if cookies == 'fixture=attachment' else '"changed-fixture"'),
            ('Content-Type', 'application/octet-stream'),
            ('Content-Disposition', 'attachment; filename="fixture.bin"'),
        ], FILE[lower:upper + 1])

    def reply(self, code, headers=(), body=b''):
        self.send_response(code)
        self.send_header('Content-Length', str(len(body)))
        for key, value in headers:
            self.send_header(key, value)
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass


CHECKS = r'''package tv.orbit.shield

import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val base = args.single()
    val page = "$base/login#entered"
    val attachment = "$base/start?fixture=token"
    val mime = "Application/Octet-Stream; charset=binary"
    val fileUrl = "$base/file?fixture=private"
    var fileCookie: String? = "fixture=attachment"
    val queried = mutableListOf<String>()
    val lookup: (String) -> String? = { endpoint ->
        queried += endpoint
        if (endpoint == fileUrl) fileCookie else "fixture=entry"
    }
    check(HttpDownloadSession.create(page.replace("https:", "http:"), attachment, 12, mime, lookup) == null)
    check(HttpDownloadSession.create(page, attachment, 12, "text/html", lookup) == null)
    check(queried.isEmpty())
    val session = requireNotNull(HttpDownloadSession.create(page, attachment, 12, mime, lookup))
    val mismatched = runCatching { HttpDownloadAdmission.probe("$base/other", session = session) }.exceptionOrNull()
    check(mismatched is HttpDownloadAdmissionException && mismatched.reason == HttpDownloadAdmissionError.AUTH && queried.isEmpty())

    val admitted = runBlocking { HttpDownloadAdmission.probeCancellable(attachment, session = session) }
    check(admitted.session === session && admitted.size == 12L && admitted.strongEtag == "\"fixture-content\"")
    check(queried == listOf(attachment, fileUrl))
    check(!session.toString().contains(base) && !admitted.toString().contains("token"))

    val cross = "$base/cross-origin"
    val crossSession = requireNotNull(HttpDownloadSession.create(page, cross, 12, mime, lookup))
    val denied = runCatching { HttpDownloadAdmission.probe(cross, session = crossSession) }.exceptionOrNull()
    check(denied is HttpDownloadAdmissionException && denied.reason == HttpDownloadAdmissionError.REDIRECT)
    check(queried == listOf(attachment, fileUrl, cross))

    val source = HttpDownloadOrigin(null, admitted.resourceKey, admitted.strongEtag, sessionBound = true)
    val saved = TorrDownloadCheckpoint(4, admitted.strongEtag)
    val path = Files.createTempFile("gostex-session-test-", ".part")
    try {
        val partial = "fileuntrusted-tail".toByteArray()
        Files.write(path, partial)
        RandomAccessFile(path.toFile(), "rw").use { output ->
            val target = TorrFileTarget(output)
            fun expectNoWrite(grant: HttpDownloadSession?, reason: String) {
                val failure = runCatching { TorrDownloadEngine.transfer(source, 12, target, saved,
                    saveCheckpoint = { error("Unexpected checkpoint") }, session = grant) }.exceptionOrNull()
                check(failure is TorrSourceFailure && failure.reason == reason && failure.cause == null)
                check(Files.readAllBytes(path).contentEquals(partial))
            }
            expectNoWrite(null, "source_auth")
            fileCookie = "fixture=active\r\nX-Injected: yes"
            expectNoWrite(session, "source_auth")
            fileCookie = "fixture=expired"
            expectNoWrite(session, "source_auth")
            fileCookie = "fixture=changed"
            expectNoWrite(session, "source_changed")
            fileCookie = "fixture=attachment"
            var durable = saved
            val done = TorrDownloadEngine.transfer(source, 12, target, saved,
                saveCheckpoint = { durable = it }, session = session)
            check(done == durable && done.offset == 12L && Files.readString(path) == "file-fixture")
            check(TorrDownloadEngine.transfer(source, 12, target, done,
                saveCheckpoint = { error("Offline finalization changed state") }) == done)
        }
    } finally { Files.deleteIfExists(path) }
    println("PASS: HTTPS attachment, hop-local cookies, rejected redirect and expired session with durable resume")
}
'''

with tempfile.TemporaryDirectory(prefix='gostex-http-session-') as temp:
    folder = Path(temp)
    cert, key, store = (folder / name for name in ('cert.pem', 'key.pem', 'trust.p12'))
    subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                    '-subj', '/CN=127.0.0.1', '-addext', 'subjectAltName=IP:127.0.0.1',
                    '-out', str(cert), '-keyout', str(key)], check=True, stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL, timeout=20)
    subprocess.run([str(JAVA.with_name('keytool')), '-importcert', '-noprompt', '-alias', 'fixture', '-file', str(cert),
                    '-storetype', 'PKCS12', '-keystore', str(store), '-storepass', 'fixture-only'],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(cert, key)
    with ThreadingHTTPServer(('127.0.0.1', 0), Fixture) as origin, ThreadingHTTPServer(('127.0.0.1', 0), Fixture) as other:
        origin.other_origin = f'https://127.0.0.1:{other.server_port}'
        for server in (origin, other):
            server.requests = []
            server.socket = tls.wrap_socket(server.socket, server_side=True)
        workers = [Thread(target=server.serve_forever, daemon=True) for server in (origin, other)]
        for worker in workers:
            worker.start()
        try:
            code, classes = folder / 'Checks.kt', folder / 'classes'
            code.write_text(CHECKS, encoding='utf-8')
            app = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
            cp = f'{COMPILER[1]}:{COMPILER[4]}'
            subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                            '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(classes),
                            str(app / 'HttpDownloadAdmission.kt'), str(app / 'TorrDownloadEngine.kt'), str(code)],
                           check=True, timeout=60)
            subprocess.run([str(JAVA), f'-Djavax.net.ssl.trustStore={store}', '-Djavax.net.ssl.trustStorePassword=fixture-only',
                            '-cp', f'{classes}:{cp}', 'tv.orbit.shield.ChecksKt', f'https://127.0.0.1:{origin.server_port}'],
                           check=True, timeout=20)
            assert not other.requests
            assert origin.requests.count(('/file', 'bytes=4-', ETAG)) == 3
        finally:
            for server in (origin, other):
                server.shutdown()
            for worker in workers:
                worker.join(timeout=2)
