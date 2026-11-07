"""Exercise official-client release selection and source restrictions on the JVM."""
from pathlib import Path
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
checks = r'''package tv.orbit.shield
import org.json.JSONObject
import org.json.JSONArray
fun main() {
    fun item(url: String = "https://github.com/YouROK/TorrServe/releases/download/v1/TorrServe_v1.apk", size: Long = 123, digest: String = "sha256:" + "a".repeat(64)) =
        JSONObject().put("name", "TorrServe_v1.apk").put("browser_download_url", url).put("size", size).put("digest", digest)
    fun doc(vararg entries: JSONObject) = JSONObject().put("assets", JSONArray(entries.toList())).toString()
    val asset = TorrServeReleasePolicy.asset(doc(item()))
    check(asset.bytes == 123L && asset.sha256 == "a".repeat(64))
    check(TorrServeReleasePolicy.allowedUrl("https://release-assets.githubusercontent.com/file?token=abc"))
    check(!TorrServeReleasePolicy.allowedUrl("http://github.com/path"))
    check(!TorrServeReleasePolicy.allowedUrl("https://github.com.evil.example/path"))
    check(!TorrServeReleasePolicy.allowedUrl("https://user@github.com/path"))
    check(!TorrServeReleasePolicy.allowedUrl("https://github.com:444/path"))
    check(runCatching { TorrServeReleasePolicy.asset(doc(item(), item())) }.isFailure)
    check(runCatching { TorrServeReleasePolicy.asset(doc(item(url = "https://evil.example/file"))) }.isFailure)
    check(runCatching { TorrServeReleasePolicy.asset(doc(item(size = 0))) }.isFailure)
    check(runCatching { TorrServeReleasePolicy.asset(doc(item(size = 100_000_000))) }.isFailure)
    check(runCatching { TorrServeReleasePolicy.asset(doc(item(digest = "bad"))) }.isFailure)
    check(runCatching { TorrServeReleasePolicy.asset("{}") }.isFailure)
    println("PASS: official APK selection, host restrictions, size and SHA-256 metadata")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-torr-install-') as td:
    folder = Path(td); (folder / 'Checks.kt').write_text(checks)
    cp = f'{compiler[1]}:{json_jar}'
    source = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/TorrServeReleasePolicy.kt'
    result = subprocess.run([str(JAVA), '-cp', ':'.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(folder/'classes'), str(source), str(folder/'Checks.kt')], capture_output=True, text=True)
    if result.returncode: raise SystemExit(result.stderr)
    result = subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{cp}', 'tv.orbit.shield.ChecksKt'], capture_output=True, text=True)
    print(result.stdout, end='')
    if result.returncode: raise SystemExit(result.stderr)
