"""Test the actual TorrServer catalog adapter using a controlled HTTP server."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
import json
import subprocess
import sys
import tempfile
import time
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / '.tools/native/gradle-cache/caches/modules-2/files-2.1'
JAVA = ROOT / '.tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java'
def jar(group, artifact, version): return next((CACHE / group / artifact / version).glob('*/*.jar'))
compiler = [jar('org.jetbrains.kotlin', a, v) for a, v in [
    ('kotlin-compiler-embeddable', '2.4.21'), ('kotlin-stdlib', '2.4.21'),
    ('kotlin-script-runtime', '2.4.21'), ('kotlin-reflect', '1.6.10'),
]] + [jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.9.0'), jar('org.jetbrains', 'annotations', '13.0')]
json_jar = next((Path.home() / '.gradle/caches/modules-2/files-2.1/org.json/json/20180813').glob('*/*.jar'))
ENTRY = {'hash':'a'*40, 'title':'test', 'file_stats':[{'id':1,'path':'dir/game.bin','length':4096}], 'download_speed':259364.74951573115, 'connected_seeders':3, 'preloaded_bytes':4194304, 'preload_size':33554432, 'stat':2}
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_GET(self):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        if parsed.path == '/stream' and query.get('play') == ['1']:
            index = int(query['index'][0]); total = 10_000 if index == 11 else 200_000
            assert query['link'] == ['a'*40]
            start, end = map(int, self.headers['Range'].removeprefix('bytes=').split('-'))
            self.server.ranges.append((index, start, end))
            self.send_response(200 if index == 8 else 206)
            self.send_header('Content-Range', f'bytes {start}-{end}/{total if index != 9 else total+1}')
            self.send_header('Content-Length', str(end-start+1))
            self.end_headers()
            self.wfile.write(b'x' * (3 if index == 10 else end-start+1))
            self.close_connection = True
            return
        if self.path == '/stream?link=' + 'a'*40 + '&index=2&preload&stat':
            payload = json.dumps(ENTRY).encode()
            self.send_response(200); self.send_header('Content-Length', str(len(payload))); self.end_headers(); self.wfile.write(payload)
            return
        if self.path == '/stream?link=' + 'b'*40 + '&stat=1':
            time.sleep(0.6)
            self.send_response(200); self.send_header('Content-Length', '2'); self.end_headers()
            try: self.wfile.write(b'{}')
            except (BrokenPipeError, ConnectionResetError): pass
            return
        assert self.path in ('/stream?link=' + 'a'*40 + '&stat=1', '/stream?link=' + 'A'*40 + '&stat=1', '/stream?link=' + 'f'*40 + '&stat=1')
        payload = json.dumps(ENTRY).encode()
        self.send_response(200); self.send_header('Content-Length', str(len(payload))); self.end_headers(); self.wfile.write(payload)
    def do_POST(self):
        body = self.rfile.read(int(self.headers['Content-Length']))
        if self.path == '/torrents':
            request = json.loads(body)
            self.server.events.append(request)
            assert request['action'] in ('list', 'get', 'add', 'rem')
            if request['action'] == 'get' and request.get('hash') == '4'*40:
                time.sleep(0.6)
                self.send_response(200); self.send_header('Content-Length', '2'); self.end_headers()
                try: self.wfile.write(b'{}')
                except (BrokenPipeError, ConnectionResetError): pass
                return
            if request['action'] == 'add': assert request['save_to_db'] and (request['link'].startswith('magnet:?') or request['link'] == 'https://example.com/file.torrent')
            if request['action'] == 'rem': assert request == {'action': 'rem', 'hash': 'a'*40}
            if request['action'] == 'get' and request.get('hash') in ('e'*40, '1'*40, '3'*40):
                self.server.fail_next_list = request['hash'] == '1'*40
                self.send_response(404); self.end_headers(); return
            if request['action'] == 'list' and getattr(self.server, 'fail_next_list', False):
                self.server.fail_next_list = False
                self.send_response(404); self.end_headers(); return
            if request['action'] == 'get' and request.get('hash') == '2'*40:
                self.send_response(500); self.end_headers(); return
            result = ENTRY
            if request['action'] == 'add' and request.get('link') == 'magnet:?xt=urn:btih:' + 'e'*40:
                result = {'hash':'e'*40, 'title':'restored'}
            if request['action'] == 'get' and request.get('hash') == 'b'*40:
                result = {'hash':'b'*40,'title':'unknown'}
            if request['action'] == 'get' and request.get('hash') == 'c'*40:
                result = {'hash':'c'*40,'title':'bad','download_speed':-1,'connected_seeders':3.5,'preloaded_bytes':-1,'preload_size':0,'stat':99}
            if request['action'] == 'get' and request.get('hash') == 'd'*40:
                result = {'hash':'d'*40,'file_stats':[{'id':1,'path':'first.mkv','length':5},{'id':1,'path':'second.mkv','length':6}]}
            payload = b'' if request['action'] == 'rem' else json.dumps([result] if request['action'] == 'list' else result).encode()
        elif self.path == '/torrent/upload':
            assert b'name="save"\r\n\r\ntrue' in body and b'name="file"' in body
            self.server.uploads.append(body)
            if b'fixture-bytes' in body: result = ENTRY
            else:
                assert b'd4:infod4:name5:helloee' in body
                result = {'hash':'ceff46bbeb156d9d8ebecb01d6f8bbb8213bb97b', 'title':'restored'}
            payload = json.dumps(result).encode()
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
    val buffer = TorrServerCatalog.preload(hash,2,port)
    check(buffer.cachedBytes == 4194304L && buffer.preloadBytes == 33554432L && buffer.serverState == 2)
    check(runCatching { TorrServerCatalog.preload(hash,0,port) }.isFailure)
    check(TorrServerCatalog.canReadVideoEdges(hash, 7, 200_000, port))
    check(!TorrServerCatalog.canReadVideoEdges(hash, 8, 200_000, port))
    check(!TorrServerCatalog.canReadVideoEdges(hash, 9, 200_000, port))
    check(!TorrServerCatalog.canReadVideoEdges(hash, 10, 200_000, port))
    check(TorrServerCatalog.canReadVideoEdges(hash, 11, 10_000, port))
    check(runCatching { TorrServerCatalog.canReadVideoEdges(hash, 0, 1, port) }.isFailure)
    val unknown = TorrServerCatalog.get("b".repeat(40),port)
    check(unknown.downloadBytesPerSecond == null && unknown.connectedSeeders == null)
    check(unknown.cachedBytes == null && unknown.preloadBytes == null && unknown.serverState == null)
    val malformed = TorrServerCatalog.get("c".repeat(40),port)
    check(malformed.downloadBytesPerSecond == null && malformed.connectedSeeders == null)
    check(malformed.cachedBytes == null && malformed.preloadBytes == null && malformed.serverState == null)
    check(runCatching { TorrServerCatalog.get("d".repeat(40),port) }.isFailure)
    check(runCatching { TorrServerCatalog.get("e".repeat(40),port) }.exceptionOrNull() is TorrEntryMissing)
    check(runCatching { TorrServerCatalog.get("f".repeat(40),port) }.exceptionOrNull() is java.io.IOException)
    val endpointFailure = runCatching { TorrServerCatalog.get("1".repeat(40),port) }.exceptionOrNull()
    check(endpointFailure is java.io.IOException && endpointFailure !is TorrEntryMissing)
    val serverFailure = runCatching { TorrServerCatalog.get("2".repeat(40),port) }.exceptionOrNull()
    check(serverFailure is java.io.IOException && serverFailure !is TorrEntryMissing)
    check(TorrServerCatalog.status(hash,port) == entry)
    check(runCatching { TorrServerCatalog.status("4".repeat(40),port,100) }.exceptionOrNull() is java.net.SocketTimeoutException)
    check(runCatching { TorrServerCatalog.status("f".repeat(40),port) }.isFailure)
    check(runCatching { TorrServerCatalog.status("e".repeat(40),port) }.isFailure)
    check(TorrServerCatalog.get(hash,port) == entry)
    check(TorrServerCatalog.restoreByHash(hash,port) == entry)
    check(TorrServerCatalog.restoreByHash("E".repeat(40),port).hash == "e".repeat(40))
    check(runCatching { TorrServerCatalog.restoreByHash("3".repeat(40),port) }.exceptionOrNull() is java.io.IOException)
    check(runCatching { TorrServerCatalog.restoreByHash("1".repeat(40),port) }.exceptionOrNull() is java.io.IOException)
    check(runCatching { TorrServerCatalog.restoreByHash("2".repeat(40),port) }.exceptionOrNull() is java.io.IOException)
    check(runCatching { TorrServerCatalog.restoreByHash("invalid",port) }.isFailure)
    check(TorrServerCatalog.loadFiles(hash,port) == entry)
    check(TorrServerCatalog.loadFiles(hash.uppercase(),port) == entry)
    check(runCatching { TorrServerCatalog.loadFiles("f".repeat(40),port) }.exceptionOrNull() is java.io.IOException)
    check(runCatching { TorrServerCatalog.loadFiles("b".repeat(40),port,100) }.exceptionOrNull() is TorrMetadataTimeout)
    check(TorrServerCatalog.addMagnet("magnet:?xt=urn:btih:$hash",port) == entry)
    check(TorrServerCatalog.addTorrentUrl("https://example.com/file.torrent",port) == entry)
    check(runCatching { TorrServerCatalog.addTorrentUrl("https://user:secret@example.com/file.torrent",port) }.isFailure)
    check(runCatching { TorrServerCatalog.addTorrentUrl("file:///tmp/file.torrent",port) }.isFailure)
    check(runCatching { TorrServerCatalog.addTorrentUrl("https://example.com/movie.mp4",port) }.isFailure)
    check(TorrServerCatalog.upload(ByteArrayInputStream("fixture-bytes".toByteArray()),port) == entry)
    val torrent = "d4:infod4:name5:helloee".toByteArray()
    val fileHash = "ceff46bbeb156d9d8ebecb01d6f8bbb8213bb97b"
    check(TorrentFileIdentity.infoHash(torrent) == fileHash)
    check(runCatching { TorrServerCatalog.restoreFromFile(ByteArrayInputStream(torrent),hash,port) }.exceptionOrNull() is TorrentIdentityMismatch)
    check(TorrServerCatalog.restoreFromFile(ByteArrayInputStream(torrent),fileHash.uppercase(),port).hash == fileHash)
    for (invalid in listOf("", "d4:info3:abce", "d4:infode4:infodee", "d4:infodeeEXTRA", "d4:infod1:xi-0eee", "d4:infod1:x01:aee", "d4:infod1:x999999999999:aee"))
        check(runCatching { TorrentFileIdentity.infoHash(invalid.toByteArray()) }.isFailure)
    check(runCatching { TorrentFileIdentity.infoHash(ByteArray(4*1024*1024+1)) }.isFailure)
    check(runCatching { TorrentFileIdentity.infoHash(("d4:infod1:x" + "l".repeat(70) + "e".repeat(72)).toByteArray()) }.isFailure)
    TorrServerCatalog.remove(hash,port)
    check(runCatching { TorrServerCatalog.remove("invalid", port) }.isFailure)
    check(runCatching { TorrServerCatalog.get("invalid", port) }.isFailure)
    check(runCatching { TorrServerCatalog.addMagnet("http://example.com", port) }.isFailure)
    check(runCatching { TorrServerCatalog.upload(ByteArrayInputStream(ByteArray(4*1024*1024+1)),port) }.isFailure)
    println("PASS: catalog list, file metadata, magnet, torrent upload and input bounds")
}
'''
server = ThreadingHTTPServer(('127.0.0.1', 0), Handler); server.events = []; server.uploads = []; server.ranges = []; thread = Thread(target=server.serve_forever,daemon=True); thread.start()
try:
    with tempfile.TemporaryDirectory(prefix='gostex-catalog-test-') as td:
        folder=Path(td); (folder/'Checks.kt').write_text(checks); cp=f'{compiler[1]}:{json_jar}'
        source=ROOT/'native-launcher/app/src/main/java/tv/orbit/shield/TorrServerCatalog.kt'
        result=subprocess.run([str(JAVA),'-cp',':'.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
            '-no-stdlib','-no-reflect','-classpath',cp,'-d',str(folder/'classes'),str(source),str(source.parent/'TorrentFileIdentity.kt'),str(source.parent/'TorrPlaylistSafety.kt'),str(folder/'Checks.kt')],capture_output=True,text=True)
        if result.returncode: raise SystemExit(result.stderr)
        result=subprocess.run([str(JAVA),'-cp',f'{folder / "classes"}:{cp}','tv.orbit.shield.ChecksKt',str(server.server_port)],capture_output=True,text=True)
        print(result.stdout,end='')
        if result.returncode: raise SystemExit(result.stderr)
        assert (7, 0, 65535) in server.ranges and (7, 134464, 199999) in server.ranges
        assert [r for r in server.ranges if r[0] == 11] == [(11, 0, 9999)]
        events = server.events
        assert len(server.uploads) == 2  # ordinary upload plus matching restore; mismatch did not reach server
        assert any(e == {'action':'get', 'hash':'e'*40} and events[i+1] == {'action':'get', 'hash':'a'*40} for i,e in enumerate(events[:-1]))  # status 404 has no list fallback
        adds = [event['link'] for event in server.events if event.get('action') == 'add']
        assert adds.count('magnet:?xt=urn:btih:' + 'e'*40) == 1
        assert 'magnet:?xt=urn:btih:' + '1'*40 not in adds and 'magnet:?xt=urn:btih:' + '2'*40 not in adds
        assert adds.count('magnet:?xt=urn:btih:' + 'a'*40) == 1  # explicit existing add, not restore
        if len(sys.argv) == 3 and sys.argv[1] == '--live-port':
            result=subprocess.run([str(JAVA),'-cp',f'{folder / "classes"}:{cp}','tv.orbit.shield.ChecksKt',sys.argv[2],'live'],capture_output=True,text=True)
            print(result.stdout,end='')
            if result.returncode: raise SystemExit(result.stderr)
finally:
    server.shutdown(); server.server_close(); thread.join()
