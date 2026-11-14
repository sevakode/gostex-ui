"""Exercise the real store/codec using a file-backed fake journal, not SQLite guarantees."""
from pathlib import Path
import subprocess
import tempfile

from kotlin_toolchain import ROOT, JAVA, COMPILER

json_candidates = list((Path.home() / '.gradle/caches/modules-2/files-2.1/org.json/json').glob('*/*/*.jar'))
json_candidates += list((ROOT / '.tools/native/gradle-cache/caches/modules-2/files-2.1/org.json/json').glob('*/*/*.jar'))
jsonjar = next(p for p in json_candidates if not p.name.endswith(('-sources.jar', '-javadoc.jar')))

context = r'''package android.content
import java.io.File
import java.util.Properties
open class Context(path: String) {
    companion object { const val MODE_PRIVATE = 0 }
    val journalFile = File(path + ".journal")
    var failReadOnce = false
    var failWriteOnce = false
    var journalWrites = 0
    var journalCloses = 0
    var lastChanged = emptySet<String>()
    val prefs = SharedPreferences(File(path))
    fun getSharedPreferences(name: String, mode: Int) = prefs
}
class SharedPreferences(private val file: File) {
    private val values = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }
    val writes = mutableMapOf<String, Int>()
    var failNextCommit = false
    fun getString(key: String, fallback: String?): String? = values.getProperty(key) ?: fallback
    fun edit() = Editor()
    inner class Editor {
        private val pending = mutableMapOf<String, String?>()
        fun putString(key: String, value: String): Editor { pending[key] = value; return this }
        fun remove(key: String): Editor { pending[key] = null; return this }
        fun commit(): Boolean {
            for ((key, value) in pending) {
                if (value == null) values.remove(key) else values.setProperty(key, value)
                writes[key] = (writes[key] ?: 0) + 1
            }
            // Android exposes the new in-memory values even when disk commit fails.
            if (failNextCommit) { failNextCommit = false; return false }
            file.outputStream().use { values.store(it, null) }
            return true
        }
    }
}
'''
uri = '''package android.net
class Uri private constructor(val scheme: String?) {
 companion object { fun parse(value: String) = Uri(value.substringBefore(":", "")) }
}
'''
source = '''package tv.orbit.shield
class TorrDownloadSource(hash: String, index: Int, size: Long) {
 init { require(hash.matches(Regex("[0-9a-fA-F]{40}")) && index > 0 && size >= 0) }
}
'''
journal = r'''package tv.orbit.shield
import android.content.Context
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject
// Host fake for facade publication, no-op, persistence and codec boundaries only.
// Android SQLite transaction, migration marker and physical I/O tests are separate.
internal class TorrDownloadSqliteJournal private constructor(private val context: Context) : TorrDownloadJournal {
    companion object {
        fun open(context: Context): TorrDownloadJournal = TorrDownloadSqliteJournal(context)
    }
    private var closed = false
    private var snapshot = if (context.journalFile.exists()) {
        val array = JSONArray(context.journalFile.readText())
        (0 until array.length()).map { TorrDownloadJournalCodec.decode(array.getJSONObject(it).toString()) }
            .also(TorrDownloadJournalCodec::validate)
    } else {
        TorrDownloadJournalCodec.readLegacy(
            context.prefs.getString(TorrDownloadJournalCodec.ACTIVE_KEY, null),
            context.prefs.getString(TorrDownloadJournalCodec.COMPLETED_KEY, null)).also(::save)
    }
    override fun read(): List<TorrDownloadJob> {
        check(!closed)
        if (context.failReadOnce) { context.failReadOnce = false; throw IOException("Read failed") }
        return snapshot
    }
    override fun write(previous: List<TorrDownloadJob>, next: List<TorrDownloadJob>) {
        check(!closed && previous == snapshot)
        TorrDownloadJournalCodec.validate(next)
        if (context.failWriteOnce) { context.failWriteOnce = false; throw IOException("Commit failed") }
        save(next)
        context.journalWrites++
        context.lastChanged = (previous.map { it.id } + next.map { it.id }).toSet().filter { id ->
            previous.find { it.id == id } != next.find { it.id == id }
        }.toSet()
        snapshot = next
    }
    private fun save(jobs: List<TorrDownloadJob>) {
        val array = JSONArray()
        jobs.forEach { array.put(JSONObject(TorrDownloadJournalCodec.encode(it))) }
        val temp = java.io.File(context.journalFile.path + ".tmp")
        temp.writeText(array.toString())
        Files.move(temp.toPath(), context.journalFile.toPath(), StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE)
    }
    override fun close() { if (!closed) { closed = true; context.journalCloses++ } }
}
'''
checks = r'''package tv.orbit.shield
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
private const val ACTIVE = TorrDownloadJournalCodec.ACTIVE_KEY
private const val SAVED = TorrDownloadJournalCodec.COMPLETED_KEY
private fun job(state: String = "paused", media: String = "") = TorrDownloadJob(
    UUID.randomUUID().toString(), "a".repeat(40), 1, 1024, "episode.mkv", "content://tree",
    "content://document/" + UUID.randomUUID(), "episode.mkv", offset = if (state == "done") 1024 else 0,
    state = state, parentId = UUID.randomUUID().toString(), grouped = true, prefetchMovieId = media)
private fun encode(j: TorrDownloadJob) = JSONObject(TorrDownloadJournalCodec.encode(j))
fun main(args: Array<String>) {
    val ctx = Context(args[1])
    when (args[0]) {
        "legacy" -> {
            val offline = job("done", "series:test")
            val pending = job()
            val legacy = JSONArray().put(encode(offline)).put(encode(pending)).toString()
            check(ctx.prefs.edit().putString(ACTIVE, legacy).putString("clock.options", "unchanged").commit())
            // Failure after obtaining a handle closes it and leaves flow unpublished.
            ctx.failReadOnce = true
            check(runCatching { TorrDownloadStore.init(ctx) }.isFailure)
            check(ctx.journalCloses == 1 && TorrDownloadStore.jobs.value.isEmpty())
            TorrDownloadStore.init(ctx)
            check(TorrDownloadStore.jobs.value == listOf(offline, pending))
            val originalDisk = ctx.journalFile.readText()
            ctx.failWriteOnce = true
            check(runCatching { TorrDownloadStore.update(ctx, pending.id) { it.copy(offset = 7) } }.isFailure)
            check(ctx.journalFile.readText() == originalDisk)
            check(TorrDownloadStore.jobs.value.single { it.id == pending.id }.offset == 0L)
            check(ctx.prefs.getString(ACTIVE, null) == legacy && ctx.prefs.getString(SAVED, null) == null)
            TorrDownloadStore.update(ctx, pending.id) { it.copy(offset = 8) }
            // A large completed library must not consume the 100 pending slots.
            repeat(110) { TorrDownloadStore.add(ctx, job("done", "series:test")) }
            TorrDownloadStore.requireActiveCapacity(ctx, 99)
            repeat(99) { TorrDownloadStore.add(ctx, job()) }
            check(runCatching { TorrDownloadStore.add(ctx, job()) }.isFailure)
            check(runCatching { TorrDownloadStore.requireActiveCapacity(ctx, 1) }.isFailure)
            check(TorrDownloadStore.jobs.value.count { it.state != "done" } == 100)
            check(TorrDownloadStore.jobs.value.count { it.state == "done" } == 111)
            val previousWrites = ctx.journalWrites
            repeat(32) { TorrDownloadStore.update(ctx, pending.id) { it.copy(offset = 8) } }
            check(ctx.journalWrites == previousWrites)
            TorrDownloadStore.queue(ctx, listOf(pending.id))
            val queuedWrites = ctx.journalWrites
            repeat(32) { TorrDownloadStore.queue(ctx, listOf(pending.id)) }
            check(ctx.journalWrites == queuedWrites)
            TorrDownloadStore.update(ctx, pending.id) { it.copy(offset = 16) }
            check(ctx.lastChanged == setOf(pending.id))
            // Clearing history cannot discard a media document reference.
            check(runCatching { TorrDownloadStore.removeRecord(ctx, offline.id) }.isFailure)
            check(TorrDownloadStore.jobs.value.any { it.document == offline.document })
            TorrDownloadStore.update(ctx, pending.id) { it.copy(state = "done", offset = it.total) }
            TorrDownloadStore.add(ctx, job())
            check(ctx.prefs.getString("clock.options", null) == "unchanged")
            check(TorrDownloadStore.jobs.value.count { it.state != "done" } == 100)
            check(TorrDownloadStore.jobs.value.count { it.state == "done" } == 112)
            println("PASS: facade read/commit failure isolation, 111 saved + 100 pending, no-op, protected media")
        }
        "reload" -> {
            TorrDownloadStore.init(ctx)
            check(TorrDownloadStore.jobs.value.size == 212)
            check(TorrDownloadStore.jobs.value.count { it.state == "done" } == 112)
            check(TorrDownloadStore.jobs.value.count { it.prefetchMovieId == "series:test" } == 111)
            check(TorrDownloadStore.jobs.value[0].prefetchMovieId == "series:test")
            check(TorrDownloadStore.jobs.value[1].state == "done")
            println("PASS: separate fresh JVM restored record order, completed media and pending queue")
        }
        "corrupt" -> {
            val active = JSONArray().put(encode(job())).toString()
            ctx.prefs.edit().putString(ACTIVE, active).putString(SAVED, "[broken").commit()
            check(runCatching { TorrDownloadStore.init(ctx) }.isFailure)
            check(runCatching { TorrDownloadStore.add(ctx, job()) }.isFailure)
            check(ctx.prefs.getString(ACTIVE, null) == active && ctx.prefs.getString(SAVED, null) == "[broken")
            check(!ctx.journalFile.exists())
            println("PASS: damaged legacy completed index cannot publish or overwrite records")
        }
        "duplicate" -> {
            val a = job()
            ctx.prefs.edit().putString(ACTIVE, JSONArray().put(encode(a)).toString())
                .putString(SAVED, JSONArray().put(encode(a.copy(state = "done", offset = a.total))).toString()).commit()
            check(runCatching { TorrDownloadStore.init(ctx) }.isFailure)
            check(!ctx.journalFile.exists())
            println("PASS: duplicate references across legacy journals rejected")
        }
        "codec" -> {
            val pending = job()
            val done = job("done", "series:test")
            val pa = JSONArray().put(encode(pending)).toString()
            val da = JSONArray().put(encode(done)).toString()
            check(TorrDownloadJournalCodec.readLegacy(null, null).isEmpty())
            check(TorrDownloadJournalCodec.readLegacy(pa, da) == listOf(pending, done))
            check(runCatching { TorrDownloadJournalCodec.readLegacy(da, "[]") }.isFailure)
            check(runCatching { TorrDownloadJournalCodec.readLegacy("[]", pa) }.isFailure)
            check(runCatching { TorrDownloadJournalCodec.readLegacy("[broken", null) }.isFailure)
            check(runCatching { TorrDownloadJournalCodec.readLegacy(" ".repeat(8*1024*1024+1), null) }.isFailure)
            check(runCatching { TorrDownloadJournalCodec.validate(listOf(pending, pending.copy(id=UUID.randomUUID().toString()))) }.isFailure)
            check(runCatching { TorrDownloadJournalCodec.decode(TorrDownloadJournalCodec.encode(pending.copy(offset=1025))) }.isFailure)
            val missingOptional = encode(pending).apply { remove("prefetchMovieId"); remove("parentId"); remove("grouped"); remove("error") }
            check(TorrDownloadJournalCodec.decode(missingOptional.toString()).prefetchMovieId.isEmpty())
            println("PASS: combined/split legacy codec, limits, optional fields and malformed-state rejection")
        }
        else -> error("Unknown phase")
    }
}
'''

if __name__ == "__main__":
    with tempfile.TemporaryDirectory(prefix='gostex-completed-store-') as directory:
        folder = Path(directory)
        for name, body in [('Context.kt', context), ('Uri.kt', uri), ('Source.kt', source), ('Journal.kt', journal), ('Checks.kt', checks)]:
            (folder / name).write_text(body)
        cp = ':'.join(map(str, [COMPILER[1], COMPILER[4], jsonjar]))
        base = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
        production = [base / name for name in ('TorrDownloadStore.kt', 'TorrDownloadJournal.kt', 'TorrDownloadJournalCodec.kt', 'HttpDownloadAdmission.kt')]
        compile_result = subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)),
            'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', cp,
            '-d', str(folder / 'classes'), *map(str, production), *map(str, folder.glob('*.kt'))], capture_output=True, text=True)
        if compile_result.returncode:
            raise SystemExit(compile_result.stderr)
        for phase in ('legacy', 'reload', 'corrupt', 'duplicate', 'codec'):
            path = folder / ('library.properties' if phase in ('legacy', 'reload') else phase + '.properties')
            result = subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{cp}',
                'tv.orbit.shield.ChecksKt', phase, str(path)], capture_output=True, text=True)
            print(result.stdout, end='')
            if result.returncode:
                raise SystemExit(result.stderr)
