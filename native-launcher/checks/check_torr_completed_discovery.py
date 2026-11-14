"""Run production completed-file discovery against a counting, read-only SAF provider."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import JAVA, COMPILER, ROOT, CACHE

base = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield"
json_candidates = list((CACHE / "org.json/json").glob("*/*/*.jar"))
json_candidates += list((Path.home() / ".gradle/caches/modules-2/files-2.1/org.json/json").glob("*/*/*.jar"))
jsonjar = next(p for p in json_candidates if not p.name.endswith(("-sources.jar", "-javadoc.jar")))

uri = r'''package android.net
data class Uri(val raw: String) {
 val scheme: String? get()=raw.substringBefore(":", "").ifBlank { null }
 val authority: String? get()=raw.substringAfter("://", "").substringBefore('/').ifBlank { null }
 override fun toString()=raw
 companion object { fun parse(value:String?)=Uri(value ?: "") }
}
'''
context = r'''package android.content
import android.net.Uri
import java.io.Closeable
data class FakeDocument(val id:String, val parent:String, val name:String, val size:Long?, val mime:String)
class FakeCursor(private val rows:List<List<Any?>>):Closeable {
 private var index=-1
 fun moveToNext():Boolean { index++;return index<rows.size }
 fun moveToFirst():Boolean { index=0;return rows.isNotEmpty() }
 fun getString(i:Int)=rows[index][i]?.toString()
 fun isNull(i:Int)=rows[index][i]==null
 fun getLong(i:Int)=(rows[index][i] as Number).toLong()
 override fun close() {}
}
class FakeDescriptor:Closeable { override fun close() {} }
data class Permission(val uri:Uri,val isReadPermission:Boolean=true,val isWritePermission:Boolean=true)
class ContentResolver {
 val documents=linkedMapOf<String,FakeDocument>()
 val lists=mutableMapOf<String,Int>()
 var opens=0;var creates=0;var deletes=0
 var afterQuery:((String)->Unit)?=null
 val persistedUriPermissions=mutableListOf<Permission>()
 fun takePersistableUriPermission(uri:Uri, flags:Int) {}
 fun openAssetFileDescriptor(uri:Uri,mode:String):FakeDescriptor? {
  check(mode=="r");opens++;return if(uri.raw.substringAfterLast('/') in documents) FakeDescriptor() else null
 }
 fun query(uri:Uri,columns:Array<String>,selection:String?,args:Array<String>?,sort:String?):FakeCursor? {
  val id=uri.raw.substringAfterLast('/')
  val docs=if ("/children/" in uri.raw) {
   lists[id]=(lists[id] ?: 0)+1;documents.values.filter { it.parent==id }
  } else listOfNotNull(documents[id])
  val rows=docs.map { d -> columns.map { when(it) { "id"->d.id;"name"->d.name;"size"->d.size;"mime"->d.mime;else->error(it) } } }
  afterQuery?.invoke(id)
  return FakeCursor(rows)
 }
}
open class Context {
 companion object { const val MODE_PRIVATE=0 }
 val contentResolver=ContentResolver()
 val prefs=SharedPreferences()
 fun getSharedPreferences(name:String,mode:Int)=prefs
}
class SharedPreferences {
 val values=mutableMapOf<String,Any>()
 fun getString(key:String,default:String?):String?=values[key] as String? ?: default
 fun getInt(key:String,default:Int)=values[key] as Int? ?: default
 fun edit()=Editor(values)
 class Editor(private val values:MutableMap<String,Any>) {
  private val pending=mutableMapOf<String,Any>()
  fun putInt(key:String,value:Int):Editor { pending[key]=value;return this }
  fun putString(key:String,value:String):Editor { pending[key]=value;return this }
  fun commit():Boolean { values.putAll(pending);return true }
 }
}
class Intent { companion object { const val FLAG_GRANT_READ_URI_PERMISSION=1;const val FLAG_GRANT_WRITE_URI_PERMISSION=2 } }
'''
documents = r'''package android.provider
import android.content.ContentResolver
import android.net.Uri
object DocumentsContract {
 object Document {
  const val COLUMN_DOCUMENT_ID="id";const val COLUMN_DISPLAY_NAME="name"
  const val COLUMN_SIZE="size";const val COLUMN_MIME_TYPE="mime";const val MIME_TYPE_DIR="dir"
 }
 fun getTreeDocumentId(tree:Uri)=tree.raw.substringAfter("/tree/").substringBefore('/')
 fun getDocumentId(uri:Uri)=uri.raw.substringAfterLast('/')
 fun buildChildDocumentsUriUsingTree(tree:Uri,id:String)=Uri("content://${tree.authority}/tree/${getTreeDocumentId(tree)}/children/$id")
 fun buildDocumentUriUsingTree(tree:Uri,id:String)=Uri("content://${tree.authority}/tree/${getTreeDocumentId(tree)}/document/$id")
 fun createDocument(resolver:ContentResolver,parent:Uri,mime:String,name:String):Uri? { resolver.creates++;error("Recovery must not create documents") }
 fun deleteDocument(resolver:ContentResolver,document:Uri):Boolean { resolver.deletes++;error("Recovery must not delete documents") }
 fun isChildDocument(resolver:ContentResolver,parent:Uri,child:Uri):Boolean {
  val parentId=getDocumentId(parent);var id=getDocumentId(child)
  repeat(40) { id=resolver.documents[id]?.parent ?: return false;if(id==parentId)return true }
  return false
 }
}
'''
stubs = r'''package tv.orbit.shield
import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
data class TorrCatalogFile(val id:Int,val path:String,val size:Long)
data class TorrCatalogEntry(val hash:String,val title:String,val files:List<TorrCatalogFile>)
object TorrServerCatalog {
 var entry=TorrCatalogEntry("a".repeat(40),"Show",emptyList())
 var cachedRequests=0;var peerRequests=0
 fun status(hash:String,timeoutMs:Int):TorrCatalogEntry { check(timeoutMs in 1..1000);cachedRequests++;check(hash==entry.hash);return entry }
 fun loadFiles(hash:String):TorrCatalogEntry { peerRequests++;error("Discovery opened a torrent") }
}
internal data class TorrDownloadJob(val id:String,val hash:String,val index:Int,val total:Long,val name:String,
 val tree:String,val document:String,val finalName:String,val offset:Long=0,val state:String="paused",
 val parentId:String="",val grouped:Boolean=false,val prefetchMovieId:String="",val http:HttpDownloadOrigin?=null)
internal object TorrDownloadStore {
 val jobs=MutableStateFlow<List<TorrDownloadJob>>(emptyList())
 var broken=false
 fun recover(context:Context) { if(broken) throw IOException("Damaged journal") }
 fun requireActiveCapacity(context:Context, additional:Int) { recover(context); require(jobs.value.count { it.state != "done" } + additional <= 100) }
 fun requireHttpAdmission(context:Context,tree:String,resourceKey:String) { error("Torrent discovery must not admit new HTTP jobs") }
 @Synchronized fun add(context:Context,job:TorrDownloadJob) { check(jobs.value.none { it.document==job.document });jobs.value=jobs.value+job }
 fun beginDeletePrefetch(context:Context,id:String):TorrDownloadJob?=null
 fun finishDeletePrefetch(context:Context,id:String) {}
 fun endDiscard(id:String) {}
 fun beginDiscard(context:Context,id:String):TorrDownloadJob?=null
 fun finishDiscard(context:Context,id:String) {}
 fun queue(context:Context,ids:List<String>) {}
}
object PortableMaintenance { var running=false }
object TorrPlayback { fun isVideo(path:String)=path.endsWith(".mkv") }
object TorrDownloadSpace { fun read(context:Context)=0 }
object TorrDownloadActions { fun startMany(context:Context,ids:List<String>) {} }
data class PrefetchEpisode(val id:Int,val path:String,val bytes:Long)
fun planEpisodePrefetch(files:List<PrefetchEpisode>,current:Int,count:Int,existing:Set<Int>,free:Long?,reserve:Long,quota:Long,used:Long,includeCurrent:Boolean=false)=emptyList<PrefetchEpisode>()
fun orderedEpisodeFiles(files:List<String>)=true
class TorrSafTarget:Closeable {
 fun size()=0L
 fun availableBytes()=Long.MAX_VALUE
 override fun close() {}
 companion object { fun open(resolver:ContentResolver,uri:Uri)=TorrSafTarget() }
}
fun portableSafDirectChild(resolver:ContentResolver,tree:Uri,parent:String,child:String):Boolean=error("Recovery must reuse its verified parent listing")
'''
checks = r'''package tv.orbit.shield
import android.content.Context
import android.content.FakeDocument
import android.content.Permission
import android.net.Uri
import android.provider.DocumentsContract as Docs
import kotlinx.coroutines.*
fun main()=runBlocking {
 val hash="a".repeat(40);val tree=Uri.parse("content://provider/tree/root")
 fun fixture():Context {
  TorrDownloadStore.jobs.value=emptyList();TorrDownloadStore.broken=false
  TorrServerCatalog.cachedRequests=0;TorrServerCatalog.peerRequests=0;PortableMaintenance.running=false
  val ctx=Context();ctx.contentResolver.persistedUriPermissions+=Permission(tree)
  ctx.prefs.values["downloads.folder"]=tree.toString()
  ctx.prefs.values["downloads.torrserver.groups"]="""[{"tree":"$tree","hash":"$hash","id":"group","name":"Managed"}]"""
  val docs=ctx.contentResolver.documents
  docs["group"]=FakeDocument("group","root","Managed",null,"dir")
  docs["season"]=FakeDocument("season","group","Season",null,"dir")
  docs["disc"]=FakeDocument("disc","season","Disc",null,"dir")
  TorrServerCatalog.entry=TorrCatalogEntry(hash,"Show",(1..40).map { id ->
   val name="Show.S01E${id.toString().padStart(2,'0')}.mkv"
   docs["file$id"]=FakeDocument("file$id","disc",name,100,"video")
   TorrCatalogFile(id,"Season/Disc/$name",100)
  })
  return ctx
 }
 var ctx=fixture()
 TorrPrefetch.recoverSaved(ctx,"movie",listOf(hash,hash.uppercase()))
 check(TorrServerCatalog.cachedRequests==1 && TorrServerCatalog.peerRequests==0)
 check(TorrDownloadStore.jobs.value.size==40 && TorrDownloadStore.jobs.value.all { it.state=="done" && it.prefetchMovieId=="movie" })
 check(ctx.contentResolver.lists==mapOf("root" to 1,"group" to 1,"season" to 1,"disc" to 1)) { ctx.contentResolver.lists }
 check(ctx.contentResolver.opens==40 && ctx.contentResolver.creates==0 && ctx.contentResolver.deletes==0)
 val before=ctx.contentResolver.lists.toMap()
 TorrPrefetch.recoverEntry(ctx,"movie",TorrServerCatalog.entry)
 check(ctx.contentResolver.lists==before) // All known files: no SAF scan at all.

 ctx=fixture();val provider=ctx.contentResolver
 provider.documents["file1"]=provider.documents.getValue("file1").copy(size=99)
 provider.documents["duplicate"]=provider.documents.getValue("file2").copy(id="duplicate")
 provider.documents.remove("file3")
 TorrPrefetch.recoverEntry(ctx,"movie",TorrServerCatalog.entry)
 check(TorrDownloadStore.jobs.value.size==37 && TorrDownloadStore.jobs.value.none { it.index in 1..3 })
 check(provider.creates==0 && provider.deletes==0)

 ctx=fixture();ctx.prefs.values["downloads.torrserver.groups"]="[]"
 TorrPrefetch.recoverSaved(ctx,"movie",listOf(hash))
 check(TorrServerCatalog.cachedRequests==0 && ctx.contentResolver.lists.isEmpty() && TorrDownloadStore.jobs.value.isEmpty())
 ctx=fixture();TorrDownloadStore.broken=true
 check(runCatching { TorrPrefetch.recoverSaved(ctx,"movie",listOf(hash)) }.isFailure)
 check(TorrServerCatalog.cachedRequests==0 && ctx.contentResolver.lists.isEmpty())

 ctx=fixture();val entry=TorrServerCatalog.entry
 withContext(Dispatchers.Default) {
  awaitAll(async { TorrPrefetch.recoverEntry(ctx,"movie",entry) },async { TorrPrefetch.recoverEntry(ctx,"movie",entry) })
 }
 check(TorrDownloadStore.jobs.value.size==40 && ctx.contentResolver.lists.values.all { it==1 })

 ctx=fixture()
 val cancelled=launch {
  val job=currentCoroutineContext()[Job]!!
  ctx.contentResolver.afterQuery={ if(it=="disc")job.cancel() }
  TorrPrefetch.recoverEntry(ctx,"movie",TorrServerCatalog.entry)
 }
 cancelled.join()
 check(cancelled.isCancelled && TorrDownloadStore.jobs.value.isEmpty() && ctx.contentResolver.opens==0)
 ctx=fixture();ctx.contentResolver.afterQuery={ if(it=="disc")PortableMaintenance.running=true }
 check(runCatching { TorrPrefetch.recoverEntry(ctx,"movie",TorrServerCatalog.entry) }.exceptionOrNull() is CancellationException)
 check(TorrDownloadStore.jobs.value.isEmpty())
 ctx=fixture();ctx.contentResolver.afterQuery={ if(it=="disc")ctx.prefs.values["downloads.folder"]="content://provider/tree/other" }
 check(runCatching { TorrPrefetch.recoverEntry(ctx,"movie",TorrServerCatalog.entry) }.exceptionOrNull() is CancellationException)
 check(TorrDownloadStore.jobs.value.isEmpty())
 check(TorrServerCatalog.peerRequests==0 && ctx.contentResolver.creates==0 && ctx.contentResolver.deletes==0)
 println("PASS: cached-only discovery, bounded directory IO, existing/colliding/missing files, damaged journal, concurrent requests and cancellation/maintenance/folder change")
}
'''

with tempfile.TemporaryDirectory(prefix="gostex-completed-discovery-") as td:
    folder = Path(td)
    sources = {"Uri.kt": uri, "Context.kt": context, "Documents.kt": documents,
               "Build.kt": "package android.os\nobject Build { object VERSION { const val SDK_INT=30 } }\n",
               "Stubs.kt": stubs, "Checks.kt": checks}
    for name, body in sources.items():
        (folder / name).write_text(body)
    cp = ":".join(map(str, [COMPILER[1], COMPILER[4], jsonjar]))
    command = [str(JAVA), "-cp", ":".join(map(str, COMPILER)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
               "-no-stdlib", "-no-reflect", "-classpath", cp, "-d", str(folder / "classes")]
    command += [str(base / name) for name in ("TorrPrefetch.kt", "TorrDownloadGroups.kt", "TorrDownloadDocuments.kt", "TorrDownloadPaths.kt", "HttpDownloadAdmission.kt")]
    command += [str(folder / name) for name in sources]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode:
        raise SystemExit(result.stderr)
    result = subprocess.run([str(JAVA), "-cp", f"{folder / 'classes'}:{cp}", "tv.orbit.shield.ChecksKt"], capture_output=True, text=True)
    print(result.stdout, end="")
    if result.returncode:
        raise SystemExit(result.stderr)
