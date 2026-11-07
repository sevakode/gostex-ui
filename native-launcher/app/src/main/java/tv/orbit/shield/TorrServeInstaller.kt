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
import java.io.File
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

/** Explicit, on-demand installer for the official Android client, never a server binary. */
internal object TorrServeInstaller {
    const val PACKAGE = "ru.yourok.torrserve"
    private const val CERT = "6cbc24b62d7c7cbcdfa534a863b3612e8abde28af6ab41a566a3a9b879293f4e"
    private const val API = "https://api.github.com/repos/YouROK/TorrServe/releases/latest"

    private fun open(url: String): HttpsURLConnection {
        var next = url
        repeat(6) {
            val parsed = URL(next)
            require(TorrServeReleasePolicy.allowedUrl(next))
            val connection = (parsed.openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 15000; readTimeout = 30000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "GOSTEX")
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: error("Missing redirect")
                next = URL(parsed, location).toString(); connection.disconnect()
            } else {
                require(code == 200) { "Unexpected HTTP response" }
                return connection
            }
        }
        error("Too many redirects")
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Suppress("DEPRECATION")
    suspend fun download(activity: MainActivity): File {
        val api = open(API)
        val json = try { api.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 1024 * 1024)
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        } } finally { api.disconnect() }
        val asset = TorrServeReleasePolicy.asset(json)
        val size = asset.bytes
        val expected = asset.sha256
        val dir = File(activity.cacheDir, "torrserve-install").apply { mkdirs() }
        val file = File(dir, "torrserve.apk")
        val conn = open(asset.url)
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            var total = 0L
            conn.inputStream.use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count; require(total <= size && total <= TorrServeReleasePolicy.MAX_BYTES)
                    hash.update(buffer, 0, count); output.write(buffer, 0, count)
                }
            } }
            require(total == size && hash.digest().joinToString("") { "%02x".format(it) } == expected)
            val info = activity.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("Invalid APK")
            require(info.packageName == PACKAGE)
            val signatures = info.signingInfo?.apkContentsSigners ?: error("Missing signature")
            require(signatures.size == 1 && digest(signatures.single().toByteArray()) == CERT)
            return file
        } catch (e: Exception) { file.delete(); throw e } finally { conn.disconnect() }
    }
}

@Composable internal fun TorrServeInstallSetting(activity: MainActivity, modifier: Modifier = Modifier) {
    val owner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val installed = remember(revision) { runCatching { activity.packageManager.getPackageInfo(TorrServeInstaller.PACKAGE, 0) }.isSuccess }
    SettingRow(tr(if (installed) R.string.torr_install_open else R.string.torr_install_title),
        tr(message ?: if (busy) R.string.torr_install_downloading else if (installed) R.string.torr_install_installed else R.string.torr_install_hint), modifier,
        onClick = {
            if (installed) activity.openTorrServer()
            else if (!busy) {
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    message = R.string.torr_install_permission
                    runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))) }
                        .onFailure { message = R.string.torr_install_failed }
                } else {
                    busy = true; message = null
                    scope.launch {
                        try {
                            val apk = withContext(Dispatchers.IO) { TorrServeInstaller.download(activity) }
                            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.torrserve-installer", apk)
                            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            message = R.string.torr_install_confirm
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { message = R.string.torr_install_failed }
                        finally { busy = false }
                    }
                }
            }
        })
}
