"""Exercise the real shelf anchor state on the host; UI focus and gestures need Android QA."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / '.tools/native/gradle-cache/caches/modules-2/files-2.1'
JAVA = ROOT / '.tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java'
source = (ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/HomeFeed.kt').read_text()
start, end = 'internal data class HomeFeedAnchor', '@Composable internal fun HomeFeed('
assert source.count(start) == source.count(end) == 1
production = source[source.index(start):source.index(end)]

def jar(group, name, version):
    return next((CACHE / group / name / version).glob('*/*.jar'))

compiler = [jar('org.jetbrains.kotlin', a, v) for a, v in [
    ('kotlin-compiler-embeddable', '2.4.21'), ('kotlin-stdlib', '2.4.21'),
    ('kotlin-script-runtime', '2.4.21'), ('kotlin-reflect', '1.6.10'),
]] + [jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.9.0'), jar('org.jetbrains', 'annotations', '13.0')]
files = {
    'Runtime.kt': '''package androidx.compose.runtime
annotation class Stable
class State<T>(var value: T)
fun <T> mutableStateOf(value: T) = State(value)
operator fun <T> State<T>.getValue(owner: Any?, property: kotlin.reflect.KProperty<*>) = value
operator fun <T> State<T>.setValue(owner: Any?, property: kotlin.reflect.KProperty<*>, next: T) { value = next }
''',
    'List.kt': 'package androidx.compose.foundation.lazy\nclass LazyListState\n',
    'Focus.kt': 'package androidx.compose.ui.focus\nclass FocusRequester\n',
    'Production.kt': '''package tv.orbit.shield
import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.focus.FocusRequester
internal data class HomeShelfContent(val shelf: String, val keys: List<String>)
''' + production,
    'Checks.kt': '''package tv.orbit.shield
fun main() {
    val s = HomeFeedState()
    val rows = listOf(HomeShelfContent("favorites", listOf("game:one", "game:two")),
        HomeShelfContent("games", listOf("game:one", "game:three")))
    val fav = s.focus("favorites", "game:one")
    val all = s.focus("games", "game:one")
    check(fav !== all && s.focus("favorites", "game:one") === fav)
    s.activeShelf = "favorites"; s.activeIndex = 0; s.selected["favorites"] = "game:one"
    val anchor = s.anchor()
    check(anchor == HomeFeedAnchor("favorites", 0, "game:one"))
    s.activeShelf = "games"; s.activeIndex = 1; s.selected["games"] = "game:one"
    check(s.anchor() == HomeFeedAnchor("games", 1, "game:one"))
    s.restore(anchor)
    check(s.request.anchor == anchor && s.request.step == 0)
    val seq = s.request.sequence
    s.moveBy(50)
    check(s.request.sequence == seq + 1 && s.request.step == 1 && s.request.anchor == null)
    s.moveBy(-50)
    check(s.request.step == -1)
    s.retain(rows.drop(1))
    check("favorites" !in s.selected && s.selected["games"] == "game:one")
    check(s.focus("games", "game:one") === all)
    check(s.focus("favorites", "game:one") !== fav)
    println("PASS: shelf-specific anchors, restore requests, bounded movement and removed-shelf cleanup")
}
''',
}
with tempfile.TemporaryDirectory(prefix='gostex-home-anchor-') as td:
    folder = Path(td)
    for name, contents in files.items():
        (folder / name).write_text(contents)
    cp = str(compiler[1])
    result = subprocess.run([str(JAVA), '-cp', ':'.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(folder / 'classes'),
        *[str(folder / name) for name in files]], capture_output=True, text=True)
    if result.returncode:
        raise SystemExit(result.stderr)
    result = subprocess.run([str(JAVA), '-cp', f'{folder / "classes"}:{cp}', 'tv.orbit.shield.ChecksKt'], capture_output=True, text=True)
    print(result.stdout, end='')
    if result.returncode:
        raise SystemExit(result.stderr)
