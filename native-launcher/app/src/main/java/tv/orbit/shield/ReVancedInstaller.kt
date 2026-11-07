package tv.orbit.shield

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

/** Downloads the independent Manager Plus project release on request. No background work. */
internal object ReVancedInstaller {
    const val PACKAGE = "com.revanced.net.revancedmanager"
    private const val MAX_BYTES = 100L * 1024 * 1024
    private const val BASE = "https://api.github.com/repos/vancedto/vanced-manager-plus/releases"
    private val hosts = setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    private fun open(url: String): HttpsURLConnection {
        var next = url
        repeat(6) {
            val u = URI(next)
            require(u.scheme == "https" && u.host?.lowercase() in hosts && u.rawUserInfo == null && (u.port == -1 || u.port == 443))
            val connection = URL(next).openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000; connection.readTimeout = 30000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "GOSTEX")
            val code = connection.responseCode
            if (code in 300..399) {
                next = URL(URL(next), connection.getHeaderField("Location") ?: error("Missing redirect")).toString()
                connection.disconnect()
            } else {
                require(code == 200); return connection
            }
        }
        error("Too many redirects")
    }
    suspend fun download(activity: MainActivity): File {
        val api = open("$BASE/latest")
        val json = try {
            api.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf); if (n < 0) break
                    require(out.size() + n <= 1024 * 1024); out.write(buf, 0, n)
                }
                out.toString("UTF-8")
            }
        } finally { api.disconnect() }
        val release = JSONObject(json)
        val tag = release.getString("tag_name")
        require(tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+(?:-dev\\.[0-9]+)?")))
        val assets = release.getJSONArray("assets")
        val matches = (0 until assets.length()).map { assets.getJSONObject(it) }.filter {
            it.optString("name") == "vanced.to_revanced_manager_plus_${tag}.apk"
        }
        require(matches.size == 1)
        val asset = matches.single()
        val bytes = asset.getLong("size"); require(bytes in 1..MAX_BYTES)
        val expected = asset.getString("digest").removePrefix("sha256:").lowercase()
        require(expected.matches(Regex("[a-f0-9]{64}")))
        val url = asset.getString("browser_download_url")
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "github.com" && uri.rawQuery == null && uri.path == "/vancedto/vanced-manager-plus/releases/download/$tag/${asset.getString("name")}")
        val file = File(File(activity.cacheDir, "revanced-install").apply { mkdirs() }, "revanced-manager.apk")
        val conn = open(url)
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            var total = 0L
            conn.inputStream.use { input -> file.outputStream().use { output ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf); if (n < 0) break
                    total += n; require(total <= bytes && total <= MAX_BYTES)
                    hash.update(buf, 0, n); output.write(buf, 0, n)
                }
            } }
            require(total == bytes && hash.digest().joinToString("") { "%02x".format(it) } == expected)
            val info = activity.packageManager.getPackageArchiveInfo(file.path, 0) ?: error("Invalid APK")
            require(info.packageName == PACKAGE && (info.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= android.os.Build.VERSION.SDK_INT)
            return file
        } catch (e: Exception) { file.delete(); throw e } finally { conn.disconnect() }
    }
}

@Composable internal fun ReVancedInstallSetting(activity: MainActivity, modifier: Modifier = Modifier) {
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                revision++
                if (message == R.string.revanced_confirm) {
                    message = null
                    File(activity.cacheDir, "revanced-install/revanced-manager.apk").delete()
                }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val installed = remember(revision) { runCatching { activity.packageManager.getPackageInfo(ReVancedInstaller.PACKAGE, 0) }.isSuccess }
    SettingRow(tr(if (installed) R.string.revanced_open else R.string.revanced_title),
        tr(message ?: if (busy) R.string.revanced_downloading else if (installed) R.string.revanced_installed else R.string.revanced_hint), modifier,
        onClick = {
            if (installed) {
                val intent = activity.packageManager.getLeanbackLaunchIntentForPackage(ReVancedInstaller.PACKAGE)
                    ?: activity.packageManager.getLaunchIntentForPackage(ReVancedInstaller.PACKAGE)
                if (intent == null) message = R.string.revanced_failed
                else runCatching { activity.startActivity(intent) }.onFailure { message = R.string.revanced_failed }
            } else if (!busy) {
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    message = R.string.revanced_permission
                    runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))) }
                        .onFailure { message = R.string.revanced_failed }
                } else {
                    busy = true; message = null
                    scope.launch {
                        try {
                            val apk = withContext(Dispatchers.IO) { ReVancedInstaller.download(activity) }
                            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.torrserve-installer", apk)
                            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            message = R.string.revanced_confirm
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { message = R.string.revanced_failed }
                        finally { busy = false }
                    }
                }
            }
        })
}
