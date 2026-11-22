"""Run the production library reconnect route with controlled Android grant and picker boundaries."""
from pathlib import Path
import subprocess
import tempfile

from kotlin_toolchain import COMPILER, JAVA, ROOT

product = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield"
activity = (product / "MainActivity.kt").read_text()
route = activity.split("    private var pendingLibraryReconnect:", 1)[1].split("    private fun confirmRemoveLibrarySource(", 1)[0]
route = "    private var pendingLibraryReconnect:" + route
assert "restoreLibraryReconnect(savedInstanceState)" in activity and "saveLibraryReconnect(outState)" in activity

sources = {
    "Uri.kt": r'''package android.net
import java.net.URI
import java.net.URLDecoder
class Uri private constructor(private val raw: String) {
    private val parsed = URI(raw)
    val scheme get() = parsed.scheme
    val authority get() = parsed.rawAuthority
    val query get() = parsed.rawQuery
    val fragment get() = parsed.rawFragment
    val pathSegments get() = parsed.rawPath.orEmpty().split('/').filter(String::isNotEmpty)
        .map { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
    override fun toString() = raw
    companion object { fun parse(raw: String) = Uri(raw) }
}
''',
    "Content.kt": '''package android.content
import android.net.Uri
object Intent {
    const val FLAG_GRANT_READ_URI_PERMISSION = 1
    const val FLAG_GRANT_WRITE_URI_PERMISSION = 2
    const val ACTION_OPEN_DOCUMENT_TREE = "open-tree"
}
data class Grant(val uri: Uri, val isReadPermission: Boolean, val isWritePermission: Boolean = false)
class ContentResolver {
    val persistedUriPermissions = mutableListOf<Grant>()
    val takes = mutableListOf<String>()
    var retainRead = true
    fun takePersistableUriPermission(uri: Uri, flags: Int) {
        check(flags == Intent.FLAG_GRANT_READ_URI_PERMISSION)
        takes += uri.toString()
        persistedUriPermissions += Grant(uri, retainRead)
    }
    companion object { const val SCHEME_CONTENT = "content" }
}
''',
    "Bundle.kt": '''package android.os
class Bundle {
    private val stored = mutableMapOf<String, Any>()
    fun putBundle(key: String, value: Bundle) { stored[key] = value }
    fun getBundle(key: String) = stored[key] as? Bundle
    fun putString(key: String, value: String) { stored[key] = value }
    fun getString(key: String) = stored[key] as? String
}
''',
    "Documents.kt": '''package android.provider
import android.net.Uri
object DocumentsContract {
    fun isTreeUri(uri: Uri) = uri.pathSegments.size >= 2 && uri.pathSegments[0] == "tree"
    fun getTreeDocumentId(uri: Uri) = uri.pathSegments[1]
    fun buildDocumentUriUsingTree(tree: Uri, id: String) = Uri.parse(tree.toString() + "/document/" + id)
}
''',
    "Checks.kt": '''package tv.orbit.shield
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract

internal object LibraryImport { data class Root(val platform: String, val uri: String, val source: LibrarySource) }
object PlatformRegistry { fun find(platform: String) = platform.takeIf { it == "SNES" || it == "Nintendo 3DS" } }
object PortableRestoreStartup { var ready = true }
object PortableMaintenance { var running = false }
object ActivityResultContracts { class OpenDocumentTree }
object R { object string {
    const val import_reconnect_stale = 1
    const val import_reconnect_wrong = 2
    const val import_reconnect_done = 3
    const val import_reconnect_failed = 4
} }

private const val TREE = "content://drive.example/tree/USB%3ASNES"
private val original = LibraryImport.Root("SNES", TREE, LibrarySource.FILES)
private val other = original.copy(uri = "content://drive.example/tree/USB%3AOther")

private class Screen {
    val contentResolver = ContentResolver()
    var roots = listOf(original, other)
    var notice = ""
    var scans = 0
    var available = true
    val launches = mutableListOf<String>()
    private lateinit var result: (Uri?) -> Unit
    private val activityResultRegistry = object {
        fun register(key: String, owner: Screen, contract: ActivityResultContracts.OpenDocumentTree, callback: (Uri?) -> Unit): Picker {
            check(key == "library-folder-reconnect" && owner === this@Screen)
            result = callback
            return Picker { launches += it.toString() }
        }
    }
    class Picker(private val onLaunch: (Uri) -> Unit) { fun launch(uri: Uri) = onLaunch(uri) }
    fun getString(id: Int) = id.toString()
    fun showLibraryReconnectFailure(id: Int) { notice = getString(id) }
    fun rootsOrNotify(): List<LibraryImport.Root>? = roots
    fun refreshLibrary() { scans++ }
    fun launchDocumentPicker(action: String, launch: () -> Unit): Boolean {
        check(action == Intent.ACTION_OPEN_DOCUMENT_TREE)
        if (available) launch()
        return available
    }
    fun choose(root: LibraryImport.Root = original) = reconnectLibraryFolder(root)
    fun deliver(uri: String? = TREE) = result(uri?.let(Uri::parse))
    fun save() = Bundle().also(::saveLibraryReconnect)
    fun restore(state: Bundle) = restoreLibraryReconnect(state)
''' + route + '''
}

fun main() {
    val fresh = Screen()
    fresh.deliver()
    check(fresh.contentResolver.takes.isEmpty() && fresh.scans == 0)
    fresh.choose()
    fresh.choose(other)
    check(fresh.launches == listOf(TREE + "/document/USB:SNES"))
    fresh.deliver(null)
    check(fresh.contentResolver.takes.isEmpty() && fresh.scans == 0)

    for (wrong in listOf(other.uri, TREE.replace("drive.example", "different.example"), TREE + "/document/USB%3ASNES")) {
        fresh.choose()
        fresh.deliver(wrong)
        check(fresh.notice == R.string.import_reconnect_wrong.toString())
        check(fresh.contentResolver.takes.isEmpty() && fresh.roots == listOf(original, other) && fresh.scans == 0)
    }

    val grant = Screen()
    grant.choose()
    val rotated = Screen()
    rotated.restore(grant.save())
    rotated.choose(other)
    check(rotated.launches.isEmpty())
    rotated.deliver(TREE.replace("%3A", "%3a"))
    check(rotated.contentResolver.takes.size == 1 && rotated.scans == 1)
    check(rotated.contentResolver.persistedUriPermissions.single().let { it.isReadPermission && !it.isWritePermission })
    check(rotated.roots == listOf(original, other) && rotated.notice == R.string.import_reconnect_done.toString())
    rotated.deliver()
    check(rotated.contentResolver.takes.size == 1 && rotated.scans == 1)

    val changed = Screen()
    changed.choose()
    changed.roots = listOf(original.copy(source = LibrarySource.PORTABLE_SNAPSHOT), other)
    changed.deliver()
    check(changed.contentResolver.takes.isEmpty() && changed.scans == 0)
    check(changed.notice == R.string.import_reconnect_stale.toString())
    changed.choose(changed.roots.first())
    check(changed.launches.size == 1)

    val unavailable = Screen()
    unavailable.available = false
    unavailable.choose()
    unavailable.available = true
    unavailable.choose()
    check(unavailable.launches.size == 1)
    PortableMaintenance.running = true
    unavailable.deliver()
    PortableMaintenance.running = false
    check(unavailable.contentResolver.takes.isEmpty() && unavailable.scans == 0)
    unavailable.choose()
    unavailable.contentResolver.retainRead = false
    unavailable.deliver()
    check(unavailable.contentResolver.takes.size == 1 && unavailable.scans == 0)
    check(unavailable.notice == R.string.import_reconnect_failed.toString())
    println("PASS library reconnect: exact tree/read grant, cancel, recreation, stale result, duplicate launch and denied grant")
}
''',
}

with tempfile.TemporaryDirectory(prefix="gostex-library-reconnect-") as directory:
    folder = Path(directory)
    for filename, source in sources.items():
        (folder / filename).write_text(source)
    runtime = str(COMPILER[1])
    subprocess.run([
        str(JAVA), "-cp", ":".join(map(str, COMPILER)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-classpath", runtime, "-d", str(folder / "classes"),
        str(product / "TorrDownloadFolderAccess.kt"), str(product / "ThreeDsInstalledLayout.kt"), *map(str, folder.glob("*.kt")),
    ], check=True, timeout=60)
    subprocess.run([str(JAVA), "-cp", f"{folder / 'classes'}:{runtime}", "tv.orbit.shield.ChecksKt"], check=True, timeout=15)
