"""Exercise mixed queue persistence and finalization through the real codec/store."""
from pathlib import Path
import subprocess
import tempfile

from check_torr_completed_store import context, uri, source, journal, jsonjar
from kotlin_toolchain import JAVA, COMPILER, ROOT

checks = r'''package tv.orbit.shield
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val FILE = "https://example.invalid/record.bin?download=private-fixture"
private const val ACTIVE_ID = "00000000-0000-4000-8000-000000000002"
private const val DONE_ID = "00000000-0000-4000-8000-000000000003"
private const val RESTART_ID = "00000000-0000-4000-8000-000000000005"
private const val RESTART_DONE_ID = "00000000-0000-4000-8000-000000000006"
private const val SESSION_ID = "00000000-0000-4000-8000-000000000007"
private const val SESSION_FULL_ID = "00000000-0000-4000-8000-000000000008"
private const val SESSION_URL = "https://example.invalid/file?ticket=private-session-fixture"
private const val SESSION_COOKIE = "session=private-cookie-fixture"
private const val TREE = "content://provider/tree/downloads"
private val tag = "\"strong-private-fixture\""
private val legacyHash = "b".repeat(40)

fun main(args: Array<String>) {
    val ctx = Context(args[1])
    val origin = HttpDownloadOrigin(HttpDownloadUrl.parse(FILE), "a".repeat(64), tag)
    val pending = TorrDownloadJob(ACTIVE_ID, "", 0, 1024, "record.bin", TREE,
        "$TREE/document/partial", "record.bin", http = origin)
    val finalize = pending.copy(id = DONE_ID, document = "$TREE/document/other-partial", name = "done.bin", finalName = "done.bin",
        http = origin.copy(url = HttpDownloadUrl.parse("https://example.invalid/done.bin?download=finish-fixture"), resourceKey = "c".repeat(64)))
    val restart = pending.copy(id = RESTART_ID, document = "$TREE/document/restart-partial", name = "whole.bin", finalName = "whole.bin",
        http = origin.copy(resourceKey = "e".repeat(64), expectedEtag = null))
    val restartDone = restart.copy(id = RESTART_DONE_ID, document = "$TREE/document/whole-done", name = "whole-done.bin",
        finalName = "whole-done.bin", state = "done", offset = restart.total,
        http = restart.http!!.copy(url = null, resourceKey = "f".repeat(64)))
    val session = checkNotNull(HttpDownloadSession.create("https://example.invalid/", SESSION_URL, 1024, "application/octet-stream") { SESSION_COOKIE })
    val admitted = HttpDownloadAdmissionResult(session.url, session.url.resourceKey(), 1024, tag, session)
    val sessionJob = pending.copy(id = SESSION_ID, name = "site.bin", finalName = "site.bin", document = "$TREE/document/site",
        http = HttpDownloadOrigin(null, admitted.resourceKey, tag, sessionBound = true))
    val full = sessionJob.copy(id = SESSION_FULL_ID, name = "site-full.bin", finalName = "site-full.bin",
        tree = "content://provider/tree/archive", document = "content://provider/tree/archive/document/site")
    fun encode(job: TorrDownloadJob) = TorrDownloadJournalCodec.encode(job)
    fun row(id: String) = TorrDownloadStore.jobs.value.single { it.id == id }

    if (args[0] == "reload") {
        TorrDownloadStore.recover(ctx)
        val jobs = TorrDownloadStore.jobs.value
        check(jobs.size == 7 && jobs.first().hash == legacyHash && jobs.first().http == null)
        check(row(ACTIVE_ID) == pending.copy(offset = 17, etag = tag, state = "paused"))
        check(row(DONE_ID) == finalize.copy(offset = 1024, etag = tag, state = "done", document = "$TREE/document/done", http = finalize.http!!.copy(url = null)))
        check(!encode(row(DONE_ID)).contains("finish-fixture") && !encode(row(DONE_ID)).contains("example.invalid"))
        check(runCatching { TorrDownloadStore.requireHttpAdmission(ctx, TREE, row(DONE_ID).http!!.resourceKey) }.exceptionOrNull() is HttpDownloadDuplicate)
        check(row(RESTART_ID) == restart.copy(offset = 31))
        check(row(RESTART_ID).requiresHttpRestart)
        check(row(RESTART_DONE_ID) == restartDone && !row(RESTART_DONE_ID).requiresHttpRestart)
        check(FILE !in encode(row(RESTART_DONE_ID)))
        check(row(SESSION_ID) == sessionJob.copy(offset = 123, etag = tag))
        check(TorrDownloadStore.sessionFor(SESSION_ID) == null)
        check(runCatching { TorrDownloadStore.queue(ctx, listOf(SESSION_ID)) }.exceptionOrNull() is HttpDownloadSessionRequired)
        check(row(SESSION_FULL_ID) == full.copy(offset = 1024, etag = tag))
        check(TorrDownloadStore.sessionFor(SESSION_FULL_ID) == null)
        TorrDownloadStore.queue(ctx, listOf(SESSION_FULL_ID)) // Finalization needs only the verified local partial.
        check(row(SESSION_FULL_ID).state == "queued")
        check(SESSION_URL !in ctx.journalFile.readText() && SESSION_COOKIE !in ctx.journalFile.readText())
        println("PASS: cold mixed queue reopen and pause recovery; completed URL stays removed")
        return
    }

    val legacy = pending.copy(id = "00000000-0000-4000-8000-000000000001", hash = legacyHash, index = 3,
        document = "$TREE/document/torrent", name = "torrent.bin", finalName = "torrent.bin", http = null, grouped = true, parentId = "group")
    val legacyWire = JSONObject(encode(legacy))
    check(legacyWire.getString("hash") == legacyHash && legacyWire.getInt("index") == 3 && !legacyWire.has("http"))
    check(TorrDownloadJournalCodec.readLegacy(JSONArray().put(legacyWire).toString(), "[]") == listOf(legacy))

    val httpWire = JSONObject(encode(pending))
    check(!httpWire.has("hash") && !httpWire.has("index")) // Old readers require both; can't mistake this for a torrent.
    check(httpWire.getJSONObject("http").let { it.getString("url") == FILE && it.getString("expectedEtag") == tag && !it.has("restartOnly") })
    check(TorrDownloadJournalCodec.decode(httpWire.toString()) == pending)
    check("private-fixture" !in pending.toString() && "private-fixture" !in pending.copy(etag = tag).toString())
    for (value in listOf(JSONArray().put(httpWire).toString(), JSONArray().put(legacyWire).put(httpWire).toString())) {
        check(runCatching { TorrDownloadJournalCodec.readLegacy(value, null) }.isFailure)
        check(runCatching { TorrDownloadJournalCodec.readLegacy("[]", value) }.isFailure)
    }
    fun rejects(base: JSONObject = httpWire, change: JSONObject.() -> Unit) {
        val malformed = JSONObject(base.toString()).apply(change)
        val error = runCatching { TorrDownloadJournalCodec.decode(malformed.toString()) }.exceptionOrNull()
        check(error is IllegalArgumentException && error.cause == null && "private-fixture" !in error.toString())
    }
    rejects { put("hash", "") }
    rejects { put("index", 0) }
    rejects { put("http", JSONObject.NULL) }
    rejects { put("http", FILE) }
    rejects { put("unexpected", FILE) }
    rejects { getJSONObject("http").put("resourceKey", "not-a-key") }
    rejects { getJSONObject("http").put("expectedEtag", "W/$tag") }
    rejects { getJSONObject("http").put("url", "https://test:private-fixture@example.invalid/file") }
    rejects { getJSONObject("http").remove("url") }
    rejects { getJSONObject("http").put("url", JSONObject.NULL) }
    rejects { put("offset", 17) }
    rejects { put("offset", 17); put("etag", "\"changed\"") }
    rejects { put("offset", 0); put("etag", "\"changed\"") }
    rejects { put("total", 0) }
    rejects { put("total", "1024") }
    rejects { put("offset", 0.5) }
    rejects { put("grouped", true); put("parentId", "group") }
    rejects { put("prefetchMovieId", "movie:test") }
    rejects { put("state", "finalizing") }
    rejects { put("state", "done"); put("offset", 1024); put("etag", tag) }
    check(TorrDownloadJournalCodec.decode(encode(pending.copy(etag = tag))) == pending.copy(etag = tag))

    val restartWire = JSONObject(encode(restart.copy(offset = 31)))
    val restartOriginWire = restartWire.getJSONObject("http")
    check(restartOriginWire.opt("restartOnly") == true && !restartOriginWire.has("expectedEtag"))
    check(!restartWire.has("hash") && !restartWire.has("index") && restartWire.isNull("etag"))
    check(TorrDownloadJournalCodec.decode(restartWire.toString()) == restart.copy(offset = 31))
    check(runCatching { TorrDownloadJournalCodec.readLegacy("[${restartWire}]", null) }.isFailure)
    rejects(restartWire) { getJSONObject("http").put("expectedEtag", tag) }
    rejects(restartWire) { getJSONObject("http").put("expectedEtag", JSONObject.NULL) }
    rejects(restartWire) { getJSONObject("http").put("restartOnly", false) }
    rejects(restartWire) { getJSONObject("http").put("restartOnly", "true") }
    rejects(restartWire) { getJSONObject("http").put("restartOnly", JSONObject.NULL) }
    rejects(restartWire) { getJSONObject("http").remove("restartOnly") }
    rejects(restartWire) { put("etag", tag) }
    rejects(restartWire) { put("offset", -1) }
    rejects(restartWire) { put("offset", 1025) }
    rejects(restartWire) { put("state", "finalizing") }
    rejects(restartWire) { put("state", "done"); put("offset", 1024) }
    check(TorrDownloadJournalCodec.decode(encode(restartDone)) == restartDone)

    check(ctx.prefs.edit().putString("downloads.folder", "unchanged").commit())
    TorrDownloadStore.add(ctx, legacy)
    TorrDownloadStore.requireHttpAdmission(ctx, TREE, origin.resourceKey)
    TorrDownloadStore.add(ctx, pending)
    check(!TorrDownloadStore.beginTorrentRemoval(ctx, legacyHash))
    val otherHash = "d".repeat(40)
    check(TorrDownloadStore.beginTorrentRemoval(ctx, otherHash))
    check(runCatching { TorrDownloadStore.requireHttpAdmission(ctx, TREE, origin.resourceKey) }.exceptionOrNull() is HttpDownloadDuplicate)
    val duplicate = pending.copy(id = "00000000-0000-4000-8000-000000000004", document = "$TREE/document/different", finalName = "other.bin",
        http = origin.copy(url = HttpDownloadUrl.parse("https://example.invalid/alternate-redirect")))
    check(runCatching { TorrDownloadStore.add(ctx, duplicate) }.exceptionOrNull() is HttpDownloadDuplicate)
    val otherTree = "content://provider/tree/other"
    TorrDownloadStore.requireHttpAdmission(ctx, otherTree, origin.resourceKey)
    TorrDownloadStore.add(ctx, duplicate.copy(tree = otherTree, document = "$otherTree/document/partial"))
    TorrDownloadStore.removeRecord(ctx, duplicate.id)
    TorrDownloadStore.add(ctx, finalize)
    TorrDownloadStore.queue(ctx, listOf(pending.id, finalize.id))
    check(TorrDownloadStore.claimWorker(ctx, pending.id))
    TorrDownloadStore.update(ctx, pending.id) { it.copy(state = "running", offset = 17, etag = tag) }
    check(runCatching { TorrDownloadStore.resetHttpCheckpoint(ctx, pending.id) }.isFailure)
    TorrDownloadStore.releaseWorker(pending.id)
    for (change in listOf<(TorrDownloadJob) -> TorrDownloadJob>(
        { it.copy(http = origin.copy(url = HttpDownloadUrl.parse("https://example.invalid/wrong"))) },
        { it.copy(http = origin.copy(resourceKey = "e".repeat(64))) },
        { it.copy(http = origin.copy(expectedEtag = "\"changed\""), etag = "\"changed\"") },
        { it.copy(document = "$TREE/document/not-owned") },
        { it.copy(offset = 16) },
        { it.copy(http = origin.copy(url = null), state = "done", offset = 1024) },
    )) {
        check(runCatching { TorrDownloadStore.update(ctx, pending.id, change) }.isFailure)
    }
    TorrDownloadStore.update(ctx, finalize.id) { it.copy(state = "finalizing", offset = it.total, etag = tag) }
    val beforeDone = ctx.journalFile.readText()
    check("finish-fixture" in beforeDone)
    fun accept(j: TorrDownloadJob) = j.copy(state = "done", document = "$TREE/document/done", http = j.http!!.copy(url = null))
    ctx.failWriteOnce = true
    check(runCatching { TorrDownloadStore.update(ctx, finalize.id, ::accept) }.isFailure)
    check(ctx.journalFile.readText() == beforeDone && row(finalize.id).http!!.url != null)
    TorrDownloadStore.update(ctx, finalize.id, ::accept)
    check(row(finalize.id).http!!.url == null && "finish-fixture" !in ctx.journalFile.readText())
    check("private-fixture" in ctx.journalFile.readText()) // Unfinished direct source is still resumable.
    check(runCatching { TorrDownloadStore.update(ctx, finalize.id) { it.copy(state = "paused", http = finalize.http) } }.isFailure)
    TorrDownloadStore.add(ctx, restart)
    check(!row(RESTART_ID).requiresHttpRestart)
    check(runCatching { TorrDownloadStore.queue(ctx, listOf(RESTART_ID), restartId = RESTART_ID) }.isFailure)
    TorrDownloadStore.queue(ctx, listOf(RESTART_ID))
    check(TorrDownloadStore.claimWorker(ctx, RESTART_ID))
    TorrDownloadStore.update(ctx, RESTART_ID) { it.copy(state = "running") }
    TorrDownloadStore.update(ctx, RESTART_ID) { it.copy(state = "paused") }
    check(runCatching { TorrDownloadStore.queue(ctx, listOf(RESTART_ID)) }.isFailure) // Tail flush can turn zero into a partial.
    TorrDownloadStore.update(ctx, RESTART_ID) { it.copy(offset = 64) }
    check(runCatching { TorrDownloadStore.resetHttpCheckpoint(ctx, RESTART_ID) }.isFailure) // Pause wins the reset race.
    TorrDownloadStore.releaseWorker(RESTART_ID)
    val beforeQueue = ctx.journalFile.readText()
    for (reject in listOf<() -> Unit>(
        { TorrDownloadStore.queue(ctx, listOf(RESTART_ID)) },
        { TorrDownloadStore.queue(ctx, listOf(RESTART_ID, legacy.id)) },
        { TorrDownloadStore.queue(ctx, listOf(RESTART_ID, legacy.id), restartId = RESTART_ID) },
        { TorrDownloadStore.queue(ctx, listOf(RESTART_ID), restartId = legacy.id) },
        { TorrDownloadStore.queue(ctx, listOf(legacy.id), restartId = legacy.id) },
    )) check(runCatching(reject).isFailure && ctx.journalFile.readText() == beforeQueue)
    TorrDownloadStore.queue(ctx, listOf(RESTART_ID), restartId = RESTART_ID)
    check(row(RESTART_ID) == restart.copy(offset = 64, state = "queued")) // Approval alone is not a reset.
    check(runCatching { TorrDownloadStore.resetHttpCheckpoint(ctx, RESTART_ID) }.isFailure)
    TorrDownloadStore.update(ctx, RESTART_ID) { it.copy(state = "running") }
    check(runCatching { TorrDownloadStore.resetHttpCheckpoint(ctx, RESTART_ID) }.isFailure) // No worker owns it.
    TorrDownloadStore.update(ctx, RESTART_ID) { it.copy(state = "paused") }
    TorrDownloadStore.queue(ctx, listOf(RESTART_ID), restartId = RESTART_ID)
    check(TorrDownloadStore.claimWorker(ctx, RESTART_ID))
    TorrDownloadStore.update(ctx, RESTART_ID) { it.copy(state = "running") }
    val old = row(RESTART_ID)
    val beforeReset = ctx.journalFile.readText()
    check(runCatching { TorrDownloadStore.update(ctx, RESTART_ID) { it.copy(offset = 0) } }.isFailure)
    ctx.failWriteOnce = true
    check(runCatching { TorrDownloadStore.resetHttpCheckpoint(ctx, RESTART_ID) }.isFailure)
    check(row(RESTART_ID) == old && ctx.journalFile.readText() == beforeReset)
    check(TorrDownloadStore.resetHttpCheckpoint(ctx, RESTART_ID) == old.copy(offset = 0))
    check(row(RESTART_ID) == old.copy(offset = 0) && ctx.journalFile.readText() != beforeReset)
    TorrDownloadStore.update(ctx, RESTART_ID) { it.copy(offset = 31) }
    TorrDownloadStore.releaseWorker(RESTART_ID)

    check(runCatching { TorrDownloadStore.add(ctx, restartDone) }.isFailure)
    val ready = restartDone.copy(offset = 0, state = "paused", document = "$TREE/document/whole-done-partial",
        http = restartDone.http!!.copy(url = restart.http!!.url))
    TorrDownloadStore.add(ctx, ready)
    check(runCatching { TorrDownloadStore.update(ctx, RESTART_DONE_ID) { restartDone } }.isFailure)
    TorrDownloadStore.queue(ctx, listOf(RESTART_DONE_ID))
    check(TorrDownloadStore.claimWorker(ctx, RESTART_DONE_ID))
    TorrDownloadStore.update(ctx, RESTART_DONE_ID) { it.copy(offset = it.total, state = "running") }
    check(!row(RESTART_DONE_ID).requiresHttpRestart && runCatching { TorrDownloadStore.resetHttpCheckpoint(ctx, RESTART_DONE_ID) }.isFailure)
    TorrDownloadStore.update(ctx, RESTART_DONE_ID) { it.copy(state = "finalizing") }
    check(TorrDownloadStore.update(ctx, RESTART_DONE_ID) { restartDone } == restartDone)
    TorrDownloadStore.releaseWorker(RESTART_DONE_ID)
    val sessionWire = JSONObject(encode(sessionJob))
    check(sessionWire.getJSONObject("http").let { it.opt("sessionBound") == true && !it.has("url") && it.opt("expectedEtag") == tag })
    check(TorrDownloadJournalCodec.decode(sessionWire.toString()) == sessionJob)
    for (marker in listOf(false, "true", JSONObject.NULL)) rejects(sessionWire) { getJSONObject("http").put("sessionBound", marker) }
    rejects(sessionWire) { getJSONObject("http").put("url", SESSION_URL) }
    rejects(sessionWire) { getJSONObject("http").put("url", JSONObject.NULL) }
    rejects(sessionWire) { getJSONObject("http").remove("expectedEtag"); getJSONObject("http").put("restartOnly", true) }
    rejects(sessionWire) { getJSONObject("http").remove("sessionBound") }

    check(runCatching { TorrDownloadStore.add(ctx, sessionJob) }.exceptionOrNull() is HttpDownloadSessionRequired)
    ctx.failWriteOnce = true
    check(runCatching { TorrDownloadStore.add(ctx, sessionJob, admitted) }.isFailure)
    check(TorrDownloadStore.sessionFor(SESSION_ID) == null && TorrDownloadStore.jobs.value.none { it.id == SESSION_ID })
    TorrDownloadStore.add(ctx, sessionJob, admitted)
    check(TorrDownloadStore.sessionFor(SESSION_ID) === session)
    TorrDownloadStore.queue(ctx, listOf(SESSION_ID))
    check(TorrDownloadStore.claimWorker(ctx, SESSION_ID))
    TorrDownloadStore.update(ctx, SESSION_ID) { it.copy(state = "paused", offset = 123, etag = tag) }
    check(runCatching { TorrDownloadStore.bindSession(ctx, SESSION_ID, admitted) }.isFailure)
    TorrDownloadStore.releaseWorker(SESSION_ID)
    TorrDownloadStore.invalidateSession(SESSION_ID)
    check(runCatching { TorrDownloadStore.queue(ctx, listOf(SESSION_ID, legacy.id)) }.exceptionOrNull() is HttpDownloadSessionRequired)
    check(row(legacy.id).state == "paused")
    for (other in listOf(admitted.copy(size = 1025), admitted.copy(strongEtag = "\"changed\""), admitted.copy(resourceKey = "1".repeat(64)), admitted.copy(session = null))) {
        check(runCatching { TorrDownloadStore.bindSession(ctx, SESSION_ID, other) }.isFailure)
    }
    val diskBeforeBind = ctx.journalFile.readText()
    TorrDownloadStore.bindSession(ctx, SESSION_ID, admitted)
    check(TorrDownloadStore.sessionFor(SESSION_ID) === session && ctx.journalFile.readText() == diskBeforeBind)
    TorrDownloadStore.queue(ctx, listOf(SESSION_ID))

    TorrDownloadStore.add(ctx, full, admitted)
    TorrDownloadStore.update(ctx, SESSION_FULL_ID) { it.copy(offset = it.total, etag = tag) }
    check(TorrDownloadStore.sessionFor(SESSION_FULL_ID) == null)
    val saved = ctx.journalFile.readText()
    check("private-session-fixture" !in saved && "private-cookie-fixture" !in saved)
    check(ctx.prefs.getString("downloads.folder", null) == "unchanged")
    println("PASS: typed encoding, malformed/legacy rejection, immutable checkpoints, durable restart, URL removal and volatile session binding")
}
'''

base = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
production = [base / name for name in ('TorrDownloadStore.kt', 'TorrDownloadJournal.kt', 'TorrDownloadJournalCodec.kt', 'HttpDownloadAdmission.kt')]
cp = ':'.join(map(str, [COMPILER[1], COMPILER[4], jsonjar]))
with tempfile.TemporaryDirectory(prefix='gostex-http-queue-') as td:
    folder = Path(td)
    for name, body in [('Context.kt', context), ('Uri.kt', uri), ('Source.kt', source), ('Journal.kt', journal), ('Checks.kt', checks)]:
        (folder / name).write_text(body)
    compile_cmd = [str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                   '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(folder / 'classes'),
                   *map(str, production), *map(str, folder.glob('*.kt'))]
    result = subprocess.run(compile_cmd, capture_output=True, text=True, timeout=60)
    if result.returncode:
        raise SystemExit(result.stderr)
    for phase in ('create', 'reload'):
        result = subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{cp}', 'tv.orbit.shield.ChecksKt', phase,
                                 str(folder / 'queue.properties')], capture_output=True, text=True, timeout=15)
        print(result.stdout, end='')
        if result.returncode:
            raise SystemExit(result.stderr)
