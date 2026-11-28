"""Exercise exact SAF regrant policy; Android permission APIs are controlled host doubles."""
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from kotlin_toolchain import JAVA, COMPILER, ROOT

sources = {
    'Uri.kt': r'''package android.net
import java.net.URI
import java.net.URLDecoder
class Uri private constructor(private val value: String) {
 private val parsed = URI(value)
 val scheme: String? get() = parsed.scheme
 val authority: String? get() = parsed.rawAuthority
 val query: String? get() = parsed.rawQuery
 val fragment: String? get() = parsed.rawFragment
 val pathSegments: List<String> get() = parsed.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
  .map { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
 override fun toString() = value
 companion object { fun parse(value: String) = Uri(value) }
}
''',
    'Resolver.kt': r'''package android.content
import android.net.Uri
class UriPermission(val uri: Uri, val isReadPermission: Boolean, val isWritePermission: Boolean)
object Intent { const val FLAG_GRANT_READ_URI_PERMISSION = 1; const val FLAG_GRANT_WRITE_URI_PERMISSION = 2 }
abstract class ContentResolver {
 abstract val persistedUriPermissions: List<UriPermission>
 abstract fun takePersistableUriPermission(uri: Uri, flags: Int)
 companion object { const val SCHEME_CONTENT = "content" }
}
''',
    'DocumentsContract.kt': r'''package android.provider
import android.net.Uri
object DocumentsContract {
 fun isTreeUri(uri: Uri) = uri.pathSegments.size >= 2 && uri.pathSegments[0] == "tree"
 fun getTreeDocumentId(uri: Uri): String { require(isTreeUri(uri)); return uri.pathSegments[1] }
}
''',
    'Checks.kt': r'''package tv.orbit.shield
import android.content.ContentResolver
import android.content.UriPermission
import android.net.Uri
private const val TREE = "content://storage.example/tree/USB%3AGames%2FPS1"
private class Resolver: ContentResolver() {
 var grants = mutableListOf<UriPermission>()
 var reads = 0
 var takes = 0
 var takeThrows = false
 var readThrows = false
 var readGranted = true
 var writeGranted = true
 var selected: Uri? = null
 var expectedFlags = 3
 override val persistedUriPermissions: List<UriPermission> get() {
  reads++; if (readThrows) throw SecurityException("Permission query unavailable"); return grants.toList()
 }
 override fun takePersistableUriPermission(uri: Uri, flags: Int) {
  takes++; selected = uri; check(flags == expectedFlags)
  if (takeThrows) throw SecurityException("No persistable grant")
  grants += UriPermission(uri, readGranted, writeGranted)
 }
}
fun main() {
 val access = TorrDownloadFolderAccess
 val request = TorrDownloadFolderAccess.Request("job-id", TREE,
  "content://storage.example/tree/USB%3AGames%2FPS1/document/USB%3AGames%2FPS1%2F.gostex-job.partial", "error", "storage")
 val original = request.copy()
 val identity = access.identity(TREE) ?: error("Valid tree rejected")
 check(identity.authority == "storage.example" && identity.documentId == "USB:Games/PS1")
 check(access.identity("content://storage.example/tree/USB%3aGames%2fPS1") == identity)
 check(access.identity("content://STORAGE.EXAMPLE/tree/USB%3AGames%2FPS1") != identity)
 check(access.identity(TREE + "/document/USB%3AGames%2FPS1") == null)
 check(access.identity("content://storage.example/tree/USB%3AGames%2Fps1") != identity)
 for (invalid in listOf("", "broken URI", "file:///tree/USB%3AGames%2FPS1", "content:///tree/x", "content://storage.example/document/x", "content://storage.example/tree", "content://storage.example/tree/%20", TREE + "?query=1", TREE + "#fragment")) {
  check(access.identity(invalid) == null) { "Invalid tree accepted: $invalid" }
 }
 check(!access.needsAccess(TREE, null)) // unknown snapshot is not proof of missing access
 check(access.needsAccess(TREE, emptySet()))
 check(!access.needsAccess(TREE, setOf(identity)))
 check(!access.needsAccess("broken URI", emptySet()))
 val partial = Resolver()
 partial.grants += UriPermission(Uri.parse(TREE), true, false)
 partial.grants += UriPermission(Uri.parse(TREE + "/document/USB%3AGames%2FPS1%2FChild"), true, true)
 partial.grants += UriPermission(Uri.parse("content://storage.example/tree/USB%3AOther"), true, true)
 check(access.needsAccess(TREE, access.snapshot(partial)))
 partial.grants += UriPermission(Uri.parse("content://storage.example/tree/USB%3aGames%2fPS1"), true, true)
 check(!access.needsAccess(TREE, access.snapshot(partial)))
 // The worker records finalize when all bytes were saved but publication failed.
 // Recovery after interruption during publication records finalizing instead.
 for (retryable in listOf(request, request.copy(state="paused", error=""),
  request.copy(error="finalize"), request.copy(error="finalizing"))) {
  check(access.needsRestore(retryable.state, retryable.error, TREE, emptySet()))
  check(!access.needsRestore(retryable.state, retryable.error, TREE, setOf(identity)))
  check(!access.needsRestore(retryable.state, retryable.error, TREE, null))
  val resolver = Resolver()
  val outcome = access.restore(resolver, retryable, retryable, Uri.parse(TREE))
  check(outcome.outcome == TorrDownloadFolderAccess.Outcome.RESTORED && resolver.takes == 1)
 }
 for ((state, error) in listOf("running" to "storage", "queued" to "",
  "finalizing" to "", "error" to "video_invalid", "error" to "source_auth", "paused" to "video_invalid")) {
  val changed = request.copy(state=state, error=error)
  check(!access.needsRestore(state, error, TREE, emptySet()))
  val resolver = Resolver()
  check(access.restore(resolver, changed, changed, Uri.parse(TREE)).outcome == TorrDownloadFolderAccess.Outcome.STALE)
  check(resolver.takes == 0)
 }
 // Every picker outcome takes one permission snapshot, and only an exact match can take a grant.
 for ((selected, expected) in listOf(
  null to TorrDownloadFolderAccess.Outcome.CANCELLED,
  Uri.parse("content://elsewhere.example/tree/USB%3AGames%2FPS1") to TorrDownloadFolderAccess.Outcome.DIFFERENT_FOLDER,
  Uri.parse("content://storage.example/tree/USB%3AOther") to TorrDownloadFolderAccess.Outcome.DIFFERENT_FOLDER,
  Uri.parse("content://storage.example/document/USB%3AGames%2FPS1") to TorrDownloadFolderAccess.Outcome.DIFFERENT_FOLDER,
  Uri.parse(TREE + "/document/USB%3AGames%2FPS1%2FChild") to TorrDownloadFolderAccess.Outcome.DIFFERENT_FOLDER,
  Uri.parse(TREE + "?query=1") to TorrDownloadFolderAccess.Outcome.DIFFERENT_FOLDER)) {
  val resolver = Resolver()
  val result = access.restore(resolver, request, request, selected)
  check(result.outcome == expected && result.grants == emptySet<TorrDownloadFolderAccess.Identity>())
  check(resolver.takes == 0 && resolver.reads == 1 && resolver.grants.isEmpty())
  check(request == original)
 }
 for (changed in listOf(null, request.copy(id="new-id"), request.copy(tree=TREE+"Other"),
  request.copy(document="content://storage.example/document/replaced"),
  request.copy(state="paused", error=""), request.copy(error="finalize"), request.copy(error="source_auth"))) {
  val resolver = Resolver()
  check(access.restore(resolver, request, changed, Uri.parse(TREE)).outcome == TorrDownloadFolderAccess.Outcome.STALE)
  check(resolver.takes == 0 && resolver.reads == 1 && request == original)
 }
 val restored = Resolver()
 val result = access.restore(restored, request, request, Uri.parse("content://storage.example/tree/USB%3aGames%2fPS1"))
 check(result.outcome == TorrDownloadFolderAccess.Outcome.RESTORED && result.grants == setOf(identity))
 check(restored.takes == 1 && restored.reads == 1 && request == original)
 for ((read, write) in listOf(true to false, false to true, false to false)) {
  val resolver = Resolver().apply { readGranted = read; writeGranted = write }
  check(access.restore(resolver, request, request, Uri.parse(TREE)).outcome == TorrDownloadFolderAccess.Outcome.UNAVAILABLE)
  check(resolver.takes == 1 && resolver.reads == 1)
 }
 val rejected = Resolver().apply { takeThrows = true }
 check(access.restore(rejected, request, request, Uri.parse(TREE)).outcome == TorrDownloadFolderAccess.Outcome.UNAVAILABLE)
 check(rejected.takes == 1 && rejected.reads == 1 && rejected.grants.isEmpty())
 val unknown = Resolver().apply { readThrows = true }
 val unavailable = access.restore(unknown, request, request, Uri.parse(TREE))
 check(unavailable.outcome == TorrDownloadFolderAccess.Outcome.UNAVAILABLE && unavailable.grants == null)
 check(unknown.takes == 1 && unknown.reads == 1)
 check(request == original)
 // A completed file only needs read access to its recorded folder; the job and file stay unchanged.
 val done = request.copy(state="done", error="")
 val finished = done.copy()
 check(access.canRestore("done", ""))
 check(access.needsRestore("done", "", TREE, emptySet(), emptySet()))
 check(access.needsRestore("done", "", TREE, setOf(identity), emptySet())) // read set decides for done
 check(!access.needsRestore("done", "", TREE, emptySet(), setOf(identity)))
 check(!access.needsRestore("done", "", TREE, emptySet(), null))
 check(!access.needsRestore("done", "", TREE, emptySet()))
 check(access.needsRestore("paused", "", TREE, emptySet(), setOf(identity))) // resuming still needs write
 val readOnly = Resolver()
 readOnly.grants += UriPermission(Uri.parse(TREE), true, false)
 val readOnlyGrants = access.snapshotGrants(readOnly) ?: error("Snapshot unavailable")
 check(identity in readOnlyGrants.read && identity !in readOnlyGrants.readWrite)
 check(!access.needsRestore("done", "", TREE, readOnlyGrants.readWrite, readOnlyGrants.read))
 check(access.needsRestore("paused", "", TREE, readOnlyGrants.readWrite, readOnlyGrants.read))
 val reader = Resolver().apply { expectedFlags = 1; writeGranted = false }
 val readResult = access.restore(reader, done, done, Uri.parse("content://storage.example/tree/USB%3aGames%2fPS1"))
 check(readResult.outcome == TorrDownloadFolderAccess.Outcome.RESTORED)
 check(readResult.readGrants == setOf(identity) && readResult.grants == emptySet<TorrDownloadFolderAccess.Identity>())
 check(reader.takes == 1 && reader.reads == 1 && done == finished)
 for ((selected, expected) in listOf(null to TorrDownloadFolderAccess.Outcome.CANCELLED,
  Uri.parse("content://storage.example/tree/USB%3AOther") to TorrDownloadFolderAccess.Outcome.DIFFERENT_FOLDER)) {
  val resolver = Resolver().apply { expectedFlags = 1 }
  check(access.restore(resolver, done, done, selected).outcome == expected)
  check(resolver.takes == 0 && resolver.reads == 1 && done == finished)
 }
 for (changed in listOf(null, done.copy(document="content://storage.example/document/replaced"),
  done.copy(tree=TREE+"Other"), request)) {
  val resolver = Resolver().apply { expectedFlags = 1 }
  check(access.restore(resolver, done, changed, Uri.parse(TREE)).outcome == TorrDownloadFolderAccess.Outcome.STALE)
  check(resolver.takes == 0)
 }
 val unreadable = Resolver().apply { expectedFlags = 1; readGranted = false; writeGranted = false }
 check(access.restore(unreadable, done, done, Uri.parse(TREE)).outcome == TorrDownloadFolderAccess.Outcome.UNAVAILABLE)
 println("PASS: completed file read-only regrant keeps job, file and write policy for resume")
 println("PASS: paused and publication-error recovery, exact tree/state, verified regrant, missing or unknown grants, stale and ambiguous result rejection")
}
''',
}

with tempfile.TemporaryDirectory(prefix='gostex-folder-access-') as td:
    folder = Path(td)
    for name, content in sources.items():
        (folder / name).write_text(content)
    source = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/TorrDownloadFolderAccess.kt'
    cp = str(COMPILER[1])
    result = subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                             '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(folder / 'classes'), str(source),
                             *(str(folder / name) for name in sources)], capture_output=True, text=True, timeout=60)
    if result.returncode:
        raise SystemExit(result.stderr)
    result = subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{cp}', 'tv.orbit.shield.ChecksKt'],
                            capture_output=True, text=True, timeout=15)
    print(result.stdout, end='')
    if result.returncode:
        raise SystemExit(result.stderr)

# Resource parity and a narrow wiring guard complement, rather than replace, the runtime policy test.
keys = None
for suffix in ('', '-en', '-es', '-ru'):
    resources = ET.parse(ROOT / f'native-launcher/app/src/main/res/values{suffix}/strings.xml').getroot()
    found = {item.attrib['name'] for item in resources.findall('string') if item.attrib['name'].startswith('torr_folder_access_')}
    assert len(found) == 12
    if keys is None:
        keys = found
    assert found == keys
ui = (ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/TorrDownloadUi.kt').read_text()
callback = ui[ui.index('val folderAccessPicker ='):ui.index('fun restoreFolderAccess(')]
assert 'startTorrDownload' not in callback and 'TorrDownloadStore.update' not in callback
assert 'putString' not in callback and 'chooseTorrFolder' not in callback
assert 'TorrDownloadFolderAccess.restore' in callback and 'folderAccessReturnFocus = request?.id' in callback
assert ui.count('TorrDownloadFolderAccess.snapshotGrants(') == 1 and 'TorrDownloadFolderAccess.snapshot(' not in ui
assert 'folderReadGrants = result.readGrants' in ui
assert 'val restoreAccess = needsFolderAccess(job)' in ui
assert 'filter(::canResumeInBatch)' in ui and '!needsFolderAccess(job)' in ui
assert 'if (needsFolderAccess(current)) restoreFolderAccess(current, false)' in ui
# Completed game actions read the recorded file, so a known-missing read grant routes them to the same regrant.
game = ui[ui.index('val gameAction = if (job.state == "done") downloadGameAction(job) else null'):]
game = game[:game.index('if (job.state == "paused" || job.state == "error") SettingRow(')]
assert 'if (needsFolderAccess(job) && downloadGameReadsFolder(job)) restoreFolderAccess(job, true)' in game
assert 'else activity.addDownloadedGame(job)' in game
reads = ui[ui.index('private fun downloadGameReadsFolder('):ui.index('private fun finishedFolderAccessHint(')]
assert 'setOf("cue", "gdi", "m3u")' in reads and 'GameArchiveFormat.supportsName(job.finalName)' in reads
assert ui.count('finishedFolderAccessHint(job)') == 2 and 'R.string.torr_folder_access_game_restored' in callback
print('PASS: shared row, restart and bulk policy, localized parity and no auto-start or default-folder write')
