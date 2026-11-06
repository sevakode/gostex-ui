package tv.orbit.shield

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.gostex.release.DiagnosticLog
import dev.gostex.release.Release
import dev.gostex.release.ReleaseChannels
import dev.gostex.release.SignedReleaseClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

internal data class UpdateOptions(
    val feedUrl: String = ReleaseChannels.ANDROID_ARM64_FEED,
    val checksEnabled: Boolean = true,
    val autoDownload: Boolean = true,
    val debugEnabled: Boolean = true,
    val checkIntervalHours: Int = 6
)

internal object UpdatePreferences {
    fun preferences(context: Context): SharedPreferences = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)
    fun validFeed(value: String): Boolean = value.isEmpty() || (value.length <= 2048 && value.none(Char::isISOControl) && runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null
    }.getOrDefault(false))
    fun read(context: Context): UpdateOptions {
        val prefs = preferences(context)
        val storedFeed = if (prefs.contains("updates.feedUrl")) prefs.readStringOrDefault("updates.feedUrl", "") else ReleaseChannels.ANDROID_ARM64_FEED
        val feed = storedFeed.orEmpty().takeIf(::validFeed).orEmpty()
        val interval = runCatching { prefs.getInt("updates.checkIntervalHours", 6) }.getOrDefault(6).takeIf { it in listOf(1, 6, 24) } ?: 6
        return UpdateOptions(feed, prefs.readBooleanOrDefault("updates.checksEnabled", true),
            prefs.readBooleanOrDefault("updates.autoDownload", true), prefs.readBooleanOrDefault("updates.debugEnabled", true), interval)
    }
    fun setFeed(context: Context, value: String): Boolean {
        val normalized = value.trim()
        if (!validFeed(normalized)) return false
        val prefs = preferences(context)
        val edit = prefs.edit().putString("updates.feedUrl", normalized).remove("updates.lastCheckAt")
        if (prefs.readStringOrDefault("updates.feedUrl", "") != normalized) AndroidUpdateCache.fields.forEach { edit.remove("updates.cached.$it") }
        edit.apply()
        return true
    }
    fun setBoolean(context: Context, key: String, value: Boolean) {
        require(key in setOf("updates.checksEnabled", "updates.autoDownload", "updates.debugEnabled"))
        preferences(context).edit().putBoolean(key, value).apply()
    }
    fun setInterval(context: Context, hours: Int) {
        require(hours in listOf(1, 6, 24))
        preferences(context).edit().putInt("updates.checkIntervalHours", hours).apply()
    }
}

/** Install recovery never commits a session merely because the launcher resumed. */
internal object AndroidInstallRecovery {
    data class Record(val id: Int, val token: String, val requested: Boolean, val versionCode: Long)
    enum class Action { CLEAR, KEEP, RECREATE }
    fun decide(record: Record, sessionExists: Boolean, installedCode: Long, explicitInstall: Boolean): Action = when {
        record.id < 0 || record.token.isBlank() || !record.requested || record.versionCode < 0 -> Action.CLEAR
        !sessionExists || (record.versionCode > 0 && record.versionCode <= installedCode) -> Action.CLEAR
        explicitInstall -> Action.RECREATE
        else -> Action.KEEP
    }
}

/** Main-thread handoff permit, not user state: a hidden or replaced Activity cannot open consent. */
internal class AndroidInstallGate {
    private var owner: Any? = null
    private var awaitingToken: String? = null
    private var confirmationOpened = false
    fun resume(value: Any) {
        if (owner !== value) { awaitingToken = null; confirmationOpened = false }
        owner = value
    }
    fun stop(value: Any) { if (owner === value) { cancel(value); owner = null } }
    fun cancel(value: Any) { if (owner === value) { awaitingToken = null; confirmationOpened = false } }
    fun canStart(value: Any): Boolean = owner === value && awaitingToken == null
    fun arm(value: Any, token: String): Boolean {
        if (!canStart(value) || token.isBlank()) return false
        awaitingToken = token; confirmationOpened = false
        return true
    }
    fun takeConfirmation(token: String): Boolean {
        if (owner == null || awaitingToken != token || confirmationOpened) return false
        confirmationOpened = true
        return true
    }
    fun finish(token: String) { if (awaitingToken == token) { awaitingToken = null; confirmationOpened = false } }
}

/** Pure cache codec: only app-private metadata from a successfully authenticated feed is written. */
internal object AndroidUpdateCache {
    val fields = listOf("feed", "platform", "code", "name", "url", "sha256", "size")
    fun encode(feed: String, release: Release): Map<String, String> = mapOf(
        "feed" to feed, "platform" to release.platform, "code" to release.versionCode.toString(),
        "name" to release.versionName, "url" to release.url, "sha256" to release.sha256, "size" to release.size.toString()
    )
    fun decode(values: Map<String, String>, feed: String, installedCode: Long): Release? = runCatching {
        require(values.keys == fields.toSet() && values["feed"] == feed && validHttps(feed, 2048, false))
        val platform = values.getValue("platform").also { require(it == "android-arm64") }
        val code = values.getValue("code").toLong().also { require(it > installedCode && it > 0) }
        val name = values.getValue("name").also { require(it.isNotBlank() && it.length <= 128 && it.none(Char::isISOControl)) }
        val url = values.getValue("url").also { require(validHttps(it, 8192, true)) }
        val hash = values.getValue("sha256").also { require(it.matches(Regex("[a-fA-F0-9]{64}"))) }
        val size = values.getValue("size").toLong().also { require(it in 1..1_073_741_824L) }
        Release(platform, code, name, url, hash, size)
    }.getOrNull()
    private fun validHttps(value: String, max: Int, queryAllowed: Boolean): Boolean =
        value.length <= max && value.none { it.isWhitespace() || it.isISOControl() } && runCatching {
            val uri = URI(value)
            uri.scheme.equals("https", true) && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawFragment == null &&
                (queryAllowed || uri.rawQuery == null) && uri.port in -1..65535 && uri.port != 0
        }.getOrDefault(false)
}

/** App-owned bounded diagnostics; payloads deliberately contain no titles, URIs or preference dumps. */
internal object AndroidDiagnostics {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var log: DiagnosticLog? = null
    @Synchronized fun initialize(context: Context, enabled: Boolean): DiagnosticLog {
        val existing = log
        if (existing != null) { existing.enabled = enabled; return existing }
        val created = DiagnosticLog(File(context.applicationContext.filesDir, "diagnostics")).also { it.enabled = enabled }
        log = created
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { created.failure("uncaught", sanitized(error)) }
            if (previous != null) previous.uncaughtException(thread, error)
            else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
        }
        return created
    }
    fun event(type: String, detail: String = "") { scope.launch { runCatching { log?.event(type, detail) } } }
    fun failure(where: String, error: Throwable) { val safe = sanitized(error); scope.launch { runCatching { log?.failure(where, safe) } } }
    private fun sanitized(error: Throwable): Throwable = RuntimeException(error.javaClass.simpleName).apply {
        stackTrace = error.stackTrace.take(32).toTypedArray()
    }
}

/** Only foreground/resume-triggered update work; no scheduler, background service or polling timer. */
internal class LauncherUpdates(private val activity: MainActivity) {
    private val prefs = UpdatePreferences.preferences(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client by lazy { SignedReleaseClient() }
    private val diagnostics = AndroidDiagnostics.initialize(activity, UpdatePreferences.read(activity).debugEnabled)
    private var operation: Job? = null
    private var operationId = 0L
    private var foreground = false
    private var closed = false
    private var download: File? = null
    private var cacheRestored = false
    private val installOwner = Any()
    var options by mutableStateOf(UpdatePreferences.read(activity))
        private set
    var release by mutableStateOf<Release?>(null)
        private set
    var status by mutableStateOf(R.string.updates_idle)
        private set
    var busy by mutableStateOf(false)
        private set
    var ready by mutableStateOf(false)
        private set
    val versionName: String get() = installedInfo().versionName.orEmpty()
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in OPTION_KEYS) {
            val previous = options
            options = UpdatePreferences.read(activity)
            diagnostics.enabled = options.debugEnabled
            if (previous.feedUrl != options.feedUrl) {
                stopOperation()
                clearCachedRelease()
                cacheRestored = true
                release = null; download = null; ready = false
                status = if (options.feedUrl.isEmpty()) R.string.updates_no_channel else R.string.updates_idle
            } else if ((!options.checksEnabled && previous.checksEnabled) || (!options.autoDownload && previous.autoDownload)) stopOperation()
            if (foreground && !cacheRestored) restoreCachedRelease()
            else if (foreground && options.autoDownload && !previous.autoDownload && release != null && !ready && !busy) downloadUpdate()
            else if (foreground && options.checksEnabled) check(false)
        }
        if (key == "updates.installResultPending") consumeInstallResult()
    }
    init {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        status = if (options.feedUrl.isEmpty()) R.string.updates_no_channel else R.string.updates_idle
        AndroidDiagnostics.event("launcher.create", "version=$versionName sdk=${Build.VERSION.SDK_INT}")
    }
    @Suppress("DEPRECATION") private fun installedInfo() = activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_SIGNING_CERTIFICATES)

    fun onResume() {
        foreground = true
        installGate.resume(installOwner)
        AndroidDiagnostics.event("launcher.resume")
        consumeInstallResult()
        reconcileInstallSession()
        if (!cacheRestored) restoreCachedRelease() else resumeUpdateWork()
    }
    fun onStop() { foreground = false; installGate.stop(installOwner); stopOperation(); AndroidDiagnostics.event("launcher.stop") }
    fun close() {
        if (closed) return
        closed = true; foreground = false
        installGate.stop(installOwner)
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        scope.cancel()
        AndroidDiagnostics.event("launcher.destroy")
    }
    private fun stopOperation() {
        operationId++
        installGate.cancel(installOwner)
        operation?.cancel(); operation = null; busy = false
        status = when {
            ready -> R.string.updates_ready
            release != null -> R.string.updates_available_status
            options.feedUrl.isEmpty() -> R.string.updates_no_channel
            else -> R.string.updates_idle
        }
    }
    private fun clearCachedRelease() {
        val edit = prefs.edit()
        AndroidUpdateCache.fields.forEach { edit.remove("updates.cached.$it") }
        edit.apply()
    }
    private fun cacheRelease(feed: String, release: Release?) {
        if (release == null) { clearCachedRelease(); return }
        val edit = prefs.edit()
        AndroidUpdateCache.encode(feed, release).forEach { (field, value) -> edit.putString("updates.cached.$field", value) }
        edit.apply()
    }
    private fun artifactFile(release: Release): File = File(File(activity.cacheDir, "updates"), "gostex-${release.versionCode}.apk")
    /** Offline recovery rechecks the APK itself; a saved ready flag is never trusted. */
    private fun restoreCachedRelease() {
        if (busy || closed || !foreground) return
        val source = options.feedUrl
        val generation = ++operationId
        busy = true
        operation = scope.launch {
            var resumed = false
            try {
                val restored = runInterruptible(Dispatchers.IO) {
                    val fields = AndroidUpdateCache.fields.mapNotNull { field ->
                        prefs.readStringOrDefault("updates.cached.$field")?.let { field to it }
                    }.toMap()
                    val candidate = AndroidUpdateCache.decode(fields, source, installedInfo().longVersionCode)
                    if (candidate == null) null
                    else {
                        val file = artifactFile(candidate)
                        val verified = if (!file.isFile) false else try { verifyArchive(file, candidate); true }
                        catch (error: InterruptedException) { throw error }
                        catch (error: Exception) { AndroidDiagnostics.failure("update.cache", error); false }
                        candidate to file.takeIf { verified }
                    }
                }
                currentCoroutineContext().ensureActive()
                if (generation != operationId || source != options.feedUrl) return@launch
                if (restored == null) clearCachedRelease()
                release = restored?.first
                download = restored?.second
                ready = download != null
                cacheRestored = true
                if (release != null) status = if (ready) R.string.updates_ready else R.string.updates_available_status
                resumed = true
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (generation == operationId) { cacheRestored = true; status = R.string.updates_check_failed }
                AndroidDiagnostics.failure("update.cache", error)
            } finally { if (generation == operationId) { busy = false; operation = null } }
            if (resumed && generation == operationId && foreground && !closed) resumeUpdateWork()
        }
    }
    private fun resumeUpdateWork() {
        if (release != null && !ready && options.autoDownload) downloadUpdate()
        else check(false)
    }
    fun check(manual: Boolean) {
        if (busy || closed || !foreground) return
        if (options.feedUrl.isEmpty()) { status = R.string.updates_no_channel; return }
        if (!manual && !options.checksEnabled) return
        val now = System.currentTimeMillis()
        val last = runCatching { prefs.getLong("updates.lastCheckAt", 0L) }.getOrDefault(0L)
        if (!manual && now >= last && now - last < options.checkIntervalHours * 3_600_000L) return
        val feed = options.feedUrl
        val generation = ++operationId
        busy = true; status = R.string.updates_checking
        // Failed/aborted checks are throttled too; the manual action always bypasses this timestamp.
        prefs.edit().putLong("updates.lastCheckAt", now).apply()
        operation = scope.launch {
            try {
                val current = installedInfo().longVersionCode
                val result = runInterruptible(Dispatchers.IO) { client.check(feed, PLATFORM, current) }
                currentCoroutineContext().ensureActive()
                if (generation != operationId) return@launch
                cacheRelease(feed, result)
                cacheRestored = true
                release = result; ready = false; download = null
                if (result == null) status = R.string.updates_current
                else {
                    status = R.string.updates_available_status
                    activity.notice = activity.getString(R.string.updates_found_notice, result.versionName)
                    AndroidDiagnostics.event("update.available", "code=${result.versionCode}")
                    if (options.autoDownload) downloadRelease(result, generation)
                }
                AndroidDiagnostics.event("update.check", if (result == null) "current" else "available")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (generation == operationId) status = R.string.updates_check_failed; AndroidDiagnostics.failure("update.check", error) }
            finally { if (generation == operationId) { busy = false; operation = null } }
        }
    }
    fun downloadUpdate() {
        val target = release ?: return
        if (busy || !foreground || closed) return
        val generation = ++operationId
        busy = true
        operation = scope.launch {
            try { downloadRelease(target, generation) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (generation == operationId) status = R.string.updates_download_failed; AndroidDiagnostics.failure("update.download", error) }
            finally { if (generation == operationId) { busy = false; operation = null } }
        }
    }
    private suspend fun downloadRelease(target: Release, generation: Long) {
        status = R.string.updates_downloading
        val file = runInterruptible(Dispatchers.IO) {
            val folder = File(activity.cacheDir, "updates").apply { mkdirs() }
            val destination = artifactFile(target)
            folder.listFiles()?.filter { it.name.matches(Regex("gostex-[0-9]+\\.apk")) && it != destination }?.forEach { it.delete() }
            client.download(target, destination).also { verifyArchive(it, target) }
        }
        currentCoroutineContext().ensureActive()
        if (generation != operationId) return
        download = file; ready = true; status = R.string.updates_ready
        activity.notice = activity.getString(R.string.updates_ready_notice, target.versionName)
        AndroidDiagnostics.event("update.verified", "code=${target.versionCode}")
    }
    /** Validate identity/signers and manifest, in addition to the signed feed's size/SHA-256. */
    @Suppress("DEPRECATION") private fun verifyArchive(file: File, target: Release) {
        require(target.platform == PLATFORM && file.isFile && file.length() == target.size) { "Invalid artifact" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(64 * 1024); while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val read = input.read(bytes); if (read < 0) break; digest.update(bytes, 0, read)
        } }
        require(digest.digest().joinToString("") { "%02x".format(it) }.equals(target.sha256, true)) { "Invalid artifact digest" }
        val archive = activity.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("Invalid APK")
        val installed = installedInfo()
        require(archive.packageName == activity.packageName && archive.longVersionCode == target.versionCode.toLong() && archive.longVersionCode > installed.longVersionCode) { "Wrong APK package or version" }
        val oldSigners = installed.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet().orEmpty()
        val newSigners = archive.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet().orEmpty()
        require(oldSigners.isNotEmpty() && oldSigners == newSigners) { "Wrong APK signing identity" }
    }
    private fun installRecord() = AndroidInstallRecovery.Record(
        runCatching { prefs.getInt(INSTALL_SESSION, -1) }.getOrDefault(-1),
        prefs.readStringOrDefault(INSTALL_TOKEN).orEmpty(), prefs.readBooleanOrDefault(INSTALL_REQUESTED, false),
        runCatching { prefs.getLong(INSTALL_VERSION, 0L) }.getOrDefault(-1L)
    )
    private fun ownedInstallSession(record: AndroidInstallRecovery.Record): PackageInstaller.SessionInfo? =
        if (record.id < 0) null else activity.packageManager.packageInstaller.getSessionInfo(record.id)?.takeIf {
            it.installerPackageName == activity.packageName && it.appPackageName == activity.packageName
        }
    private fun clearInstallRecord(record: AndroidInstallRecovery.Record) {
        // A delayed cancellation/result must never remove a newer attempt's marker.
        val current = installRecord()
        if (current.id != record.id || current.token != record.token) return
        prefs.edit().remove(INSTALL_SESSION).remove(INSTALL_TOKEN).remove(INSTALL_REQUESTED).remove(INSTALL_VERSION).apply()
    }
    private fun reconcileInstallSession() {
        try {
            val record = installRecord()
            val session = ownedInstallSession(record)
            if (AndroidInstallRecovery.decide(record, session != null, installedInfo().longVersionCode, false) == AndroidInstallRecovery.Action.CLEAR) {
                clearInstallRecord(record)
                if (session != null) activity.packageManager.packageInstaller.abandonSession(record.id)
            }
        } catch (error: Exception) { AndroidDiagnostics.failure("update.install-recovery", error) }
    }
    fun installUpdate() {
        val target = release ?: return
        val apk = download ?: return
        if (!ready || busy || !foreground || closed) return
        if (!installGate.canStart(installOwner)) { status = R.string.updates_confirm_install; return }
        if (!activity.packageManager.canRequestPackageInstalls()) {
            status = R.string.updates_permission_required
            try { activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))) }
            catch (error: Exception) { status = R.string.updates_install_failed; AndroidDiagnostics.failure("update.permission", error) }
            return
        }
        val generation = ++operationId
        busy = true; status = R.string.updates_installing
        operation = scope.launch {
            try {
                // Also serializes a cancelled old Activity's cleanup against its replacement.
                installMutex.withLock {
                    val installer = activity.packageManager.packageInstaller
                    var id = -1
                    val token = UUID.randomUUID().toString()
                    var committed = false
                    try {
                        currentCoroutineContext().ensureActive()
                        val previous = installRecord()
                        val previousSession = ownedInstallSession(previous)
                        val recovery = AndroidInstallRecovery.decide(previous, previousSession != null, installedInfo().longVersionCode, true)
                        check(recovery != AndroidInstallRecovery.Action.KEEP)
                        clearInstallRecord(previous)
                        runInterruptible(Dispatchers.IO) {
                            // Recreate sealed/pending and pre-commit sessions alike. Android 11
                            // need not replay a lost consent Intent; this is an explicit retry.
                            if (previousSession != null) installer.abandonSession(previous.id)
                            verifyArchive(apk, target)
                            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                                setAppPackageName(activity.packageName); setSize(apk.length())
                                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                            }
                            id = installer.createSession(params)
                            installer.openSession(id).use { session ->
                                session.openWrite("base.apk", 0, apk.length()).use { output ->
                                    apk.inputStream().use { input -> val bytes = ByteArray(64 * 1024); while (true) {
                                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                                        val read = input.read(bytes); if (read < 0) break; output.write(bytes, 0, read)
                                    } }; session.fsync(output)
                                }
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        if (generation != operationId || !foreground || closed) return@withLock
                        // These final Main-thread steps do not suspend: onStop/feed cancellation
                        // cannot interleave between the visibility check and commit.
                        check(installGate.arm(installOwner, token))
                        check(prefs.edit().putInt(INSTALL_SESSION, id).putString(INSTALL_TOKEN, token)
                            .putBoolean(INSTALL_REQUESTED, true).putLong(INSTALL_VERSION, target.versionCode).commit())
                        installer.openSession(id).use { session ->
                            val callback = Intent(activity, UpdateInstallReceiver::class.java).setAction(INSTALL_ACTION)
                                .setData(Uri.parse("gostex-update://session/$id")).putExtra("token", token)
                            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                            val result = PendingIntent.getBroadcast(activity, id, callback, flags)
                            session.commit(result.intentSender)
                            committed = true
                        }
                    } finally {
                        if (!committed) withContext(NonCancellable) {
                            installGate.finish(token)
                            if (id >= 0) {
                                runInterruptible(Dispatchers.IO) { runCatching { installer.abandonSession(id) } }
                                clearInstallRecord(AndroidInstallRecovery.Record(id, token, true, target.versionCode))
                            }
                        }
                    }
                }
                if (generation == operationId) status = R.string.updates_confirm_install
                AndroidDiagnostics.event("update.install-request", "code=${target.versionCode}")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (generation == operationId) status = R.string.updates_install_failed; AndroidDiagnostics.failure("update.install", error) }
            finally { if (generation == operationId) { busy = false; operation = null } }
        }
    }
    private fun consumeInstallResult() {
        if (!prefs.readBooleanOrDefault("updates.installResultPending", false)) return
        val result = runCatching { prefs.getInt("updates.installResult", PackageInstaller.STATUS_FAILURE) }.getOrDefault(PackageInstaller.STATUS_FAILURE)
        prefs.edit().putBoolean("updates.installResultPending", false).apply()
        status = if (result == PackageInstaller.STATUS_SUCCESS) R.string.updates_install_success else R.string.updates_install_failed
        activity.notice = activity.getString(status)
    }
    fun exportDiagnostics(uri: Uri) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val file = File.createTempFile("gostex-report-", ".zip", activity.cacheDir)
                    try {
                        diagnostics.export(file, mapOf("package" to activity.packageName, "version" to versionName, "versionCode" to installedInfo().longVersionCode.toString(),
                            "sdk" to Build.VERSION.SDK_INT.toString(), "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL))
                        activity.contentResolver.openOutputStream(uri, "wt")?.use { output -> file.inputStream().use { it.copyTo(output) } }
                            ?: error("No report destination")
                    } finally { file.delete() }
                }
                activity.notice = activity.getString(R.string.updates_exported)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { activity.notice = activity.getString(R.string.updates_export_failed); AndroidDiagnostics.failure("diagnostics.export", error) }
        }
    }
    fun clearDiagnostics() { scope.launch {
        try { withContext(Dispatchers.IO) { diagnostics.clear() }; activity.notice = activity.getString(R.string.updates_debug_cleared) }
        catch (error: Exception) { if (error is CancellationException) throw error; activity.notice = activity.getString(R.string.updates_export_failed) }
    } }
    companion object {
        const val PLATFORM = "android-arm64"
        const val INSTALL_ACTION = "tv.orbit.shield.UPDATE_INSTALL_RESULT"
        const val INSTALL_SESSION = "updates.installSessionId"
        const val INSTALL_TOKEN = "updates.installToken"
        const val INSTALL_REQUESTED = "updates.installRequested"
        const val INSTALL_VERSION = "updates.installVersionCode"
        internal val installGate = AndroidInstallGate()
        private val installMutex = Mutex()
        private val OPTION_KEYS = setOf("updates.feedUrl", "updates.checksEnabled", "updates.autoDownload", "updates.debugEnabled", "updates.checkIntervalHours")
    }
}

/** Explicit, private install callback. Never installs or opens a confirmation from an automatic check. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!PortableRestoreStartup.ready) return
        if (PortableMaintenance.running) {
            val saved = Intent(intent)
            PortableMaintenance.afterMaintenance { onReceive(context.applicationContext, saved) }
            return
        }
        if (intent.action != LauncherUpdates.INSTALL_ACTION) return
        val prefs = UpdatePreferences.preferences(context)
        val expected = runCatching { prefs.getInt(LauncherUpdates.INSTALL_SESSION, -1) }.getOrDefault(-1)
        val token = intent.getStringExtra("token").orEmpty()
        if (expected < 0 || intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != expected ||
            token.isBlank() || token != prefs.readStringOrDefault(LauncherUpdates.INSTALL_TOKEN) ||
            !prefs.readBooleanOrDefault(LauncherUpdates.INSTALL_REQUESTED, false)) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        AndroidDiagnostics.initialize(context, UpdatePreferences.read(context).debugEnabled)
        AndroidDiagnostics.event("update.install-result", "status=$status")
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Only the still-visible explicit Install attempt may open system consent.
            // Otherwise keep the session for a later explicit abandon/recreate retry.
            if (!LauncherUpdates.installGate.takeConfirmation(token)) return
            @Suppress("DEPRECATION") val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirmation != null) {
                try { context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
                catch (error: Exception) { AndroidDiagnostics.failure("update.confirmation", error) }
            }
            runCatching { context.packageManager.packageInstaller.abandonSession(expected) }
        }
        LauncherUpdates.installGate.finish(token)
        prefs.edit().putInt("updates.installResult", if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) PackageInstaller.STATUS_FAILURE else status)
            .putBoolean("updates.installResultPending", true).remove(LauncherUpdates.INSTALL_SESSION)
            .remove(LauncherUpdates.INSTALL_TOKEN).remove(LauncherUpdates.INSTALL_REQUESTED).remove(LauncherUpdates.INSTALL_VERSION).apply()
    }
}
