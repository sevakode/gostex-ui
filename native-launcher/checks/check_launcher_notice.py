"""Exercise production notice state and timeout through replacement and lifecycle races.

The production state and LaunchedEffect body are compiled unchanged. Compose state storage,
elapsed time, and delay scheduling are deterministic JVM boundaries; real focus/layout and
Android accessibility timeout selection remain device checks.
"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = root / "native-launcher/app/src/main/java/tv/orbit/shield/LauncherNotice.kt"
code = source.read_text()
state_code = code[code.index("internal data class LauncherNotice"):code.index("/**\n * A transient TV notice")]
effect_code = code[code.index("    LaunchedEffect(notice.id,"):code.index("    fun close()")].strip()
assert "remember(notice.id, timeout) { mutableLongStateOf" in code, "Each occurrence needs its own remembered remaining-time state"

cache = root / ".tools/native/gradle-cache/caches/modules-2/files-2.1"
def jar(group, artifact, version):
    return next((cache / group / artifact / version).glob("*/*.jar"))
compiler = [jar("org.jetbrains.kotlin", a, v) for a, v in [
    ("kotlin-compiler-embeddable", "2.4.21"), ("kotlin-stdlib", "2.4.21"),
    ("kotlin-script-runtime", "2.4.21"), ("kotlin-reflect", "1.6.10")]]
compiler += [jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.9.0"), jar("org.jetbrains", "annotations", "13.0")]
java = root / ".tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java"
runtime = ":".join(map(str, [compiler[1], compiler[4]]))

harness = r'''package tv.orbit.shield
import kotlin.reflect.KProperty
import kotlin.coroutines.resume
import kotlinx.coroutines.*

class MutableState<T>(var value: T)
fun <T> mutableStateOf(value: T) = MutableState(value)
operator fun <T> MutableState<T>.getValue(owner: Any?, property: KProperty<*>) = value
operator fun <T> MutableState<T>.setValue(owner: Any?, property: KProperty<*>, value: T) { this.value = value }
object SystemClock { var now = 0L; fun elapsedRealtime() = now }
data class Pending(val deadline: Long, val continuation: CancellableContinuation<Unit>)
val pending = mutableListOf<Pending>()
val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
suspend fun delay(milliseconds: Long) {
    if (milliseconds <= 0L) return
    suspendCancellableCoroutine<Unit> { continuation ->
        val item = Pending(SystemClock.now + milliseconds, continuation)
        pending += item
        continuation.invokeOnCancellation { pending.remove(item) }
    }
}
fun advance(milliseconds: Long) {
    val end = SystemClock.now + milliseconds
    while (true) {
        val item = pending.filter { it.continuation.isActive && it.deadline <= end }.minByOrNull { it.deadline } ?: break
        pending.remove(item)
        SystemClock.now = item.deadline
        item.continuation.resume(Unit)
    }
    SystemClock.now = end
}
@Suppress("UNUSED_PARAMETER")
fun LaunchedEffect(vararg keys: Any?, block: suspend () -> Unit) = timerScope.launch(start = CoroutineStart.UNDISPATCHED) { block() }

__STATE__

internal fun timer(state: LauncherNoticeState, notice: LauncherNotice, remainingCell: MutableState<Long>,
          focused: Boolean = false, started: Boolean = true, active: Boolean = true): Job {
    var remaining by remainingCell
    val timeout = remainingCell.value
    return __EFFECT__
}

fun main() {
    var count = 0
    fun pass(name: String, ok: Boolean) { check(ok) { name }; println("PASS|$name"); count++ }
    val state = LauncherNoticeState()
    state.show("Needs access")
    val first = state.current!!
    val remaining = mutableStateOf(8_000L)
    val running = timer(state, first, remaining)
    advance(3_000L); running.cancel()
    advance(60_000L)
    pass("Background time does not consume the five seconds still owed on screen", state.current == first && remaining.value == 5_000L && pending.isEmpty())
    timer(state, first, remaining)
    advance(4_999L)
    pass("Resuming preserves the unread interval", state.current == first)
    advance(1L)
    pass("Notice eventually disappears after its total visible interval", state.current == null && remaining.value == 0L)

    state.show("Needs access")
    val focusedItem = state.current!!
    val focusTime = mutableStateOf(8_000L)
    val beforeFocus = timer(state, focusedItem, focusTime)
    advance(2_000L); beforeFocus.cancel()
    val focusedJob = timer(state, focusedItem, focusTime, focused = true)
    advance(120_000L)
    pass("Reading the close button with remote focus prevents expiration", focusedJob.isCompleted && state.current == focusedItem && focusTime.value == 6_000L && pending.isEmpty())
    timer(state, focusedItem, focusTime); advance(6_000L)
    pass("Moving focus away resumes automatic dismissal", state.current == null)

    state.show("Long error")
    val obscured = state.current!!
    val obscuredTime = mutableStateOf(8_000L)
    timer(state, obscured, obscuredTime, active = false)
    timer(state, obscured, obscuredTime, started = false)
    advance(20_000L)
    pass("Menus and stopped Activity create no expiry task", state.current == obscured && obscuredTime.value == 8_000L && pending.isEmpty())

    val oldRemaining = mutableStateOf(8_000L)
    val oldJob = timer(state, obscured, oldRemaining)
    advance(2_500L)
    state.show("Long error")
    val replacement = state.current!!
    val newRemaining = mutableStateOf(8_000L)
    timer(state, replacement, newRemaining)
    oldJob.cancel()
    pass("Repeated identical errors get independent occurrences and full time", replacement.id != obscured.id && oldRemaining.value == 5_500L && newRemaining.value == 8_000L)
    pass("A late click from the old notice cannot dismiss its replacement", !state.dismiss(obscured.id) && state.current == replacement)
    advance(7_999L)
    pass("Cancellation finally updates only the old captured remaining-time state", state.current == replacement && newRemaining.value == 8_000L)
    advance(1L)
    pass("The replacement expires at its own deadline", state.current == null)

    state.show("First")
    val stale = state.current!!
    timer(state, stale, mutableStateOf(8_000L))
    advance(3_000L)
    state.show("Second")
    val latest = state.current!!
    timer(state, latest, mutableStateOf(8_000L))
    advance(5_000L)
    pass("Even an already queued stale timeout cannot clear the new message", state.current == latest)
    pass("Explicit close dismisses the current message exactly once", state.dismiss(latest.id) && !state.dismiss(latest.id))
    state.show("Third")
    val third = state.current!!
    advance(3_000L)
    pass("The manually closed message's old timeout cannot remove a later notice", state.current == third)
    state.show("\n \t")
    pass("Clearing a notice is compatible with existing blank-string assignments", state.current == null)
    timerScope.cancel()
    pass("All timeout work is released after disposal", pending.isEmpty())
    println("COUNT|$count")
}
'''.replace("__STATE__", state_code).replace("__EFFECT__", effect_code)

report = {
    "timestampUtc": datetime.now(timezone.utc).isoformat(), "scope": __doc__,
    "sourceSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
}
with tempfile.TemporaryDirectory(prefix="gostex-notice-") as directory:
    folder = Path(directory)
    check = folder / "Checks.kt"
    check.write_text(harness)
    result = subprocess.run([str(java), "-cp", ":".join(map(str, compiler)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-classpath", runtime, "-d", str(folder / "classes"), str(check)], capture_output=True, text=True)
    report.update({"compileCode": result.returncode, "compileOutput": result.stderr})
    if result.returncode == 0:
        result = subprocess.run([str(java), "-cp", str(folder / "classes") + ":" + runtime, "tv.orbit.shield.ChecksKt"], capture_output=True, text=True)
        report.update({"runCode": result.returncode, "stdout": result.stdout, "stderr": result.stderr})
out = root / ".reports/usability36"
out.mkdir(parents=True, exist_ok=True)
(out / "notice-checks.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report))
raise SystemExit(result.returncode)
