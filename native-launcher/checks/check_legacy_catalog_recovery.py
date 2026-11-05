"""Test recovery orchestration with fake fetches; the real signed client has separate tests."""
import hashlib
from pathlib import Path
import subprocess
import tempfile
import zipfile
from kotlin_toolchain import ROOT, JAVA, COMPILER

package = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
context = '''package android.content
import java.io.File
import java.io.InputStream
abstract class Context {
 companion object { const val MODE_PRIVATE = 0 }
 abstract val filesDir: File
 abstract val cacheDir: File
 abstract val assets: Assets
 abstract fun getSharedPreferences(name: String, mode: Int): Preferences
}
interface Preferences { val all: Map<String, *> }
interface Assets { fun open(name: String): InputStream }
'''
atomic = '''package android.util
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
class AtomicFile(val baseFile: File) {
 fun openRead(): FileInputStream = FileInputStream(baseFile)
 fun startWrite(): FileOutputStream = FileOutputStream(File(baseFile.path + ".new"))
 fun finishWrite(out: FileOutputStream) { out.close(); check(File(baseFile.path + ".new").renameTo(baseFile)) }
 fun failWrite(out: FileOutputStream) { out.close(); File(baseFile.path + ".new").delete() }
}
'''
signed = '''package dev.gostex.release
import java.io.File
data class Release(val versionCode: Long, val url: String, val size: Long)
object FakeSigned { lateinit var source: File; var code = 100004L; var url = "https://github.com/sevakode/gostex-ui/releases/download/build-4/gostex-android-arm64-0.33.4.apk"; var size = 100L; var downloads = 0 }
class SignedReleaseClient(val transferCheckpoint: () -> Unit = {}) {
 fun check(feed: String, platform: String, code: Long): Release? {
  transferCheckpoint()
  check(feed.endsWith("/build-4/android-arm64.properties") && platform == "android-arm64" && code == 0L)
  return Release(FakeSigned.code, FakeSigned.url, FakeSigned.size)
 }
 fun download(release: Release, destination: File): File { FakeSigned.downloads++; FakeSigned.source.copyTo(destination, overwrite = true); transferCheckpoint(); return destination }
}
'''
checks = r'''package tv.orbit.shield
import android.content.*
import dev.gostex.release.FakeSigned
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
data class Game(val id: String, val tree: String, val files: List<String>)
object LibraryImport { fun decode(text: String): List<Game> = if (text == "synthetic legacy") listOf(Game("id", "primary:qa", listOf("qa.rom"))) else error("bad") }
class TestContext(override val filesDir: File, override val cacheDir: File): Context() {
 override val assets = object: Assets { override fun open(name: String) = ByteArrayInputStream("empty".toByteArray()) }
 override fun getSharedPreferences(name: String, mode: Int) = object: Preferences { override val all = emptyMap<String, Any>() }
}
fun main(args: Array<String>) = runBlocking {
 val dir = File(args[0]); val files = File(dir,"files").apply { mkdirs() }; val cache = File(dir,"cache").apply { mkdirs() }
 val ctx = TestContext(files, cache); val apk = File(dir,"valid.apk"); val digest = args[1]
 FakeSigned.source = apk
 LegacyCatalogRecovery.restore(ctx, expectedCatalogSha256 = digest)
 val target = File(files, "legacy-catalog.json")
 check(target.readText() == "synthetic legacy" && FakeSigned.downloads == 1 && cache.listFiles()!!.isEmpty())
 LegacyCatalogRecovery.restore(ctx, expectedCatalogSha256 = digest)
 check(FakeSigned.downloads == 1 && cache.listFiles()!!.isEmpty())
 target.writeText("corrupt")
 check(runCatching { LegacyCatalogRecovery.restore(ctx, expectedCatalogSha256 = digest) }.isFailure)
 check(target.readText() == "corrupt" && FakeSigned.downloads == 1 && cache.listFiles()!!.isEmpty())
 target.delete(); val count = FakeSigned.downloads
 FakeSigned.code = 42
 check(runCatching { LegacyCatalogRecovery.restore(ctx, expectedCatalogSha256 = digest) }.isFailure)
 check(FakeSigned.downloads == count && !target.exists() && cache.listFiles()!!.isEmpty())
 FakeSigned.code = 100004; FakeSigned.url = "https://example.invalid/other.apk"
 check(runCatching { LegacyCatalogRecovery.restore(ctx, expectedCatalogSha256 = digest) }.isFailure)
 check(FakeSigned.downloads == count && !target.exists() && cache.listFiles()!!.isEmpty())
 FakeSigned.url = "https://github.com/sevakode/gostex-ui/releases/download/build-4/gostex-android-arm64-0.33.4.apk"
 FakeSigned.size = 64L * 1024 * 1024 + 1
 check(runCatching { LegacyCatalogRecovery.restore(ctx, expectedCatalogSha256 = digest) }.isFailure)
 check(FakeSigned.downloads == count && cache.listFiles()!!.isEmpty())
 FakeSigned.size = 100
 var calls = 0
 check(runCatching { LegacyCatalogRecovery.restore(ctx, expectedCatalogSha256 = digest, networkCheckpoint = { if (++calls == 3) error("network became metered") }) }.isFailure)
 check(!target.exists() && cache.listFiles()!!.isEmpty())
 check(runCatching { LegacyCatalogRecovery.restore(ctx, HistoricalPackageFetcher { error("offline") }, digest) }.isFailure)
 check(!target.exists() && cache.listFiles()!!.isEmpty())
 val started = CountDownLatch(1)
 val job = launch { LegacyCatalogRecovery.restore(ctx, HistoricalPackageFetcher { started.countDown(); Thread.sleep(30000) }, digest) }
 withContext(Dispatchers.IO) { check(started.await(5, TimeUnit.SECONDS)) }
 job.cancelAndJoin()
 check(!target.exists() && cache.listFiles()!!.isEmpty())
 println("PASS: strict release identity, atomic adoption, failure and cancellation cleanup")
}
'''

with tempfile.TemporaryDirectory(prefix='gostex-catalog-recovery-') as temp:
    directory = Path(temp)
    payload = b'synthetic legacy'
    with zipfile.ZipFile(directory / 'valid.apk', 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('assets/catalog.json', payload)
    for name, text in [('Context.kt', context), ('AtomicFile.kt', atomic), ('Signed.kt', signed), ('Checks.kt', checks)]:
        (directory / name).write_text(text)
    cp = ':'.join(map(str, COMPILER))
    runtime = ':'.join(map(str, [COMPILER[1], COMPILER[4], COMPILER[5]]))
    sources = [package / name for name in ('LegacyCatalogArchive.kt', 'LegacyCatalogBridge.kt', 'LegacyCatalogRecovery.kt')]
    subprocess.run([str(JAVA), '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                    '-classpath', runtime, '-d', str(directory / 'classes'), *map(str, sources), *map(str, directory.glob('*.kt'))], check=True)
    subprocess.run([str(JAVA), '-cp', str(directory / 'classes') + ':' + runtime, 'tv.orbit.shield.ChecksKt',
                    str(directory), hashlib.sha256(payload).hexdigest()], check=True, timeout=20)
