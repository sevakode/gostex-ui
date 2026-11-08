package tv.orbit.shield

import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

internal class EmulatorApkIncompatible : Exception()

/** A small allowlist of developers that publish stable, standalone Android APKs. */
internal object OfficialEmulatorApk {
    private data class Source(val repo: String, val asset: Regex)
    private val sources = mapOf(
        "com.brouken.player" to Source("moneytoo/Player", Regex("Just\\.Player\\.v[0-9]+(?:\\.[0-9]+)+\\.apk")),
        "me.magnum.melonds" to Source("rafaelvcaetano/melonDS-android", Regex("app-gitHub-prod-release\\.apk")),
        "com.flycast.emulator" to Source("flyinghead/flycast", Regex("flycast-[0-9][A-Za-z0-9._-]*\\.apk")),
        "org.azahar_emu.azahar" to Source("azahar-emu/azahar", Regex("azahar-android-vanilla-[A-Za-z0-9._-]+\\.apk"))
    )
    private const val MAX_BYTES = 160L * 1024 * 1024
    private val hosts = setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    fun supports(pkg: String) = pkg in sources
    private fun temporaryFile(activity: MainActivity) = File(File(activity.cacheDir, "emulator-install"), "emulator.apk")
    fun discard(activity: MainActivity) { temporaryFile(activity).delete() }
    private fun open(url: String): HttpsURLConnection {
        var next = url
        repeat(6) {
            val uri = URI(next)
            require(uri.scheme == "https" && uri.host?.lowercase() in hosts && uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443))
            val connection = URL(next).openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000; connection.readTimeout = 30000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "GOSTEX")
            val status = connection.responseCode
            if (status in 300..399) {
                next = URL(URL(next), connection.getHeaderField("Location") ?: error("Missing redirect")).toString()
                connection.disconnect()
            } else { require(status == 200); return connection }
        }
        error("Too many redirects")
    }
    suspend fun download(activity: MainActivity, pkg: String): File {
        val source = sources[pkg] ?: error("Unknown source")
        val api = open("https://api.github.com/repos/${source.repo}/releases/latest")
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
        val release = JSONObject(json)
        require(!release.optBoolean("draft") && !release.optBoolean("prerelease"))
        val tag = release.getString("tag_name")
        require(tag.matches(Regex("[A-Za-z0-9._-]{1,80}")))
        val array = release.getJSONArray("assets")
        val matches = (0 until array.length()).map { array.getJSONObject(it) }.filter { source.asset.matches(it.optString("name")) }
        require(matches.size == 1)
        val asset = matches.single(); val name = asset.getString("name")
        val size = asset.getLong("size"); require(size in 1..MAX_BYTES)
        val expected = asset.getString("digest"); require(expected.matches(Regex("sha256:[a-fA-F0-9]{64}")))
        val url = asset.getString("browser_download_url"); val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "github.com" && uri.rawQuery == null &&
            uri.path == "/${source.repo}/releases/download/$tag/$name")
        val file = temporaryFile(activity).also { it.parentFile?.mkdirs() }
        val conn = open(url)
        try {
            val hash = MessageDigest.getInstance("SHA-256"); var total = 0L
            conn.inputStream.use { input -> file.outputStream().use { output ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf); if (n < 0) break
                    total += n; require(total <= size && total <= MAX_BYTES)
                    hash.update(buf, 0, n); output.write(buf, 0, n)
                }
            } }
            require(total == size && hash.digest().joinToString("") { "%02x".format(it) } == expected.removePrefix("sha256:").lowercase())
            val info = activity.packageManager.getPackageArchiveInfo(file.path, 0) ?: error("Invalid APK")
            require(info.packageName == pkg)
            if ((info.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) > Build.VERSION.SDK_INT) throw EmulatorApkIncompatible()
            val abis = ZipFile(file).use { zip ->
                zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }
                    .mapNotNull { it.split('/').getOrNull(1) }.toSet()
            }
            if (abis.isNotEmpty() && Build.SUPPORTED_ABIS.none { it in abis }) throw EmulatorApkIncompatible()
            return file
        } catch (e: Exception) { file.delete(); throw e } finally { conn.disconnect() }
    }
}
