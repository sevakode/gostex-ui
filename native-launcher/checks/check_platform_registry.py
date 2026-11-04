"""Execute production registry routing against small Android argument containers.

This checks outgoing intent contents and admission rules, not emulator execution or
Android's permission enforcement. Device launch checks remain separately required.
"""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import tempfile
from datetime import datetime, timezone

root = Path(__file__).resolve().parents[2]
source = root / 'native-launcher/app/src/main/java/tv/orbit/shield/PlatformRegistry.kt'
cache = root / '.tools/native/gradle-cache/caches/modules-2/files-2.1'
def jar(group, artifact, version):
    return next((cache / group / artifact / version).glob('*/*.jar'))
compiler = [jar('org.jetbrains.kotlin', a, v) for a, v in [
    ('kotlin-compiler-embeddable', '2.4.21'), ('kotlin-stdlib', '2.4.21'),
    ('kotlin-script-runtime', '2.4.21'), ('kotlin-reflect', '1.6.10')]]
compiler += [jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.9.0'), jar('org.jetbrains', 'annotations', '13.0')]
java = root / '.tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java'
stubs = {
'Uri.kt': '''package android.net
class Uri private constructor(private val raw: String) {
 val authority: String? get() = raw.substringAfter("://", "").substringBefore('/').ifEmpty { null }
 val scheme: String? get() = raw.substringBefore(":", "").ifEmpty { null }
 val lastPathSegment: String? get() = java.net.URLDecoder.decode(raw.substringAfterLast('/'), "UTF-8")
 override fun toString() = raw
 companion object { fun parse(value: String) = Uri(value) }
}''',
'DocumentsContract.kt': '''package android.provider
import android.net.Uri
object DocumentsContract {
 fun getTreeDocumentId(uri:Uri)=java.net.URLDecoder.decode(uri.toString().substringAfter("/tree/").substringBefore('/'), "UTF-8")
 fun getDocumentId(uri:Uri)=java.net.URLDecoder.decode(uri.toString().substringAfter("/document/").substringBefore('/'), "UTF-8")
}''',
'Intent.kt': '''package android.content
import android.net.Uri
class Intent(val action: String) {
 var component: ComponentName? = null; var data: Uri? = null; var clipData: ClipData? = null; var flags = 0
 val extras = mutableMapOf<String,String>()
 fun addFlags(value: Int): Intent { flags = flags or value; return this }
 fun putExtra(key: String, value: String): Intent { extras[key] = value; return this }
 companion object { const val ACTION_VIEW="android.intent.action.VIEW"; const val ACTION_MAIN="android.intent.action.MAIN"
 const val FLAG_ACTIVITY_NEW_TASK=0x10000000; const val FLAG_GRANT_READ_URI_PERMISSION=1; const val FLAG_GRANT_PREFIX_URI_PERMISSION=128 }
}
class ComponentName(val packageName: String, val className: String) {
 companion object { fun unflattenFromString(value: String): ComponentName? { val p=value.split('/'); if(p.size!=2)return null
 return ComponentName(p[0], if(p[1].startsWith('.')) p[0]+p[1] else p[1]) } }
}
class ClipData(val uri: Uri) { companion object { fun newRawUri(label: String, uri: Uri) = ClipData(uri) } }
open class Context(val packageManager: android.content.pm.PackageManager = android.content.pm.PackageManager()) {
 var permissionResult=0
 fun getString(id: Int,vararg args: Any) = id.toString()+args.joinToString()
 fun checkSelfPermission(permission: String) = permissionResult
}''',
'PackageManager.kt': '''package android.content.pm
class ApplicationInfo { var enabled=true }
class ActivityInfo { var exported=true; var enabled=true; var applicationInfo=ApplicationInfo(); var permission: String?=null }
open class PackageManager {
 var info=ActivityInfo(); var missing=false
 fun getActivityInfo(component: android.content.ComponentName, flags: Int): ActivityInfo { if(missing)throw NameNotFoundException();return info }
 class NameNotFoundException: Exception()
 companion object { const val PERMISSION_GRANTED=0 }
}''',
'Checks.kt': '''package tv.orbit.shield
import android.content.*
import android.net.Uri
fun main() {
 var checks=0
 fun pass(name: String, check: Boolean) { check(check) { name }; println("PASS|"+name); checks++ }
 val all=PlatformRegistry.all
 pass("unique persisted platform IDs", all.map { it.id }.toSet().size == all.size && all.size == 17)
 pass("existing IDs and routes preserved", all.take(4).map{it.id} == listOf("SNES","PSP","Nintendo 64","Dreamcast") && all[3].component=="com.flycast.emulator/.MainActivity")
 pass("unknown platform has no fallback launch", PlatformRegistry.find("missing") == null)
 pass("each registered component has explicit package and activity", all.all { ComponentName.unflattenFromString(it.component)!=null })
 pass("extensions are case insensitive", PlatformRegistry.find("NES")!!.accepts("demo.NES"))
 pass("PS1 exposes descriptors but never loose BIN tracks", listOf("game.cue","set.m3u").all { PlatformRegistry.find("PlayStation")!!.accepts(it) } && !PlatformRegistry.find("PlayStation")!!.accepts("track.bin"))
 pass("shared formats follow the selected platform", PlatformRegistry.find("PSP")!!.accepts("demo.iso") && PlatformRegistry.find("GameCube")!!.accepts("demo.iso") && !PlatformRegistry.find("SNES")!!.accepts("demo.iso"))
 pass("Wii-only WBFS does not leak into GameCube", PlatformRegistry.find("Wii")!!.accepts("demo.wbfs") && !PlatformRegistry.find("GameCube")!!.accepts("demo.wbfs"))
 pass("cartridge-only imports skip CD images", !PlatformRegistry.find("Mega Drive")!!.accepts("demo.chd") && !PlatformRegistry.find("PC Engine")!!.accepts("demo.cue"))
 val uri=Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ARoms/document/primary%3ARoms%2Fdemo.chd")
 val tree=Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ARoms")
 fun intent(id:String, input:Uri=uri)=PlatformRegistry.launchIntent(PlatformRegistry.find(id)!!,input,tree)
 for(spec in all) {
  val outgoing=intent(spec.id)
  pass(spec.id+" carries file data and read-only grant", outgoing.data===uri && outgoing.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0 && outgoing.flags and 2==0)
 }
 val threeDs=PlatformRegistry.find("Nintendo 3DS")!!
 pass("3DS imports launchable images and homebrew but never CIA installation packages", listOf("title.CCI","title.cxi","demo.3dsx","title.zcci","00000000.app").all { threeDs.accepts(it) } && !threeDs.accepts("install.cia") && !threeDs.accepts("mixed.zip"))
 val azahar=intent("Nintendo 3DS")
 pass("Azahar uses its exported VIEW activity with the exact content URI and no foreign parcelables", azahar.action==Intent.ACTION_VIEW && azahar.component!!.packageName=="org.azahar_emu.azahar" && azahar.component!!.className=="org.citra.citra_emu.activities.EmulationActivity" && azahar.data===uri && azahar.extras.isEmpty() && azahar.clipData==null && azahar.flags and 2==0)
 val ds=intent("Nintendo DS")
 pass("melonDS uses custom action and data, no deprecated PATH", ds.action=="me.magnum.melonds.LAUNCH_ROM" && ds.extras.isEmpty())
 val canonicalDs=PlatformRegistry.find("Nintendo DS")!!
 val retroSpec=PlatformRegistry.resolve("Nintendo DS", NdsEmulatorChoice.RETROARCH_MELON_DS_DS)!!
 pass("Nintendo DS backend selection preserves identity and import formats", retroSpec.id==canonicalDs.id && retroSpec.extensions==canonicalDs.extensions && retroSpec.titleRes==canonicalDs.titleRes)
 pass("Standalone DS selection retains its existing launch contract", PlatformRegistry.resolve("Nintendo DS",NdsEmulatorChoice.MELON_DS)===canonicalDs)
 pass("DS preference cannot change another platform", all.filter { it.id!="Nintendo DS" }.all { PlatformRegistry.resolve(it.id,NdsEmulatorChoice.RETROARCH_MELON_DS_DS)===it })
 pass("Unknown platform remains unavailable with either selected backend", PlatformRegistry.resolve("unknown", NdsEmulatorChoice.RETROARCH_MELON_DS_DS)==null)
 val retroArgs=RetroArchLaunchArguments("/storage/0123-4567/Games/DS/Game.nds", "/mnt/expand/volume/user/0/com.retroarch.aarch64/cores/melondsds_libretro_android.so",
  "/mnt/expand/volume/user/0/com.retroarch.aarch64", "/data/app/variant/base.apk", "/storage/emulated/0", "/storage/emulated/0/Android/data/com.retroarch.aarch64/files",
  "/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg", "example/.Keyboard")
 val retro=PlatformRegistry.launchIntent(retroSpec,uri,tree,retroArgs)
 pass("RetroArch routes to exported execution activity instead of its menu", retro.action==Intent.ACTION_MAIN && retro.component!!.packageName=="com.retroarch.aarch64" && retro.component!!.className=="com.retroarch.browser.retroactivity.RetroActivityFuture")
 pass("RetroArch carries exact installed core, direct ROM and configuration metadata", retro.extras==mapOf("ROM" to retroArgs.romPath,"LIBRETRO" to retroArgs.corePath,"DATADIR" to retroArgs.dataDir,"APK" to retroArgs.apkPath,"SDCARD" to retroArgs.storageRoot,"EXTERNAL" to retroArgs.externalFiles,"CONFIGFILE" to retroArgs.configFile,"IME" to retroArgs.inputMethod))
 pass("RetroArch preserves its data grant while avoiding unrelated tree access", retro.data===uri && retro.clipData==null && retro.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0 && retro.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION==0)
 pass("RetroArch launch cannot silently omit required runtime paths", runCatching { PlatformRegistry.launchIntent(retroSpec,uri,tree) }.isFailure)
 val volumes=mapOf("primary" to "/storage/emulated/0", "0123-ABCD" to "/storage/0123-ABCD")
 fun path(document:String,root:String?="primary:Games")=RetroArchPath.externalDocument("com.android.externalstorage.documents",document,root,volumes)
 pass("Trusted primary document keeps Unicode, spaces and literal percent filenames", path("primary:Games/DS/Тест 50%+.nds")=="/storage/emulated/0/Games/DS/Тест 50%+.nds")
 pass("Volume UUID uses mounted directory from the platform instead of a guessed root", path("0123-abcd:Roms/Game.nds","0123-ABCD:Roms")=="/storage/0123-ABCD/Roms/Game.nds")
 pass("Volume-root grants admit descendants", path("primary:Games/Game.nds","primary:")=="/storage/emulated/0/Games/Game.nds")
 pass("Unknown providers cannot be interpreted as local filesystem IDs", RetroArchPath.externalDocument("downloads.example","primary:Games/Game.nds","primary:Games",volumes)==null)
 pass("Unmounted or unknown volumes cannot become fabricated filesystem paths", path("MISSING:Games/Game.nds","MISSING:Games")==null)
 pass("Tree containment rejects sibling-prefix and different-volume documents", path("primary:Games2/Game.nds")==null && path("0123-ABCD:Games/Game.nds")==null)
 pass("Traversal, absolute document paths and malformed IDs are rejected", listOf("primary:Games/../outside.nds", "primary:/Games/Game.nds", "primary:Games/./Game.nds", "primary:Games//Game.nds", "primary", ":Games/Game.nds", "primary:").all { path(it)==null })
 pass("Direct path selection is confined to enumerated storage roots", RetroArchPath.storedFile("/storage/emulated/0/Games/../Game.nds",volumes)=="/storage/emulated/0/Game.nds" && RetroArchPath.storedFile("/storage/emulated/01/Game.nds",volumes)==null && RetroArchPath.storedFile("/data/private/Game.nds",volumes)==null && RetroArchPath.storedFile("relative/Game.nds",volumes)==null)
 val ps=intent("PlayStation")
 pass("DuckStation path SAF bootPath and content grant agree without save-state override", ps.extras==mapOf("bootPath" to "/android_content/com.android.externalstorage.documents/tree@primary/Roms/document@primary/Roms/demo.chd") && ps.data===uri && ps.clipData?.uri===tree && ps.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION!=0)
 for(ext in listOf("cue","m3u")) {
  val disc=intent("PlayStation", Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ARoms/document/primary%3ARoms%2Fgame.$ext"))
  pass("DuckStation $ext has read-only sibling tree grant", disc.clipData?.uri===tree && disc.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION!=0 && disc.flags and 2==0)
 }
 for(id in listOf("GameCube","Wii")) {
  val dolphin=intent(id)
  pass(id+" uses exported TV startup with no directory ClipData", dolphin.action==Intent.ACTION_MAIN && dolphin.component!!.className.endsWith(".ui.main.TvMainActivity") && dolphin.clipData==null && dolphin.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION==0)
 }
 for(ext in listOf("chd","pbp")) {
  val singleUri=Uri.parse("content://com.android.externalstorage.documents/document/primary%3ARoms%2Fdemo.$ext")
  val single=PlatformRegistry.launchIntent(PlatformRegistry.find("PlayStation")!!,singleUri)
  pass("DuckStation single $ext launches exact document without an invented tree",single.extras["bootPath"]=="/android_content/com.android.externalstorage.documents/document@primary/Roms/demo.$ext" && single.data===singleUri && single.clipData==null && single.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION==0 && single.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
 }
 val gdi=intent("Dreamcast", Uri.parse("content://provider/document/primary%3ARoms%2Fdemo.gdi"))
 pass("Flycast GDI retains sibling folder grant", gdi.clipData?.uri===tree && gdi.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION!=0)
 pass("Flycast single-file CHD needs no sibling folder grant", intent("Dreamcast").clipData==null)
 val raw=intent("Dreamcast",Uri.parse("/storage/emulated/0/Android/data/com.flycast.emulator/files/test.cdi"))
 pass("existing Flycast private raw path stays unchanged", raw.data!!.toString().endsWith("/test.cdi") && raw.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION==0)
 val context=Context(); val spec=all[0]
 pass("available exported activity accepted", PlatformRegistry.unavailableReason(context,spec)==null)
 context.packageManager.missing=true
 pass("missing emulator gets a message", PlatformRegistry.unavailableReason(context,spec)!=null)
 context.packageManager.missing=false;context.packageManager.info.exported=false
 pass("nonexported emulator gets a message", PlatformRegistry.unavailableReason(context,spec)!=null)
 context.packageManager.info.exported=true;context.packageManager.info.applicationInfo.enabled=false
 pass("disabled emulator gets a message", PlatformRegistry.unavailableReason(context,spec)!=null)
 context.packageManager.info.applicationInfo.enabled=true;context.packageManager.info.permission="denied";context.permissionResult=-1
 pass("protected emulator gets a message", PlatformRegistry.unavailableReason(context,spec)!=null)
 println("TOTAL|"+checks)
}'''
}
ids = sorted(set(re.findall(r'R\.string\.(\w+)', source.read_text())))
stubs['R.kt'] = 'package tv.orbit.shield\nobject R { object string {\n' + ''.join(f'const val {name}={i+1}\n' for i,name in enumerate(ids)) + '} }\n'
library_options = source.with_name('LibraryOptions.kt').read_text()
stubs['NdsChoice.kt'] = 'package tv.orbit.shield\n' + library_options[library_options.index('internal enum class NdsEmulatorChoice'):library_options.index('internal data class LibraryOptions')]
report = {'timestamp_utc': datetime.now(timezone.utc).isoformat(), 'scope': __doc__, 'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(), 'reproduce': ['python3', str(Path(__file__).resolve())]}
with tempfile.TemporaryDirectory(prefix='gostex-platform-check-') as tmp:
    folder=Path(tmp)
    for name,text in stubs.items(): (folder/name).write_text(text)
    out=folder/'classes'
    command=[str(java),'-cp',':'.join(map(str,compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(compiler[1]),'-d',str(out),str(source),str(source.with_name('DuckStationPath.kt')),str(source.with_name('RetroArchPath.kt'))] + [str(folder/name) for name in stubs]
    compile_result=subprocess.run(command,text=True,capture_output=True)
    report['compile']={'returncode':compile_result.returncode,'stdout':compile_result.stdout,'stderr':compile_result.stderr}
    if compile_result.returncode==0:
        result=subprocess.run([str(java),'-cp',str(out)+':'+str(compiler[1]),'tv.orbit.shield.ChecksKt'],text=True,capture_output=True)
        report['run']={'returncode':result.returncode,'stdout':result.stdout,'stderr':result.stderr}
        report['checks']=[line.removeprefix('PASS|') for line in result.stdout.splitlines() if line.startswith('PASS|')]
        report['passed']=result.returncode==0
    else: report['passed']=False
path=root/'.reports/platform-registry-checks.json'
path.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'passed':report['passed'],'checks':len(report.get('checks',[])),'report':str(path)}))
if not report['passed']:
    print(report)
    raise SystemExit(1)
