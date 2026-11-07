"""Exercise the production player receipt against an isolated Android boundary."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import JAVA, COMPILER, ROOT
src = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
android = '''package android.content
open class Context {
    companion object { const val MODE_PRIVATE = 0 }
    val prefs = SharedPreferences()
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
}
class SharedPreferences {
    val data = java.util.concurrent.ConcurrentHashMap<String, Any>()
    fun edit() = Editor(this)
    class Editor(private val prefs: SharedPreferences) {
        private val changes = mutableMapOf<String, Any>()
        fun putLong(key: String, value: Long) = apply { changes[key] = value }
        fun putString(key: String, value: String) = apply { changes[key] = value }
        fun apply() { prefs.data.putAll(changes) }
    }
}
open class BroadcastReceiver {
    open fun onReceive(context: Context, intent: Intent) {}
    fun goAsync() = PendingResult()
    class PendingResult { fun finish() {} }
}
class Intent(val action: String?, private val extras: Map<String, String> = emptyMap()) {
    fun getStringExtra(key: String): String? = extras[key]
}
'''
model = '''package tv.orbit.shield
import android.content.Context
data class MovieEpisode(val id: String, val season: Int, val episode: Int, val title: String)
data class TestMovie(val id: String, val mediaType: String)
object MovieLibraryStore {
    var saved = listOf<TestMovie>()
    fun read(context: Context) = saved
}
'''
tests = '''package tv.orbit.shield
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
fun main() = runBlocking {
    val c = Context()
    MoviePlaybackReceipt.mark(c, "tt1", "tt1:2:3")
    check(c.prefs.data[episodeHistoryKey("tt1")] == "tt1:2:3")
    MoviePlaybackReceipt.mark(c, "tt1", "tt2:2:3")
    check(c.prefs.data[episodeHistoryKey("tt1")] == "tt1:2:3")
    check(!c.prefs.data.containsKey(episodeHistoryKey("tt2")))
    val receiver = MoviePlaybackReceipt()
    MovieLibraryStore.saved = listOf(TestMovie("tt3", "series"), TestMovie("tt4", "movie"))
    fun receipt(id: String, episode: String) = Intent(MoviePlaybackReceipt.ACTION,
        mapOf(MoviePlaybackReceipt.EXTRA_ID to id, MoviePlaybackReceipt.EXTRA_EPISODE_ID to episode))
    receiver.onReceive(c, receipt("tt3", "tt3:1:4"))
    withTimeout(1000) { while (c.prefs.data[episodeHistoryKey("tt3")] == null) delay(10) }
    check(c.prefs.data[episodeHistoryKey("tt3")] == "tt3:1:4")
    receiver.onReceive(c, receipt("tt4", "tt4:1:4"))
    withTimeout(1000) { while (c.prefs.data["played:movie:tt4"] == null) delay(10) }
    check(!c.prefs.data.containsKey(episodeHistoryKey("tt4")))
    receiver.onReceive(c, receipt("tt5", "tt5:1:4")); delay(80)
    check(!c.prefs.data.containsKey("played:movie:tt5"))
    println("PASS: confirmed player receipt, series isolation and removed-media protection")
}
'''
with tempfile.TemporaryDirectory(prefix='gostex-episode-receipt-') as td:
    folder = Path(td)
    for name, text in [('Android.kt', android), ('Model.kt', model), ('Checks.kt', tests)]: (folder / name).write_text(text)
    cp = ':'.join(map(str, (COMPILER[1], COMPILER[4])))
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(folder / 'classes'),
                    str(src / 'MovieEpisodeHistory.kt'), str(src / 'MoviePlaybackReceipt.kt'),
                    str(folder / 'Android.kt'), str(folder / 'Model.kt'), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{cp}', 'tv.orbit.shield.ChecksKt'], check=True)
