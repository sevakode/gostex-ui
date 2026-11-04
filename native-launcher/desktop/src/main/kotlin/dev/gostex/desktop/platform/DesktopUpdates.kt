package dev.gostex.desktop.platform

import androidx.compose.runtime.*
import dev.gostex.release.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.security.MessageDigest
import java.util.Properties

object DesktopBuild {
    private val values = Properties().apply {
        DesktopBuild::class.java.getResourceAsStream("/gostex-build.properties")?.use { load(it) }
    }
    val code: Long = values.getProperty("code", "41").toLong()
    val name: String = values.getProperty("name", "0.33.0")
}

fun desktopDataPath(): Path {
    val home = Path.of(System.getProperty("user.home"))
    return if (System.getProperty("os.name").startsWith("Mac")) home.resolve("Library/Application Support/GOSTEX")
    else (System.getenv("XDG_DATA_HOME")?.let { runCatching { Path.of(it) }.getOrNull() }?.takeIf { it.isAbsolute }
        ?: home.resolve(".local/share")).resolve("gostex")
}

/** UI owns the coroutine. There is no timer or network activity while hidden or in a game. */
class DesktopUpdates(val root: Path = desktopDataPath()) {
    val log = DiagnosticLog(root.resolve("diagnostics").toFile())
    var release by mutableStateOf<Release?>(null)
        private set
    var status by mutableStateOf("updates_unconfigured")
        private set
    var working by mutableStateOf(false)
        private set
    var ready by mutableStateOf(false)
        private set
    private var lastCheck = 0L
    private var endpoint = ""

    suspend fun check(settings: DesktopSettings, force: Boolean = false) {
        log.enabled = settings.debugEnabled
        if (working) return
        if (!force && !settings.updatesEnabled) return
        if (settings.feedUrl.isBlank()) { status = "updates_unconfigured"; return }
        if (settings.feedUrl != endpoint) { release = null; ready = false; endpoint = settings.feedUrl; lastCheck = 0 }
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < settings.updateIntervalHours * 3_600_000L) return
        val platform = platform() ?: run { status = "updates_linux_only"; return }
        working = true
        status = "updates_checking"
        try {
            val next = withContext(Dispatchers.IO) { SignedReleaseClient().check(settings.feedUrl, platform, DesktopBuild.code) }
            lastCheck = now
            release = next
            status = if (next == null) "updates_current" else "updates_available"
            log.event("update.check", if (next == null) "current" else "available:${next.versionCode}")
            if (next != null && settings.autoDownload) downloadInternal(next)
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { status = "updates_error"; log.failure("update.check", error) }
        finally { working = false }
    }

    suspend fun download() {
        val next = release ?: return
        if (working) return
        working = true
        try { downloadInternal(next) }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { status = "updates_error"; log.failure("update.download", error) }
        finally { working = false }
    }

    private suspend fun downloadInternal(next: Release) {
        status = "updates_downloading"
        withContext(Dispatchers.IO) {
            val updates = root.resolve("updates")
            Files.createDirectories(updates)
            val archive = updates.resolve("${next.versionCode}.zip")
            SignedReleaseClient().download(next, archive.toFile())
            // The stable Linux bootstrap consumes this only on the next launcher start.
            val pending = Properties().apply {
                setProperty("versionCode", next.versionCode.toString())
                setProperty("versionName", next.versionName)
                setProperty("path", archive.toAbsolutePath().toString())
                setProperty("sha256", next.sha256)
            }
            val temporary = Files.createTempFile(updates, ".pending-", ".tmp")
            try {
                Files.newBufferedWriter(temporary, Charsets.UTF_8).use { pending.store(it, "Verified GOSTEX release") }
                Files.move(temporary, root.resolve("pending.properties"), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        }
        ready = true
        status = "updates_ready"
        log.event("update.staged", next.versionCode.toString())
    }

    suspend fun export(destination: File, settings: DesktopSettings): File = withContext(Dispatchers.IO) {
        val runtime = Runtime.getRuntime()
        log.export(destination, mapOf(
            "app.version" to DesktopBuild.name, "app.code" to DesktopBuild.code.toString(),
            "platform" to (platform() ?: "host-preview"), "os.version" to System.getProperty("os.version"),
            "java.version" to System.getProperty("java.version"), "heap.usedBytes" to (runtime.totalMemory() - runtime.freeMemory()).toString(),
            "heap.maxBytes" to runtime.maxMemory().toString(), "library.cardCount" to settings.items.size.toString(),
            "debug.enabled" to settings.debugEnabled.toString(), "update.state" to status,
            "update.availableCode" to (release?.versionCode?.toString() ?: "none")
        ))
    }

    companion object {
        fun platform(): String? = if (System.getProperty("os.name").lowercase().contains("linux") &&
            System.getProperty("os.arch").lowercase() in setOf("amd64", "x86_64")) "linux-x64" else null
    }
}
