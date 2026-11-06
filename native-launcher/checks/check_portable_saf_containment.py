"""Exercise Android-version branches with synthetic document-provider responses."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import ROOT, JAVA, COMPILER

sources = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
stubs = {
    'Uri.kt': '''package android.net\ndata class Uri(val text: String)''',
    'Build.kt': '''package android.os\nobject Build { object VERSION { var SDK_INT = 28 } }''',
    'Cursor.kt': '''package android.database
import java.io.Closeable
class Cursor(private val rows: List<String?>): Closeable {
 var pos = -1
 fun moveToNext(): Boolean { pos++; return pos < rows.size }
 fun moveToFirst(): Boolean { pos = 0; return rows.isNotEmpty() }
 fun getString(i: Int): String? = rows[pos]
 override fun close() {}
}''',
    'Resolver.kt': '''package android.content
import android.database.Cursor
import android.net.Uri
open class ContentResolver { open fun query(uri: Uri, projection: Array<String>, selection: String?, args: Array<String>?, sort: String?): Cursor? = null }''',
    'Docs.kt': '''package android.provider
import android.content.ContentResolver
import android.net.Uri
object DocumentsContract {
 object Document { const val COLUMN_DOCUMENT_ID = "id" }
 var platformResult = true
 var calls = 0
 fun buildChildDocumentsUriUsingTree(tree: Uri, id: String) = Uri(tree.text + "/children/" + id)
 fun buildDocumentUriUsingTree(tree: Uri, id: String) = Uri(tree.text + "/document/" + id)
 fun isChildDocument(resolver: ContentResolver, parent: Uri, child: Uri): Boolean { calls++; return platformResult }
}''',
}
checks = r'''package tv.orbit.shield
import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract as Docs
private class Resolver: ContentResolver() {
    var children = listOf<String?>("child")
    var document = listOf<String?>("child")
    var unavailable = false
    var queries = mutableListOf<String>()
    override fun query(uri: Uri, projection: Array<String>, selection: String?, args: Array<String>?, sort: String?): Cursor? {
        queries += uri.text
        if (unavailable) return null
        return Cursor(if (uri.text.contains("/children/")) children else document)
    }
}
fun main() {
    var n = 0
    fun ok(b: Boolean, name: String) { check(b) { name }; n++ }
    val tree = Uri("tree:granted")
    val r = Resolver(); Build.VERSION.SDK_INT = 28; Docs.calls = 0
    ok(portableSafDirectChild(r, tree, "parent", "child"), "API28 direct child")
    ok(r.queries == listOf("tree:granted/children/parent", "tree:granted/document/child") && Docs.calls == 0, "tree-bound queries used")
    ok(!portableSafDirectChild(r, tree, "parent", "parent"), "self rejected")
    r.children = emptyList(); ok(!portableSafDirectChild(r, tree, "parent", "child"), "missing child rejected")
    r.children = listOf("child", "child"); ok(!portableSafDirectChild(r, tree, "parent", "child"), "duplicate membership rejected")
    r.children = listOf("child"); r.document = listOf("other"); ok(!portableSafDirectChild(r, tree, "parent", "child"), "different identity rejected")
    r.document = listOf("child", "child"); ok(!portableSafDirectChild(r, tree, "parent", "child"), "ambiguous document rejected")
    r.unavailable = true; ok(runCatching { portableSafDirectChild(r, tree, "parent", "child") }.isFailure, "unavailable provider fails closed")
    r.unavailable = false; r.document = listOf("child"); Build.VERSION.SDK_INT = 29; Docs.platformResult = false
    ok(!portableSafDirectChild(r, tree, "parent", "child") && Docs.calls == 1, "API29 platform denial honored")
    Docs.platformResult = true; ok(portableSafDirectChild(r, tree, "parent", "child"), "API29 platform confirmation")
    println("PASS: $n SAF containment checks")
}'''
with tempfile.TemporaryDirectory() as tmp:
    folder = Path(tmp)
    for name, content in stubs.items(): (folder / name).write_text(content)
    (folder / 'Checks.kt').write_text(checks)
    subprocess.run([str(JAVA), '-cp', ':'.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', str(COMPILER[1]), '-d', str(folder / 'classes'),
                    str(sources / 'PortableSafContainment.kt'), *map(str, (folder / name for name in stubs)), str(folder / 'Checks.kt')], check=True)
    subprocess.run([str(JAVA), '-cp', str(folder / 'classes') + ':' + str(COMPILER[1]), 'tv.orbit.shield.ChecksKt'], check=True)
