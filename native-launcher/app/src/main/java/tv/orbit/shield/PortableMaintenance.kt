package tv.orbit.shield

import android.os.Looper
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex

/** Process-local barrier; acquisition and drain begin on the main thread, before
 * any caller may write. A restore must never execute if drain fails or is cancelled. */
internal enum class PortableMaintenancePhase { PREPARING, CANCELLING, SAVING }

internal object PortableMaintenance {
    private val mutex = Mutex()
    private val mutableActive = MutableStateFlow(false)
    private val mutablePhase = MutableStateFlow(PortableMaintenancePhase.PREPARING)
    private var activeJob: Job? = null
    var pendingOutcome: Int? = null
    private val participants = linkedMapOf<Any, () -> Unit>()
    val active = mutableActive.asStateFlow()
    val phase = mutablePhase.asStateFlow()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val running: Boolean get() = mutableActive.value

    fun registerParticipant(owner: Any, stop: () -> Unit): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (running) { stop(); return false }
        check(owner !in participants)
        participants[owner] = stop
        return true
    }
    fun unregisterParticipant(owner: Any) {
        check(Looper.myLooper() == Looper.getMainLooper())
        participants.remove(owner)
    }

    fun requestCancel() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (running && mutablePhase.value == PortableMaintenancePhase.PREPARING) {
            mutablePhase.value = PortableMaintenancePhase.CANCELLING
            activeJob?.cancel()
        }
    }
    suspend fun sealForCommit() {
        check(Looper.myLooper() == Looper.getMainLooper() && running)
        currentCoroutineContext().ensureActive()
        check(mutablePhase.value == PortableMaintenancePhase.PREPARING)
        mutablePhase.value = PortableMaintenancePhase.SAVING
    }

    fun afterMaintenance(action: () -> Unit) {
        scope.launch { active.first { !it }; action() }
    }

    suspend fun <T> runExclusive(context: Context, drain: suspend () -> Unit, operation: suspend () -> T): T {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Maintenance must start on main" }
        check(PortableRestoreStartup.ready) { "Startup recovery required" }
        val job = currentCoroutineContext()[Job] ?: error("Maintenance requires a job")
        check(mutex.tryLock()) { "Maintenance already running" }
        activeJob = job
        mutablePhase.value = PortableMaintenancePhase.PREPARING
        mutableActive.value = true
        var drained = false
        try {
            participants.values.toList().forEach { it() }
            drain()
            withTimeout(10_000) { while (participants.isNotEmpty()) delay(20) }
            drained = true
            currentCoroutineContext().ensureActive()
            val result = operation()
            val recovered = withContext(NonCancellable + Dispatchers.IO) {
                PortableAndroidRestoreRecovery.recoverUnderExclusiveAccess(context.applicationContext)
            }
            check(!recovered) { "Operation left an unfinished restore" }
            return result
        } catch (cause: Throwable) {
            if (drained) {
                try {
                    withContext(NonCancellable + Dispatchers.IO) {
                        PortableAndroidRestoreRecovery.recoverUnderExclusiveAccess(context.applicationContext)
                    }
                } catch (_: Throwable) { PortableRestoreStartup.requireRecovery() }
            }
            throw cause
        } finally {
            mutableActive.value = false
            activeJob = null
            mutablePhase.value = PortableMaintenancePhase.PREPARING
            mutex.unlock()
        }
    }
}
