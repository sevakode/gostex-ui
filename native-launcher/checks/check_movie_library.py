"""Compile production movie source/storage code against a fake Android boundary."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / '.tools/native/gradle-cache/caches/modules-2/files-2.1'
JAVA = ROOT / '.tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java'
JSON = next((Path.home() / '.gradle/caches/modules-2/files-2.1/org.json/json/20180813').glob('*/*.jar'))

def jar(group, artifact, version):
    return next((CACHE / group / artifact / version).glob('*/*.jar'))

compiler = [jar('org.jetbrains.kotlin', a, v) for a, v in [
    ('kotlin-compiler-embeddable', '2.4.21'), ('kotlin-stdlib', '2.4.21'),
    ('kotlin-script-runtime', '2.4.21'), ('kotlin-reflect', '1.6.10'),
]] + [jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.9.0'), jar('org.jetbrains', 'annotations', '13.0')]
android = '''package android.content
open class Context(val prefs: SharedPreferences = SharedPreferences()) {
    companion object { const val MODE_PRIVATE = 0 }
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
}
class SharedPreferences {
    var raw: String? = null
    fun getString(key: String, default: String?): String? = raw ?: default
    fun edit(): Editor = Editor(this)
    class Editor(private val prefs: SharedPreferences) {
        private var next: String? = null
        fun putString(key: String, value: String): Editor { next = value; return this }
        fun commit(): Boolean { prefs.raw = next; return true }
    }
}
'''
uri = '''package android.net
class Uri private constructor(private val u: java.net.URI) {
    val scheme get() = u.scheme
    val host get() = u.host
    val userInfo get() = u.userInfo
    val authority get() = u.authority
    val schemeSpecificPart get() = u.schemeSpecificPart
    companion object {
        fun parse(value: String) = Uri(java.net.URI(value))
        fun decode(value: String) = java.net.URLDecoder.decode(value, "UTF-8")
    }
}
'''
checks = '''package tv.orbit.shield
import android.content.Context
import org.json.JSONObject
fun main() {
    val ctx = Context()
    val hash = "0123456789abcdef0123456789abcdef01234567"
    val magnet = "magnet:?xt=urn:btih:$hash&dn=Film"
    check(MovieLibraryStore.validSource(magnet))
    check(MovieLibraryStore.validSource("magnet:?xt=urn%3Abtih%3A$hash"))
    check(!MovieLibraryStore.validSource("magnet:?xt=urn:btih:broken"))
    check(!MovieLibraryStore.validSource("file:///data/private"))
    check(MovieLibraryStore.validSource("content://docs/123"))
    val movie = SavedMovie("tt123", "Movie", "2024", null, listOf(MovieSource("My link", magnet)))
    check(MovieLibraryStore.save(ctx, movie))
    check(MovieLibraryStore.read(ctx) == listOf(movie))
    check(MovieLibraryStore.save(ctx, movie.copy(title = "New title")))
    check(MovieLibraryStore.read(ctx).single().sources.single().uri == magnet)
    val broken = "{bad"
    ctx.prefs.raw = broken
    check(runCatching { MovieLibraryStore.save(ctx, movie) }.isFailure)
    check(ctx.prefs.raw == broken)
    val response = JSONObject().put("streams", org.json.JSONArray()
        .put(JSONObject().put("name", "torrent").put("infoHash", hash).put("fileIdx", 3))
        .put(JSONObject().put("name", "bad").put("url", "file:///secret"))
        .put(JSONObject().put("name", "http").put("url", "https://example.com/movie.mp4")))
    val streams = MovieAddonClient.parseStreams(response)
    check(streams.size == 2 && streams[0].fileIndex == 3 && streams[0].uri == "magnet:?xt=urn:btih:$hash")
    check(streams[1].uri == "https://example.com/movie.mp4")
    println("PASS: movie data round-trip, damaged-state protection, safe source validation and add-on selection")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-movies-') as td:
    folder = Path(td)
    (folder / 'Context.kt').write_text(android)
    (folder / 'Uri.kt').write_text(uri)
    (folder / 'Checks.kt').write_text(checks)
    cp = f'{compiler[1]}:{JSON}'
    src = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
    result = subprocess.run([str(JAVA), '-cp', ':'.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(folder / 'classes'),
        str(src / 'MovieLibraryStore.kt'), str(src / 'MovieAddonClient.kt'),
        str(folder / 'Context.kt'), str(folder / 'Uri.kt'), str(folder / 'Checks.kt')], capture_output=True, text=True)
    if result.returncode:
        raise SystemExit(result.stderr)
    result = subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{cp}', 'tv.orbit.shield.ChecksKt'], capture_output=True, text=True)
    print(result.stdout, end='')
    if result.returncode:
        raise SystemExit(result.stderr)
