"""Test joining synthetic live metadata to verified copied file receipts."""
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
home_source = (sources / 'HomeSections.kt').read_text()
home_model = home_source[home_source.index('internal enum class HomeSectionKind'):home_source.index('internal fun homeGameKey')]
main_source = (sources / 'MainActivity.kt').read_text()
game_model = main_source[main_source.index('data class Game('):main_source.index('private data class PortablePreviewDecisionSession')]
live_model = '''internal data class PortableLiveLibrary(val games: List<Game>, val home: HomeSectionsOptions, val settings: Map<String, Any>, val covers: Map<String, ManagedCover>, val coverChoices: Map<String, String>)'''
checks = r'''package tv.orbit.shield
fun main() {
    var n = 0
    fun ok(v: Boolean, s: String) { check(v) { s }; n++ }
    val digest = "a".repeat(64); val other = "b".repeat(64); val gen = "gostex-content-" + "1".repeat(32)
    fun copied(id: String, path: List<String>, hash: String) = PortableCopiedContent(id, listOf(gen) + path, "local-$id", PortableCopyReceipt(42, hash))
    val cue = copied("cue", listOf("PS1", "game.cue"), other)
    val track = copied("track", listOf("PS1", "track.bin"), other)
    val cover = copied("art", listOf("covers", "art.png"), digest)
    val content = PortableGeneratedContent("local-generation", gen, listOf(cue, track, cover))
    val locator = "content://private/tree/game.cue"
    val game = Game("g1", "Игра", "PlayStation", "DuckStation", "app/.Activity", listOf(locator), "content://private/tree", "media/base.png", "media/scene.png", "genre", "2000", "dev", "1", false, "description")
    val home = HomeSectionsOptions(listOf(HomeSection("custom", HomeSectionKind.CUSTOM, "My games", listOf("game:g1", "app:missing"))), listOf("game:g1"))
    val key = managedCoverKey(digest)
    val art = ManagedCover(manual = key, sourceName = "source", sourceUrl = "https://example.com/art/1", author = "artist")
    val live = PortableLiveLibrary(listOf(game), home, mapOf("language" to "ru"), mapOf("g1" to art), mapOf("g1" to "manual"))
    val bindings = mapOf(locator to PortableVariantBinding("cue", listOf("track")))
    val covers = mapOf(key to "art")
    fun assemble(l: PortableLiveLibrary = live, c: PortableGeneratedContent = content,
                 v: Map<String, PortableVariantBinding> = bindings, a: Map<String,String> = covers) =
        PortableSnapshotAssembler.assemble("01234567-89ab-cdef-0123-456789abcdef", l, c, v, a)
    val snapshot = assemble(); val encoded = PortableSnapshotCodec.encode(snapshot)
    ok(PortableSnapshotCodec.decode(encoded) == snapshot, "full state roundtrip")
    ok(!encoded.contains("content://private") && !encoded.contains("local-generation"), "source locator and document IDs excluded")
    ok(snapshot.index.games.single().variants == listOf("cue") && snapshot.index.files.any { it.id == "track" }, "descriptor and companion included")
    ok(snapshot.index.games.single().manualCover == "art" && snapshot.index.games.single().coverChoice == "manual", "selected cover retained")
    ok(snapshot.index.games.single().baseCover == "media/base.png" && snapshot.index.games.single().scene == "media/scene.png" && snapshot.index.games.single().coverAuthor == "artist", "art metadata retained")
    ok(snapshot.home == home && snapshot.settings["language"] == "ru", "home and preferences preserved")
    ok(runCatching { assemble(v = emptyMap()) }.isFailure, "missing variant blocked")
    ok(runCatching { assemble(v = mapOf(locator to PortableVariantBinding("cue"))) }.isFailure, "descriptor without companions blocked")
    val gdiContent = content.copy(files = listOf(cue.copy(path = listOf(gen, "DC", "disc.gdi")), track, cover))
    ok(runCatching { assemble(c = gdiContent, v = mapOf(locator to PortableVariantBinding("cue"))) }.isFailure, "GDI without companions blocked")
    ok(runCatching { assemble(v = mapOf(locator to PortableVariantBinding("cue", listOf("missing")))) }.isFailure, "missing companion blocked")
    ok(runCatching { assemble(a = emptyMap()) }.isFailure, "missing cover blocked")
    ok(runCatching { assemble(c = content.copy(files = listOf(cue, track, cover.copy(receipt = cover.receipt.copy(sha256 = other))))) }.isFailure, "wrong cover digest blocked")
    ok(runCatching { assemble(c = content.copy(files = content.files + copied("unused", listOf("unused"), other))) }.isFailure, "unreferenced copied file blocked")
    ok(runCatching { assemble(l = live.copy(covers = live.covers + ("orphan" to art))) }.isFailure, "orphan cover not silently dropped")
    ok(runCatching { assemble(l = live.copy(games = listOf(game.copy(scene = "content://private/image")))) }.isFailure, "device specific artwork rejected")
    ok(runCatching { assemble(l = live.copy(covers = mapOf("g1" to art.copy(sourceUrl = "https://example.com/?key=secret")))) }.isFailure, "secret-bearing provenance rejected")
    val empty = PortableLiveLibrary(emptyList(), HomeSectionsOptions(emptyList(), emptyList()), emptyMap(), emptyMap(), emptyMap())
    ok(PortableSnapshotAssembler.assemble("01234567-89ab-cdef-0123-456789abcdef", empty, content.copy(files = emptyList()), emptyMap(), emptyMap()).index.games.isEmpty(), "empty library supported")
    println("PASS: $n portable assembler checks")
}'''
with tempfile.TemporaryDirectory() as tmp:
    folder = Path(tmp)
    (folder / 'Models.kt').write_text('package tv.orbit.shield\n' + home_model + game_model + live_model)
    (folder / 'Checks.kt').write_text(checks)
    runtime_cp = str(COMPILER[1]) + ':' + str(jar)
    files = ['PortablePaths.kt', 'PortableJsonSafety.kt', 'PortableGameIndex.kt', 'PortableHomeState.kt', 'PortableSettings.kt',
             'PortableSnapshot.kt', 'PortableVerifiedCopy.kt', 'PortableGenerationWriter.kt', 'ManagedCoverModel.kt', 'PortableSnapshotAssembler.kt']
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', runtime_cp, '-d', str(folder / 'classes'),
                    *map(str, (sources / name for name in files)), str(folder / 'Models.kt'), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', str(folder / 'classes') + ':' + runtime_cp, 'tv.orbit.shield.ChecksKt'], check=True)
