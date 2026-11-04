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
class DesktopUpdates(
    val root: Path = desktopDataPath(),
    private val clientFactory: () -> SignedReleaseClient = { SignedReleaseClient() },
    private val platformProvider: () -> String? = { platform() }
) {
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
    @Volatile private var foreground = true
    private var operation: Job? = null
    private var configuration = 0L

    fun setForeground(visible: Boolean) {
        foreground = visible
        if (!visible) operation?.cancel()
    }

    suspend fun configure(settings: DesktopSettings) {
        log.enabled = settings.debugEnabled
        if (settings.feedUrl == endpoint) return
        val previous = operation
        val clearPending = endpoint.isNotEmpty()
        configuration++
        release = null; ready = false; lastCheck = 0
        if (previous != currentCoroutineContext()[Job]) previous?.cancelAndJoin()
        lastCheck = withContext(Dispatchers.IO) {
            if (clearPending) Files.deleteIfExists(root.resolve("pending.properties"))
            readLastCheck(settings.feedUrl)
        }
        endpoint = settings.feedUrl
        status = if (endpoint.isEmpty()) "updates_unconfigured" else "updates_paused"
    }

    suspend fun check(settings: DesktopSettings, force: Boolean = false) {
        configure(settings)
        if (working || !foreground) return
        if (!force && !settings.updatesEnabled) return
        if (settings.feedUrl.isBlank()) { status = "updates_unconfigured"; return }
        val now = System.currentTimeMillis()
        if (!force && now >= lastCheck && now - lastCheck < settings.updateIntervalHours * 3_600_000L) return
        val platform = platformProvider() ?: run { status = "updates_linux_only"; return }
        working = true
        operation = currentCoroutineContext()[Job]
        val revision = configuration
        status = "updates_checking"
        try {
            lastCheck = now
            withContext(Dispatchers.IO) { recordLastCheck(endpoint, now) }
            val next = runInterruptible(Dispatchers.IO) { clientFactory().check(settings.feedUrl, platform, DesktopBuild.code) }
            currentCoroutineContext().ensureActive()
            if (revision != configuration || !foreground) return
            release = next
            status = if (next == null) "updates_current" else "updates_available"
            log.event("update.check", if (next == null) "current" else "available:${next.versionCode}")
            val rejected = next != null && withContext(Dispatchers.IO) { next.versionCode <= rejectedCode() }
            if (rejected) status = "updates_skipped"
            if (next != null && settings.autoDownload && !rejected) downloadInternal(next)
        } catch (cancel: CancellationException) {
            if (revision == configuration) status = if (ready) "updates_ready" else if (release != null) "updates_available" else "updates_paused"
            throw cancel
        }
        catch (error: Exception) { status = "updates_error"; log.failure("update.check", error) }
        finally { working = false; operation = null }
    }

    suspend fun download() {
        val next = release ?: return
        if (working || !foreground) return
        working = true
        operation = currentCoroutineContext()[Job]
        try {
            // A deliberate Download action permits retrying a rolled-back version.
            withContext(Dispatchers.IO) { Files.deleteIfExists(root.resolve("rejected-version.properties")) }
            downloadInternal(next)
        }
        catch (cancel: CancellationException) { status = "updates_available"; throw cancel }
        catch (error: Exception) { status = "updates_error"; log.failure("update.download", error) }
        finally { working = false; operation = null }
    }

    private suspend fun downloadInternal(next: Release) {
        status = "updates_downloading"
        val owner = currentCoroutineContext()[Job]
        val revision = configuration
        runInterruptible(Dispatchers.IO) {
            val updates = root.resolve("updates")
            Files.createDirectories(updates)
            val archive = updates.resolve("${next.versionCode}.zip")
            clientFactory().download(next, archive.toFile())
            if (!foreground || owner?.isActive == false || revision != configuration) throw CancellationException()
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
                if (!foreground || owner?.isActive == false || revision != configuration) throw CancellationException()
                Files.move(temporary, root.resolve("pending.properties"), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        }
        ready = true
        status = "updates_ready"
        log.event("update.staged", next.versionCode.toString())
    }

    private fun rejectedCode(): Long = runCatching {
        val file = root.resolve("rejected-version.properties")
        if (!Files.isRegularFile(file) || Files.size(file) > 4096) return@runCatching 0L
        Properties().apply { Files.newBufferedReader(file).use { load(it) } }.getProperty("versionCode").toLongOrNull() ?: 0L
    }.getOrDefault(0L)

    private fun channelHash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun readLastCheck(feed: String): Long = runCatching {
        val file = root.resolve("last-check.properties")
        if (!Files.isRegularFile(file) || Files.size(file) > 4096) return@runCatching 0L
        val data = Properties().apply { Files.newBufferedReader(file).use { load(it) } }
        if (data.getProperty("channel") != channelHash(feed)) 0L else data.getProperty("time").toLongOrNull()?.coerceAtLeast(0) ?: 0L
    }.getOrDefault(0L)

    private fun recordLastCheck(feed: String, now: Long) {
        Files.createDirectories(root)
        val temporary = Files.createTempFile(root, ".check-", ".tmp")
        try {
            Files.newBufferedWriter(temporary).use { output ->
                Properties().apply { setProperty("channel", channelHash(feed)); setProperty("time", now.toString()) }.store(output, null)
            }
            Files.move(temporary, root.resolve("last-check.properties"), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
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
