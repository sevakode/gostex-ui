"""Synthetic end-to-end checks for the portable state envelope."""
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
source = (sources / 'HomeSections.kt').read_text()
model = source[source.index('internal enum class HomeSectionKind'):source.index('internal fun homeGameKey')]
checks = r'''package tv.orbit.shield
import org.json.JSONObject
fun main() {
    var count = 0
    fun ok(v: Boolean, name: String) { check(v) { name }; count++ }
    val file = PortableIndexedFile("rom", listOf("PS1", "test.bin"), 42, "a".repeat(64))
    val game = PortableIndexedGame("g1", "Игра", "PS1", "e", "c", listOf("rom"), "", "", "", "", "", false, null, null, null, "auto")
    val home = HomeSectionsOptions(listOf(HomeSection("video", HomeSectionKind.CUSTOM, "Видео", listOf("game:g1", "app:missing"), true)), listOf("game:g1"))
    val snapshot = PortableSnapshot(PortableSnapshotCodec.newId(), PortableGameIndex(listOf(file), listOf(game)), home,
        mapOf("language" to "ru", "clock.visible" to false, "video.visible" to setOf("app.example")))
    val encoded = PortableSnapshotCodec.encode(snapshot)
    ok(PortableSnapshotCodec.decode(encoded) == snapshot, "round trip all parts and absent app")
    fun bad(change: (JSONObject) -> Unit): Boolean { val doc = JSONObject(encoded); change(doc); return runCatching { PortableSnapshotCodec.decode(doc.toString()) }.isFailure }
    ok(bad { it.put("version", 2) }, "future envelope rejected")
    ok(bad { it.put("id", "bad") }, "invalid generation ID rejected")
    ok(bad { it.put("extra", true) }, "extra envelope data rejected")
    for (key in listOf("index", "home", "settings")) {
        ok(bad { it.getJSONObject(key).put("content", "{}") }, "corrupt $key rejected")
        ok(bad { it.getJSONObject(key).put("sha256", "f".repeat(64)) }, "wrong $key digest rejected")
        ok(bad { it.getJSONObject(key).put("extra", true) }, "extra $key field rejected")
    }
    ok(bad { it.remove("home") }, "missing part rejected")
    ok(bad { doc ->
        val invalid = "{}"
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(invalid.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        doc.getJSONObject("home").put("content", invalid).put("sha256", digest)
    }, "valid checksum does not bypass section validation")
    ok(runCatching { PortableSnapshotCodec.decode(encoded.dropLast(1)) }.isFailure, "interrupted write rejected")
    ok(runCatching { PortableSnapshotCodec.encode(snapshot.copy(id = "bad")) }.isFailure, "bad generation not written")
    val index = JSONObject(PortableGameIndexCodec.encode(snapshot.index)); index.put("future", true)
    ok(runCatching { PortableGameIndexCodec.decode(index.toString()) }.isFailure, "unknown index field rejected")
    val row = JSONObject(PortableGameIndexCodec.encode(snapshot.index)); row.getJSONArray("games").getJSONObject(0).put("future", true)
    ok(runCatching { PortableGameIndexCodec.decode(row.toString()) }.isFailure, "unknown game field rejected")
    println("PASS: $count portable snapshot checks")
}'''
with tempfile.TemporaryDirectory() as tmp:
    folder = Path(tmp)
    (folder / 'Model.kt').write_text('package tv.orbit.shield\n' + model)
    (folder / 'Checks.kt').write_text(checks)
    runtime_cp = str(COMPILER[1]) + ':' + str(jar)
    files = ['PortableJsonSafety.kt', 'PortablePaths.kt', 'PortableGameIndex.kt', 'PortableHomeState.kt', 'PortableSettings.kt', 'PortableSnapshot.kt']
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', runtime_cp, '-d', str(folder / 'classes'),
                    *map(str, (sources / name for name in files)), str(folder / 'Model.kt'), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', str(folder / 'classes') + ':' + runtime_cp, 'tv.orbit.shield.ChecksKt'], check=True)
