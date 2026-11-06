"""Exercise the portable game index using test-only org.json; Android uses its own implementation."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import ROOT, JAVA, COMPILER

json_jar = Path(os.environ.get('PORTABLE_TEST_JSON_JAR', ROOT / '.tools/native/test-json/json-20240303.jar'))
expected = '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
if not json_jar.is_file() or hashlib.sha256(json_jar.read_bytes()).hexdigest() != expected:
    raise SystemExit('Provide the verified org.json 20240303 test jar in PORTABLE_TEST_JSON_JAR')
sources = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
checks = r'''package tv.orbit.shield
import org.json.JSONObject

fun main() {
    var checks = 0
    fun ok(b: Boolean, message: String) { check(b) { message }; checks++ }
    val hash = "a".repeat(64)
    val files = listOf(
        PortableIndexedFile("rom-a", listOf("PSP", "日本語 + 100%.iso"), 500, hash),
        PortableIndexedFile("rom-b", listOf("PSP", "other.cso"), 0, hash),
        PortableIndexedFile("cover", listOf("covers", "manual.png"), 42, hash),
    )
    val first = PortableIndexedGame("game:one", "同じタイトル", "PSP", "PPSSPP", "org.example/.Game",
        listOf("rom-a", "rom-b"), "genre", "2000", "developer", "1", "Line one\nСтрока", false,
        "cover", null, null, "manual")
    val other = first.copy(id = "game:two", platform = "Future console", variants = listOf("rom-b"))
    val index = PortableGameIndex(files, listOf(first, other))
    val encoded = PortableGameIndexCodec.encode(index)
    ok(PortableGameIndexCodec.decode(encoded) == index, "full round trip preserves variants, metadata and cover")
    ok(!encoded.contains("content://") && !encoded.contains("/storage/"), "no absolute path or grant encoded")
    ok(PortableGameIndexCodec.decode(PortableGameIndexCodec.encode(PortableGameIndex(emptyList(), emptyList()))).games.isEmpty(), "empty library")
    fun rejected(mutate: (JSONObject) -> Unit): Boolean {
        val obj = JSONObject(encoded); mutate(obj)
        return runCatching { PortableGameIndexCodec.decode(obj.toString()) }.isFailure
    }
    ok(rejected { it.put("version", 3) }, "unknown version rejected")
    val legacy = JSONObject(encoded).put("version", 1)
    for (i in 0 until legacy.getJSONArray("games").length()) {
        val row = legacy.getJSONArray("games").getJSONObject(i)
        listOf("baseCover", "scene", "coverSourceName", "coverSourceUrl", "coverAuthor").forEach(row::remove)
    }
    ok(PortableGameIndexCodec.decode(legacy.toString()) == index, "v1 index remains readable")
    val extended = first.copy(baseCover = "media/cover.png", scene = "media/scene.png", coverSourceName = "source", coverSourceUrl = "https://example.com/art/1", coverAuthor = "author")
    ok(PortableGameIndexCodec.decode(PortableGameIndexCodec.encode(index.copy(games = listOf(extended)))).games.single() == extended, "art references and provenance preserved")
    ok(runCatching { PortableGameIndexCodec.encode(index.copy(games = listOf(extended.copy(coverSourceUrl = "https://example.com/?token=secret")))) }.isFailure, "URL with potential token rejected")
    ok(runCatching { PortableGameIndexCodec.encode(index.copy(games = listOf(extended.copy(baseCover = "content://private/art")))) }.isFailure, "device-specific art URI rejected")
    ok(rejected { it.getJSONArray("files").getJSONObject(0).put("id", "rom-b") }, "duplicate file ID rejected")
    ok(rejected { it.getJSONArray("files").getJSONObject(0).put("path", org.json.JSONArray(listOf("..", "bad"))) }, "parent traversal rejected")
    ok(rejected { it.getJSONArray("files").getJSONObject(0).put("path", org.json.JSONArray(listOf("/absolute"))) }, "absolute path rejected")
    ok(rejected { it.getJSONArray("files").getJSONObject(0).put("sha256", "not-a-digest") }, "bad digest rejected")
    ok(rejected { it.getJSONArray("files").getJSONObject(0).put("bytes", -1) }, "negative size rejected")
    ok(rejected { it.getJSONArray("files").getJSONObject(0).put("bytes", "500") }, "coerced size rejected")
    ok(rejected { it.getJSONArray("games").getJSONObject(0).put("variants", org.json.JSONArray(listOf("missing"))) }, "missing variant rejected")
    ok(rejected { it.getJSONArray("games").getJSONObject(0).put("manualCover", "missing") }, "missing cover rejected")
    ok(rejected { it.getJSONArray("games").getJSONObject(1).put("id", "game:one") }, "duplicate game ID rejected")
    ok(rejected { it.getJSONArray("games").getJSONObject(0).put("coverChoice", "unknown") }, "bad cover mode rejected")
    ok(runCatching { PortableGameIndexCodec.decode("{".repeat(8 * 1024 * 1024 + 1)) }.isFailure, "oversized input rejected")
    ok(runCatching { PortableGameIndexCodec.decode("[".repeat(17) + "]".repeat(17)) }.isFailure, "excessive nesting rejected")
    ok(runCatching { PortableGameIndexCodec.encode(index.copy(games = listOf(first.copy(description = "x".repeat(128 * 1024 + 1))))) }.isFailure,
        "oversized game metadata rejected before serialization")
    ok(runCatching { PortableGameIndexCodec.encode(index.copy(files = files + files.first())) }.isFailure, "invalid object rejected on export")
    val dupPath = files[1].copy(path = files[0].path)
    ok(runCatching { PortableGameIndexCodec.encode(index.copy(files = listOf(files[0], dupPath, files[2]))) }.isFailure, "duplicate path rejected")
    println("PASS: $checks portable index checks")
}
'''
with tempfile.TemporaryDirectory() as tmp:
    folder = Path(tmp)
    (folder / 'Checks.kt').write_text(checks)
    cp = ':'.join(map(str, COMPILER))
    runtime_cp = str(COMPILER[1]) + ':' + str(json_jar)
    subprocess.run([str(JAVA), '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', runtime_cp,
                    '-d', str(folder / 'classes'), str(sources / 'PortablePaths.kt'),
                    str(sources / 'PortableGameIndex.kt'), str(sources / 'PortableJsonSafety.kt'), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', str(folder / 'classes') + ':' + runtime_cp,
                    'tv.orbit.shield.ChecksKt'], check=True)
