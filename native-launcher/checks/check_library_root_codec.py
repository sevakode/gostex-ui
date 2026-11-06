"""Check legacy and downgrade-safe portable library root documents."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import ROOT, JAVA, COMPILER

jar = Path(os.environ.get('PORTABLE_TEST_JSON_JAR', ROOT / '.tools/native/test-json/json-20240303.jar'))
if not jar.is_file() or hashlib.sha256(jar.read_bytes()).hexdigest() != '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed':
    raise SystemExit('Provide the verified org.json 20240303 test jar in PORTABLE_TEST_JSON_JAR')
sources = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
checks = r'''package tv.orbit.shield
import org.json.JSONArray
import org.json.JSONObject
fun main() {
    var n = 0
    fun ok(v: Boolean, msg: String) { check(v) { msg }; n++ }
    val normal = LibraryRootRecord("PSP", "content://source/tree/a", LibrarySource.FILES)
    val single = LibraryRootRecord("PSP", "content://source/document/b", LibrarySource.SINGLE_FILE)
    val three = LibraryRootRecord("Nintendo 3DS", "content://source/tree/c", LibrarySource.AZAHAR_INSTALLED)
    val portable = LibraryRootRecord("PlayStation", "content://source/tree/d", LibrarySource.PORTABLE_SNAPSHOT)
    val legacy = LibraryRootCodec.encode(listOf(normal, single, three))
    ok(legacy.startsWith("[") && LibraryRootCodec.decode(legacy) == listOf(normal, single, three), "legacy array unchanged")
    ok(JSONArray(legacy).length() == 3, "old client reads legacy array")
    val v2 = LibraryRootCodec.encode(listOf(normal, portable))
    ok(v2.startsWith("{") && LibraryRootCodec.decode(v2) == listOf(normal, portable), "portable versioned roundtrip")
    ok(runCatching { JSONArray(v2) }.isFailure, "old client rejects portable object instead of scanning it")
    ok(runCatching { LibraryRootCodec.decode("[{\"platform\":\"PSP\",\"uri\":\"x\",\"source\":\"portable-snapshot\"}]") }.isFailure, "portable marker prohibited in legacy array")
    fun invalid(change:(JSONObject)->Unit):Boolean { val o=JSONObject(v2); change(o); return runCatching { LibraryRootCodec.decode(o.toString()) }.isFailure }
    ok(invalid { it.put("version", 3) }, "future version rejected")
    ok(invalid { it.put("future", true) }, "unknown envelope field rejected")
    ok(invalid { it.getJSONArray("roots").getJSONObject(0).put("source", "future") }, "unknown source rejected")
    ok(invalid { it.getJSONArray("roots").getJSONObject(0).put("newField", true) }, "unknown root field rejected")
    ok(invalid { it.getJSONArray("roots").put(it.getJSONArray("roots").getJSONObject(0)) }, "duplicate identity rejected")
    ok(runCatching { LibraryRootCodec.requireLosslessLegacy("[{\"platform\":\"PSP\",\"uri\":\"x\",\"future\":true}]") }.isFailure, "unknown legacy fields block migration")
    ok(runCatching { LibraryRootCodec.requireLosslessLegacy("[{\"platform\":\"PSP\",\"uri\":\"x\",\"source\":\"future\"}]") }.isFailure, "unknown legacy source blocks migration")
    ok(runCatching { LibraryRootCodec.requireLosslessLegacy(legacy) }.isSuccess, "known legacy roots migrate")
    ok(LibraryRootCodec.decode("[{\"platform\":\"PSP\",\"uri\":\"x\"}]").single().source == LibrarySource.FILES, "legacy missing source retains default")
    ok(LibraryRootCodec.decode("[]").isEmpty(), "empty legacy roots")
    println("PASS: $n library root codec checks")
}'''
with tempfile.TemporaryDirectory() as tmp:
    folder = Path(tmp); (folder / 'Checks.kt').write_text(checks)
    runtime_cp = str(COMPILER[1]) + ':' + str(jar)
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', runtime_cp, '-d', str(folder / 'classes'),
                    str(sources / 'ThreeDsInstalledLayout.kt'), str(sources / 'LibraryRootCodec.kt'), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', str(folder / 'classes') + ':' + runtime_cp, 'tv.orbit.shield.ChecksKt'], check=True)
