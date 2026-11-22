"""Exercise production download discovery and creation against a counting SAF provider."""
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
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
data class FakeDocument(val id:String, val parent:String, val name:String, val size:Long?, val mime:String)
class FakeExtras(private val values:Map<String,Any>) {
 fun getBoolean(key:String,default:Boolean=false)=values[key] as? Boolean ?: default
 fun getString(key:String)=values[key] as? String
 fun containsKey(key:String)=values.containsKey(key)
}
class FakeCursor(private val rows:List<List<Any?>>,private val cursorExtras:((Boolean)->Map<String,Any>)? = null):Closeable {
 private var index=-1
 val extras get()=FakeExtras(cursorExtras?.invoke(index>=rows.size) ?: emptyMap())
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
 val documentQueries=mutableListOf<Uri>()
 var opens=0;var creates=0;var deletes=0
 var writeOpens=0
 var onCreate:((FakeDocument)->Uri)?=null
 var allowDelete=false
 var afterQuery:((String)->Unit)?=null
 var afterRead:(()->Unit)?=null
 var headerBytesRead=0;var chunkSize=4096
 val deniedDocuments=mutableSetOf<String>()
 val deniedChildren=mutableSetOf<String>()
 val childExtras=mutableMapOf<String,(Boolean)->Map<String,Any>>()
 val bodies=mutableMapOf<String,ByteArray>()
 val failedReads=mutableSetOf<String>()
 val unavailableBodies=mutableSetOf<String>()
 val persistedUriPermissions=mutableListOf<Permission>()
 fun takePersistableUriPermission(uri:Uri, flags:Int) {}
 fun openAssetFileDescriptor(uri:Uri,mode:String):FakeDescriptor? {
  check(mode=="r");opens++;return if(uri.raw.substringAfterLast('/') in documents) FakeDescriptor() else null
 }
 fun openInputStream(uri:Uri):InputStream? {
  opens++
  val id=uri.raw.substringAfterLast('/')
  val document=documents[id] ?: return null
  if(id in unavailableBodies) return null
  val body=bodies[id] ?: ByteArray(minOf(document.size ?: 0,4096L).toInt()).also { bytes ->
   val ebml=byteArrayOf(0x1A,0x45,0xDF.toByte(),0xA3.toByte())
   ebml.copyInto(bytes,endIndex=minOf(ebml.size,bytes.size))
  }
  return object:ByteArrayInputStream(body) {
   override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
    if(id in failedReads) throw IOException("Fixture read error")
    val count=super.read(buffer,offset,minOf(length,chunkSize))
    if(count>0) headerBytesRead+=count
    afterRead?.invoke()
    return count
   }
  }
 }
 fun query(uri:Uri,columns:Array<String>,selection:String?,args:Array<String>?,sort:String?):FakeCursor? {
  val id=uri.raw.substringAfterLast('/')
  val isChildQuery="/children/" in uri.raw
  val docs=if (isChildQuery) {
   lists[id]=(lists[id] ?: 0)+1;documents.values.filter { it.parent==id }
  } else {
   documentQueries+=uri
   if(id in deniedDocuments)return null
   listOfNotNull(documents[id])
  }
  val rows=docs.map { d -> columns.map { when(it) { "id"->d.id;"name"->d.name;"size"->d.size;"mime"->d.mime;else->error(it) } } }
  afterQuery?.invoke(id)
  return FakeCursor(rows,if (isChildQuery) childExtras[id] else null)
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
import android.content.FakeDocument
import android.net.Uri
object DocumentsContract {
 const val EXTRA_LOADING="loading";const val EXTRA_ERROR="error"
 object Document {
  const val COLUMN_DOCUMENT_ID="id";const val COLUMN_DISPLAY_NAME="name"
  const val COLUMN_SIZE="size";const val COLUMN_MIME_TYPE="mime";const val MIME_TYPE_DIR="dir"
 }
 fun getTreeDocumentId(tree:Uri)=tree.raw.substringAfter("/tree/").substringBefore('/')
 fun getDocumentId(uri:Uri)=uri.raw.substringAfterLast('/')
 fun buildChildDocumentsUriUsingTree(tree:Uri,id:String)=Uri("content://${tree.authority}/tree/${getTreeDocumentId(tree)}/children/$id")
 fun buildDocumentUriUsingTree(tree:Uri,id:String)=Uri("content://${tree.authority}/tree/${getTreeDocumentId(tree)}/document/$id")
 fun createDocument(resolver:ContentResolver,parent:Uri,mime:String,name:String):Uri? {
  resolver.creates++
  return checkNotNull(resolver.onCreate) { "Recovery must not create documents" }(FakeDocument("partial",getDocumentId(parent),name,0,mime))
 }
 fun deleteDocument(resolver:ContentResolver,document:Uri):Boolean {
  resolver.deletes++
  check(resolver.allowDelete) { "Unexpected deletion" }
  return resolver.documents.remove(getDocumentId(document))!=null
 }
 fun isChildDocument(resolver:ContentResolver,parent:Uri,child:Uri):Boolean {
  val parentId=getDocumentId(parent);var id=getDocumentId(child)
  if(id in resolver.deniedChildren)return false
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
 var allowHttpAdmission=false
 fun recover(context:Context) { if(broken) throw IOException("Damaged journal") }
 fun requireActiveCapacity(context:Context, additional:Int) { recover(context); require(jobs.value.count { it.state != "done" } + additional <= 100) }
 fun requireHttpAdmission(context:Context,tree:String,resourceKey:String) { check(allowHttpAdmission) { "Unexpected HTTP admission" } }
 @Synchronized fun add(context:Context,job:TorrDownloadJob,admission:HttpDownloadAdmissionResult?=null) { check(jobs.value.none { it.document==job.document });jobs.value=jobs.value+job }
 fun beginDeletePrefetch(context:Context,id:String):TorrDownloadJob?=null
 fun finishDeletePrefetch(context:Context,id:String) {}
 fun endDiscard(id:String) {}
 fun beginDiscard(context:Context,id:String):TorrDownloadJob?=null
 fun finishDiscard(context:Context,id:String) {}
 fun queue(context:Context,ids:List<String>) {}
}
object PortableMaintenance { var running=false }
object TorrDownloadSpace { fun read(context:Context)=0 }
object TorrDownloadActions {
 var starts=0
 fun startMany(context:Context,ids:List<String>) { starts++ }
}
data class PrefetchEpisode(val id:Int,val path:String,val bytes:Long)
fun planEpisodePrefetch(files:List<PrefetchEpisode>,current:Int,count:Int,existing:Set<Int>,free:Long?,reserve:Long,quota:Long,used:Long,includeCurrent:Boolean=false)=emptyList<PrefetchEpisode>()
fun orderedEpisodeFiles(files:List<String>)=true
class TorrSafTarget(private val bytes:Long):Closeable {
 fun size()=bytes
 fun availableBytes()=Long.MAX_VALUE
 override fun close() {}
 companion object {
  fun open(resolver:ContentResolver,uri:Uri):TorrSafTarget {
   resolver.writeOpens++
   return TorrSafTarget(resolver.documents[uri.raw.substringAfterLast('/')]?.size ?: throw IOException("Unseekable document"))
  }
 }
}
'''
checks = r'''package tv.orbit.shield
import android.content.ContentResolver
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
  TorrDownloadStore.allowHttpAdmission=false
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

 fun mixedFixture()=fixture().also {
  it.contentResolver.documents["direct"]=FakeDocument("direct","season","direct.mkv",100,"video")
  it.contentResolver.documents["nested"]=FakeDocument("nested","disc","nested.mkv",100,"video")
 }
 ctx=mixedFixture()
 val mixedDepth=ctx.contentResolver
 val mixedEntry=TorrCatalogEntry(hash,"Mixed-depth season",listOf(
  TorrCatalogFile(1,"Season/direct.mkv",100),
  TorrCatalogFile(2,"Season/Disc/nested.mkv",100),
 ))
 TorrPrefetch.recoverEntry(ctx,"movie",mixedEntry)
 check(TorrDownloadStore.jobs.value.associate { it.index to it.document }==mapOf(
  1 to Docs.buildDocumentUriUsingTree(tree,"direct").toString(),
  2 to Docs.buildDocumentUriUsingTree(tree,"nested").toString(),
 ))
 val mixedLists=mapOf("root" to 1,"group" to 1,"season" to 1,"disc" to 1)
 check(mixedDepth.lists==mixedLists) { "Mixed-depth recovery enumerations: ${mixedDepth.lists}" }
 check(mixedDepth.opens==2 && mixedDepth.creates==0 && mixedDepth.deletes==0)
 TorrPrefetch.recoverEntry(ctx,"movie",mixedEntry)
 check(mixedDepth.lists==mixedLists && mixedDepth.opens==2)
 println("PASS: mixed-depth recovery enumerates the shared Season folder once")

 for (extras in listOf<(Boolean)->Map<String,Any>>(
  { exhausted -> if (!exhausted) mapOf(Docs.EXTRA_LOADING to true) else emptyMap() },
  { exhausted -> if (exhausted) mapOf(Docs.EXTRA_ERROR to "Fixture cursor failed") else emptyMap() },
 )) {
  ctx=mixedFixture()
  val partial=ctx.contentResolver
  partial.childExtras["season"]=extras
  TorrPrefetch.recoverEntry(ctx,"movie",mixedEntry)
  check(TorrDownloadStore.jobs.value.isEmpty() && partial.opens==0)
  partial.childExtras.clear()
  TorrPrefetch.recoverEntry(ctx,"movie",mixedEntry)
  check(TorrDownloadStore.jobs.value.size==2 && partial.opens==2)
 }
 println("PASS: incomplete cursor extras block recovery until a fresh complete enumeration")

 ctx=fixture();val provider=ctx.contentResolver
 provider.documents["file1"]=provider.documents.getValue("file1").copy(size=99)
 provider.documents["duplicate"]=provider.documents.getValue("file2").copy(id="duplicate")
 provider.documents.remove("file3")
 TorrPrefetch.recoverEntry(ctx,"movie",TorrServerCatalog.entry)
 check(TorrDownloadStore.jobs.value.size==37 && TorrDownloadStore.jobs.value.none { it.index in 1..3 })
 check(provider.creates==0 && provider.deletes==0)

 ctx=fixture()
 val files=ctx.contentResolver
 files.bodies["file7"]=ByteArray(100) // Matching metadata with a zero-filled prefix.
 files.bodies["file9"]=ByteArray(3)
 files.failedReads+="file10"
 files.unavailableBodies+="file11"
 TorrPrefetch.recoverEntry(ctx,"movie",TorrServerCatalog.entry)
 check(TorrDownloadStore.jobs.value.size==36 && TorrDownloadStore.jobs.value.none { it.index in setOf(7,9,10,11) })
 val validJob=TorrDownloadStore.jobs.value.single { it.index==8 }
 val badJob=validJob.copy(document=Docs.buildDocumentUriUsingTree(tree,"file7").toString(),finalName="Show.S01E07.mkv")
 check(TorrPrefetch.playable(ctx,validJob) && !TorrPrefetch.playable(ctx,badJob))
 check(TorrPrefetch.savedStatus(ctx,badJob)==SavedVideoIntegrity.Result.INVALID)
 check(TorrPrefetch.savedStatus(ctx,badJob.copy(document=Docs.buildDocumentUriUsingTree(tree,"file10").toString()))==SavedVideoIntegrity.Result.UNAVAILABLE)
 check(TorrPrefetch.savedStatus(ctx,validJob.copy(total=99))==SavedVideoIntegrity.Result.INVALID)
 check(TorrPrefetch.savedStatus(ctx,validJob.copy(state="paused"))==SavedVideoIntegrity.Result.UNAVAILABLE)
 var cancelledStatus=false
 val priorOpens=files.opens
 files.afterQuery={ cancelledStatus=true }
 check(runCatching { TorrPrefetch.savedStatus(ctx,validJob) { if(cancelledStatus) throw CancellationException() } }
  .exceptionOrNull() is CancellationException && files.opens==priorOpens)
 files.afterQuery=null
 fun checkFile(extension:String,size:Long):SavedVideoIntegrity.Result = SavedVideoIntegrity.check(files,Uri.parse(validJob.document),"video.$extension",size)
 files.bodies["file8"]=ByteArray(8192).also { it[0]=1 }
 files.headerBytesRead=0;files.chunkSize=200
 check(checkFile("mpg",8192)==SavedVideoIntegrity.Result.VALID && files.headerBytesRead==4096)
 check(checkFile("mkv",8192)==SavedVideoIntegrity.Result.INVALID)
 check(checkFile("WEBM",8192)==SavedVideoIntegrity.Result.INVALID)
 files.bodies["file8"]=ByteArray(8192)
 check(checkFile("m2ts",8192)==SavedVideoIntegrity.Result.INVALID)
 val opens=files.opens
 check(checkFile("zip",8192)==SavedVideoIntegrity.Result.VALID && files.opens==opens)
 check(files.creates==0 && files.deletes==0)

 val httpDocument=Docs.buildDocumentUriUsingTree(tree,"attachment")
 val httpJob=validJob.copy(document=httpDocument.toString(),parentId="root",grouped=false,finalName="attachment.mkv",
  http=HttpDownloadOrigin(null,"fixture",null))
 files.documents["attachment"]=FakeDocument("attachment","root","attachment.mkv",100,"video")
 check(TorrDownloadDocuments.verifyCompletedHttp(ctx,httpJob)==httpDocument)
 files.bodies["attachment"]=ByteArray(100)
 check(runCatching { TorrDownloadDocuments.verifyCompletedHttp(ctx,httpJob) }.isFailure)
 files.documents["attachment"]=FakeDocument("attachment","root","attachment.zip",100,"archive")
 val beforeZip=files.opens
 check(TorrDownloadDocuments.verifyCompletedHttp(ctx,httpJob.copy(finalName="attachment.zip"))==httpDocument && files.opens==beforeZip)

 val url=HttpDownloadUrl.parse("https://example.invalid/movie.mkv")
 val admission=HttpDownloadAdmissionResult(url,url.resourceKey(),100,null)
 val partialUri=Docs.buildDocumentUriUsingTree(tree,"partial")
 fun downloadFixture(afterCreate:(ContentResolver,FakeDocument)->Uri={ _,_ -> partialUri }):Context = fixture().also { download ->
  TorrDownloadStore.allowHttpAdmission=true
  val resolver=download.contentResolver
  resolver.onCreate={ file ->
   resolver.documents[file.id]=file
   afterCreate(resolver,file)
  }
 }
 fun enqueueHttp(download:Context)=TorrDownloadDocuments.enqueueHttp(download,tree,admission,"movie.mkv")
 fun rejectBeforeOwnership(download:Context) {
  check(runCatching { enqueueHttp(download) }.isFailure)
  check(download.contentResolver.writeOpens==0 && download.contentResolver.deletes==0 && TorrDownloadStore.jobs.value.isEmpty())
 }
 ctx=downloadFixture()
 val queued=enqueueHttp(ctx)
 check(queued.document==partialUri.toString() && TorrDownloadStore.jobs.value==listOf(queued))
 check(ctx.contentResolver.lists==mapOf("root" to 2)) { ctx.contentResolver.lists }
 check(ctx.contentResolver.documentQueries==listOf(partialUri) && ctx.contentResolver.writeOpens==1 && ctx.contentResolver.deletes==0)

 ctx=downloadFixture()
 ctx.contentResolver.documents["existing"]=FakeDocument("existing","root","MOVIE.MKV",100,"video")
 check(runCatching { enqueueHttp(ctx) }.exceptionOrNull() is TorrDownloadDestinationExists)
 check(ctx.contentResolver.lists==mapOf("root" to 1) && ctx.contentResolver.creates==0 && ctx.contentResolver.documents.containsKey("existing"))

 val otherUri=Docs.buildDocumentUriUsingTree(tree,"other")
 ctx=downloadFixture { _,_ -> otherUri }
 ctx.contentResolver.documents["other"]=FakeDocument("other","root","existing.mkv",100,"video")
 rejectBeforeOwnership(ctx)
 check(ctx.contentResolver.lists==mapOf("root" to 1) && ctx.contentResolver.documents.containsKey("other"))
 ctx=downloadFixture { resolver,file ->
  resolver.documents["other"]=file.copy(id="other",name="another.partial")
  otherUri
 }
 rejectBeforeOwnership(ctx)
 check(ctx.contentResolver.lists==mapOf("root" to 2) && ctx.contentResolver.documents.containsKey("other"))

 for (duplicateId in listOf(false,true)) {
  ctx=downloadFixture { resolver,file ->
   resolver.documents["duplicate"]=if(duplicateId) file.copy(name="alias.partial") else file.copy(id="duplicate")
   partialUri
  }
  rejectBeforeOwnership(ctx)
 }
 ctx=downloadFixture();ctx.contentResolver.deniedDocuments+="partial"
 rejectBeforeOwnership(ctx)
 check(ctx.contentResolver.documentQueries==listOf(partialUri))
 ctx=downloadFixture();ctx.contentResolver.deniedChildren+="partial"
 rejectBeforeOwnership(ctx)
 ctx=downloadFixture { resolver,file ->
  resolver.documents[file.id]=file.copy(size=1)
  partialUri
 }
 ctx.contentResolver.allowDelete=true
 check(runCatching { enqueueHttp(ctx) }.isFailure)
 check(ctx.contentResolver.writeOpens==1 && ctx.contentResolver.deletes==1 && !ctx.contentResolver.documents.containsKey("partial"))
 check(TorrDownloadStore.jobs.value.isEmpty())
 println("PASS: two directory queries and one tree-rooted child identity query for creation, with ownership checks before write or cleanup")

 ctx=fixture()
 val cancelledOnRead=launch {
  val job=currentCoroutineContext()[Job]!!
  ctx.contentResolver.afterRead={ job.cancel() }
  TorrPrefetch.recoverEntry(ctx,"movie",TorrServerCatalog.entry)
 }
 cancelledOnRead.join()
 check(cancelledOnRead.isCancelled && TorrDownloadStore.jobs.value.isEmpty() && ctx.contentResolver.opens==1)

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

 ctx=fixture()
 val currentEntry=TorrServerCatalog.entry
 val paused=validJob.copy(hash=hash.uppercase(),state="paused",offset=20)
 fun store(vararg jobs:TorrDownloadJob) { TorrDownloadStore.jobs.value=jobs.toList() }
 suspend fun selected(canStart:()->Boolean={true}) =
  TorrPrefetch.start(ctx,"movie",currentEntry,currentEntry.files,paused.index,canStart)
 store(paused)
 check(selected()==paused && TorrDownloadActions.starts==0)
 check(selected { false }==null)
 TorrPrefetch.setCount(ctx,0)
 val running=paused.copy(state="running")
 store(running)
 check(selected()==running)
 val manual=running.copy(prefetchMovieId="")
 store(manual)
 check(selected()==manual)
 store(manual, manual.copy(id="duplicate"))
 check(selected()==null)
 store(paused.copy(prefetchMovieId="other-movie"))
 check(selected()==null)
 store(paused.copy(tree="content://provider/tree/other"))
 check(selected()==null)
 store(paused.copy(total=99), paused.copy(index=9))
 check(selected()==null)
 store(paused)
 PortableMaintenance.running=true
 check(selected()==null && TorrDownloadActions.starts==0)
 println("PASS: cached-only discovery, video integrity and exact selected-episode matching, including disabled prefetch and manual pause")
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
    command += [str(base / name) for name in ("TorrPrefetch.kt", "TorrDownloadGroups.kt", "TorrDownloadDocuments.kt", "PortableSafContainment.kt", "TorrDownloadPaths.kt", "HttpDownloadAdmission.kt", "SavedVideoIntegrity.kt", "TorrPlayback.kt")]
    command += [str(folder / name) for name in sources]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode:
        raise SystemExit(result.stderr)
    result = subprocess.run([str(JAVA), "-cp", f"{folder / 'classes'}:{cp}", "tv.orbit.shield.ChecksKt"], capture_output=True, text=True)
    print(result.stdout, end="")
    if result.returncode:
        raise SystemExit(result.stderr)
