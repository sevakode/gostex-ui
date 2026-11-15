"""Synthetic tests for the portable settings codec; never touches device preferences."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import ROOT, JAVA, COMPILER, jar as gradle_jar

local_json = ROOT / '.tools/native/test-json/json-20240303.jar'
jar = (Path(os.environ['PORTABLE_TEST_JSON_JAR']) if os.environ.get('PORTABLE_TEST_JSON_JAR')
       else local_json if local_json.is_file() else gradle_jar('org.json', 'json', '20180813'))
verified_json = {
    '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed',
    '518080049ba83181914419d11a25d9bc9833a2d729b6a6e7469fa52851356da8',
}
if not jar.is_file() or hashlib.sha256(jar.read_bytes()).hexdigest() not in verified_json:
    raise SystemExit('Provide a verified org.json 20180813 or 20240303 test jar in PORTABLE_TEST_JSON_JAR')
sources = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
checks = r'''package tv.orbit.shield
import org.json.JSONObject
fun main() {
    var count = 0
    fun ok(value: Boolean, label: String) { check(value) { label }; count++ }
    val prefs = mapOf<String, Any>("language" to "ru", "clock.zones" to "[\"Asia/Tokyo\",\"Europe/Madrid\"]",
        "clock.visible" to false, "library.groupVersions" to false, "art.holdSeconds" to 30, "art.speed" to "future-choice",
        "video.visible" to setOf("tv.example", "missing.app"),
        "apps.catalogOrder" to "[\"future.app\",\"com.brouken.player\"]", "apps.catalogHidden" to setOf("future.hidden", "me.magnum.melonds"), "home.shelves.visible" to emptySet<String>(),
        "covers.steamgriddbKey" to "secret", "grafana.url" to "https://private.example", "library.uri" to "content://private")
    val captured = PortableSettingsCodec.capture(prefs)
    ok(captured.size == 10, "only reviewed fields captured")
    val encoded = PortableSettingsCodec.encode(captured)
    ok(!encoded.contains("secret") && !encoded.contains("private"), "sensitive keys excluded")
    ok(PortableSettingsCodec.decode(encoded) == captured, "round trip choices, empty set and missing apps")
    ok(PortableSettingsCodec.decode(PortableSettingsCodec.encode(emptyMap())).isEmpty(), "absent settings remain absent")
    ok(captured["art.speed"] == "future-choice", "unknown choices are preserved")
    fun reject(change: (JSONObject) -> Unit): Boolean { val root = JSONObject(encoded); change(root); return runCatching { PortableSettingsCodec.decode(root.toString()) }.isFailure }
    ok(reject { it.put("version", 2) }, "future schema rejected")
    ok(reject { it.put("extra", true) }, "unknown root field rejected")
    ok(reject { it.getJSONObject("values").put("covers.steamgriddbKey", "secret") }, "secret key cannot be restored")
    ok(reject { it.getJSONObject("values").put("clock.visible", "false") }, "no boolean coercion")
    ok(reject { it.getJSONObject("values").put("art.holdSeconds", 30.5) }, "no numeric coercion")
    ok(reject { it.getJSONObject("values").put("video.visible", org.json.JSONArray(listOf("a", "a"))) }, "duplicate set item rejected")
    ok(reject { it.getJSONObject("values").put("video.visible", org.json.JSONArray(listOf(1))) }, "wrong set item rejected")
    ok(runCatching { PortableSettingsCodec.encode(mapOf("grafana.url" to "x")) }.isFailure, "unknown key cannot be exported")
    ok(runCatching { PortableSettingsCodec.capture(mapOf("clock.visible" to "true")) }.isFailure, "wrong source type rejected")
    ok(runCatching { PortableSettingsCodec.encode(mapOf("language" to "x".repeat(8193))) }.isFailure, "oversized value rejected")
    ok(runCatching { PortableSettingsCodec.decode("[".repeat(17) + "]".repeat(17)) }.isFailure, "deep nesting rejected")
    ok(reject { it.getJSONObject("values").put("apps.catalogOrder", "[1]") }, "catalog order requires string IDs")
    ok(reject { it.getJSONObject("values").put("apps.catalogOrder", "[\"bad/id\"]") }, "catalog order rejects paths")
    ok(reject { it.getJSONObject("values").put("apps.catalogHidden", org.json.JSONArray(listOf("bad/id"))) }, "catalog hidden rejects paths")
    ok(reject { it.getJSONObject("values").put("apps.catalogHidden", org.json.JSONArray((0..128).map { "future.p$it" })) }, "catalog bound survives portable decoding")
    ok(prefs.containsKey("covers.steamgriddbKey"), "original source untouched")
    println("PASS: $count portable settings checks")
}'''
with tempfile.TemporaryDirectory() as tmp:
    folder = Path(tmp)
    (folder / 'Checks.kt').write_text(checks)
    runtime_cp = str(COMPILER[1]) + ':' + str(jar)
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', runtime_cp, '-d', str(folder / 'classes'),
                    str(sources / 'PortableJsonSafety.kt'), str(sources / 'AppCatalogPreferencesCodec.kt'), str(sources / 'PortableSettings.kt'), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', str(folder / 'classes') + ':' + runtime_cp, 'tv.orbit.shield.ChecksKt'], check=True)
