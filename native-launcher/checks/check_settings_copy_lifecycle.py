"""Run the production settings-copy controller with deterministic ActivityResult boundaries."""
from pathlib import Path
import subprocess
import tempfile

from kotlin_toolchain import COMPILER, JAVA, ROOT

path = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield/SettingsCopyUi.kt"
controller = path.read_text().split("\n@Composable\n", 1)[0]
for unused in (
    "android.app.AlertDialog", "androidx.compose.runtime.Composable", "androidx.compose.runtime.DisposableEffect",
    "androidx.compose.ui.Modifier", "androidx.compose.ui.graphics.Color", "androidx.compose.ui.unit.sp", "androidx.tv.material3.Text",
):
    controller = controller.replace(f"import {unused}\n", "")

sources = {
    "Controller.kt": controller,
    "Intent.kt": '''package android.content
object Intent {
    const val ACTION_CREATE_DOCUMENT = "create"
    const val ACTION_OPEN_DOCUMENT = "open"
}
''',
    "Results.kt": '''package androidx.activity.result.contract
abstract class Contract<I, O>
object ActivityResultContracts {
    class CreateDocument(mime: String) : Contract<String, String?>()
    class OpenDocument : Contract<Array<String>, String?>()
}
class Launcher<I>(private val action: (I) -> Unit) { fun launch(input: I) = action(input) }
''',
    "Compose.kt": '''package androidx.compose.runtime
import kotlin.reflect.KProperty
class State<T>(var value: T)
fun <T> mutableStateOf(value: T) = State(value)
operator fun <T> State<T>.getValue(owner: Any?, property: KProperty<*>) = value
operator fun <T> State<T>.setValue(owner: Any?, property: KProperty<*>, newValue: T) { value = newValue }
''',
    "Lifecycle.kt": '''package androidx.lifecycle
import tv.orbit.shield.MainActivity
val MainActivity.lifecycleScope get() = scope
''',
    "Checks.kt": '''package tv.orbit.shield
import androidx.activity.result.contract.*
import kotlinx.coroutines.*

private const val EXPORT = 0
private const val IMPORT = 1

class MainActivity {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val launches = mutableListOf<Int>()
    var onLaunch: (Int) -> Unit = {}
    private val results = mutableListOf<(String?) -> Unit>()
    @Suppress("UNCHECKED_CAST")
    fun <I, O> registerForActivityResult(contract: Contract<I, O>, result: (O) -> Unit): Launcher<I> {
        val index = results.size
        results += { result(it as O) }
        return Launcher { launches += index; onLaunch(index) }
    }
    fun result(index: Int, uri: String?) = results[index](uri)
    fun recreate() = Unit
}
object PortableMaintenance { const val running = false }
object PortableSettingsTransfer {
    data class Preview(val count: Int = 1, val needsRecreation: Boolean = false)
    val destinations = mutableListOf<String>()
    val sources = mutableListOf<String>()
    var result: CompletableDeferred<Preview>? = null
    suspend fun export(activity: MainActivity, uri: String) { destinations += uri }
    suspend fun preview(activity: MainActivity, uri: String): Preview {
        sources += uri
        return result?.await() ?: Preview()
    }
    suspend fun apply(activity: MainActivity, preview: Preview) = true
}
object R { object string {
    const val settings_copy_busy = 1
    const val settings_copy_export_failed = 2
    const val settings_copy_exported = 3
    const val settings_copy_import_failed = 4
    const val settings_copy_no_changes = 5
    const val settings_copy_apply_failed = 6
    const val settings_copy_applied = 7
    const val settings_copy_stale = 8
} }

private class Fixture {
    val activity = MainActivity()
    var pickerAvailable = true
    val controller = SettingsCopyController(activity) { _, _, launch ->
        if (pickerAvailable) launch()
        pickerAvailable
    }
}

fun main() {
    val transfer = PortableSettingsTransfer
    val stale = Fixture()
    stale.controller.chooseImport()
    stale.controller.chooseExport()
    stale.controller.leave()
    stale.controller.chooseImport()
    check(stale.activity.launches == listOf(IMPORT))
    stale.activity.result(IMPORT, "stale-before-read")
    check(transfer.sources.isEmpty() && stale.controller.preview == null)
    stale.controller.chooseExport()
    stale.activity.result(IMPORT, "unmatched-import")
    stale.controller.chooseImport()
    check(stale.activity.launches == listOf(IMPORT, EXPORT))
    stale.activity.result(EXPORT, null)
    stale.controller.chooseImport()
    stale.activity.result(IMPORT, null)
    stale.controller.chooseImport()
    check(stale.activity.launches == listOf(IMPORT, EXPORT, IMPORT, IMPORT) && transfer.destinations.isEmpty())

    val recreated = Fixture()
    recreated.activity.result(IMPORT, "replayed-import")
    recreated.activity.result(EXPORT, "replayed-export")
    check(transfer.sources.isEmpty() && recreated.controller.preview == null)
    check(transfer.destinations == listOf("replayed-export"))

    val normal = Fixture()
    normal.pickerAvailable = false
    normal.controller.chooseExport()
    normal.pickerAvailable = true
    normal.activity.onLaunch = { index -> normal.activity.result(index, "selected-import") }
    normal.controller.chooseImport()
    val preview = checkNotNull(normal.controller.preview)
    normal.controller.chooseExport()
    check(normal.activity.launches == listOf(IMPORT) && transfer.sources == listOf("selected-import"))
    normal.controller.dismiss(preview)
    normal.activity.onLaunch = {}
    normal.controller.chooseExport()
    normal.controller.leave()
    normal.activity.result(EXPORT, "selected-export")
    check(transfer.destinations == listOf("replayed-export", "selected-export"))

    val reading = Fixture()
    val decoded = CompletableDeferred<PortableSettingsTransfer.Preview>()
    transfer.result = decoded
    reading.controller.chooseImport()
    reading.activity.result(IMPORT, "stale-during-read")
    reading.controller.leave()
    decoded.complete(PortableSettingsTransfer.Preview())
    check(transfer.sources.last() == "stale-during-read" && reading.controller.preview == null)
    listOf(stale, recreated, normal, reading).forEach { it.activity.scope.cancel() }
    println("PASS settings-copy picker slots, cancellation, stale import and restored export")
}
''',
}

runtime = ":".join(map(str, [COMPILER[1], COMPILER[4]]))
with tempfile.TemporaryDirectory(prefix="gostex-settings-copy-") as directory:
    folder = Path(directory)
    for filename, source in sources.items():
        (folder / filename).write_text(source)
    subprocess.run([
        str(JAVA), "-cp", ":".join(map(str, COMPILER)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-classpath", runtime, "-d", str(folder / "classes"), *map(str, folder.glob("*.kt")),
    ], check=True, timeout=60)
    subprocess.run([str(JAVA), "-cp", f"{folder / 'classes'}:{runtime}", "tv.orbit.shield.ChecksKt"], check=True, timeout=15)
