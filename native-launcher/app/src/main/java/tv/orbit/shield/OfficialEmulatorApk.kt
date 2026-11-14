package tv.orbit.shield

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.os.Build
import kotlinx.coroutines.ensureActive
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

internal class EmulatorApkIncompatible : Exception()

/** One on-demand transfer path for stable upstream APKs and the independently pinned GOSTEX player. */
internal object OfficialEmulatorApk {
    const val VLC_PACKAGE = OfficialApkPolicy.VLC_PACKAGE
    enum class InstallStatus { MISSING, CURRENT, UPDATE_AVAILABLE, UNTRUSTED }
    private const val STALE_MS = 24L * 60 * 60 * 1000
    private val attempts = ConcurrentHashMap.newKeySet<String>()
    private val filename = Regex("emulator-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.apk")
    fun supports(pkg: String) = pkg in OfficialApkPolicy.sources
    private fun directory(context: Context) = File(context.cacheDir, "emulator-install")
    private fun owned(context: Context, file: File): Boolean =
        filename.matches(file.name) && file.canonicalFile.parentFile == directory(context).canonicalFile && file.canonicalFile.name == file.name

    /** Recover a staged installer handoff using a managed filename, never an arbitrary saved path. */
    fun restoreAttempt(context: Context, name: String?): File? {
        if (name == null || !filename.matches(name)) return null
        return runCatching { File(directory(context), name).takeIf {
            owned(context, it) && it.isFile && it.length() in 1..OfficialApkPolicy.MAX_BYTES
        }?.also { attempts += it.absolutePath } }.getOrNull()
    }

    /** Allocate before withContext(IO), so cancellation during result delivery cannot lose the file. */
    fun createAttempt(context: Context): File {
        val dir = directory(context).apply { require(isDirectory || mkdirs()) }
        val now = System.currentTimeMillis()
        dir.listFiles()?.take(64)?.forEach { old ->
            if (owned(context, old) && old.isFile && old.absolutePath !in attempts && old.lastModified() > 0 && now - old.lastModified() > STALE_MS) old.delete()
        }
        return File(dir, "emulator-${UUID.randomUUID()}.apk").also {
            require(it.createNewFile())
            attempts += it.absolutePath
        }
    }

    /** Remove only this caller's staging file; an older Activity must never delete a new attempt. */
    fun discard(context: Context, file: File?) {
        if (file != null && owned(context, file)) {
            file.delete()
            attempts -= file.absolutePath
        }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun signers(info: PackageInfo): List<String> = info.signingInfo?.apkContentsSigners?.map { digest(it.toByteArray()) }.orEmpty()

    @Suppress("DEPRECATION")
    fun installStatus(context: Context, pkg: String): InstallStatus {
        val source = OfficialApkPolicy.sources[pkg] ?: return InstallStatus.UNTRUSTED
        return try {
            val info = context.packageManager.getPackageInfo(pkg, if (source.pin != null) PackageManager.GET_SIGNING_CERTIFICATES else 0)
            InstallStatus.valueOf(OfficialApkPolicy.installedState(pkg, info.longVersionCode, if (source.pin != null) signers(info) else emptyList()).name)
        } catch (_: PackageManager.NameNotFoundException) { InstallStatus.MISSING }
        catch (_: Exception) { InstallStatus.UNTRUSTED }
    }

    private suspend fun open(url: String): HttpsURLConnection {
        var next = url
        repeat(6) {
            coroutineContext.ensureActive()
            require(OfficialApkPolicy.allowedUrl(next))
            val connection = URL(next).openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000; connection.readTimeout = 30000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "GOSTEX")
            try {
                val status = connection.responseCode
                coroutineContext.ensureActive()
                if (status in 300..399) {
                    next = URL(URL(next), connection.getHeaderField("Location") ?: error("Missing redirect")).toString()
                    connection.disconnect()
                } else { require(status == 200); return connection }
            } catch (error: Throwable) { connection.disconnect(); throw error }
        }
        error("Too many redirects")
    }
    @Suppress("DEPRECATION")
    suspend fun download(context: Context, pkg: String, target: File = createAttempt(context)): File {
        require(owned(context, target) && target.absolutePath in attempts && target.isFile && target.length() == 0L)
        try {
            coroutineContext.ensureActive()
            val source = OfficialApkPolicy.sources[pkg] ?: error("Unknown source")
            if (source.pin != null) require(installStatus(context, pkg) != InstallStatus.UNTRUSTED)
            val api = open(OfficialApkPolicy.releaseUrl(pkg))
            val json = try {
                api.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf); if (n < 0) break
                        require(out.size() + n <= 1024 * 1024); out.write(buf, 0, n)
                    }
                    out.toString("UTF-8")
                }
            } finally { api.disconnect() }
            val asset = OfficialApkPolicy.artifact(pkg, json)
            val conn = open(asset.url)
            try {
                val hash = MessageDigest.getInstance("SHA-256"); var total = 0L
                conn.inputStream.use { input -> target.outputStream().use { output ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf); if (n < 0) break
                        total += n; require(total <= asset.bytes && total <= OfficialApkPolicy.MAX_BYTES)
                        hash.update(buf, 0, n); output.write(buf, 0, n)
                    }
                } }
                coroutineContext.ensureActive()
                require(total == asset.bytes && hash.digest().joinToString("") { "%02x".format(it) } == asset.sha256)
                val info = context.packageManager.getPackageArchiveInfo(target.path, if (source.pin != null) PackageManager.GET_SIGNING_CERTIFICATES else 0) ?: error("Invalid APK")
                require(info.packageName == pkg)
                source.pin?.let { pin ->
                    require(info.longVersionCode == pin.versionCode && OfficialApkPolicy.trustedSigners(pin, signers(info)))
                    require(installStatus(context, pkg) != InstallStatus.UNTRUSTED)
                }
                if ((info.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) > Build.VERSION.SDK_INT) throw EmulatorApkIncompatible()
                val abis = ZipFile(target).use { zip ->
                    zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }
                        .mapNotNull { it.split('/').getOrNull(1) }.toSet()
                }
                if (abis.isNotEmpty() && Build.SUPPORTED_ABIS.none { it in abis }) throw EmulatorApkIncompatible()
                coroutineContext.ensureActive()
                return target
            } finally { conn.disconnect() }
        } catch (error: Throwable) { runCatching { discard(context, target) }; throw error }
    }
}
