"""Exercise production history preferences, observers, filtering and page boundaries with a fake Android store."""
from pathlib import Path
import subprocess
import tempfile

from kotlin_toolchain import ROOT, JAVA, COMPILER

compiler = COMPILER

android = '''package android.content
abstract class Context {
  companion object { const val MODE_PRIVATE = 0 }
  abstract fun getSharedPreferences(name: String, mode: Int): SharedPreferences
}
interface SharedPreferences {
  fun getStringSet(key: String, default: Set<String>?): Set<String>?
  fun edit(): Editor
  fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener)
  fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener)
  fun interface OnSharedPreferenceChangeListener { fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) }
  interface Editor { fun putStringSet(key: String, value: Set<String>?): Editor; fun commit(): Boolean }
}
'''

checks = '''package tv.orbit.shield
import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

data class TorrDownloadJob(val id: String, val state: String)
class Preferences : SharedPreferences {
  var raw: Any? = null
  var commits = 0
  var fail = false
  val listeners = linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
  @Suppress("UNCHECKED_CAST") override fun getStringSet(key: String, default: Set<String>?): Set<String>? {
    check(key == TorrDownloadHistory.KEY)
    return if (raw == null) default else raw as Set<String>
  }
  override fun edit() = object : SharedPreferences.Editor {
    var next: Set<String>? = null
    override fun putStringSet(key: String, value: Set<String>?): SharedPreferences.Editor {
      check(key == TorrDownloadHistory.KEY); next = value?.toSet(); return this
    }
    override fun commit(): Boolean {
      commits++
      raw = next
      notify(TorrDownloadHistory.KEY)
      return !fail
    }
  }
  override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners += listener }
  override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners -= listener }
  fun notify(key: String?) { listeners.toList().forEach { it.onSharedPreferenceChanged(this, key) } }
}
class TestContext(val preferences: Preferences) : Context() {
  override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
    check(name == "orbit" && mode == MODE_PRIVATE); return preferences
  }
}
fun main() {
  val one = "00000000-0000-0000-0000-000000000001"
  val two = "00000000-0000-0000-0000-000000000002"
  val three = "00000000-0000-0000-0000-000000000003"
  val prefs = Preferences(); val context = TestContext(prefs)
  check(TorrDownloadHistory.read(context).isEmpty())
  prefs.raw = "old incorrect preference type"
  check(TorrDownloadHistory.read(context).isEmpty() && prefs.commits == 0)
  TorrDownloadHistory.hide(context, one)
  check(TorrDownloadHistory.read(context) == setOf(one))
  prefs.commits = 0
  prefs.raw = setOf(one, "", "not-an-id", "1-1-1-1-1")
  check(TorrDownloadHistory.read(context) == setOf(one) && prefs.commits == 0)
  val source = linkedSetOf(one)
  prefs.raw = source
  val detached = TorrDownloadHistory.read(context)
  source += two
  check(detached == setOf(one))

  val observed = mutableListOf<Set<String>>()
  val stop = TorrDownloadHistory.observe(context) { observed += it }
  check(observed.last() == setOf(one, two) && prefs.listeners.size == 1)
  TorrDownloadHistory.hide(context, three)
  check(TorrDownloadHistory.read(TestContext(prefs)) == setOf(one, two, three))
  check(observed.last() == setOf(one, two, three))
  val commits = prefs.commits
  TorrDownloadHistory.hide(context, three)
  check(prefs.commits == commits)
  check(runCatching { TorrDownloadHistory.hide(context, "invalid") }.isFailure && prefs.commits == commits)
  val events = observed.size
  prefs.notify("unrelated")
  check(observed.size == events)
  prefs.raw = setOf(two); prefs.notify(null)
  check(observed.last() == setOf(two))
  stop()
  check(prefs.listeners.isEmpty())
  val stoppedEvents = observed.size
  prefs.raw = setOf(one); prefs.notify(TorrDownloadHistory.KEY)
  check(observed.size == stoppedEvents)
  val stopAgain = TorrDownloadHistory.observe(context) { observed += it }
  check(observed.last() == setOf(one))
  stopAgain()

  val failedEvents = mutableListOf<Set<String>>()
  val stopFailed = TorrDownloadHistory.observe(context) { failedEvents += it }
  prefs.fail = true
  check(runCatching { TorrDownloadHistory.hide(context, two) }.isFailure)
  check(TorrDownloadHistory.read(context) == setOf(one))
  check(failedEvents.takeLast(2) == listOf(setOf(one, two), setOf(one)))
  stopFailed()
  prefs.fail = false; prefs.raw = emptySet<String>()
  val ready = CountDownLatch(2); val start = CountDownLatch(1)
  val writers = listOf(one, two).map { id -> thread { ready.countDown(); start.await(); TorrDownloadHistory.hide(context, id) } }
  ready.await(); start.countDown(); writers.forEach { it.join() }
  check(TorrDownloadHistory.read(context) == setOf(one, two))

  val jobs = listOf(TorrDownloadJob(one, "done"), TorrDownloadJob(two, "paused"), TorrDownloadJob(three, "done"))
  check(TorrDownloadHistory.completed(jobs, setOf(one)).map { it.id } == listOf(three))
  check(jobs.size == 3 && jobs.first().state == "done")
  check(TorrDownloadHistory.completed(jobs, setOf(one, three)).isEmpty())
  check(torrDownloadHistoryPage(0, 0) == 0)
  check(torrDownloadHistoryPage(1, 21) == 1)
  check(torrDownloadHistoryPage(1, 20) == 0)
  check(torrDownloadHistoryPage(5, 1) == 0)
  check(torrDownloadHistoryPage(-1, 25) == 0)
  println("PASS: invalid preferences, detached reads, persisted hides, listeners/disposal, write errors/races, completed visibility and empty-page boundaries")
}
'''

with tempfile.TemporaryDirectory(prefix='gostex-download-history-') as td:
    folder = Path(td)
    (folder / 'Android.kt').write_text(android)
    (folder / 'Checks.kt').write_text(checks)
    cp = str(compiler[1])
    src = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/TorrDownloadHistory.kt'
    result = subprocess.run([str(JAVA), '-cp', ':'.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(folder / 'classes'), str(src), str(folder / 'Android.kt'), str(folder / 'Checks.kt')], capture_output=True, text=True)
    if result.returncode:
        raise SystemExit(result.stderr)
    result = subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{cp}', 'tv.orbit.shield.ChecksKt'], capture_output=True, text=True)
    print(result.stdout, end='')
    if result.returncode:
        raise SystemExit(result.stderr)
