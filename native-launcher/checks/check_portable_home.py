"""Test strict portable home state with synthetic data; no preferences are changed."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import ROOT, JAVA, COMPILER

json_jar = Path(os.environ.get('PORTABLE_TEST_JSON_JAR', ROOT / '.tools/native/test-json/json-20240303.jar'))
if not json_jar.is_file() or hashlib.sha256(json_jar.read_bytes()).hexdigest() != '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed':
    raise SystemExit('Provide the verified org.json 20240303 test jar in PORTABLE_TEST_JSON_JAR')
sources = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
source = (sources / 'HomeSections.kt').read_text()
model = source[source.index('internal enum class HomeSectionKind'):source.index('internal fun homeGameKey')]
checks = r'''package tv.orbit.shield
import org.json.JSONObject
fun main() {
    var count = 0
    fun ok(b: Boolean, name: String) { check(b) { name }; count++ }
    val home = HomeSectionsOptions(listOf(
        HomeSection("video", HomeSectionKind.CUSTOM, "Моё видео", listOf("app:missing.example", "game:game-1"), true),
        HomeSection("favorites", HomeSectionKind.FAVORITES, visible = true),
        HomeSection("apps", HomeSectionKind.APPS, visible = false),
        HomeSection("games", HomeSectionKind.GAMES, visible = true)
    ), listOf("app:missing.example", "game:game-1", "game:missing-game"))
    val encoded = PortableHomeStateCodec.encode(home)
    ok(PortableHomeStateCodec.decode(encoded) == home, "order, missing apps/games, names and visibility preserved")
    ok(PortableHomeStateCodec.decode(PortableHomeStateCodec.encode(HomeSectionsOptions(emptyList(), emptyList()))) == HomeSectionsOptions(emptyList(), emptyList()), "explicit empty home stays empty")
    fun rejected(change: (JSONObject) -> Unit): Boolean {
        val doc = JSONObject(encoded); change(doc)
        return runCatching { PortableHomeStateCodec.decode(doc.toString()) }.isFailure
    }
    ok(rejected { it.put("version", 2) }, "future version rejected")
    ok(rejected { it.put("unexpected", true) }, "unknown field rejected")
    ok(rejected { it.getJSONArray("sections").getJSONObject(0).put("id", "../bad") }, "bad section ID rejected")
    ok(rejected { it.getJSONArray("sections").getJSONObject(0).put("id", "favorites") }, "duplicate section ID rejected")
    ok(rejected { it.getJSONArray("sections").getJSONObject(1).put("kind", "other") }, "unknown kind rejected")
    ok(rejected { it.getJSONArray("sections").getJSONObject(2).put("kind", "favorites") }, "duplicate builtin rejected")
    ok(rejected { it.getJSONArray("sections").getJSONObject(0).put("name", " Bad  Name ") }, "lossy name rejected")
    ok(rejected { it.getJSONArray("sections").getJSONObject(0).put("visible", "false") }, "coerced bool rejected")
    ok(rejected { it.getJSONArray("sections").getJSONObject(0).put("items", org.json.JSONArray(listOf("content://bad"))) }, "invalid ref rejected")
    ok(rejected { it.put("favorites", org.json.JSONArray(listOf("game:x", "game:x"))) }, "duplicate favorite rejected")
    ok(rejected { it.getJSONArray("sections").getJSONObject(1).put("items", org.json.JSONArray()) }, "unexpected builtin data rejected")
    ok(runCatching { PortableHomeStateCodec.decode("[".repeat(17) + "]".repeat(17)) }.isFailure, "deep nesting rejected")
    val bad = home.copy(sections = home.sections + HomeSection("apps-2", HomeSectionKind.APPS))
    ok(runCatching { PortableHomeStateCodec.encode(bad) }.isFailure, "invalid model not serialized")
    println("PASS: $count portable home checks")
}
'''
with tempfile.TemporaryDirectory() as tmp:
    folder = Path(tmp)
    (folder / 'Model.kt').write_text('package tv.orbit.shield\n' + model)
    (folder / 'Checks.kt').write_text(checks)
    runtime_cp = str(COMPILER[1]) + ':' + str(json_jar)
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', runtime_cp, '-d', str(folder / 'classes'),
                    str(sources / 'PortableJsonSafety.kt'), str(sources / 'PortableHomeState.kt'),
                    str(folder / 'Model.kt'), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', str(folder / 'classes') + ':' + runtime_cp,
                    'tv.orbit.shield.ChecksKt'], check=True)
