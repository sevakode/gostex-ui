"""Exercise the production download retry wait against deterministic Android event stubs."""
import os
from pathlib import Path
import subprocess
import tempfile

from kotlin_toolchain import COMPILER, JAVA, ROOT


sources = {
    'Context.kt': r'''
package android.content

import android.net.ConnectivityManager
import java.util.concurrent.CopyOnWriteArraySet

class Context(private val route: ConnectivityManager) {
    companion object { const val MODE_PRIVATE = 0 }
    val applicationContext get() = this
    val prefs = SharedPreferences()
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    fun <T> getSystemService(service: Class<T>): T? = service.cast(route)
}

class SharedPreferences {
    fun interface OnSharedPreferenceChangeListener {
        fun onSharedPreferenceChanged(preferences: SharedPreferences, key: String?)
    }

    private val listeners = CopyOnWriteArraySet<OnSharedPreferenceChangeListener>()
    @Volatile private var value: Boolean? = null
    val listenerCount get() = listeners.size
    fun getBoolean(key: String, default: Boolean): Boolean = value ?: default
    fun edit(): Editor = Editor()
    fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {
        check(listeners.add(listener))
    }
    fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {
        check(listeners.remove(listener))
    }

    inner class Editor {
        private var key = ""
        private var next = false
        fun putBoolean(key: String, value: Boolean) = apply { this.key = key; next = value }
        fun commit(): Boolean {
            value = next
            listeners.forEach { it.onSharedPreferenceChanged(this@SharedPreferences, key) }
            return true
        }
    }
}
''',
    'ConnectivityManager.kt': r'''
package android.net

import java.util.concurrent.CountDownLatch

class Network
class NetworkCapabilities

class ConnectivityManager {
    open class NetworkCallback {
        open fun onAvailable(network: Network) = Unit
        open fun onLost(network: Network) = Unit
        open fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = Unit
        open fun onBlockedStatusChanged(network: Network, blocked: Boolean) = Unit
    }

    @Volatile var activeNetwork: Network? = null
        private set
    @Volatile private var callback: NetworkCallback? = null
    val observed get() = callback != null
    val registered = CountDownLatch(1)

    fun registerDefaultNetworkCallback(observer: NetworkCallback) {
        check(callback == null)
        callback = observer
        registered.countDown()
    }

    fun unregisterNetworkCallback(observer: NetworkCallback) {
        check(callback === observer)
        callback = null
    }

    fun connect() {
        val route = Network()
        activeNetwork = route
        callback?.onAvailable(route)
    }
}
''',
    'SystemClock.kt': r'''
package android.os

object SystemClock {
    fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000
}
''',
    'WaitCheck.kt': r'''
package tv.orbit.shield

import android.content.Context
import android.net.ConnectivityManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.concurrent.thread
import tv.orbit.shield.TorrDownloadRetryWait.Result

private class Fixture(offlineTimeoutMs: Long = 10_000L) {
    val route = ConnectivityManager()
    val context = Context(route)
    val wait = TorrDownloadRetryWait(context, { false }, offlineTimeoutMs)
    val waiting = CountDownLatch(1)

    fun start(): FutureTask<Result> {
        val result = FutureTask { wait.await(0) { waiting.countDown() } }
        thread(isDaemon = true) { result.run() }
        check(waiting.await(2, SECONDS)) { "Offline status was never announced" }
        check(route.registered.await(2, SECONDS)) { "Default-network observer was never registered" }
        return result
    }

    fun checkCleanup() {
        check(!route.observed) { "Default-network observer leaked" }
        check(context.prefs.listenerCount == 0) { "Retry preference observer leaked" }
    }
}

fun main() {
    val reconnect = Fixture()
    val resumed = reconnect.start()
    check(!resumed.isDone)
    reconnect.route.connect()
    check(resumed.get(2, SECONDS) == Result.READY)
    reconnect.checkCleanup()

    val pause = Fixture()
    val paused = pause.start()
    pause.wait.cancel()
    check(paused.get(2, SECONDS) == Result.CANCELLED)
    pause.checkCleanup()

    val toggle = Fixture()
    val disabled = toggle.start()
    TorrDownloadRetryOptions.write(toggle.context, false)
    check(disabled.get(2, SECONDS) == Result.CANCELLED)
    toggle.checkCleanup()

    val timeout = Fixture(offlineTimeoutMs = 80)
    check(timeout.wait.await(0) { } == Result.TIMEOUT)
    check(timeout.route.registered.count == 0L)
    timeout.checkCleanup()
    println("PASS: reconnect, prompt pause and retry opt-out, bounded offline timeout, observers disposed")
}
''',
}

app = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
with tempfile.TemporaryDirectory(prefix='gostex-retry-wait-') as temporary:
    directory = Path(temporary)
    for name, source in sources.items():
        (directory / name).write_text(source)
    runtime = os.pathsep.join(map(str, [COMPILER[1], COMPILER[4]]))
    classes = directory / 'classes'
    subprocess.run([
        str(JAVA), '-cp', os.pathsep.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', runtime, '-d', str(classes),
        str(app / 'TorrDownloadRetryWait.kt'), str(app / 'TorrDownloadRetryOptions.kt'),
        *(str(directory / name) for name in sources),
    ], check=True, timeout=30)
    subprocess.run([
        str(JAVA), '-cp', os.pathsep.join([str(classes), runtime]), 'tv.orbit.shield.WaitCheckKt',
    ], check=True, timeout=12)
