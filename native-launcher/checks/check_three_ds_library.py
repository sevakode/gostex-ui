"""Compile production 3DS import against a deterministic host document provider.

Exercises folder/single-file persistence, installed-layout pruning, metadata and cancellation.
Android URI grant enforcement and activity lifecycle are device checks, not simulated here.
"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source_dir = root / 'native-launcher/app/src/main/java/tv/orbit/shield'
sources = [source_dir / name for name in ('LibraryImport.kt', 'ThreeDsInstalledLayout.kt',
    'ThreeDsMetadata.kt', 'LibrarySnapshot.kt', 'DiscImport.kt')]
cache = root / '.tools/native/gradle-cache/caches/modules-2/files-2.1'
def jar(group, artifact, version):
    return next((cache / group / artifact / version).glob('*/*.jar'))
compiler = [jar('org.jetbrains.kotlin', a, v) for a, v in [
    ('kotlin-compiler-embeddable', '2.4.21'), ('kotlin-stdlib', '2.4.21'),
    ('kotlin-script-runtime', '2.4.21'), ('kotlin-reflect', '1.6.10')]]
coroutines = jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.9.0')
compiler += [coroutines, jar('org.jetbrains', 'annotations', '13.0')]
gson = jar('com.google.code.gson', 'gson', '2.11.0')
classpath = ':'.join(map(str, [compiler[1], coroutines, gson]))
java = root / '.tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java'
stubs = {
'Uri.kt': r'''package android.net
data class Uri(val raw:String) {
 val authority:String? get()=java.net.URI(raw).authority
 override fun toString()=raw
 companion object {fun parse(s:String)=Uri(s)}
}
''',
'Documents.kt': r'''package android.provider
import android.net.Uri
object DocumentsContract {
 object Document {const val COLUMN_DOCUMENT_ID="id";const val COLUMN_DISPLAY_NAME="name";const val COLUMN_MIME_TYPE="mime";const val MIME_TYPE_DIR="directory"}
 fun encode(s:String)=java.net.URLEncoder.encode(s,"UTF-8")
 fun decode(s:String)=java.net.URLDecoder.decode(s,"UTF-8")
 fun buildTreeDocumentUri(authority:String,id:String)=Uri("content://$authority/tree/${encode(id)}")
 fun getTreeDocumentId(uri:Uri)=decode(uri.raw.substringAfter("/tree/").substringBefore('/'))
 fun buildChildDocumentsUriUsingTree(uri:Uri,id:String)=Uri(uri.raw.substringBefore("/children/")+"/children/"+encode(id))
 fun buildDocumentUriUsingTree(uri:Uri,id:String)=Uri(uri.raw.substringBefore("/document/")+"/document/"+encode(id))
 fun getDocumentId(uri:Uri)=decode(uri.raw.substringAfter("/document/"))
}
''',
'Parcel.kt': r'''package android.os
class ParcelFileDescriptor(val file:java.io.File):java.io.Closeable {
 override fun close() {}
 class AutoCloseInputStream(descriptor:ParcelFileDescriptor):java.io.FileInputStream(descriptor.file)
}
''',
'Context.kt': r'''package android.content
import android.net.Uri
import android.provider.DocumentsContract as Docs
data class Permission(val uri:Uri,val isReadPermission:Boolean=true)
data class Row(val id:String,val name:String,val mime:String="file")
class Cursor(val rows:List<Row>):java.io.Closeable {var at=-1;fun moveToNext()=++at<rows.size;fun getString(i:Int)=when(i){0->rows[at].id;1->rows[at].name;else->rows[at].mime};override fun close() {}}
class Resolver {
 val persistedUriPermissions=mutableListOf<Permission>()
 val children=mutableMapOf<String,List<Row>>();val files=mutableMapOf<String,java.io.File>()
 val queried=mutableListOf<String>();val opened=mutableListOf<String>()
 var fail:Exception?=null;var queryFail:Exception?=null;var beforeQuery:(()->Unit)?=null
 fun query(uri:Uri,cols:Array<String>,where:String?,args:Array<String>?,order:String?):Cursor? {
  beforeQuery?.invoke();queryFail?.let{throw it}
  if("/children/" !in uri.raw) {
   val id=Docs.getDocumentId(uri);queried+=id
   return Cursor(children.values.flatten().filter{it.id==id}.distinctBy{it.id})
  }
  val id=Docs.decode(uri.raw.substringAfter("/children/"));queried+=id
  return children[id]?.let{Cursor(it)}
 }
 fun openInputStream(uri:Uri)=files[Docs.getDocumentId(uri)]?.inputStream()
 fun openFileDescriptor(uri:Uri,mode:String):android.os.ParcelFileDescriptor? {
  val id=Docs.getDocumentId(uri);opened+=id;fail?.let{throw it}
  return files[id]?.let{android.os.ParcelFileDescriptor(it)}
 }
}
class Prefs {
 val values=mutableMapOf<String,Any?>()
 fun getString(k:String,d:String?)=values[k] as? String ?: d
 fun getBoolean(k:String,d:Boolean)=values[k] as? Boolean ?: d
 fun edit()=Editor(this)
 class Editor(val p:Prefs) {fun putString(k:String,v:String)=apply{p.values[k]=v};fun putBoolean(k:String,v:Boolean)=apply{p.values[k]=v};fun apply(){}}
}
class Resources {val configuration=Configuration()}
class Configuration {val locales=listOf(java.util.Locale.forLanguageTag("ru"))}
class Context(val filesDir:java.io.File) {
 val contentResolver=Resolver();val prefs=Prefs();val resources=Resources()
 fun getString(id:Int,vararg args:Any?)="res:$id:"+args.joinToString(",")
 fun getSharedPreferences(name:String,mode:Int)=prefs
}
''',
'Json.kt': r'''package org.json
import com.google.gson.*
class JSONObject(val o:JsonObject=JsonObject()) {
 fun put(k:String,v:Any?):JSONObject {o.add(k,encode(v));return this}
 fun getString(k:String)=o.get(k).asString
 fun optString(k:String)=o.get(k)?.takeUnless{it.isJsonNull}?.asString ?: ""
 fun optBoolean(k:String)=o.get(k)?.takeUnless{it.isJsonNull}?.asBoolean ?: false
 fun getJSONArray(k:String)=JSONArray(o.getAsJsonArray(k))
 override fun toString()=o.toString()
}
fun encode(v:Any?):JsonElement=when(v) {null->JsonNull.INSTANCE;is JSONObject->v.o;is JSONArray->v.a;is Boolean->JsonPrimitive(v);is Number->JsonPrimitive(v);else->JsonPrimitive(v.toString())}
class JSONArray(val a:JsonArray=JsonArray()) {
 constructor(text:String?):this(JsonParser.parseString(text ?: "[]").asJsonArray)
 constructor(items:Collection<*>):this(JsonArray()) {items.forEach{put(it)}}
 fun put(v:Any?):JSONArray {a.add(encode(v));return this}
 fun length()=a.size()
 fun getJSONObject(i:Int)=JSONObject(a.get(i).asJsonObject)
 fun getString(i:Int)=a.get(i).asString
 override fun toString()=a.toString()
}
''',
'Models.kt': r'''package tv.orbit.shield
data class Game(val id:String,val title:String,val platform:String,val emulator:String,val component:String,val files:List<String>,val tree:String="",val cover:String="",val scene:String="",val genre:String="",val year:String="",val developer:String="",val players:String="",val demo:Boolean=false,val description:String="")
enum class LaunchKind {VIEW,DUCKSTATION}
data class Spec(val emulator:String="Azahar",val component:String="azahar/Emulation",val launchKind:LaunchKind=LaunchKind.VIEW) {fun accepts(name:String)=name.substringAfterLast('.').lowercase() in setOf("app","cxi","cci","3ds","3dsx","nds")}
object PlatformRegistry {fun find(platform:String)=if(platform in setOf("Nintendo 3DS","Nintendo DS"))Spec() else null;fun title(context:android.content.Context,platform:String)=platform}
object GameMetadataMatcher {fun find(context:android.content.Context,platform:String,name:String,bundled:List<Game>):Game?=null}
''',
}
ids=sorted(set(re.findall(r'R\.string\.(\w+)', sources[0].read_text())))
stubs['Resources.kt']='package tv.orbit.shield\nobject R { object string {\n'+'\n'.join(f'const val {v}={i+1}' for i,v in enumerate(ids))+'\n}}'
checks=r'''package tv.orbit.shield
import android.content.*
import android.provider.DocumentsContract as Docs
import kotlinx.coroutines.*
import java.io.File
fun main(args:Array<String>)=runBlocking {
 var count=0
 fun pass(name:String,ok:Boolean){check(ok){name};println("PASS|$name");count++}
 val p="sdmc/Nintendo 3DS/"+"0".repeat(32)+"/"+"0".repeat(32)+"/title/00040000/001bd200/content"
 pass("Every application path ancestor is reachable",(0..8).all{ThreeDsInstalledLayout.directory(p.split('/').take(it).joinToString("/"))})
 pass("Main installed content accepted",ThreeDsInstalledLayout.application("$p/00000000.app"))
 pass("Hex and extension casing do not hide applications",ThreeDsInstalledLayout.application(p.replace("001bd200","ABCDEF12")+"/ABCDEF12.ApP"))
 pass("Application demos supported",ThreeDsInstalledLayout.application(p.replace("00040000","00040002")+"/00000000.app"))
 pass("Updates DLC and system title branches pruned",listOf("0004000e","0004008c","00040010","00040130").all{!ThreeDsInstalledLayout.directory(p.replace("00040000",it))})
 pass("Saves metadata and extracted/manual directories excluded",listOf("$p/00000001.sav","$p/00000000.tmd","$p/manual/00000000.app","$p/../../00000000.app").none{ThreeDsInstalledLayout.application(it)})
 pass("Shader nand and configuration trees pruned",listOf("nand","shader","shaders","config","sdmc/extdata","sdmc/Nintendo 3DS/abc").none{ThreeDsInstalledLayout.directory(it)})
 pass("Malformed title identifiers rejected",listOf("../","abcdefgh","123","123456789").none{ThreeDsInstalledLayout.application(p.replace("001bd200",it)+"/00000000.app")})
 pass("Unknown and legacy root sources default to files",listOf(null,"","unknown","FILES").all{LibrarySource.read(it,"Nintendo 3DS")==LibrarySource.FILES})
 pass("Installed source only applies to 3DS",LibrarySource.read("azahar-installed","Nintendo DS")==LibrarySource.FILES && LibrarySource.read("azahar-installed","Nintendo 3DS")==LibrarySource.AZAHAR_INSTALLED)
 fun ctx(name:String)=Context(File(args[0],name).apply{mkdirs()})
 fun tree()=Docs.buildTreeDocumentUri("com.android.externalstorage.documents","primary:chosen")
 val root=LibraryImport.Root("Nintendo 3DS",tree().toString(),LibrarySource.AZAHAR_INSTALLED)
 val persistence=ctx("prefs")
 persistence.prefs.values["libraryRoots"]="""[{"platform":"Nintendo DS","uri":"legacy"},{"platform":"Nintendo 3DS","uri":"old-three-ds","source":"unknown"}]"""
 pass("Existing roots without source load unchanged",LibraryImport.roots(persistence).map{it.source}==listOf(LibrarySource.FILES,LibrarySource.FILES))
 LibraryImport.addRoot(persistence,root)
 pass("Installed source survives JSON save and reload",LibraryImport.roots(persistence).last()==root && LibraryImport.roots(persistence).size==3)
 LibraryImport.addRoot(persistence,root)
 pass("Repeated folder import does not duplicate saved roots",LibraryImport.roots(persistence).size==3)
 val changed=root.copy(source=LibrarySource.FILES);LibraryImport.addRoot(persistence,changed)
 pass("Changing same folder source preserves one root",LibraryImport.roots(persistence).last()==changed && LibraryImport.roots(persistence).size==3)
 LibraryImport.removeRoot(persistence,changed)
 pass("Removing a root leaves legacy roots intact",LibraryImport.roots(persistence).map{it.uri}==listOf("legacy","old-three-ds"))
 val shared=ctx("shared-root")
 val dsRoot=root.copy(platform="Nintendo DS",source=LibrarySource.FILES)
 LibraryImport.addRoot(shared,dsRoot);LibraryImport.addRoot(shared,root)
 pass("Adding 3DS to the same folder keeps the DS root",LibraryImport.roots(shared)==listOf(dsRoot,root))
 LibraryImport.addRoot(shared,root.copy(source=LibrarySource.FILES))
 pass("Changing 3DS source does not change same-folder DS source",LibraryImport.roots(shared)==listOf(dsRoot,root.copy(source=LibrarySource.FILES)))
 LibraryImport.removeRoot(shared,root.copy(source=LibrarySource.FILES))
 pass("Removing 3DS root preserves DS using the same grant",LibraryImport.roots(shared)==listOf(dsRoot))
 val migrated=ctx("migration")
 migrated.contentResolver.persistedUriPermissions+=Permission(tree())
 val oldDs=Game("ds","DS game","Nintendo DS","melonDS","ds/Main",listOf("ds.nds"),root.uri)
 val oldThreeDs=Game("3ds","3DS game","Nintendo 3DS","Azahar","azahar/Main",listOf("3ds.cxi"),root.uri)
 LibraryImport.migrateGrantedCatalogRoots(migrated,listOf(oldDs,oldThreeDs))
 pass("Granted catalog migration keeps both platforms sharing a folder",LibraryImport.roots(migrated)==listOf(dsRoot,root.copy(source=LibrarySource.FILES)))
 pass("Failed 3DS scan cannot retain DS cards through a shared URI",libraryRootSnapshot(root.uri,null,listOf(oldDs,oldThreeDs),"Nintendo 3DS")==listOf(oldThreeDs))
 pass("Failed DS scan cannot retain 3DS cards through a shared URI",libraryRootSnapshot(root.uri,null,listOf(oldDs,oldThreeDs),"Nintendo DS")==listOf(oldDs))
 pass("Successful empty scan clears only its own platform snapshot",libraryRootSnapshot(root.uri,emptyList(),listOf(oldDs,oldThreeDs),"Nintendo 3DS").isEmpty())
 pass("Legacy snapshot callers retain URI-only behavior",libraryRootSnapshot(root.uri,null,listOf(oldDs,oldThreeDs))==listOf(oldDs,oldThreeDs))
 fun add(context:Context,path:String,file:File?=null) {
  val parts=path.split('/');var parent="primary:chosen"
  for((i,part) in parts.withIndex()) {
   val id="primary:chosen/"+parts.take(i+1).joinToString("/")
   val directory=i<parts.lastIndex || file==null
   val row=Row(id,part,if(directory)Docs.Document.MIME_TYPE_DIR else "file")
   val siblings=context.contentResolver.children.getOrPut(parent){emptyList()}
   if(siblings.none{it.id==id})context.contentResolver.children[parent]=siblings+row
   if(directory)context.contentResolver.children.putIfAbsent(id,emptyList())
   parent=id
  }
  if(file!=null)context.contentResolver.files[parent]=file
 }
 fun put32(bytes:ByteArray,offset:Int,value:Int){for(i in 0..3)bytes[offset+i]=(value ushr (i*8)).toByte()}
 val bytes=ByteArray(0x4000).apply {
  "NCCH".toByteArray().copyInto(this,0x100);put32(this,0x104,size/512);this[0x18d]=3;this[0x18f]=4
  put32(this,0x1a0,1);put32(this,0x1a4,size/512-1);"icon".toByteArray().copyInto(this,512);put32(this,524,0x36c0)
  "SMDH".toByteArray().copyInto(this,1024);"Русская игра".toByteArray(Charsets.UTF_16LE).copyInto(this,1024+8+10*512)
  "Studio".toByteArray(Charsets.UTF_16LE).copyInto(this,1024+8+10*512+384)
 }
 val executable=File(args[0],"game.app").apply{writeBytes(bytes)}
 val data=File(args[0],"manual.app").apply{writeBytes(bytes.copyOf().apply{this[0x18d]=1})}
 val junk=File(args[0],"junk").apply{writeText("not a ROM")}
 val context=ctx("scan")
 add(context,"$p/00000000.app",executable);add(context,"$p/00000001.app",data)
 add(context,"$p/00000000.tmd",junk)
 add(context,p.replace("00040000","0004000e")+"/00000000.app",executable)
 add(context,"shader/"+"cache/".repeat(20)+"large.bin",junk)
 add(context,"nand/00040000/00000000.app",executable)
 add(context,"sdmc/Nintendo 3DS/"+"0".repeat(32)+"/"+"0".repeat(32)+"/title/00040000/001bd200/data/save.bin",junk)
 val found=LibraryImport.scan(context,root,emptyList())
 pass("Actual scanner imports localized installed title and publisher",found.single().title=="Русская игра" && found.single().developer=="Studio")
 pass("Installed file retains selected tree and exact launch path",found.single().tree==root.uri && found.single().files.single()=="/storage/emulated/0/chosen/$p/00000000.app")
 pass("Installed scan only opens executable candidates",context.contentResolver.opened.size==2 && context.contentResolver.opened.all{it.endsWith(".app")})
 pass("Installed scan never traverses expensive or non-game branches",context.contentResolver.queried.none{it.contains("shader")||it.contains("nand")||it.contains("0004000e")||it.endsWith("/data")})
 pass("Repeated installed scans preserve game identity",LibraryImport.scan(context,root,emptyList()).single().id==found.single().id)
 val wrong=ctx("wrong");add(wrong,"ROMs/Game.cxi",executable)
 var wrongRejected=false;try{LibraryImport.scan(wrong,root,emptyList())}catch(_:IllegalStateException){wrongRejected=true}
 pass("Wrong Azahar data root rejected without replacing snapshot",wrongRejected && libraryRootSnapshot(root.uri,null,found)==found)
 val empty=ctx("empty");add(empty,"sdmc")
 val warnings=mutableListOf<String>();val emptyGames=LibraryImport.scan(empty,root,emptyList()){warnings+=it}
 pass("Empty valid installed root produces a useful warning",emptyGames.isEmpty() && warnings.single().startsWith("res:${R.string.three_ds_installed_empty}:"))
 val roms=ctx("roms");add(roms,"my.cia",junk);add(roms,"Game.cxi",executable)
 val ciaWarnings=mutableListOf<String>();val romGames=LibraryImport.scan(roms,root.copy(source=LibrarySource.FILES),emptyList()){ciaWarnings+=it}
 pass("CIA installer never becomes a launchable card",romGames.size==1 && romGames.single().files.single().endsWith("Game.cxi") && ciaWarnings.single().startsWith("res:${R.string.three_ds_cia_found}:"))
 val unreadable=ctx("unreadable");add(unreadable,"$p/00000000.app",executable);unreadable.contentResolver.fail=java.io.IOException("USB unavailable")
 var io=false;try{LibraryImport.scan(unreadable,root,emptyList())}catch(_:java.io.IOException){io=true}
 pass("Storage failure propagates so cached cards survive",io)
 unreadable.contentResolver.fail=SecurityException("grant revoked")
 var security=false;try{LibraryImport.scan(unreadable,root,emptyList())}catch(_:SecurityException){security=true}
 pass("Revoked read grant does not silently remove installed games",security)
 unreadable.contentResolver.fail=CancellationException("cancel metadata")
 var cancelled=false;try{LibraryImport.scan(unreadable,root,emptyList())}catch(_:CancellationException){cancelled=true}
 pass("Metadata cancellation propagates unchanged",cancelled)
 val cancelledContext=ctx("cancel-query");add(cancelledContext,"sdmc")
 var cancellationObserved=false
 val scanJob=launch {try {cancelledContext.contentResolver.beforeQuery={cancel("user left library")};LibraryImport.scan(cancelledContext,root,emptyList())}catch(_:CancellationException){cancellationObserved=true}}
 scanJob.join()
 pass("Cancellation during provider traversal exits before commit",cancellationObserved)

 pass("Single document source survives decoding for every platform",LibrarySource.read("single-file","Nintendo DS")==LibrarySource.SINGLE_FILE && LibrarySource.read("single-file","Nintendo 3DS")==LibrarySource.SINGLE_FILE)
 fun document(id:String)=android.net.Uri("content://com.android.externalstorage.documents/document/"+Docs.encode(id))
 val single=ctx("single");add(single,"Game.cxi",executable);add(single,"Other.cxi",executable)
 val singleRoot=LibraryImport.Root("Nintendo 3DS",document("primary:chosen/Game.cxi").toString(),LibrarySource.SINGLE_FILE)
 val singleGames=LibraryImport.scan(single,singleRoot,emptyList())
 pass("File picker imports only the selected game, not its sibling",singleGames.size==1 && single.contentResolver.queried==listOf("primary:chosen/Game.cxi"))
 pass("Single-file metadata reads title and publisher",singleGames.single().title=="Русская игра" && singleGames.single().developer=="Studio")
 pass("Single file uses exact document URI without fabricated tree or copy",singleGames.single().files==listOf(singleRoot.uri) && singleGames.single().tree==singleRoot.uri && single.filesDir.listFiles().orEmpty().isEmpty())
 LibraryImport.addRoot(single,singleRoot);LibraryImport.addRoot(single,singleRoot)
 pass("Repeated file selection persists one source with its kind",LibraryImport.roots(single)==listOf(singleRoot))
 val folderGames=LibraryImport.scan(single,singleRoot.copy(uri=root.uri,source=LibrarySource.FILES),emptyList())
 pass("Same file imported by folder and document has a stable ID",folderGames.single{it.files.single().endsWith("Game.cxi")}.id==singleGames.single().id)
 pass("Folder and file overlap yields one card per physical document",mergeImportedVariants(folderGames+singleGames).size==2 && mergeImportedVariants(singleGames+folderGames).size==2)
 val catalog=Game("old-favorite","Catalog title","Nintendo 3DS","Azahar","azahar/Main",listOf("/storage/emulated/0/chosen/Game.cxi"),"primary:chosen",cover="existing-cover")
 val catalogSingle=LibraryImport.scan(single,singleRoot,listOf(catalog)).single()
 pass("Selecting a catalog game preserves favorite identity and cover",catalogSingle.id==catalog.id && catalogSingle.cover==catalog.cover && catalogSingle.files==listOf(singleRoot.uri))
 val knownFolder=LibraryImport.scan(single,singleRoot.copy(uri=root.uri,source=LibrarySource.FILES),listOf(catalog))
 pass("Known catalog file and folder entries do not duplicate",mergeImportedVariants(knownFolder+catalogSingle).size==2)
 val checks=ctx("single-invalid")
 for(name in listOf("Installer.CIA","Game.cue","Game.M3U","Game.gdi","Game.pdf","Manual.app")) add(checks,name,if(name.endsWith(".app"))data else junk)
 for((name,message) in listOf("Installer.CIA" to R.string.import_file_cia_hint,"Game.cue" to R.string.import_file_companions_hint,"Game.M3U" to R.string.import_file_companions_hint,"Game.gdi" to R.string.import_file_companions_hint,"Game.pdf" to R.string.import_file_wrong_type,"Manual.app" to R.string.import_file_not_game)) {
  var reason="";try{LibraryImport.scan(checks,singleRoot.copy(uri=document("primary:chosen/$name").toString()),emptyList())}catch(e:IllegalArgumentException){reason=e.message.orEmpty()}catch(e:IllegalStateException){reason=e.message.orEmpty()}
  pass("Single $name rejected with actionable localized guidance",reason.startsWith("res:$message:"))
 }
 pass("Unsupported single files do not write any library metadata",checks.filesDir.listFiles().orEmpty().isEmpty() && LibraryImport.roots(checks).isEmpty())
 single.contentResolver.children.clear()
 pass("A deleted document returns an empty successful snapshot",LibraryImport.scan(single,singleRoot,emptyList()).isEmpty())
 single.contentResolver.queryFail=SecurityException("grant revoked")
 var revoked=false;try{LibraryImport.scan(single,singleRoot,emptyList())}catch(_:SecurityException){revoked=true}
 pass("Revoked single-file permission keeps cached card on scan failure",revoked && libraryRootSnapshot(singleRoot.uri,null,singleGames,"Nintendo 3DS")==singleGames)
 val cancelledFile=ctx("cancel-file");add(cancelledFile,"Game.cxi",executable)
 var fileCancelled=false
 val fileJob=launch {try{cancelledFile.contentResolver.beforeQuery={cancel("picker activity closed")};LibraryImport.scan(cancelledFile,singleRoot,emptyList())}catch(_:CancellationException){fileCancelled=true}}
 fileJob.join()
 pass("Single-file cancellation stops before metadata or commit",fileCancelled && cancelledFile.contentResolver.opened.isEmpty())
 LibraryImport.removeRoot(single,singleRoot)
 pass("Removing single source preserves the selected ROM",LibraryImport.roots(single).isEmpty() && executable.exists())
 println("TOTAL|$count")
}
'''
report={'timestamp_utc':datetime.now(timezone.utc).isoformat(),'scope':__doc__,
        'source_sha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}}
with tempfile.TemporaryDirectory(prefix='gostex-3ds-import-') as tmp:
    folder=Path(tmp)
    for name,body in stubs.items(): (folder/name).write_text(body)
    (folder/'Checks.kt').write_text(checks)
    out=folder/'classes'
    command=[str(java),'-cp',':'.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
             '-no-stdlib','-no-reflect','-classpath',classpath,'-d',str(out)]+list(map(str,sources))+list(map(str,folder.glob('*.kt')))
    compiled=subprocess.run(command,text=True,capture_output=True)
    report['compile']={'returncode':compiled.returncode,'stderr':compiled.stderr}
    report['passed']=False
    if compiled.returncode==0:
        result=subprocess.run([str(java),'-cp',str(out)+':'+classpath,'tv.orbit.shield.ChecksKt',str(folder/'fixtures')],text=True,capture_output=True)
        report['run']={'returncode':result.returncode,'stdout':result.stdout,'stderr':result.stderr}
        report['checks']=[line.removeprefix('PASS|') for line in result.stdout.splitlines() if line.startswith('PASS|')]
        report['passed']=result.returncode==0
path=root/'.reports/three-ds-library-checks.json';path.parent.mkdir(exist_ok=True)
path.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'passed':report['passed'],'checks':len(report.get('checks',[])),'report':str(path)}))
if not report['passed']:
    print(json.dumps(report,ensure_ascii=False,indent=2));raise SystemExit(1)
