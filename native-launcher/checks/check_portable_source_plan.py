"""Test source discovery, PS1 dependencies and copy assembly on synthetic providers."""
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
home = (sources / 'HomeSections.kt').read_text()
home_model = home[home.index('internal enum class HomeSectionKind'):home.index('internal fun homeGameKey')]
main = (sources / 'MainActivity.kt').read_text()
game_model = main[main.index('data class Game('):main.index('private data class PortablePreviewDecisionSession')]
live_model = '''internal data class PortableLiveLibrary(val games: List<Game>, val home: HomeSectionsOptions, val settings: Map<String, Any>, val covers: Map<String, ManagedCover>, val coverChoices: Map<String, String>)'''
tree_model = '''internal data class PortableContentEntry(val id: String, val name: String, val directory: Boolean)
internal interface PortableContentTree { val rootId: String; fun children(parentId: String): List<PortableContentEntry>; fun isChild(parentId: String, childId: String): Boolean; fun openRead(id: String): java.io.InputStream }
'''
checks = r'''package tv.orbit.shield
import java.io.*
import java.security.MessageDigest
private class Source : PortablePlanningSource {
    override val rootDocumentId = "source-root"
    val data = linkedMapOf("cue" to "FILE \"track.bin\" BINARY\nTRACK 01 MODE2/2352\nINDEX 01 00:00:00\n".toByteArray(), "track" to "track data".toByteArray(), "single" to "game".toByteArray())
    val paths = mutableMapOf("cue" to listOf("PS1", "game.cue"), "track" to listOf("PS1", "track.bin"), "single" to listOf("PSP", "game.iso"))
    val locators = mutableMapOf("content://source/cue" to "cue", "content://source/single" to "single")
    var modified = 123L; var missingTrack = false; var enumerations = 0
    override fun locate(savedFile: String, checkActive: () -> Unit) = resolve(locators.getValue(savedFile), checkActive)
    override fun resolve(documentId: String, checkActive: () -> Unit): PortableSourceFile { checkActive(); return PortableSourceFile(paths.getValue(documentId), documentId) }
    override fun files(checkActive: () -> Unit): List<DiscImport.File> { enumerations++; return paths.filterKeys { !missingTrack || it != "track" }.map { DiscImport.File(it.value.joinToString("/"), it.key) } }
    override fun stamp(documentId: String) = PortableSourceStamp(data.getValue(documentId).size.toLong(), modified)
    override fun open(documentId: String): InputStream = ByteArrayInputStream(data.getValue(documentId))
}
private class Cover(private val data: ByteArray) : PortablePlanningCover {
    var modified = 12L
    override fun stamp() = PortableSourceStamp(data.size.toLong(), modified)
    override fun open(): InputStream = ByteArrayInputStream(data)
}
private class Destination : PortableWritableTree, PortableStagingDirectory {
    override val rootId = "root"
    val rows = mutableMapOf(rootId to mutableListOf<PortableWritableEntry>())
    val data = mutableMapOf<String, ByteArray>(); var next = 0
    var onFirstRead: (() -> Unit)? = null
    override fun children(parentId: String) = rows.getValue(parentId).toList()
    override fun create(parentId: String, name: String, directory: Boolean): PortableWritableEntry {
        val entry = PortableWritableEntry("created-${++next}", name, directory); rows.getValue(parentId).add(entry)
        if (directory) rows[entry.id] = mutableListOf(); return entry
    }
    override fun isChild(parentId: String, childId: String) = true
    override fun openWrite(id: String): OutputStream = object : ByteArrayOutputStream() { override fun close() { data[id] = toByteArray() } }
    override fun openRead(id: String): InputStream {
        val result = ByteArrayInputStream(data.getValue(id))
        val hook = onFirstRead; onFirstRead = null; hook?.invoke()
        return result
    }
    override fun children() = children(rootId).map { PortableStagingEntry(it.id, it.name, if (it.directory) "vnd.android.document/directory" else "application/octet-stream") }
    override fun create(name: String, mime: String): PortableStagingEntry = create(rootId, name, false).let { PortableStagingEntry(it.id, it.name, mime) }
    override fun isChild(id: String) = isChild(rootId, id)
}
fun main() {
    var n=0
    fun ok(b:Boolean, name:String) { check(b) {name}; n++ }
    val source = Source(); val coverBytes = "image".toByteArray()
    val hash = MessageDigest.getInstance("SHA-256").digest(coverBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    val coverKey = managedCoverKey(hash); val cover = Cover(coverBytes)
    val game = Game("game", "Game", "PlayStation", "DuckStation", "pkg/.Act", listOf("content://source/cue"), "tree1", "", "", "", "", "", "", false, "")
    val psp = game.copy(id = "psp", platform = "PSP", files = listOf("content://source/single"))
    val live = PortableLiveLibrary(listOf(game, psp), HomeSectionsOptions(emptyList(), listOf("game:game")), mapOf("language" to "ru"), mapOf("game" to ManagedCover(manual = coverKey)), mapOf("game" to "manual"))
    fun plan(l: PortableLiveLibrary = live, sources: Map<String, PortablePlanningSource> = mapOf("tree1" to source)) = PortableSourcePlanner.prepare(l, sources, { cover })
    val prepared = plan()
    ok(prepared.inputs.size == 4 && prepared.protectedDocumentIds == setOf("source-root", "cue", "track", "single"), "variants, companion, root and cover discovered")
    ok(prepared.inputs.map { it.path } .contains(listOf("games", "source-0001", "PS1", "track.bin")), "disc relative paths preserved")
    ok(prepared.variants.getValue("content://source/cue").companionIds?.size == 1, "validated cue track bound")
    val copied = PortableGenerationWriter.copy(Destination(), prepared.inputs, prepared.protectedDocumentIds, prepared.validate)
    val result = PortableSnapshotAssembler.assemble(PortableSnapshotCodec.newId(), live, copied, prepared.variants, prepared.covers)
    ok(result.index.games.size == 2 && result.index.files.size == 4 && result.home.favorites == listOf("game:game"), "discovery to copied snapshot")
    source.modified++
    ok(runCatching { prepared.validate(prepared.inputs.first()) }.isFailure, "source modification detected")
    source.modified--; cover.modified++
    ok(runCatching { prepared.validate(prepared.inputs.last()) }.isFailure, "cover modification detected")
    cover.modified--
    ok(runCatching { plan(sources = emptyMap()) }.isFailure, "missing source permission")
    source.missingTrack = true; ok(runCatching { plan() }.isFailure, "incomplete disc rejected")
    source.missingTrack = false
    val gdi = game.copy(platform = "Dreamcast", files = listOf("content://source/cue"))
    ok(plan(live.copy(games = listOf(gdi))).variants.getValue("content://source/cue").companionIds?.size == 1, "Dreamcast cue has its track")
    val standaloneSource = Source(); val one = live.copy(games = listOf(psp), covers = emptyMap(), coverChoices = emptyMap())
    PortableSourcePlanner.prepare(one, mapOf("tree1" to standaloneSource), { error("no covers") })
    ok(standaloneSource.enumerations == 0, "single image avoids full tree scan")
    ok(runCatching { prepared.validate(PortableCopyInput("unknown", listOf("x"), 0) { ByteArrayInputStream(byteArrayOf()) }) }.isFailure, "unknown validation input rejected")
    val destination = Destination()
    val events = mutableListOf<String>()
    val exported = PortableExportWorkflow.export(live, { live }, mapOf("tree1" to source), { cover }, destination, destination,
        onSourceVerification = { i, total -> events += "verify:$i/$total" }, onFile = { _, i, total -> events += "copy:$i/$total" })
    val saved = PortableSnapshotStore.readCommitted(destination, exported.commitDocumentId).snapshot
    ok(saved.index.games.size == 2 && saved.index.files.size == 4 && saved.home.favorites == listOf("game:game"), "end-to-end export commits complete snapshot")
    ok(exported.fileCount == 4 && exported.bytes == source.data.values.sumOf { it.size.toLong() } + coverBytes.size, "export result counts copied bytes")
    ok(events == (0..3).map { "copy:$it/4" } + (0..3).map { "verify:$it/4" }, "copy and source-verification progress are reported in order")
    val changedDestination = Destination()
    ok(runCatching { PortableExportWorkflow.export(live, { live.copy(settings = mapOf("language" to "es")) }, mapOf("tree1" to source), { cover }, changedDestination, changedDestination) }.isFailure &&
        changedDestination.children().none { it.name.startsWith("gostex-commit-") }, "changed library blocks commit")
    val contentChanged = Destination()
    val singleLive = live.copy(games = listOf(psp), covers = emptyMap(), coverChoices = emptyMap())
    contentChanged.onFirstRead = { source.data["single"] = "evil".toByteArray() }
    ok(runCatching { PortableExportWorkflow.export(singleLive, { singleLive }, mapOf("tree1" to source), { error("no cover") }, contentChanged, contentChanged) }.isFailure &&
        contentChanged.children().none { it.name.startsWith("gostex-commit-") }, "same-size same-timestamp source change blocks commit")
    source.data["single"] = "game".toByteArray()
    val invalidDestination = Destination()
    val invalidLive = live.copy(games = live.games.map { it.copy(scene = "content://private/image") })
    ok(runCatching { PortableExportWorkflow.export(invalidLive, { invalidLive }, mapOf("tree1" to source), { cover }, invalidDestination, invalidDestination) }.isFailure &&
        invalidDestination.children().isEmpty(), "unsupported metadata rejected before copy")
    val gdiText = "3\n1 0 4 2352 track01.bin 0\n2 1000 0 2352 \"audio track.raw\" 0\n3 45000 4 2352 track03.bin 0\n"
    source.data.putAll(mapOf("gdi" to gdiText.toByteArray(), "dc1" to byteArrayOf(1), "dc2" to byteArrayOf(2), "dc3" to byteArrayOf(3)))
    source.paths.putAll(mapOf("gdi" to listOf("DC", "disc.gdi"), "dc1" to listOf("DC", "track01.bin"),
        "dc2" to listOf("DC", "audio track.raw"), "dc3" to listOf("DC", "track03.bin")))
    source.locators["content://source/gdi"] = "gdi"
    val dreamcast = gdi.copy(id = "dc", files = listOf("content://source/gdi"))
    val dcLive = live.copy(games = listOf(dreamcast), covers = emptyMap(), coverChoices = emptyMap())
    val dcPlan = plan(dcLive)
    ok(dcPlan.inputs.size == 4 && dcPlan.variants.getValue("content://source/gdi").companionIds?.size == 3, "GDI and all tracks planned")
    val dcDest = Destination()
    val dcExport = PortableExportWorkflow.export(dcLive, { dcLive }, mapOf("tree1" to source), { error("no cover") }, dcDest, dcDest)
    val dcSaved = PortableSnapshotStore.readCommitted(dcDest, dcExport.commitDocumentId).snapshot
    ok(dcSaved.index.files.size == 4 && dcSaved.index.files.any { it.path.last() == "audio track.raw" }, "GDI tracks copied in snapshot")
    source.paths.remove("dc2")
    ok(runCatching { plan(dcLive) }.isFailure, "missing GDI track rejected before copy")
    source.paths["dc2"] = listOf("DC", "audio track.raw")
    source.data["gdi"] = gdiText.replace("track03.bin", "../../outside.bin").toByteArray()
    ok(runCatching { plan(dcLive) }.isFailure, "GDI path escape rejected")
    source.data["gdi"] = gdiText.replace("45000", "1234").toByteArray()
    ok(runCatching { plan(dcLive) }.isFailure, "invalid high density track rejected")
    source.data["gdi"] = gdiText.replace("3\n1", "4\n1").toByteArray()
    ok(runCatching { plan(dcLive) }.isFailure, "incorrect track count rejected")
    source.data["gdi"] = gdiText.replace("2 1000 0", "1 1000 0").toByteArray()
    ok(runCatching { plan(dcLive) }.isFailure, "duplicate track number rejected")
    source.data["gdi"] = gdiText.toByteArray()
    val dcFiles = source.files {}.filter { it.path.startsWith("DC/") }
    val aliases = dcFiles.map { if (it.id == "dc2") it.copy(id = "dc1") else it }
    ok(runCatching { PortableDreamcastDiscPlanner.build(aliases, listOf("DC/disc.gdi"), { gdiText }) }.isFailure, "different tracks cannot alias a document")
    ok(runCatching { PortableDreamcastDiscPlanner.build(dcFiles + dcFiles.first { it.id == "dc2" }, listOf("DC/disc.gdi"), { gdiText }) }.isFailure, "ambiguous track name rejected")
    source.enumerations = 0
    val mixed = live.copy(games = listOf(game, dreamcast))
    val mixedPlan = plan(mixed)
    ok(source.enumerations == 1 && mixedPlan.variants.size == 2, "mixed discs scan the tree only once")
    var checkpoints = 0
    ok(runCatching { PortableSourcePlanner.prepare(dcLive, mapOf("tree1" to source), { error("no cover") }, {
        if (++checkpoints == 12) throw java.util.concurrent.CancellationException()
    }) }.isFailure && checkpoints == 12, "GDI plan respects cancellation during enumeration")
    println("PASS: $n source plan checks")
}'''
with tempfile.TemporaryDirectory() as tmp:
    folder = Path(tmp)
    (folder / 'Models.kt').write_text('package tv.orbit.shield\n' + home_model + game_model + live_model + '\n' + tree_model)
    (folder / 'Checks.kt').write_text(checks)
    runtime_cp = str(COMPILER[1]) + ':' + str(jar)
    files = ['PortablePaths.kt','PortableJsonSafety.kt','PortableGameIndex.kt','PortableHomeState.kt','PortableSettings.kt','PortableSnapshot.kt',
             'PortableVerifiedCopy.kt','PortableGenerationWriter.kt','ManagedCoverModel.kt','PortableLiveCapture.kt',
             'PortableSnapshotAssembler.kt','DiscImport.kt','PortableDiscPlan.kt','PortableDreamcastDiscPlan.kt','PortableSourceLookup.kt','PortableSourcePlan.kt',
             'PortableStaging.kt','PortableSnapshotStore.kt','PortableExportWorkflow.kt']
    files.remove('PortableLiveCapture.kt')
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', runtime_cp, '-d', str(folder / 'classes'),
                    *map(str, (sources / name for name in files)), str(folder / 'Models.kt'), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', str(folder / 'classes') + ':' + runtime_cp, 'tv.orbit.shield.ChecksKt'], check=True)
