package tv.orbit.shield

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    private val attemptName = Regex("torrserve-[A-Za-z0-9_-]+\\.apk")

    private fun directory(activity: MainActivity) = File(activity.cacheDir, "torrserve-install")

    fun createAttempt(activity: MainActivity): File =
        File.createTempFile("torrserve-", ".apk", directory(activity).apply { mkdirs() })

    fun discardAttempt(activity: MainActivity, name: String) {
        if (attemptName.matches(name)) File(directory(activity), name).delete()
    }

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
    suspend fun download(activity: MainActivity, file: File) {
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
        } finally { conn.disconnect() }
    }
}

@Composable internal fun TorrServeInstallSetting(activity: MainActivity, modifier: Modifier = Modifier) {
    val owner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<Int?>(null) }
    var stagedName by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val name = stagedName ?: return@rememberLauncherForActivityResult
        stagedName = null
        revision++
        message = if (result.resultCode == Activity.RESULT_CANCELED) R.string.torr_install_cancelled
            else R.string.torr_install_incomplete
        activity.lifecycleScope.launch(Dispatchers.IO) { TorrServeInstaller.discardAttempt(activity, name) }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                revision++
                if (message == R.string.torr_install_permission && activity.packageManager.canRequestPackageInstalls()) message = null
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val installed = remember(revision) { runCatching { activity.packageManager.getPackageInfo(TorrServeInstaller.PACKAGE, 0) }.isSuccess }
    val retry = message == R.string.torr_install_cancelled || message == R.string.torr_install_incomplete || message == R.string.torr_install_failed
    val title = when {
        installed -> R.string.torr_install_open
        retry -> R.string.torr_install_retry
        else -> R.string.torr_install_title
    }
    val detail = when {
        installed -> R.string.torr_install_installed
        stagedName != null -> R.string.torr_install_confirm
        busy -> R.string.torr_install_downloading
        else -> message ?: R.string.torr_install_hint
    }
    SettingRow(tr(title), tr(detail), modifier,
        onClick = {
            if (installed) activity.openTorrServer()
            else if (!busy && stagedName == null && !PortableMaintenance.running) {
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    message = R.string.torr_install_permission
                    runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))) }
                        .onFailure { message = R.string.torr_install_failed }
                } else {
                    busy = true; message = null
                    scope.launch {
                        var attempt: File? = null
                        var handedOff = false
                        try {
                            val apk = withContext(Dispatchers.IO) {
                                val file = TorrServeInstaller.createAttempt(activity)
                                attempt = file
                                TorrServeInstaller.download(activity, file)
                                file
                            }
                            coroutineContext.ensureActive()
                            if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                                activity.isFinishing || activity.isDestroyed || PortableMaintenance.running) return@launch
                            if (!activity.packageManager.canRequestPackageInstalls()) {
                                message = R.string.torr_install_permission
                                return@launch
                            }
                            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.torrserve-installer", apk)
                            stagedName = apk.name
                            @Suppress("DEPRECATION")
                            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                                .putExtra(Intent.EXTRA_RETURN_RESULT, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            installer.launch(intent)
                            handedOff = true
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { message = R.string.torr_install_failed }
                        finally {
                            if (!handedOff) {
                                stagedName = null
                                attempt?.let { file ->
                                    withContext(NonCancellable + Dispatchers.IO) { TorrServeInstaller.discardAttempt(activity, file.name) }
                                }
                            }
                            busy = false
                        }
                    }
                }
            }
        })
}
