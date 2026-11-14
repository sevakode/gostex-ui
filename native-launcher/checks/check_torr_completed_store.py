"""Exercise production queue migration, offline inventory and durable checkpoints."""
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
checks = r'''package tv.orbit.shield
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
private const val ACTIVE = "downloads.torrserver.jobs"
private const val SAVED = "downloads.torrserver.completed"
private fun job(state: String = "paused", media: String = "") = TorrDownloadJob(
    UUID.randomUUID().toString(), "a".repeat(40), 1, 1024, "episode.mkv", "content://tree",
    "content://document/" + UUID.randomUUID(), "episode.mkv", offset = if (state == "done") 1024 else 0,
    state = state, parentId = UUID.randomUUID().toString(), grouped = true, prefetchMovieId = media)
private fun encode(j: TorrDownloadJob) = JSONObject().apply {
    put("id", j.id); put("hash", j.hash); put("index", j.index); put("total", j.total)
    put("name", j.name); put("tree", j.tree); put("document", j.document); put("finalName", j.finalName)
    put("offset", j.offset); put("etag", JSONObject.NULL); put("state", j.state); put("error", "")
    put("parentId", j.parentId); put("grouped", j.grouped); put("prefetchMovieId", j.prefetchMovieId)
}
fun main(args: Array<String>) {
    val ctx = Context(args[1])
    when (args[0]) {
        "legacy" -> {
            val offline = job("done", "series:test")
            val pending = job()
            val legacy = JSONArray().put(encode(offline)).put(encode(pending)).toString()
            check(ctx.prefs.edit().putString(ACTIVE, legacy).commit())
            TorrDownloadStore.init(ctx)
            check(TorrDownloadStore.jobs.value.size == 2)
            ctx.prefs.failNextCommit = true
            check(runCatching { TorrDownloadStore.update(ctx, pending.id) { it.copy(offset = 7) } }.isFailure)
            check(ctx.prefs.getString(ACTIVE, null) == legacy && ctx.prefs.getString(SAVED, null) == null)
            check(TorrDownloadStore.jobs.value.single { it.id == pending.id }.offset == 0L)
            TorrDownloadStore.update(ctx, pending.id) { it.copy(offset = 8) }
            check(JSONArray(ctx.prefs.getString(ACTIVE, null)).length() == 1)
            check(JSONArray(ctx.prefs.getString(SAVED, null)).length() == 1)
            // A large completed library must not consume the 100 pending slots.
            repeat(110) { TorrDownloadStore.add(ctx, job("done", "series:test")) }
            TorrDownloadStore.requireActiveCapacity(ctx, 99)
            repeat(99) { TorrDownloadStore.add(ctx, job()) }
            check(runCatching { TorrDownloadStore.add(ctx, job()) }.isFailure)
            check(runCatching { TorrDownloadStore.requireActiveCapacity(ctx, 1) }.isFailure)
            check(TorrDownloadStore.jobs.value.count { it.state != "done" } == 100)
            check(TorrDownloadStore.jobs.value.count { it.state == "done" } == 111)
            val savedWrites = ctx.prefs.writes[SAVED]
            val savedBytes = ctx.prefs.getString(SAVED, null)
            TorrDownloadStore.update(ctx, pending.id) { it.copy(offset = 16) }
            check(ctx.prefs.writes[SAVED] == savedWrites && ctx.prefs.getString(SAVED, null) == savedBytes)
            // Clearing history cannot discard a media document reference.
            check(runCatching { TorrDownloadStore.removeRecord(ctx, offline.id) }.isFailure)
            check(TorrDownloadStore.jobs.value.any { it.document == offline.document })
            TorrDownloadStore.update(ctx, pending.id) { it.copy(state = "done", offset = it.total) }
            TorrDownloadStore.add(ctx, job())
            check(JSONArray(ctx.prefs.getString(ACTIVE, null)).length() == 100)
            check(JSONArray(ctx.prefs.getString(SAVED, null)).length() == 112)
            println("PASS: migration rollback, 111 saved + 100 pending, isolated checkpoint writes, protected media")
        }
        "reload" -> {
            TorrDownloadStore.init(ctx)
            check(TorrDownloadStore.jobs.value.size == 212)
            check(TorrDownloadStore.jobs.value.count { it.state == "done" } == 112)
            check(TorrDownloadStore.jobs.value.count { it.prefetchMovieId == "series:test" } == 111)
            println("PASS: separate fresh JVM restored completed media and pending queue")
        }
        "corrupt" -> {
            val active = JSONArray().put(encode(job())).toString()
            ctx.prefs.edit().putString(ACTIVE, active).putString(SAVED, "[broken").commit()
            check(runCatching { TorrDownloadStore.init(ctx) }.isFailure)
            check(runCatching { TorrDownloadStore.add(ctx, job()) }.isFailure)
            check(ctx.prefs.getString(ACTIVE, null) == active && ctx.prefs.getString(SAVED, null) == "[broken")
            println("PASS: damaged completed index never overwrites either journal")
        }
        "duplicate" -> {
            val a = job()
            ctx.prefs.edit().putString(ACTIVE, JSONArray().put(encode(a)).toString())
                .putString(SAVED, JSONArray().put(encode(a.copy(state = "done", offset = a.total))).toString()).commit()
            check(runCatching { TorrDownloadStore.init(ctx) }.isFailure)
            println("PASS: duplicate references across journals are rejected")
        }
        else -> error("Unknown phase")
    }
}
'''

with tempfile.TemporaryDirectory(prefix='gostex-completed-store-') as directory:
    folder = Path(directory)
    for name, body in [('Context.kt', context), ('Uri.kt', uri), ('Source.kt', source), ('Checks.kt', checks)]:
        (folder / name).write_text(body)
    cp = ':'.join(map(str, [COMPILER[1], COMPILER[4], jsonjar]))
    production = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/TorrDownloadStore.kt'
    compile_result = subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', cp,
        '-d', str(folder / 'classes'), str(production), *map(str, folder.glob('*.kt'))], capture_output=True, text=True)
    if compile_result.returncode:
        raise SystemExit(compile_result.stderr)
    for phase in ('legacy', 'reload', 'corrupt', 'duplicate'):
        path = folder / ('library.properties' if phase in ('legacy', 'reload') else phase + '.properties')
        result = subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{cp}',
            'tv.orbit.shield.ChecksKt', phase, str(path)], capture_output=True, text=True)
        print(result.stdout, end='')
        if result.returncode:
            raise SystemExit(result.stderr)
