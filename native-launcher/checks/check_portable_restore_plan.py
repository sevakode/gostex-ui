"""Test a read-only restore preview using synthetic committed state and content."""
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
checks = r'''package tv.orbit.shield
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.CancellationException
private class Metadata : PortableStagingDirectory {
    val rows = mutableListOf<PortableStagingEntry>(); val contents = mutableMapOf<String, ByteArray>()
    override fun children() = rows.toList()
    override fun create(name: String, mime: String): PortableStagingEntry = PortableStagingEntry("m${rows.size}", name, mime).also { rows += it }
    override fun isChild(id: String) = rows.any { it.id == id }
    override fun openWrite(id: String): OutputStream = object : ByteArrayOutputStream() { override fun close() { contents[id] = toByteArray() } }
    override fun openRead(id: String): InputStream = ByteArrayInputStream(contents.getValue(id))
}
private class Content : PortableContentTree {
    override val rootId = "root"
    val children = mutableMapOf("root" to mutableListOf(PortableContentEntry("dir", "generation", true)), "dir" to mutableListOf(
        PortableContentEntry("rom-doc", "game.cue", false), PortableContentEntry("track-doc", "track.bin", false), PortableContentEntry("art-doc", "art.png", false)))
    val contents = mutableMapOf("rom-doc" to "cue".toByteArray(), "track-doc" to "track".toByteArray(), "art-doc" to "art".toByteArray())
    override fun children(parentId: String) = children.getValue(parentId).toList()
    override fun isChild(parentId: String, childId: String) = true
    override fun openRead(id: String): InputStream = ByteArrayInputStream(contents.getValue(id))
}
fun main() {
    var n=0
    fun ok(b:Boolean, s:String) { check(b) {s}; n++ }
    fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 255) }
    val content = Content()
    val files = listOf("rom" to "rom-doc", "track" to "track-doc", "art" to "art-doc").map { (id, doc) ->
        val name = content.children.getValue("dir").single { it.id == doc }.name
        val bytes = content.contents.getValue(doc); PortableIndexedFile(id, listOf("generation", name), bytes.size.toLong(), sha(bytes))
    }
    val game = PortableIndexedGame("g1", "Game", "PlayStation", "untrusted", "evil/.Activity", listOf("rom"), "genre", "2000", "dev", "1", "desc", false,
        "art", null, null, "manual", "media/base.png", "", "source", "https://example.com/art/1", "artist")
    val home = HomeSectionsOptions(listOf(HomeSection("apps", HomeSectionKind.APPS)), listOf("game:g1", "app:missing"))
    val snapshot = PortableSnapshot(PortableSnapshotCodec.newId(), PortableGameIndex(files, listOf(game)), home, mapOf("language" to "ru"))
    val metadata = Metadata(); val marker = PortableSnapshotStore.write(metadata, snapshot)
    fun inspect(platform: (String)->PortableTrustedPlatform? = { PortableTrustedPlatform("safe", "safe/.Activity") }) =
        PortableRestorePlanner.inspect(metadata, content, marker.documentId, "content://new-tree", { "content://new-tree/document/$it" }, platform)
    val result = inspect(); val plan = requireNotNull(result.plan)
    ok(plan.games.single().id == "g1" && plan.games.single().files == listOf("content://new-tree/document/rom-doc"), "stable ID and current-tree URI")
    ok(plan.games.single().component == "safe/.Activity" && plan.games.single().emulator == "safe", "only trusted current registry used")
    ok(plan.roots == listOf(PortableRestoreRoot("PlayStation", "content://new-tree")), "root requirement generated")
    ok(plan.covers.getValue("g1").manual == managedCoverKey(sha("art".toByteArray())) && plan.coverFiles.single().documentId == "art-doc", "cover mapped by verified digest")
    ok(plan.coverChoices["g1"] == "manual" && plan.snapshot.home == home && plan.snapshot.settings["language"] == "ru", "choices home and settings preserved")
    val unsupported = requireNotNull(inspect { null }.plan)
    ok(unsupported.unsupportedPlatforms == setOf("PlayStation") && unsupported.games.single().component.isEmpty() && unsupported.snapshot.index.games.single().component == "evil/.Activity", "unknown platform retained without executable component")
    content.children.getValue("dir").removeAll { it.id == "track-doc" }
    val missing = inspect(); ok(missing.plan == null && missing.inspection.content.issues.any { it.fileId == "track" }, "missing companion blocks plan without losing snapshot")
    content.children.getValue("dir").add(PortableContentEntry("track-doc", "track.bin", false))
    content.contents["track-doc"] = "wrong".toByteArray()
    ok(inspect().plan == null, "corrupt content blocks plan")
    content.contents["track-doc"] = "track".toByteArray()
    ok(runCatching { PortableRestorePlanner.inspect(metadata, content, marker.documentId, "content://new-tree", { "" }, { null }) }.isFailure, "invalid locator rejected")
    ok(runCatching { PortableRestorePlanner.inspect(metadata, content, marker.documentId, "content://new-tree", { it }, { null }, { throw CancellationException() }) }.exceptionOrNull() is CancellationException, "cancellation propagated")
    println("PASS: $n restore preview checks")
}'''
with tempfile.TemporaryDirectory() as tmp:
    folder = Path(tmp)
    (folder / 'Models.kt').write_text('package tv.orbit.shield\n' + home_model + game_model)
    (folder / 'Checks.kt').write_text(checks)
    runtime_cp = str(COMPILER[1]) + ':' + str(jar)
    files = ['PortablePaths.kt','PortableJsonSafety.kt','PortableGameIndex.kt','PortableHomeState.kt','PortableSettings.kt','PortableSnapshot.kt',
             'PortableVerifiedCopy.kt','PortableStaging.kt','PortableSnapshotStore.kt','PortableContentVerification.kt','PortableBackupVerification.kt',
             'ManagedCoverModel.kt','PortableRestorePlan.kt']
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', runtime_cp, '-d', str(folder / 'classes'),
                    *map(str, (sources / name for name in files)), str(folder / 'Models.kt'), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', str(folder / 'classes') + ':' + runtime_cp, 'tv.orbit.shield.ChecksKt'], check=True)
