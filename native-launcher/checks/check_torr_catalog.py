"""Test the actual TorrServer catalog adapter using a controlled HTTP server."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
import json
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / '.tools/native/gradle-cache/caches/modules-2/files-2.1'
JAVA = ROOT / '.tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java'
def jar(group, artifact, version): return next((CACHE / group / artifact / version).glob('*/*.jar'))
compiler = [jar('org.jetbrains.kotlin', a, v) for a, v in [
    ('kotlin-compiler-embeddable', '2.4.21'), ('kotlin-stdlib', '2.4.21'),
    ('kotlin-script-runtime', '2.4.21'), ('kotlin-reflect', '1.6.10'),
]] + [jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.9.0'), jar('org.jetbrains', 'annotations', '13.0')]
json_jar = next((Path.home() / '.gradle/caches/modules-2/files-2.1/org.json/json/20180813').glob('*/*.jar'))
ENTRY = {'hash':'a'*40, 'title':'test', 'file_stats':[{'id':1,'path':'dir/game.bin','length':4096}], 'download_speed':259364.74951573115, 'connected_seeders':3}
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_GET(self):
        if self.path == '/stream?link=' + 'b'*40 + '&stat=1':
            time.sleep(0.6)
            self.send_response(200); self.send_header('Content-Length', '2'); self.end_headers()
            try: self.wfile.write(b'{}')
            except (BrokenPipeError, ConnectionResetError): pass
            return
        assert self.path == '/stream?link=' + 'a'*40 + '&stat=1'
        payload = json.dumps(ENTRY).encode()
        self.send_response(200); self.send_header('Content-Length', str(len(payload))); self.end_headers(); self.wfile.write(payload)
    def do_POST(self):
        body = self.rfile.read(int(self.headers['Content-Length']))
        if self.path == '/torrents':
            request = json.loads(body)
            assert request['action'] in ('list', 'get', 'add', 'rem')
            if request['action'] == 'add': assert request['save_to_db'] and (request['link'].startswith('magnet:?') or request['link'] == 'https://example.com/file.torrent')
            if request['action'] == 'rem': assert request == {'action': 'rem', 'hash': 'a'*40}
            result = ENTRY
            if request['action'] == 'get' and request.get('hash') == 'b'*40:
                result = {'hash':'b'*40,'title':'unknown'}
            if request['action'] == 'get' and request.get('hash') == 'c'*40:
                result = {'hash':'c'*40,'title':'bad','download_speed':-1,'connected_seeders':3.5}
            if request['action'] == 'get' and request.get('hash') == 'd'*40:
                result = {'hash':'d'*40,'file_stats':[{'id':1,'path':'first.mkv','length':5},{'id':1,'path':'second.mkv','length':6}]}
            payload = b'' if request['action'] == 'rem' else json.dumps([result] if request['action'] == 'list' else result).encode()
        elif self.path == '/torrent/upload':
            assert b'name="save"\r\n\r\ntrue' in body and b'name="file"' in body and b'fixture-bytes' in body
            payload = json.dumps(ENTRY).encode()
        else: raise AssertionError(self.path)
        self.send_response(200); self.send_header('Content-Length', str(len(payload))); self.end_headers(); self.wfile.write(payload)
checks = '''package tv.orbit.shield
import java.io.ByteArrayInputStream
fun main(args: Array<String>) {
    val port = args[0].toInt()
    if (args.size > 1) {
        val entries = TorrServerCatalog.list(port)
        println("PASS: live catalog: ${entries.size} torrents, ${entries.sumOf { it.files.size }} files")
        if (entries.isNotEmpty()) check(TorrServerCatalog.get(entries.first().hash, port).hash == entries.first().hash)
        return
    }
    val hash = "a".repeat(40)
    val entry = TorrServerCatalog.list(port).single()
    check(entry.hash == hash && entry.title == "test" && entry.files.single() == TorrCatalogFile(1,"dir/game.bin",4096))
    check(entry.downloadBytesPerSecond == 259364L && entry.connectedSeeders == 3)
    val unknown = TorrServerCatalog.get("b".repeat(40),port)
    check(unknown.downloadBytesPerSecond == null && unknown.connectedSeeders == null)
    val malformed = TorrServerCatalog.get("c".repeat(40),port)
    check(malformed.downloadBytesPerSecond == null && malformed.connectedSeeders == null)
    check(runCatching { TorrServerCatalog.get("d".repeat(40),port) }.isFailure)
    check(TorrServerCatalog.get(hash,port) == entry)
    check(TorrServerCatalog.loadFiles(hash,port) == entry)
    check(runCatching { TorrServerCatalog.loadFiles("b".repeat(40),port,100) }.exceptionOrNull() is TorrMetadataTimeout)
    check(TorrServerCatalog.addMagnet("magnet:?xt=urn:btih:$hash",port) == entry)
    check(TorrServerCatalog.addTorrentUrl("https://example.com/file.torrent",port) == entry)
    check(runCatching { TorrServerCatalog.addTorrentUrl("https://user:secret@example.com/file.torrent",port) }.isFailure)
    check(runCatching { TorrServerCatalog.addTorrentUrl("file:///tmp/file.torrent",port) }.isFailure)
    check(runCatching { TorrServerCatalog.addTorrentUrl("https://example.com/movie.mp4",port) }.isFailure)
    check(TorrServerCatalog.upload(ByteArrayInputStream("fixture-bytes".toByteArray()),port) == entry)
    TorrServerCatalog.remove(hash,port)
    check(runCatching { TorrServerCatalog.remove("invalid", port) }.isFailure)
    check(runCatching { TorrServerCatalog.get("invalid", port) }.isFailure)
    check(runCatching { TorrServerCatalog.addMagnet("http://example.com", port) }.isFailure)
    check(runCatching { TorrServerCatalog.upload(ByteArrayInputStream(ByteArray(4*1024*1024+1)),port) }.isFailure)
    println("PASS: catalog list, file metadata, magnet, torrent upload and input bounds")
}
'''
server = ThreadingHTTPServer(('127.0.0.1', 0), Handler); thread = Thread(target=server.serve_forever,daemon=True); thread.start()
try:
    with tempfile.TemporaryDirectory(prefix='gostex-catalog-test-') as td:
        folder=Path(td); (folder/'Checks.kt').write_text(checks); cp=f'{compiler[1]}:{json_jar}'
        source=ROOT/'native-launcher/app/src/main/java/tv/orbit/shield/TorrServerCatalog.kt'
        result=subprocess.run([str(JAVA),'-cp',':'.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
            '-no-stdlib','-no-reflect','-classpath',cp,'-d',str(folder/'classes'),str(source),str(folder/'Checks.kt')],capture_output=True,text=True)
        if result.returncode: raise SystemExit(result.stderr)
        result=subprocess.run([str(JAVA),'-cp',f'{folder / "classes"}:{cp}','tv.orbit.shield.ChecksKt',str(server.server_port)],capture_output=True,text=True)
        print(result.stdout,end='')
        if result.returncode: raise SystemExit(result.stderr)
        if len(sys.argv) == 3 and sys.argv[1] == '--live-port':
            result=subprocess.run([str(JAVA),'-cp',f'{folder / "classes"}:{cp}','tv.orbit.shield.ChecksKt',sys.argv[2],'live'],capture_output=True,text=True)
            print(result.stdout,end='')
            if result.returncode: raise SystemExit(result.stderr)
finally:
    server.shutdown(); server.server_close(); thread.join()
