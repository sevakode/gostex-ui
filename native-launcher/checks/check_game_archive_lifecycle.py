"""Execute the production archive controller and maintenance barrier with JVM boundaries.

Uses real kotlinx.coroutines cancellation and delayed storage cleanup. Android picker
delivery, Bundle, Compose state, and storage are stubs; this is not device/UI QA.
"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

from kotlin_toolchain import COMPILER, JAVA, ROOT

production = ROOT / "native-launcher/app/src/main/java/tv/orbit/shield"
controller_path = production / "GameArchiveUi.kt"
maintenance_path = production / "PortableMaintenance.kt"
controller = controller_path.read_text().split("\n@Composable\n", 1)[0]
for unused in ("android.app.AlertDialog", "android.widget.ProgressBar"):
    controller = controller.replace(f"import {unused}\n", "")
# The host boundaries are synchronous, keeping lifecycle ordering deterministic.
controller = controller.replace("Dispatchers.IO", "Dispatchers.Unconfined")
recovery = (production / "GameArchiveRecovery.kt").read_text()
reconnect = recovery[recovery.index("    fun matchesDestination("):recovery.index("    private fun sourceGranted(")]

sources = {
    "Controller.kt": controller,
    "AndroidContent.kt": r'''
package android.content
open class Context {
    val applicationContext: Context get() = this
    val contentResolver = ContentResolver()
}
class ContentResolver { companion object { const val SCHEME_CONTENT = "content" } }
class Intent { companion object {
    const val ACTION_OPEN_DOCUMENT = "open"
    const val ACTION_OPEN_DOCUMENT_TREE = "tree"
    const val FLAG_GRANT_READ_URI_PERMISSION = 1
    const val FLAG_GRANT_WRITE_URI_PERMISSION = 2
} }
''',
    "Formatter.kt": r'''
package android.text.format
import android.content.Context
object Formatter { fun formatFileSize(context: Context, bytes: Long) = "$bytes B" }
''',
    "AndroidOs.kt": r'''
package android.os
class Bundle {
    private val values = mutableMapOf<String, Any?>()
    fun putString(key: String, value: String?) { values[key] = value }
    fun getString(key: String): String? = values[key] as? String
    fun putBoolean(key: String, value: Boolean) { values[key] = value }
    fun getBoolean(key: String): Boolean = values[key] as? Boolean ?: false
}
object SystemClock { fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000 }
object Looper {
    fun myLooper(): Looper = this
    fun getMainLooper(): Looper = this
}
''',
    "Uri.kt": r'''
package android.net
data class Uri(val text: String) {
    val scheme: String? get() = text.substringBefore("://", "").ifEmpty { null }
    val authority: String? get() = text.substringAfter("content://", "").substringBefore('/').ifEmpty { null }
    override fun toString(): String = text
    companion object { fun parse(text: String) = Uri(text) }
}
''',
    "Documents.kt": r'''
package android.provider
import android.net.Uri
object DocumentsContract {
    fun isTreeUri(uri: Uri) = "/tree/" in uri.text
    fun buildTreeDocumentUri(authority: String, id: String) = Uri.parse("content://$authority/tree/$id")
    fun getDocumentId(uri: Uri) = uri.text.substringAfter("/document/", "")
    fun getTreeDocumentId(uri: Uri) = uri.text.substringAfter("/tree/", "").substringBefore("/document/")
    fun buildDocumentUriUsingTree(tree: Uri, id: String) = Uri.parse(tree.text + "/document/" + id)
}
''',
    "Results.kt": r'''
package androidx.activity.result.contract
import android.net.Uri
abstract class ActivityResultContract<I, O>
class ActivityResultContracts {
    class OpenDocument : ActivityResultContract<Array<String>, Uri?>()
    class OpenDocumentTree : ActivityResultContract<Uri?, Uri?>()
}
class ActivityResultLauncher<I>(private val action: (I) -> Unit) {
    fun launch(input: I) = action(input)
}
''',
    "State.kt": r'''
package androidx.compose.runtime
import kotlin.reflect.KProperty
class MutableState<T>(var value: T)
fun <T> mutableStateOf(value: T) = MutableState(value)
fun mutableLongStateOf(value: Long) = MutableState(value)
fun mutableIntStateOf(value: Int) = MutableState(value)
operator fun <T> MutableState<T>.getValue(owner: Any?, property: KProperty<*>): T = value
operator fun <T> MutableState<T>.setValue(owner: Any?, property: KProperty<*>, value: T) { this.value = value }
''',
    "Lifecycle.kt": r'''
package androidx.lifecycle
import kotlinx.coroutines.CoroutineScope
import tv.orbit.shield.MainActivity
val MainActivity.lifecycleScope: CoroutineScope get() = scope
''',
    "Boundaries.kt": r'''
package tv.orbit.shield
import android.content.Context
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract as Docs
import androidx.activity.result.contract.*
import kotlinx.coroutines.*

class MainActivity : Context() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    var notice = ""
    val launches = mutableListOf<Int>()
    var sourceMimeTypes: List<String> = emptyList()
        private set
    private val callbacks = mutableListOf<(Any?) -> Unit>()
    fun getString(id: Int, vararg args: Any): String = when (id) {
        R.string.game_archive_format_zip -> "ZIP"
        R.string.game_archive_format_seven_z -> "7z"
        R.string.game_archive_format_rar -> "RAR"
        R.string.game_archive_confirm -> args.joinToString(" · ")
        else -> id.toString()
    }
    @Suppress("UNCHECKED_CAST")
    fun <I, O> registerForActivityResult(contract: ActivityResultContract<I, O>, callback: (O) -> Unit): ActivityResultLauncher<I> {
        val index = callbacks.size
        callbacks += { value -> callback(value as O) }
        return ActivityResultLauncher { input ->
            launches += index
            if (contract is ActivityResultContracts.OpenDocument) sourceMimeTypes = (input as Array<String>).toList()
        }
    }
    fun deliver(index: Int, value: Uri?) { callbacks[index](value) }
}
object R { object string {
    const val platform_unknown = 1
    const val game_archive_invalid = 2
    const val torr_download_low_space = 18
    const val game_archive_cancelled = 3
    const val game_archive_failed = 4
    const val game_archive_expired = 5
    const val game_archive_discard_failed = 6
    const val game_archive_recovery_unavailable = 7
    const val game_archive_format_zip = 8
    const val game_archive_format_seven_z = 9
    const val game_archive_confirm = 10
    const val game_archive_format_rar = 11
    const val game_archive_original_destination = 12
    const val game_archive_destination_access_failed = 13
} }
object PlatformRegistry {
    fun find(id: String): String? = id.takeIf { it in setOf("SNES", "PSP") }
}
object PortableRestoreStartup {
    val ready = true
    fun requireRecovery(): Unit { error("Unexpected recovery failure") }
}
object PortableAndroidRestoreRecovery {
    fun recoverUnderExclusiveAccess(context: Context) = false
}
object GameArchiveRecovery {
    enum class Phase { EXTRACTING, RENAMING, READY }
    data class Job(val id: String, val platform: String, val title: String,
        val totalBytes: Long, val tree: Uri, val phase: Phase = Phase.EXTRACTING, val finalId: String? = null)
    var saved: Job? = null
    var reads = 0
    var clears = 0
    var failRead = false
    var destinationAvailable = true
    var denyDestination = false
    val grantRequests = mutableListOf<Uri>()
    fun hasDestinationAccess(context: Context, job: Job) = destinationAvailable
    private fun take(resolver: ContentResolver, uri: Uri, flags: Int): Boolean {
        check(flags == 3)
        grantRequests += uri
        if (denyDestination) return false
        destinationAvailable = true
        return true
    }
    PRODUCTION_RECONNECT
    fun read(context: Context): Job? {
        reads++
        if (failRead) error("Unreadable recovery record")
        return saved
    }
    fun clear(context: Context, expected: Job) {
        check(saved == expected)
        clears++
        saved = null
    }
    fun reset() {
        saved = null; reads = 0; clears = 0; failRead = false
        destinationAvailable = true; denyDestination = false; grantRequests.clear()
    }
}
class TorrDownloadLowSpace : java.io.IOException()
enum class GameArchiveFormat { ZIP, SEVEN_Z, RAR }
data class GameArchivePlan(val format: GameArchiveFormat)
object GameArchiveStorage {
    class Plan(val source: Uri, val archive: GameArchivePlan) { val totalBytes = 1L; val files = 1 }
    var detectedFormat = GameArchiveFormat.ZIP
    var inspections = 0
    var extractions = 0
    var resumes = 0
    var discards = 0
    var lastPlatform: String? = null
    var lastResumed: GameArchiveRecovery.Job? = null
    var failResume = false
    var failDiscard = false
    var holdCleanup = false
    var cleanupFinished = false
    var extractionStarted = CompletableDeferred<Unit>()
    var cleanupStarted = CompletableDeferred<Unit>()
    var releaseCleanup = CompletableDeferred<Unit>()
    fun reset() {
        inspections = 0; extractions = 0; resumes = 0; discards = 0; lastPlatform = null; lastResumed = null
        detectedFormat = GameArchiveFormat.ZIP
        failResume = false; failDiscard = false; holdCleanup = false; cleanupFinished = false
        GameArchiveRecovery.reset()
        extractionStarted = CompletableDeferred(); cleanupStarted = CompletableDeferred(); releaseCleanup = CompletableDeferred()
    }
    suspend fun inspect(context: Context, source: Uri): Plan { inspections++; return Plan(source, GameArchivePlan(detectedFormat)) }
    suspend fun extract(context: Context, plan: Plan, destination: Uri, platform: String, progress: (Long) -> Unit): Uri {
        extractions++
        lastPlatform = platform
        extractionStarted.complete(Unit)
        if (holdCleanup) {
            try { awaitCancellation() }
            finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    releaseCleanup.await()
                    cleanupFinished = true
                }
            }
        }
        progress(1)
        return Uri.parse(destination.text + "/prepared")
    }
    suspend fun resume(context: Context, job: GameArchiveRecovery.Job, progress: (Long) -> Unit): Uri {
        resumes++
        lastResumed = job
        if (failResume) error("Unavailable recovery destination")
        check(GameArchiveRecovery.saved == job)
        GameArchiveRecovery.saved = job.copy(phase = GameArchiveRecovery.Phase.READY, finalId = "prepared")
        progress(job.totalBytes)
        return android.provider.DocumentsContract.buildDocumentUriUsingTree(job.tree, "prepared")
    }
    suspend fun discard(context: Context, job: GameArchiveRecovery.Job) {
        discards++
        if (failDiscard) error("Unavailable recovery destination")
        GameArchiveRecovery.clear(context, job)
    }
}
''',
    "Checks.kt": r'''
package tv.orbit.shield
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.*

private class Fixture {
    val activity = MainActivity()
    var pickerAvailable = true
    var added: Pair<String, Uri>? = null
    var acceptFolder = true
    val controller = GameArchiveController(activity, { _, _, launch ->
        if (pickerAvailable) { launch(); true } else false
    }) { platform, uri ->
        if (acceptFolder) added = platform to uri
        acceptFolder
    }
    fun close() = activity.scope.cancel()
}
private val source = Uri.parse("content://fixture/game.zip")
private val destination = Uri.parse("content://fixture/output")
private var checks = 0
private fun verify(name: String, condition: Boolean) {
    check(condition) { name }; checks++; println("PASS|$name")
}

fun main() = runBlocking {
    withTimeout(5_000) {
        GameArchiveStorage.reset()
        val oldSource = Fixture()
        oldSource.controller.chooseSource("PSP")
        verify("The source picker requests ZIP, 7z, RAR and the provider fallback MIME types",
            oldSource.activity.sourceMimeTypes == listOf("application/zip", "application/x-7z-compressed", "application/vnd.rar",
                "application/x-rar-compressed", "application/octet-stream", "*/*"))
        val sourceState = Bundle().also(oldSource.controller::save)
        oldSource.close()
        val sourceRestored = Fixture()
        sourceRestored.controller.restore(sourceState)
        sourceRestored.activity.deliver(0, source)
        verify("ZIP preview displays the detected format alongside the count and size",
            sourceRestored.controller.plan?.previewMessage(sourceRestored.activity) == "ZIP · 1 · 1 B")
        sourceRestored.controller.chooseDestination()
        sourceRestored.activity.deliver(1, destination)
        sourceRestored.controller.addResult()
        verify("Recreated source selection retains the chosen platform",
            sourceRestored.added?.first == "PSP" && GameArchiveStorage.lastPlatform == "PSP")
        sourceRestored.close()

        for ((format, label) in listOf(GameArchiveFormat.SEVEN_Z to "7z", GameArchiveFormat.RAR to "RAR")) {
            GameArchiveStorage.reset()
            GameArchiveStorage.detectedFormat = format
            val archive = Fixture()
            val opaqueSource = Uri.parse("content://fixture/opaque")
            archive.controller.chooseSource("SNES")
            archive.activity.deliver(0, opaqueSource)
            verify("The archive picker passes a $label source to the shared preview",
                archive.controller.plan?.source == opaqueSource && GameArchiveStorage.inspections == 1)
            verify("An opaque source displays $label from the detected format",
                archive.controller.plan?.previewMessage(archive.activity) == "$label · 1 · 1 B")
            archive.controller.chooseDestination()
            archive.activity.deliver(1, destination)
            archive.controller.addResult()
            verify("The $label preview uses the normal extraction and folder import flow",
                archive.added?.first == "SNES" && GameArchiveStorage.lastPlatform == "SNES" && GameArchiveStorage.extractions == 1)
            archive.close()
        }

        GameArchiveStorage.reset()
        val oldDestination = Fixture()
        oldDestination.controller.inspect("SNES", source)
        oldDestination.controller.chooseDestination()
        val destinationState = Bundle().also(oldDestination.controller::save)
        oldDestination.close()
        val destinationRestored = Fixture()
        destinationRestored.controller.restore(destinationState)
        destinationRestored.controller.chooseSource("PSP")
        destinationRestored.controller.inspect("PSP", source)
        destinationRestored.activity.deliver(0, source)
        verify("An expired destination slot cannot be replaced by a new request",
            destinationRestored.controller.picking && destinationRestored.activity.launches.isEmpty() && GameArchiveStorage.inspections == 1)
        destinationRestored.activity.deliver(1, destination)
        verify("A stale destination result starts no write and explains expiry",
            GameArchiveStorage.extractions == 0 && !destinationRestored.controller.picking &&
                destinationRestored.activity.notice == R.string.game_archive_expired.toString())
        destinationRestored.close()

        GameArchiveStorage.reset()
        sourceState.putString("gameArchivePlatform", "removed-platform")
        val invalidSource = Fixture()
        invalidSource.controller.restore(sourceState)
        invalidSource.activity.deliver(0, source)
        verify("An obsolete restored platform never starts archive preparation",
            GameArchiveStorage.inspections == 0 && invalidSource.activity.notice == R.string.game_archive_expired.toString())
        invalidSource.close()

        GameArchiveStorage.reset()
        val cancellation = Fixture()
        cancellation.controller.inspect("SNES", source)
        GameArchiveStorage.holdCleanup = true
        cancellation.controller.chooseDestination()
        cancellation.activity.deliver(1, destination)
        GameArchiveStorage.extractionStarted.await()
        val joined = async { cancellation.controller.cancelAndJoin() }
        GameArchiveStorage.cleanupStarted.await()
        verify("cancelAndJoin waits while cancelled storage is still cleaning up",
            !joined.isCompleted && cancellation.controller.busy && !GameArchiveStorage.cleanupFinished)
        GameArchiveStorage.releaseCleanup.complete(Unit)
        joined.await()
        verify("cancelAndJoin clears busy state only after storage cleanup",
            GameArchiveStorage.cleanupFinished && !cancellation.controller.busy && cancellation.controller.result == null)
        cancellation.close()

        GameArchiveStorage.reset()
        val destroyed = Fixture()
        destroyed.controller.inspect("SNES", source)
        GameArchiveStorage.holdCleanup = true
        destroyed.controller.chooseDestination()
        destroyed.activity.deliver(1, destination)
        destroyed.close()
        GameArchiveStorage.cleanupStarted.await()
        val replacement = Fixture()
        var restoreStarted = false
        val restore = async {
            PortableMaintenance.runExclusive(replacement.activity, { replacement.controller.cancelAndJoin() }) {
                restoreStarted = true
            }
        }
        yield()
        verify("Maintenance waits for cleanup owned by the destroyed Activity",
            PortableMaintenance.running && !restoreStarted && !restore.isCompleted)
        GameArchiveStorage.releaseCleanup.complete(Unit)
        restore.await()
        verify("Maintenance proceeds after the old worker releases its participant",
            restoreStarted && GameArchiveStorage.cleanupFinished && !PortableMaintenance.running)
        replacement.close()

        GameArchiveStorage.reset()
        val alreadyStopped = Fixture()
        alreadyStopped.close()
        alreadyStopped.controller.inspect("SNES", source)
        verify("A cancelled lifecycle never leaves a permanently busy controller",
            !alreadyStopped.controller.busy && GameArchiveStorage.inspections == 0)
        var free = false
        PortableMaintenance.runExclusive(alreadyStopped.activity, {}) { free = true }
        verify("A cancelled-before-start worker does not leak a maintenance participant", free)

        GameArchiveStorage.reset()
        val retry = Fixture()
        retry.controller.inspect("SNES", source)
        val preview = retry.controller.plan
        retry.pickerAvailable = false
        val revision = retry.controller.dialogRevision
        retry.controller.chooseDestination()
        verify("A failed destination launch retains its preview and rebuilds the confirmation",
            !retry.controller.picking && retry.controller.plan === preview && retry.controller.dialogRevision > revision)
        retry.pickerAvailable = true
        retry.controller.chooseDestination()
        retry.activity.deliver(1, null)
        verify("Cancelling the destination picker preserves a retryable preview",
            !retry.controller.picking && retry.controller.plan === preview && GameArchiveStorage.extractions == 0)
        retry.close()

        GameArchiveStorage.reset()
        val savedJob = GameArchiveRecovery.Job("attempt", "PSP", "game.zip", 42L,
            Uri.parse("content://fixture/tree/output"))
        GameArchiveRecovery.saved = savedJob
        GameArchiveRecovery.destinationAvailable = false
        val reconnecting = Fixture()
        reconnecting.controller.restore(null)
        check(reconnecting.controller.openRecovery())
        verify("Missing destination access exposes a recoverable saved job",
            reconnecting.controller.destinationAccessMissing && reconnecting.controller.recovery == savedJob)
        reconnecting.controller.chooseRecoveryDestination()
        reconnecting.activity.deliver(1, null)
        verify("Cancelling destination recovery reopens the unchanged dialog",
            reconnecting.controller.recoveryOpen && !reconnecting.controller.picking &&
            reconnecting.controller.destinationAccessMissing && GameArchiveRecovery.grantRequests.isEmpty())
        for (wrong in listOf("content://fixture/tree/other", "content://other/tree/output",
                "content://fixture/tree/output/document/output")) {
            reconnecting.controller.chooseRecoveryDestination()
            reconnecting.activity.deliver(1, Uri.parse(wrong))
        }
        verify("Other SAF trees, authorities and document paths are rejected before requesting grants",
            reconnecting.controller.recoveryAccessMessage == R.string.game_archive_original_destination &&
                GameArchiveRecovery.grantRequests.isEmpty() && GameArchiveRecovery.saved == savedJob)
        reconnecting.controller.chooseRecoveryDestination()
        val replacementJob = savedJob.copy(id = "new-attempt")
        GameArchiveRecovery.saved = replacementJob
        reconnecting.activity.deliver(1, savedJob.tree)
        verify("A delayed destination result cannot grant access to a replaced journal",
            GameArchiveRecovery.grantRequests.isEmpty() && reconnecting.controller.recovery == replacementJob)
        reconnecting.controller.chooseRecoveryDestination()
        val pendingRestore = Bundle().also(reconnecting.controller::save)
        reconnecting.close()
        val recreateReconnect = Fixture()
        recreateReconnect.controller.restore(pendingRestore)
        recreateReconnect.activity.deliver(1, replacementJob.tree)
        verify("A recreated picker expires the previous request and returns to the recovery dialog",
            recreateReconnect.controller.recoveryOpen && !recreateReconnect.controller.picking &&
                GameArchiveRecovery.grantRequests.isEmpty() && GameArchiveRecovery.saved == replacementJob)
        recreateReconnect.controller.chooseRecoveryDestination()
        PortableMaintenance.runExclusive(recreateReconnect.activity, {}) {
            recreateReconnect.activity.deliver(1, replacementJob.tree)
        }
        verify("Maintenance ignores a destination picker result before granting access",
            GameArchiveRecovery.grantRequests.isEmpty() && GameArchiveRecovery.saved == replacementJob)
        GameArchiveRecovery.denyDestination = true
        recreateReconnect.controller.chooseRecoveryDestination()
        recreateReconnect.activity.deliver(1, replacementJob.tree)
        verify("Denied exact grant keeps the job and allows retry",
            recreateReconnect.controller.destinationAccessMissing &&
            recreateReconnect.controller.recoveryAccessMessage == R.string.game_archive_destination_access_failed)
        GameArchiveRecovery.denyDestination = false
        recreateReconnect.controller.chooseRecoveryDestination()
        recreateReconnect.activity.deliver(1, replacementJob.tree)
        recreateReconnect.activity.deliver(1, replacementJob.tree)
        verify("Exact grant restores the same job once without auto-resume or deletion",
            !recreateReconnect.controller.destinationAccessMissing && recreateReconnect.controller.recoveryOpen &&
                recreateReconnect.controller.recovery == replacementJob &&
                GameArchiveRecovery.grantRequests == listOf(replacementJob.tree, replacementJob.tree) &&
                GameArchiveStorage.resumes == 0 && GameArchiveStorage.discards == 0 && GameArchiveRecovery.clears == 0)
        recreateReconnect.close()
        GameArchiveStorage.reset()
        GameArchiveRecovery.saved = savedJob
        val cold = Fixture()
        cold.controller.restore(null)
        verify("Cold startup exposes saved recovery without starting storage writes",
            cold.controller.recovery == savedJob && !cold.controller.recoveryOpen && !cold.controller.busy &&
                GameArchiveStorage.extractions == 0 && GameArchiveStorage.resumes == 0 && GameArchiveStorage.discards == 0)
        val interruptedState = Bundle().also { it.putBoolean("gameArchiveHadWork", true) }
        cold.close()
        val recovered = Fixture()
        recovered.controller.restore(interruptedState)
        verify("Recreation preserves a saved interrupted attempt without reporting an expired preview",
            recovered.controller.recovery == savedJob && recovered.activity.notice.isEmpty() && GameArchiveRecovery.clears == 0)
        recovered.controller.chooseSource("SNES")
        verify("Choosing another ZIP first presents the existing recovery without opening a picker",
            recovered.controller.recoveryOpen && recovered.activity.launches.isEmpty() && GameArchiveStorage.inspections == 0)
        recovered.controller.resumeRecovery()
        verify("Resume is explicit and uses the platform saved with the original attempt",
            GameArchiveStorage.resumes == 1 && GameArchiveStorage.lastResumed == savedJob &&
                recovered.controller.result == android.provider.DocumentsContract.buildDocumentUriUsingTree(savedJob.tree, "prepared") && !recovered.controller.busy)
        recovered.acceptFolder = false
        val beforeReject = recovered.controller.dialogRevision
        recovered.controller.addResult()
        verify("Rejected folder import keeps the prepared result available for retry",
            recovered.controller.result != null && recovered.added == null &&
                recovered.controller.dialogRevision > beforeReject && GameArchiveRecovery.clears == 0)
        recovered.acceptFolder = true
        recovered.controller.addResult()
        verify("Choosing import retains the ready recovery until matching folder confirmation",
            recovered.added?.first == "PSP" && recovered.controller.result == null &&
                GameArchiveRecovery.saved?.phase == GameArchiveRecovery.Phase.READY && GameArchiveRecovery.clears == 0)
        val importState = Bundle().also(recovered.controller::save)
        recovered.close()
        val importRecreated = Fixture()
        importRecreated.controller.restore(importState)
        importRecreated.controller.folderImported(Uri.parse("content://fixture/tree/prepared"))
        verify("Recreated matching folder confirmation clears only the ready recovery record",
            GameArchiveRecovery.saved == null && GameArchiveRecovery.clears == 1 &&
                importRecreated.controller.recovery == null && GameArchiveStorage.discards == 0)
        importRecreated.close()

        GameArchiveStorage.reset()
        GameArchiveRecovery.saved = savedJob
        val denied = Fixture()
        denied.controller.restore(null)
        GameArchiveStorage.failResume = true
        denied.controller.resumeRecovery()
        verify("A failed resume preserves the saved attempt and allows an explicit retry",
            GameArchiveStorage.resumes == 1 && denied.controller.result == null && !denied.controller.busy &&
                denied.controller.recovery == savedJob && GameArchiveRecovery.saved == savedJob && GameArchiveRecovery.clears == 0)
        GameArchiveStorage.failResume = false
        GameArchiveStorage.failDiscard = true
        denied.controller.discardRecovery()
        verify("Failed discard keeps the recovery open without forgetting the destination",
            GameArchiveStorage.discards == 1 && denied.controller.recoveryOpen &&
                denied.controller.recovery == savedJob && GameArchiveRecovery.saved == savedJob &&
                denied.controller.discardFailed)
        GameArchiveStorage.failDiscard = false
        denied.controller.discardRecovery()
        verify("Only an explicit successful discard removes an interrupted attempt",
            GameArchiveStorage.discards == 2 && GameArchiveRecovery.saved == null && denied.controller.recovery == null &&
                !denied.controller.recoveryOpen && !denied.controller.busy && GameArchiveRecovery.clears == 1)
        denied.close()

        GameArchiveStorage.reset()
        GameArchiveRecovery.saved = savedJob
        val forgotten = Fixture()
        forgotten.controller.restore(null)
        forgotten.controller.forgetRecovery()
        verify("Forgetting is offered only after an unsuccessful deletion",
            GameArchiveRecovery.saved == savedJob && GameArchiveRecovery.clears == 0)
        GameArchiveStorage.failDiscard = true
        forgotten.controller.discardRecovery()
        forgotten.controller.forgetRecovery()
        verify("The explicit keep-files action clears only the record",
            GameArchiveStorage.discards == 1 && GameArchiveRecovery.saved == null && GameArchiveRecovery.clears == 1)
        forgotten.close()

        GameArchiveStorage.reset()
        GameArchiveRecovery.saved = savedJob
        val maintenance = Fixture()
        maintenance.controller.restore(null)
        PortableMaintenance.runExclusive(maintenance.activity, {}) {
            maintenance.controller.resumeRecovery()
            maintenance.controller.discardRecovery()
            verify("Maintenance blocks resume and discard before any recovery storage action",
                GameArchiveStorage.resumes == 0 && GameArchiveStorage.discards == 0 && GameArchiveRecovery.saved == savedJob)
        }
        maintenance.close()

        GameArchiveStorage.reset()
        GameArchiveRecovery.failRead = true
        val unreadable = Fixture()
        unreadable.controller.restore(null)
        verify("An unreadable recovery record reports an error without starting or discarding an attempt",
            unreadable.activity.notice == R.string.game_archive_recovery_unavailable.toString() &&
                GameArchiveStorage.extractions == 0 && GameArchiveStorage.resumes == 0 && GameArchiveStorage.discards == 0 &&
                GameArchiveRecovery.clears == 0)
        unreadable.close()
        println("COUNT|$checks")
    }
}
''',
}

# The compiler's coroutine runtime is resolved by every CI build; do not depend
# on an unrelated version being left in a developer's Gradle cache.
runtime = ":".join(map(str, [COMPILER[1], COMPILER[4]]))
report = {
    "timestamp": datetime.now(timezone.utc).isoformat(),
    "scope": __doc__,
    "sourceSha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in [controller_path, maintenance_path]},
    "passed": False,
}
sources["Boundaries.kt"] = sources["Boundaries.kt"].replace("PRODUCTION_RECONNECT", reconnect)

with tempfile.TemporaryDirectory(prefix="gostex-archive-lifecycle-") as directory:
    folder = Path(directory)
    for name, source_text in sources.items():
        (folder / name).write_text(source_text)
    compiled = subprocess.run([
        str(JAVA), "-cp", ":".join(map(str, COMPILER)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-classpath", runtime, "-d", str(folder / "classes"),
        str(maintenance_path), *map(str, folder.glob("*.kt")),
    ], capture_output=True, text=True, timeout=60)
    report["compile"] = {"code": compiled.returncode, "stderr": compiled.stderr}
    if compiled.returncode == 0:
        run = subprocess.run([str(JAVA), "-cp", f"{folder / 'classes'}:{runtime}", "tv.orbit.shield.ChecksKt"],
                             capture_output=True, text=True, timeout=15)
        report["run"] = {"code": run.returncode, "stdout": run.stdout, "stderr": run.stderr}
        report["passed"] = run.returncode == 0
output = ROOT / ".reports/archive-lifecycle-2026-11-14"
output.mkdir(parents=True, exist_ok=True)
(output / "controller-check.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
print(json.dumps({"passed": report["passed"], "checks": report.get("run", {}).get("stdout", "").count("PASS|"),
                  "errors": report.get("run", report["compile"]).get("stderr", "")}))
raise SystemExit(0 if report["passed"] else 1)
