package tv.orbit.shield

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class TorrDownloadStartupState { IDLE, LOADING, READY, FAILED }

/** One on-demand initialization, drained before a portable restore can change preferences. */
internal object TorrDownloadStartup {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(TorrDownloadStartupState.IDLE)
    val state = mutable.asStateFlow()
    val ready: Boolean get() = mutable.value == TorrDownloadStartupState.READY

    suspend fun prepare(context: Context): Boolean = withContext(Dispatchers.Main.immediate) {
        mutex.withLock {
            if (!PortableRestoreStartup.ready || PortableMaintenance.running) return@withLock false
            if (ready) return@withLock true
            val owner = Any()
            val job = currentCoroutineContext()[Job] ?: error("Startup requires a job")
            if (!PortableMaintenance.registerParticipant(owner) { job.cancel() }) return@withLock false
            mutable.value = TorrDownloadStartupState.LOADING
            try {
                withContext(Dispatchers.IO) { TorrDownloadStore.recover(context.applicationContext) }
                mutable.value = TorrDownloadStartupState.READY
                true
            } catch (cancelled: CancellationException) {
                mutable.value = TorrDownloadStartupState.IDLE
                throw cancelled
            } catch (_: Exception) {
                mutable.value = TorrDownloadStartupState.FAILED
                false
            } finally {
                PortableMaintenance.unregisterParticipant(owner)
            }
        }
    }
}
