"""Exercise the real Kotlin client against a loopback server, without an Android device."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / '.tools/native/gradle-cache/caches/modules-2/files-2.1'
JAVA = ROOT / '.tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java'

def jar(group, artifact, version):
    return next((CACHE / group / artifact / version).glob('*/*.jar'))

compiler = [jar('org.jetbrains.kotlin', a, v) for a, v in [
    ('kotlin-compiler-embeddable', '2.4.21'), ('kotlin-stdlib', '2.4.21'),
    ('kotlin-script-runtime', '2.4.21'), ('kotlin-reflect', '1.6.10'),
]] + [jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.9.0'), jar('org.jetbrains', 'annotations', '13.0')]
json_jar = next((Path.home() / '.gradle/caches/modules-2/files-2.1/org.json/json/20180813').glob('*/*.jar'))

class Handler(BaseHTTPRequestHandler):
    case = 0
    requests = []
    def log_message(self, *args):
        pass
    def do_GET(self):
        Handler.case += 1
        Handler.requests.append(('GET', self.path))
        if self.path != '/echo':
            self.send_error(404); return
        payload = b'MatriX.150'
        self.send_response(200)
        self.send_header('Content-Length', str(1048577 if Handler.case == 4 else len(payload)))
        self.end_headers()
        if Handler.case != 4: self.wfile.write(payload)
    def do_POST(self):
        body = self.rfile.read(int(self.headers['Content-Length']))
        Handler.requests.append(('POST', self.path, body))
        if self.path == '/settings':
            assert body == b'{"action":"get"}'
            if Handler.case == 8:
                self.send_response(500); self.end_headers(); return
            payload = {1:b'{"UseDisk":false,"CacheSize":67108864}', 6:b'{"UseDisk":true,"CacheSize":33554432}', 7:b'{"UseDisk":"false","CacheSize":-1}'}[Handler.case]
            self.send_response(200); self.send_header('Content-Length',str(len(payload))); self.end_headers(); self.wfile.write(payload); return
        assert self.path == '/torrents' and body == b'{"action":"list"}'
        if Handler.case == 2:
            self.send_response(401); self.end_headers(); return
        if Handler.case == 3:
            self.send_response(302); self.send_header('Location', 'http://example.com/'); self.end_headers(); return
        body = b'not-json' if Handler.case == 5 else b'[{"download_speed":1024.5,"title":"private"},{"download_speed":512.9}]'
        self.send_response(200); self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)

checks = '''package tv.orbit.shield
fun main(args: Array<String>) {
    val port = args[0].toInt()
    check(TorrServerStatusClient.probe(port) == TorrServerStatus.Online("MatriX.150", 2, 1536, TorrCacheInfo(false, 67108864)))
    check(TorrServerStatusClient.probe(port) == TorrServerStatus.AuthenticationRequired)
    check(TorrServerStatusClient.probe(port) == TorrServerStatus.Unavailable) // redirect is not followed
    check(TorrServerStatusClient.probe(port) == TorrServerStatus.Unavailable) // response limit
    check(TorrServerStatusClient.probe(port) == TorrServerStatus.Unavailable) // malformed response
    check(TorrServerStatusClient.probe(port) == TorrServerStatus.Online("MatriX.150", 2, 1536, TorrCacheInfo(true, 33554432)))
    check(TorrServerStatusClient.probe(port) == TorrServerStatus.Online("MatriX.150", 2, 1536, null)) // invalid mode
    check(TorrServerStatusClient.probe(port) == TorrServerStatus.Online("MatriX.150", 2, 1536, null)) // settings unavailable
    check(runCatching { TorrServerStatusClient.probe(0) }.isFailure)
    println("PASS: status, optional cache, authentication, redirect, size limit and malformed response")
}
'''
server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
thread = Thread(target=server.serve_forever, daemon=True)
thread.start()
try:
    with tempfile.TemporaryDirectory(prefix='gostex-torr-status-') as td:
        folder = Path(td)
        (folder / 'Checks.kt').write_text(checks)
        cp = f'{compiler[1]}:{json_jar}'
        source = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/TorrServerStatus.kt'
        result = subprocess.run([str(JAVA), '-cp', ':'.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
            '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(folder / 'classes'),
            str(source), str(folder / 'Checks.kt')], capture_output=True, text=True)
        if result.returncode: raise SystemExit(result.stderr)
        result = subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{cp}', 'tv.orbit.shield.ChecksKt', str(server.server_port)], capture_output=True, text=True)
        print(result.stdout, end='')
        if result.returncode: raise SystemExit(result.stderr)
        assert all(req[1] in ('/echo', '/torrents', '/settings') for req in Handler.requests)
finally:
    server.shutdown()
    server.server_close()
    thread.join()
