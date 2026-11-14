package tv.orbit.shield

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Owns the transient callbacks for one retry delay. */
internal class TorrDownloadRetryWait(
    private val context: Context,
    private val cancelled: () -> Boolean,
    private val offlineTimeoutMs: Long = 90_000L,
) {
    enum class Result { READY, CANCELLED, TIMEOUT }

    private val wakeups = ArrayBlockingQueue<Unit>(1)
    @Volatile private var stopped = false

    fun cancel() {
        stopped = true
        wakeups.offer(Unit)
    }

    fun await(delayMs: Long, onWaitingNetwork: () -> Unit): Result {
        val prefs = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if ((key == null || key == TorrDownloadRetryOptions.KEY) && !TorrDownloadRetryOptions.read(context)) cancel()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            if (shouldStop()) return Result.CANCELLED
            val retryAt = SystemClock.elapsedRealtime() + delayMs
            val manager = runCatching { context.getSystemService(ConnectivityManager::class.java) }.getOrNull()
            if (!isOffline(manager)) {
                val result = awaitBackoff(retryAt)
                if (result != Result.READY || !isOffline(manager)) return result
            }
            return awaitRoute(requireNotNull(manager), retryAt, onWaitingNetwork)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return Result.CANCELLED
        } finally {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    private fun awaitBackoff(retryAt: Long): Result {
        while (!shouldStop()) {
            val remaining = retryAt - SystemClock.elapsedRealtime()
            if (remaining <= 0L) return Result.READY
            wakeups.poll(remaining, TimeUnit.MILLISECONDS)
        }
        return Result.CANCELLED
    }

    private fun awaitRoute(manager: ConnectivityManager, retryAt: Long, onWaitingNetwork: () -> Unit): Result {
        val expiresAt = SystemClock.elapsedRealtime() + offlineTimeoutMs
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { wakeups.offer(Unit) }
            override fun onLost(network: Network) { wakeups.offer(Unit) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { wakeups.offer(Unit) }
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) { wakeups.offer(Unit) }
        }
        if (runCatching { manager.registerDefaultNetworkCallback(callback) }.isFailure) return awaitBackoff(retryAt)
        try {
            var reportedWaiting = false
            while (!shouldStop()) {
                // Query on the worker after subscribing, including changes during registration.
                val offline = isOffline(manager)
                if (offline && !reportedWaiting) {
                    onWaitingNetwork()
                    reportedWaiting = true
                }
                val deadline = if (offline) expiresAt else retryAt
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) return when {
                    shouldStop() -> Result.CANCELLED
                    offline -> Result.TIMEOUT
                    else -> Result.READY
                }
                wakeups.poll(remaining, TimeUnit.MILLISECONDS)
            }
            return Result.CANCELLED
        } finally {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }

    private fun isOffline(manager: ConnectivityManager?): Boolean =
        manager != null && runCatching { manager.activeNetwork == null }.getOrDefault(false)

    private fun shouldStop(): Boolean = stopped || cancelled() || !TorrDownloadRetryOptions.read(context)
}
