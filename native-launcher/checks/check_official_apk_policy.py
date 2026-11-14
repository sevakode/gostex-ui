"""Exercise real APK release policy and staging/status code without network or Android devices."""
from pathlib import Path
import subprocess
import tempfile
from kotlin_toolchain import ROOT, JAVA, COMPILER, CACHE

candidates = list((CACHE / "org.json/json").glob("*/*/*.jar"))
candidates += list((Path.home() / ".gradle/caches/modules-2/files-2.1/org.json/json").glob("*/*/*.jar"))
jsonjar = next(p for p in candidates if not p.name.endswith(("-sources.jar", "-javadoc.jar")))
base = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield"

packages = r'''package android.content.pm
class Signature(private val bytes:ByteArray) { fun toByteArray()=bytes }
class SigningInfo(val apkContentsSigners:Array<Signature>)
class ApplicationInfo(val minSdkVersion:Int=17)
class PackageInfo(val packageName:String,val longVersionCode:Long=1,val signingInfo:SigningInfo?=null,
                  val applicationInfo:ApplicationInfo?=ApplicationInfo())
class PackageManager {
 companion object { const val GET_SIGNING_CERTIFICATES=0x08000000 }
 class NameNotFoundException:Exception()
 var installed:PackageInfo?=null
 var inaccessible=false
 fun getPackageInfo(pkg:String,flags:Int):PackageInfo {
  if(inaccessible)error("Cannot inspect package")
  return installed?.takeIf { it.packageName==pkg } ?: throw NameNotFoundException()
 }
 fun getPackageArchiveInfo(path:String,flags:Int):PackageInfo?=null
}
'''
checks = r'''package tv.orbit.shield
import android.content.pm.*
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.*
fun main(args:Array<String>)=runBlocking {
 val pkg=OfficialEmulatorApk.VLC_PACKAGE
 val pin=OfficialApkPolicy.sources.getValue(pkg).pin!!
 check(pkg=="tv.gostex.vlc" && pin.tag=="vlc-gostex-3.7.2-1" && pin.name=="VLC-GOSTEX-3.7.2-android.apk")
 check(pin.bytes==122074150L && pin.versionCode==13070200L)
 check(pin.sha256=="181a4a2b38afa7ef72a750ccb631fa277344456b6979b9cebb3b9dcd64fc54f9")
 check(pin.certificateSha256=="9340af47ef933a86522ef340fd33045a874deafe6ddbd02049d3d42a2f6e74c8")
 check(OfficialApkPolicy.MAX_BYTES==160L*1024*1024)
 check(OfficialApkPolicy.releaseUrl(pkg)=="https://api.github.com/repos/sevakode/gostex-ui/releases/tags/${pin.tag}")
 val latest=mapOf("com.brouken.player" to ("moneytoo/Player" to "Just.Player.v0.200.apk"),
  "me.magnum.melonds" to ("rafaelvcaetano/melonDS-android" to "app-gitHub-prod-release.apk"),
  "com.flycast.emulator" to ("flyinghead/flycast" to "flycast-2.5.apk"),
  "org.azahar_emu.azahar" to ("azahar-emu/azahar" to "azahar-android-vanilla-2121.1.apk"))
 fun asset(repo:String="sevakode/gostex-ui",tag:String=pin.tag,name:String=pin.name,size:Long=pin.bytes,hash:String=pin.sha256)=
  JSONObject().put("name",name).put("size",size).put("digest","sha256:$hash")
   .put("browser_download_url","https://github.com/$repo/releases/download/$tag/$name")
 fun release(vararg assets:JSONObject,tag:String=pin.tag,pre:Boolean=true,draft:Boolean=false)=
  JSONObject().put("tag_name",tag).put("prerelease",pre).put("draft",draft).put("assets",JSONArray(assets.toList())).toString()
 val result=OfficialApkPolicy.artifact(pkg,release(asset()))
 check(result.bytes==pin.bytes && result.sha256==pin.sha256)
 for((name,value)in latest) {
  check(OfficialEmulatorApk.supports(name) && OfficialApkPolicy.sources.getValue(name).pin==null)
  val(repo,filename)=value
  check(OfficialApkPolicy.releaseUrl(name)=="https://api.github.com/repos/$repo/releases/latest")
  check(OfficialApkPolicy.artifact(name,release(asset(repo,"v1",filename,123,"a".repeat(64)),tag="v1",pre=false)).bytes==123L)
  check(runCatching { OfficialApkPolicy.artifact(name,release(asset(repo,"v1",filename,123,"a".repeat(64)),tag="v1")) }.isFailure)
 }
 fun rejected(json:String)=check(runCatching { OfficialApkPolicy.artifact(pkg,json) }.isFailure)
 rejected(release(asset(),draft=true))
 rejected(release(asset(),tag="latest"))
 rejected(release(asset(),tag="../${pin.tag}"))
 rejected(release(asset(name="VLC-GOSTEX-3.7.2-other.apk")))
 rejected(release(asset(),asset()))
 rejected(release(asset(size=0)))
 rejected(release(asset(size=pin.bytes-1)))
 rejected(release(asset(size=OfficialApkPolicy.MAX_BYTES+1)))
 rejected(release(asset(hash="a".repeat(64))))
 rejected(release(asset(hash="invalid")))
 rejected(release(asset(repo="someone/fork")))
 for(url in listOf("http://github.com/release","https://github.com.evil.example/file","https://user@github.com/file",
  "https://github.com:444/file","https://example.com/file","https://github.com/file#fragment","file:///tmp/file","not a URL")) {
  check(!OfficialApkPolicy.allowedUrl(url))
  rejected(release(asset().put("browser_download_url",url)))
 }
 for(url in listOf("https://github.com/file","https://api.github.com/repos/x/y","https://release-assets.githubusercontent.com/file?token=abc",
  "https://objects.githubusercontent.com/file","https://github.com:443/file")) check(OfficialApkPolicy.allowedUrl(url))
 val canonical=result.url
 for(url in listOf("$canonical?download=1","$canonical#ignored",canonical.replace("/download/","/latest/download/"),canonical.replace("https:","http:")))
  rejected(release(asset().put("browser_download_url",url)))
 check(runCatching { OfficialApkPolicy.artifact("unknown",release(asset())) }.isFailure)
 check(OfficialApkPolicy.installedState(pkg,null)==OfficialApkPolicy.InstalledState.MISSING)
 check(OfficialApkPolicy.installedState(pkg,pin.versionCode,listOf(pin.certificateSha256))==OfficialApkPolicy.InstalledState.CURRENT)
 check(OfficialApkPolicy.installedState(pkg,pin.versionCode+1,listOf(pin.certificateSha256))==OfficialApkPolicy.InstalledState.CURRENT)
 check(OfficialApkPolicy.installedState(pkg,pin.versionCode-1,listOf(pin.certificateSha256))==OfficialApkPolicy.InstalledState.UPDATE_AVAILABLE)
 for(signers in listOf(emptyList(),listOf("b".repeat(64)),listOf(pin.certificateSha256,pin.certificateSha256)))
  check(OfficialApkPolicy.installedState(pkg,pin.versionCode,signers)==OfficialApkPolicy.InstalledState.UNTRUSTED)
 check(OfficialApkPolicy.installedState(pkg,-1,listOf(pin.certificateSha256))==OfficialApkPolicy.InstalledState.UNTRUSTED)

 val activity=MainActivity(File(args[0]))
 check(OfficialEmulatorApk.installStatus(activity,pkg)==OfficialEmulatorApk.InstallStatus.MISSING)
 activity.packageManager.installed=PackageInfo(pkg,pin.versionCode,SigningInfo(arrayOf(Signature(byteArrayOf(1,2,3)))))
 check(OfficialEmulatorApk.installStatus(activity,pkg)==OfficialEmulatorApk.InstallStatus.UNTRUSTED)
 val foreign=activity.packageManager.installed
 val refused=OfficialEmulatorApk.createAttempt(activity)
 check(runCatching { OfficialEmulatorApk.download(activity,pkg,refused) }.isFailure)
 check(!refused.exists() && activity.packageManager.installed===foreign)
 activity.packageManager.inaccessible=true
 check(OfficialEmulatorApk.installStatus(activity,pkg)==OfficialEmulatorApk.InstallStatus.UNTRUSTED)
 activity.packageManager.inaccessible=false
 val first=OfficialEmulatorApk.createAttempt(activity)
 first.writeText("active");first.setLastModified(System.currentTimeMillis()-3L*24*60*60*1000)
 val orphan=File(first.parentFile,"emulator-${UUID.randomUUID()}.apk").apply { writeText("orphan");setLastModified(first.lastModified()) }
 val unrelated=File(first.parentFile,"user.apk").apply { writeText("keep");setLastModified(first.lastModified()) }
 val second=OfficialEmulatorApk.createAttempt(activity)
 check(first!=second && first.exists() && second.exists() && !orphan.exists() && unrelated.exists())
 OfficialEmulatorApk.discard(activity,first)
 check(!first.exists() && second.exists() && unrelated.exists())
 check(OfficialEmulatorApk.restoreAttempt(activity,"../user.apk")==null)
 check(OfficialEmulatorApk.restoreAttempt(activity,"emulator-------------------------------------.apk")==null)
 check(OfficialEmulatorApk.restoreAttempt(activity,second.name)==null) // Empty/incomplete file cannot be restored.
 second.writeText("staged")
 check(OfficialEmulatorApk.restoreAttempt(activity,second.name)==second)
 val restored=File(second.parentFile,"emulator-${UUID.randomUUID()}.apk").apply { writeText("restored");setLastModified(System.currentTimeMillis()-3L*24*60*60*1000) }
 check(OfficialEmulatorApk.restoreAttempt(activity,restored.name)==restored)
 val extra=OfficialEmulatorApk.createAttempt(activity)
 check(restored.exists())
 OfficialEmulatorApk.discard(activity,extra);OfficialEmulatorApk.discard(activity,restored)
 val oversized=File(second.parentFile,"emulator-${UUID.randomUUID()}.apk")
 RandomAccessFile(oversized,"rw").use { it.setLength(OfficialApkPolicy.MAX_BYTES+1) }
 check(OfficialEmulatorApk.restoreAttempt(activity,oversized.name)==null)
 oversized.delete()
 OfficialEmulatorApk.discard(activity,unrelated);check(unrelated.exists())
 val failed=OfficialEmulatorApk.createAttempt(activity)
 check(runCatching { OfficialEmulatorApk.download(activity,"unknown",failed) }.isFailure)
 check(!failed.exists() && second.exists())
 val cancelledTarget=OfficialEmulatorApk.createAttempt(activity)
 val cancelled=launch {
  currentCoroutineContext().cancel()
  OfficialEmulatorApk.download(activity,pkg,cancelledTarget)
 }
 cancelled.join()
 check(cancelled.isCancelled && !cancelledTarget.exists() && second.exists())
 OfficialEmulatorApk.discard(activity,second)
 check(!second.exists() && unrelated.exists())
 println("PASS: exact pinned player/stable upstream descriptors, URL/tag/size/digest/signer constraints, installed status, scoped staging/restore/stale cleanup and cancellation")
}
'''

with tempfile.TemporaryDirectory(prefix="gostex-official-apk-") as td:
    folder = Path(td)
    bodies = {
        "Packages.kt": packages,
        "Build.kt": "package android.os\nobject Build { object VERSION { const val SDK_INT=28 }; val SUPPORTED_ABIS=arrayOf(\"arm64-v8a\") }\n",
        "Activity.kt": "package tv.orbit.shield\nclass MainActivity(val cacheDir:java.io.File) { val packageManager=android.content.pm.PackageManager() }\n",
        "Checks.kt": checks,
    }
    for name, body in bodies.items():
        (folder / name).write_text(body)
    cp = ":".join(map(str, [COMPILER[1], COMPILER[4], jsonjar]))
    command = [str(JAVA), "-cp", ":".join(map(str, COMPILER)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
               "-no-stdlib", "-no-reflect", "-classpath", cp, "-d", str(folder / "classes"),
               str(base / "OfficialEmulatorApk.kt"), str(base / "OfficialApkPolicy.kt")]
    command += [str(folder / name) for name in bodies]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode:
        raise SystemExit(result.stderr)
    cache = folder / "cache"
    cache.mkdir()
    result = subprocess.run([str(JAVA), "-cp", f"{folder / 'classes'}:{cp}", "tv.orbit.shield.ChecksKt", str(cache)], capture_output=True, text=True)
    print(result.stdout, end="")
    if result.returncode:
        raise SystemExit(result.stderr)
