"""Compile production library import against a deterministic host document provider.

Exercises large folder limits, disconnected roots, single files, installed 3DS pruning and cancellation.
Android URI grant enforcement and activity lifecycle are device checks, not simulated here.
"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import os
import re
import subprocess
import tempfile

from kotlin_toolchain import COMPILER, JAVA, ROOT, jar

source_dir = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
sources = [source_dir / name for name in ('LibraryImport.kt', 'ThreeDsInstalledLayout.kt',
    'ThreeDsMetadata.kt', 'LibrarySnapshot.kt', 'DiscImport.kt', 'LibraryRootCodec.kt', 'ArchiveAdmission.kt')]
local_json = ROOT / '.tools/native/test-json/json-20240303.jar'
json_jar = (Path(os.environ['PORTABLE_TEST_JSON_JAR']) if os.environ.get('PORTABLE_TEST_JSON_JAR')
            else local_json if local_json.is_file() else jar('org.json', 'json', '20180813'))
verified_json = {
    '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed',
    '518080049ba83181914419d11a25d9bc9833a2d729b6a6e7469fa52851356da8',
}
if not json_jar.is_file() or hashlib.sha256(json_jar.read_bytes()).hexdigest() not in verified_json:
    raise SystemExit('Provide a verified org.json 20180813 or 20240303 test jar in PORTABLE_TEST_JSON_JAR')
classpath = os.pathsep.join(map(str, [COMPILER[1], COMPILER[4], json_jar]))
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
 const val EXTRA_LOADING="loading";const val EXTRA_ERROR="error"
 object Document {const val COLUMN_DOCUMENT_ID="id";const val COLUMN_DISPLAY_NAME="name";const val COLUMN_MIME_TYPE="mime";const val COLUMN_SIZE="size";const val COLUMN_LAST_MODIFIED="modified";const val MIME_TYPE_DIR="directory"}
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
 val fd:Int get()=0
 override fun close() {}
 class AutoCloseInputStream(descriptor:ParcelFileDescriptor):java.io.FileInputStream(descriptor.file)
}
''',
'Context.kt': r'''package android.content
import android.net.Uri
import android.provider.DocumentsContract as Docs
data class Permission(val uri:Uri,val isReadPermission:Boolean=true)
data class Row(val id:String,val name:String,val mime:String="file")
class Extras(val loading:Boolean=false,val error:String?=null) {fun getBoolean(key:String)=loading;fun getString(key:String)=error}
class Cursor(val rows:List<Row>,val extras:Extras=Extras()):java.io.Closeable {var at=-1;fun moveToNext()=++at<rows.size;fun moveToFirst():Boolean { at=0;return rows.isNotEmpty() };fun isNull(i:Int)=true;fun getLong(i:Int)=0L;fun getString(i:Int)=when(i){0->rows[at].id;1->rows[at].name;else->rows[at].mime};override fun close() {}}
class Resolver {
 val persistedUriPermissions=mutableListOf<Permission>()
 val children=mutableMapOf<String,List<Row>>();val files=mutableMapOf<String,java.io.File>()
 val documents=mutableMapOf<String,List<Row>>()
 val extras=mutableMapOf<String,Extras>()
 val queried=mutableListOf<String>();val opened=mutableListOf<String>()
 var fail:Exception?=null;var queryFail:Exception?=null;var beforeQuery:(()->Unit)?=null
 fun query(uri:Uri,cols:Array<String>,where:String?,args:Array<String>?,order:String?):Cursor? {
  beforeQuery?.invoke();queryFail?.let{throw it}
  if("/children/" !in uri.raw) {
   val id=Docs.getDocumentId(uri);queried+=id
   val rows=documents[id] ?: if(id=="primary:chosen" && id in children)
    listOf(Row(id,"chosen",Docs.Document.MIME_TYPE_DIR)) else children.values.flatten().filter{it.id==id}.distinctBy{it.id}
   return Cursor(rows,extras["document:$id"]?:Extras())
  }
  val id=Docs.decode(uri.raw.substringAfter("/children/"));queried+=id
  return children[id]?.let{Cursor(it,extras[id]?:Extras())}
 }
 fun openInputStream(uri:Uri)=files[Docs.getDocumentId(uri)]?.inputStream()
 fun openFileDescriptor(uri:Uri,mode:String):android.os.ParcelFileDescriptor? {
  val id=Docs.getDocumentId(uri);opened+=id;fail?.let{throw it}
  return files[id]?.let{android.os.ParcelFileDescriptor(it)}
 }
}
class Prefs {
 val values=mutableMapOf<String,Any?>()
 fun contains(k:String)=values.containsKey(k)
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
'Models.kt': r'''package tv.orbit.shield
data class Game(val id:String,val title:String,val platform:String,val emulator:String,val component:String,val files:List<String>,val tree:String="",val cover:String="",val scene:String="",val genre:String="",val year:String="",val developer:String="",val players:String="",val demo:Boolean=false,val description:String="")
enum class LaunchKind {VIEW,DUCKSTATION}
data class PlatformSpec(val emulator:String="Azahar",val component:String="azahar/Emulation",val launchKind:LaunchKind=LaunchKind.VIEW) {val extensions=setOf("app","cxi","cci","3ds","3dsx","nds");fun accepts(name:String)=name.substringAfterLast('.').lowercase() in extensions}
object PlatformRegistry {fun find(platform:String)=if(platform in setOf("Nintendo 3DS","Nintendo DS"))PlatformSpec() else null;fun title(context:android.content.Context,platform:String)=platform}
object GameMetadataMatcher {fun find(context:android.content.Context,platform:String,name:String,bundled:List<Game>):Game?=null}
''',
}
stubs['AtomicFile.kt'] = r'''package android.util
class AtomicFile(private val file:java.io.File) {
 fun openRead()=file.inputStream()
 fun startWrite()=file.outputStream()
 fun finishWrite(out:java.io.FileOutputStream) { out.close() }
 fun failWrite(out:java.io.FileOutputStream) { out.close() }
}
'''
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
 val incomplete=ctx("incomplete")
 incomplete.contentResolver.children["primary:chosen"]=emptyList()
 for ((name,extras) in listOf("loading" to Extras(loading=true), "error" to Extras(error="temporarily unavailable"))) {
  incomplete.contentResolver.extras["primary:chosen"]=extras
  val rejected=runCatching { LibraryImport.scan(incomplete,dsRoot,emptyList()) }.isFailure
  pass("$name cursor keeps the previous library snapshot",rejected && libraryRootSnapshot(dsRoot.uri,null,listOf(oldDs),dsRoot.platform)==listOf(oldDs))
 }
 incomplete.contentResolver.extras.clear()
 pass("Complete empty folder can still remove deleted games",LibraryImport.scan(incomplete,dsRoot,emptyList()).isEmpty())
 val rootId = "primary:chosen"
 val rootRow=Row(rootId,"chosen",Docs.Document.MIME_TYPE_DIR)
 for ((label, rows) in listOf("missing" to emptyList(), "wrong ID" to listOf(rootRow.copy(id="other")),
  "file in place of directory" to listOf(rootRow.copy(mime="file")), "duplicate" to listOf(rootRow,rootRow))) {
  incomplete.contentResolver.documents[rootId]=rows
  val failed=runCatching { LibraryImport.scan(incomplete,dsRoot,emptyList(),previousSnapshot=listOf(oldDs)) }
  pass("Empty children but $label root metadata preserves cached games",
   failed.exceptionOrNull()?.message?.startsWith("res:${R.string.folder_unavailable}:")==true &&
    libraryRootSnapshot(dsRoot.uri,failed.getOrNull(),listOf(oldDs),dsRoot.platform)==listOf(oldDs))
 }
 incomplete.contentResolver.documents[rootId]=listOf(rootRow)
 incomplete.contentResolver.extras["document:$rootId"]=Extras(loading=true)
 pass("An incomplete root metadata cursor cannot clear games",runCatching {
  LibraryImport.scan(incomplete,dsRoot,emptyList(),previousSnapshot=listOf(oldDs))
 }.isFailure)
 incomplete.contentResolver.extras.clear()
 val large=ctx("large-scan")
 val dirs=List(1916){index -> Row("$rootId/part-$index","part-$index",Docs.Document.MIME_TYPE_DIR)}
 large.contentResolver.children[rootId]=dirs
 fun otherFiles(parent:String,start:Int,count:Int)=List(count){index ->
  val name="file-${start+index}.dat"
  Row("$parent/$name",name)
 }
 dirs.forEachIndexed { index, row ->
  val count=if(index==0)7203 else 50135/1915+if(index<=50135%1915)1 else 0
  large.contentResolver.children[row.id]=otherFiles(row.id,0,count).toMutableList().apply {
   if(index==0)this[0]=Row("${row.id}/Game.nds","Game.nds")
  }
 }
 val largeScan=LibraryImport.scan(large,dsRoot,emptyList(),previousSnapshot=emptyList())
 pass("Production scan imports one ROM in a 59,254-node hierarchy",
  large.contentResolver.children.values.sumOf{it.size}==59254 && largeScan.single().files.single().endsWith("/Game.nds"))
 pass("The large scan queries each directory once and checks root identity",large.contentResolver.queried.size==1918)
 val second=dirs[1].id
 large.contentResolver.children[second]=large.contentResolver.children.getValue(second)+otherFiles(second,10000,65536-59254)
 pass("Exactly 65,536 total rows are accepted",
  LibraryImport.scan(large,dsRoot,emptyList(),previousSnapshot=largeScan)==largeScan)
 large.contentResolver.children[second]=large.contentResolver.children.getValue(second)+otherFiles(second,20000,1)
 val overTotal=runCatching { LibraryImport.scan(large,dsRoot,emptyList(),previousSnapshot=largeScan) }
 pass("Total row overflow preserves the previous source snapshot",
  overTotal.exceptionOrNull()?.message=="res:${R.string.library_scan_entry_limit}:65536" &&
   libraryRootSnapshot(dsRoot.uri,overTotal.getOrNull(),largeScan,dsRoot.platform)==largeScan)
 val wide=ctx("wide-scan")
 wide.contentResolver.children[rootId]=otherFiles(rootId,0,10000)
 pass("Exactly 10,000 children are accepted in one directory",
  LibraryImport.scan(wide,dsRoot,emptyList(),previousSnapshot=emptyList()).isEmpty())
 wide.contentResolver.children[rootId]=otherFiles(rootId,0,10001)
 pass("Immediate child overflow has its own diagnostic",runCatching {
  LibraryImport.scan(wide,dsRoot,emptyList(),previousSnapshot=emptyList())
 }.exceptionOrNull()?.message=="res:${R.string.too_many_files}:")
 val manyGames=ctx("many-games")
 val buckets=dirs.take(2)
 manyGames.contentResolver.children[rootId]=buckets
 buckets.forEachIndexed { index,bucket ->
  manyGames.contentResolver.children[bucket.id]=List(5000+index){number ->
   Row("${bucket.id}/Game-$number.nds","Game-$number.nds")
  }
 }
 pass("Wider traversal still caps qualifying launch entries at 10,000",runCatching {
  LibraryImport.scan(manyGames,dsRoot,emptyList(),previousSnapshot=emptyList())
 }.exceptionOrNull()?.message=="res:${R.string.library_scan_game_limit}:10000")
 val sharedSnapshot=ctx("shared-snapshot")
 sharedSnapshot.contentResolver.children["primary:chosen"]=emptyList()
 File(sharedSnapshot.filesDir,"imported-games.json").writeText("invalid")
 pass("A supplied snapshot avoids another disk read",LibraryImport.scan(sharedSnapshot,dsRoot,emptyList(),previousSnapshot=listOf(oldDs)).isEmpty())
 pass("Standalone scans still reject an unreadable snapshot",runCatching { LibraryImport.scan(sharedSnapshot,dsRoot,emptyList()) }.isFailure)
 pass("Portable roots also use the supplied snapshot",LibraryImport.scan(sharedSnapshot,dsRoot.copy(source=LibrarySource.PORTABLE_SNAPSHOT),emptyList(),previousSnapshot=listOf(oldDs))==listOf(oldDs))
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
 LibraryImport.write(context,found);context.contentResolver.opened.clear()
 val installedRescan=LibraryImport.scan(context,root,emptyList())
 pass("Known installed 3DS content is still validated on rescan",installedRescan==found && context.contentResolver.opened.size==2)
 val wrong=ctx("wrong");add(wrong,"ROMs/Game.cxi",executable)
 var wrongRejected=false;try{LibraryImport.scan(wrong,root,emptyList())}catch(_:IllegalStateException){wrongRejected=true}
 pass("Wrong Azahar data root rejected without replacing snapshot",wrongRejected && libraryRootSnapshot(root.uri,null,found)==found)
 val empty=ctx("empty");add(empty,"sdmc")
 val warnings=mutableListOf<String>();val emptyGames=LibraryImport.scan(empty,root,emptyList()){warnings+=it}
 pass("Empty valid installed root produces a useful warning",emptyGames.isEmpty() && warnings.single().startsWith("res:${R.string.three_ds_installed_empty}:"))
 val roms=ctx("roms");add(roms,"my.cia",junk);add(roms,"Game.cxi",executable)
 val ciaWarnings=mutableListOf<String>();val romGames=LibraryImport.scan(roms,root.copy(source=LibrarySource.FILES),emptyList()){ciaWarnings+=it}
 pass("CIA installer never becomes a launchable card",romGames.size==1 && romGames.single().files.single().endsWith("Game.cxi") && ciaWarnings.single().startsWith("res:${R.string.three_ds_cia_found}:"))
 LibraryImport.write(roms,romGames);roms.contentResolver.opened.clear()
 val romRescan=LibraryImport.scan(roms,root.copy(source=LibrarySource.FILES),emptyList())
 pass("Known ordinary 3DS ROM retains its card without reopening its header",romRescan==romGames && roms.contentResolver.opened.isEmpty())
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
    command=[str(JAVA),'-cp',os.pathsep.join(map(str, COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
             '-no-stdlib','-no-reflect','-classpath',classpath,'-d',str(out)]+list(map(str,sources))+list(map(str,folder.glob('*.kt')))
    compiled=subprocess.run(command,text=True,capture_output=True)
    report['compile']={'returncode':compiled.returncode,'stderr':compiled.stderr}
    report['passed']=False
    if compiled.returncode==0:
        result=subprocess.run([str(JAVA),'-cp',str(out)+os.pathsep+classpath,'tv.orbit.shield.ChecksKt',str(folder/'fixtures')],text=True,capture_output=True)
        report['run']={'returncode':result.returncode,'stdout':result.stdout,'stderr':result.stderr}
        report['checks']=[line.removeprefix('PASS|') for line in result.stdout.splitlines() if line.startswith('PASS|')]
        report['passed']=result.returncode==0
path=ROOT/'.reports/three-ds-library-checks.json';path.parent.mkdir(exist_ok=True)
path.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'passed':report['passed'],'checks':len(report.get('checks',[])),'report':str(path)}))
if not report['passed']:
    print(json.dumps(report,ensure_ascii=False,indent=2));raise SystemExit(1)
