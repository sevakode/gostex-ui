"""Execute the production ZIP controller and maintenance barrier with JVM boundaries.

Uses real kotlinx.coroutines cancellation and delayed storage cleanup. Android picker
delivery, Bundle, Compose state, and storage are stubs; this is not device/UI QA.
"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

from kotlin_toolchain import COMPILER, JAVA, ROOT

production = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield"
controller_path = production / "GameArchiveUi.kt"
maintenance_path = production / "PortableMaintenance.kt"
controller = controller_path.read_text().split("\n@Composable\n", 1)[0]
for unused in ("android.app.AlertDialog", "android.text.format.Formatter", "android.widget.ProgressBar"):
    controller = controller.replace(f"import {unused}\n", "")

sources = {
    "Controller.kt": controller,
    "AndroidContent.kt": r'''
package android.content
open class Context { val applicationContext: Context get() = this }
class Intent { companion object {
    const val ACTION_OPEN_DOCUMENT = "open"
    const val ACTION_OPEN_DOCUMENT_TREE = "tree"
} }
''',
    "AndroidOs.kt": r'''
package android.os
class Bundle {
    private val values = mutableMapOf<String, Any?>()
    fun putString(key: String, value: String?) { values[key] = value }
    fun getString(key: String): String? = values[key] as? String
    fun putBoolean(key: String, value: Boolean) { values[key] = value }
    fun getBoolean(key: String): Boolean = values[key] as? Boolean ?: false
}
object SystemClock { fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000 }
object Looper {
    fun myLooper(): Looper = this
    fun getMainLooper(): Looper = this
}
''',
    "Uri.kt": r'''
package android.net
data class Uri(val text: String) { companion object { fun parse(text: String) = Uri(text) } }
''',
    "Results.kt": r'''
package androidx.activity.result.contract
import android.net.Uri
abstract class ActivityResultContract<I, O>
class ActivityResultContracts {
    class OpenDocument : ActivityResultContract<Array<String>, Uri?>()
    class OpenDocumentTree : ActivityResultContract<Uri?, Uri?>()
}
class ActivityResultLauncher<I>(private val action: (I) -> Unit) {
    fun launch(input: I) = action(input)
}
''',
    "State.kt": r'''
package androidx.compose.runtime
import kotlin.reflect.KProperty
class MutableState<T>(var value: T)
fun <T> mutableStateOf(value: T) = MutableState(value)
fun mutableLongStateOf(value: Long) = MutableState(value)
fun mutableIntStateOf(value: Int) = MutableState(value)
operator fun <T> MutableState<T>.getValue(owner: Any?, property: KProperty<*>): T = value
operator fun <T> MutableState<T>.setValue(owner: Any?, property: KProperty<*>, value: T) { this.value = value }
''',
    "Lifecycle.kt": r'''
package androidx.lifecycle
import kotlinx.coroutines.CoroutineScope
import tv.orbit.shield.MainActivity
val MainActivity.lifecycleScope: CoroutineScope get() = scope
''',
    "Boundaries.kt": r'''
package tv.orbit.shield
import android.content.Context
import android.net.Uri
import androidx.activity.result.contract.*
import kotlinx.coroutines.*

class MainActivity : Context() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    var notice = ""
    val launches = mutableListOf<Int>()
    private val callbacks = mutableListOf<(Any?) -> Unit>()
    fun getString(id: Int): String = id.toString()
    @Suppress("UNCHECKED_CAST")
    fun <I, O> registerForActivityResult(contract: ActivityResultContract<I, O>, callback: (O) -> Unit): ActivityResultLauncher<I> {
        val index = callbacks.size
        callbacks += { value -> callback(value as O) }
        return ActivityResultLauncher { launches += index }
    }
    fun deliver(index: Int, value: Uri?) { callbacks[index](value) }
}
object R { object string {
    const val platform_unknown = 1
    const val game_archive_invalid = 2
    const val game_archive_cancelled = 3
    const val game_archive_failed = 4
    const val game_archive_expired = 5
} }
object PlatformRegistry {
    fun find(id: String): String? = id.takeIf { it in setOf("SNES", "PSP") }
}
object PortableRestoreStartup {
    val ready = true
    fun requireRecovery(): Unit { error("Unexpected recovery failure") }
}
object PortableAndroidRestoreRecovery {
    fun recoverUnderExclusiveAccess(context: Context) = false
}
object GameArchiveStorage {
    class Plan(val source: Uri) { val totalBytes = 1L }
    var inspections = 0
    var extractions = 0
    var holdCleanup = false
    var cleanupFinished = false
    var extractionStarted = CompletableDeferred<Unit>()
    var cleanupStarted = CompletableDeferred<Unit>()
    var releaseCleanup = CompletableDeferred<Unit>()
    fun reset() {
        inspections = 0; extractions = 0; holdCleanup = false; cleanupFinished = false
        extractionStarted = CompletableDeferred(); cleanupStarted = CompletableDeferred(); releaseCleanup = CompletableDeferred()
    }
    suspend fun inspect(context: Context, source: Uri): Plan { inspections++; return Plan(source) }
    suspend fun extract(context: Context, plan: Plan, destination: Uri, progress: (Long) -> Unit): Uri {
        extractions++
        extractionStarted.complete(Unit)
        if (holdCleanup) {
            try { awaitCancellation() }
            finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    releaseCleanup.await()
                    cleanupFinished = true
                }
            }
        }
        progress(1)
        return Uri.parse(destination.text + "/prepared")
    }
}
''',
    "Checks.kt": r'''
package tv.orbit.shield
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.*

private class Fixture {
    val activity = MainActivity()
    var pickerAvailable = true
    var added: Pair<String, Uri>? = null
    val controller = GameArchiveController(activity, { _, _, launch ->
        if (pickerAvailable) { launch(); true } else false
    }) { platform, uri -> added = platform to uri }
    fun close() = activity.scope.cancel()
}
private val source = Uri.parse("content://fixture/game.zip")
private val destination = Uri.parse("content://fixture/output")
private var checks = 0
private fun verify(name: String, condition: Boolean) {
    check(condition) { name }; checks++; println("PASS|$name")
}

fun main() = runBlocking {
    withTimeout(5_000) {
        GameArchiveStorage.reset()
        val oldSource = Fixture()
        oldSource.controller.chooseSource("PSP")
        val sourceState = Bundle().also(oldSource.controller::save)
        oldSource.close()
        val sourceRestored = Fixture()
        sourceRestored.controller.restore(sourceState)
        sourceRestored.activity.deliver(0, source)
        sourceRestored.controller.chooseDestination()
        sourceRestored.activity.deliver(1, destination)
        sourceRestored.controller.addResult()
        verify("Recreated source selection retains the chosen platform", sourceRestored.added?.first == "PSP")
        sourceRestored.close()

        GameArchiveStorage.reset()
        val oldDestination = Fixture()
        oldDestination.controller.inspect("SNES", source)
        oldDestination.controller.chooseDestination()
        val destinationState = Bundle().also(oldDestination.controller::save)
        oldDestination.close()
        val destinationRestored = Fixture()
        destinationRestored.controller.restore(destinationState)
        destinationRestored.controller.chooseSource("PSP")
        destinationRestored.controller.inspect("PSP", source)
        destinationRestored.activity.deliver(0, source)
        verify("An expired destination slot cannot be replaced by a new request",
            destinationRestored.controller.picking && destinationRestored.activity.launches.isEmpty() && GameArchiveStorage.inspections == 1)
        destinationRestored.activity.deliver(1, destination)
        verify("A stale destination result starts no write and explains expiry",
            GameArchiveStorage.extractions == 0 && !destinationRestored.controller.picking &&
                destinationRestored.activity.notice == R.string.game_archive_expired.toString())
        destinationRestored.close()

        GameArchiveStorage.reset()
        sourceState.putString("gameArchivePlatform", "removed-platform")
        val invalidSource = Fixture()
        invalidSource.controller.restore(sourceState)
        invalidSource.activity.deliver(0, source)
        verify("An obsolete restored platform never starts archive preparation",
            GameArchiveStorage.inspections == 0 && invalidSource.activity.notice == R.string.game_archive_expired.toString())
        invalidSource.close()

        GameArchiveStorage.reset()
        val cancellation = Fixture()
        cancellation.controller.inspect("SNES", source)
        GameArchiveStorage.holdCleanup = true
        cancellation.controller.chooseDestination()
        cancellation.activity.deliver(1, destination)
        GameArchiveStorage.extractionStarted.await()
        val joined = async { cancellation.controller.cancelAndJoin() }
        GameArchiveStorage.cleanupStarted.await()
        verify("cancelAndJoin waits while cancelled storage is still cleaning up",
            !joined.isCompleted && cancellation.controller.busy && !GameArchiveStorage.cleanupFinished)
        GameArchiveStorage.releaseCleanup.complete(Unit)
        joined.await()
        verify("cancelAndJoin clears busy state only after storage cleanup",
            GameArchiveStorage.cleanupFinished && !cancellation.controller.busy && cancellation.controller.result == null)
        cancellation.close()

        GameArchiveStorage.reset()
        val destroyed = Fixture()
        destroyed.controller.inspect("SNES", source)
        GameArchiveStorage.holdCleanup = true
        destroyed.controller.chooseDestination()
        destroyed.activity.deliver(1, destination)
        destroyed.close()
        GameArchiveStorage.cleanupStarted.await()
        val replacement = Fixture()
        var restoreStarted = false
        val restore = async {
            PortableMaintenance.runExclusive(replacement.activity, { replacement.controller.cancelAndJoin() }) {
                restoreStarted = true
            }
        }
        yield()
        verify("Maintenance waits for cleanup owned by the destroyed Activity",
            PortableMaintenance.running && !restoreStarted && !restore.isCompleted)
        GameArchiveStorage.releaseCleanup.complete(Unit)
        restore.await()
        verify("Maintenance proceeds after the old worker releases its participant",
            restoreStarted && GameArchiveStorage.cleanupFinished && !PortableMaintenance.running)
        replacement.close()

        GameArchiveStorage.reset()
        val alreadyStopped = Fixture()
        alreadyStopped.close()
        alreadyStopped.controller.inspect("SNES", source)
        verify("A cancelled lifecycle never leaves a permanently busy controller",
            !alreadyStopped.controller.busy && GameArchiveStorage.inspections == 0)
        var free = false
        PortableMaintenance.runExclusive(alreadyStopped.activity, {}) { free = true }
        verify("A cancelled-before-start worker does not leak a maintenance participant", free)

        GameArchiveStorage.reset()
        val retry = Fixture()
        retry.controller.inspect("SNES", source)
        val preview = retry.controller.plan
        retry.pickerAvailable = false
        val revision = retry.controller.dialogRevision
        retry.controller.chooseDestination()
        verify("A failed destination launch retains its preview and rebuilds the confirmation",
            !retry.controller.picking && retry.controller.plan === preview && retry.controller.dialogRevision > revision)
        retry.pickerAvailable = true
        retry.controller.chooseDestination()
        retry.activity.deliver(1, null)
        verify("Cancelling the destination picker preserves a retryable preview",
            !retry.controller.picking && retry.controller.plan === preview && GameArchiveStorage.extractions == 0)
        retry.close()
        println("COUNT|$checks")
    }
}
''',
}

# The compiler's coroutine runtime is resolved by every CI build; do not depend
# on an unrelated version being left in a developer's Gradle cache.
runtime = ":".join(map(str, [COMPILER[1], COMPILER[4]]))
report = {
    "timestamp": datetime.now(timezone.utc).isoformat(),
    "scope": __doc__,
    "sourceSha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in [controller_path, maintenance_path]},
    "passed": False,
}
with tempfile.TemporaryDirectory(prefix="gostex-archive-lifecycle-") as directory:
    folder = Path(directory)
    for name, source_text in sources.items():
        (folder / name).write_text(source_text)
    compiled = subprocess.run([
        str(JAVA), "-cp", ":".join(map(str, COMPILER)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-classpath", runtime, "-d", str(folder / "classes"),
        str(maintenance_path), *map(str, folder.glob("*.kt")),
    ], capture_output=True, text=True, timeout=60)
    report["compile"] = {"code": compiled.returncode, "stderr": compiled.stderr}
    if compiled.returncode == 0:
        run = subprocess.run([str(JAVA), "-cp", f"{folder / 'classes'}:{runtime}", "tv.orbit.shield.ChecksKt"],
                             capture_output=True, text=True, timeout=15)
        report["run"] = {"code": run.returncode, "stdout": run.stdout, "stderr": run.stderr}
        report["passed"] = run.returncode == 0
output = ROOT / ".reports/archive-lifecycle-2026-11-14"
output.mkdir(parents=True, exist_ok=True)
(output / "controller-check.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
print(json.dumps({"passed": report["passed"], "checks": report.get("run", {}).get("stdout", "").count("PASS|"),
                  "errors": report.get("run", report["compile"]).get("stderr", "")}))
raise SystemExit(0 if report["passed"] else 1)
