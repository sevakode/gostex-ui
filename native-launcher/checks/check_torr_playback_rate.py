"""Exercise production playback write limits against Android preference and audio stubs."""
import os
from pathlib import Path
import subprocess
import tempfile

from kotlin_toolchain import COMPILER, JAVA, ROOT


sources = {
    'Context.kt': r'''
package android.content

import android.media.AudioManager

class Context(private val audio: AudioManager? = null) {
    companion object { const val MODE_PRIVATE = 0 }
    val applicationContext get() = this
    val prefs = SharedPreferences()
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    fun <T> getSystemService(service: Class<T>): T? = audio?.let(service::cast)
}

class SharedPreferences {
    fun interface OnSharedPreferenceChangeListener {
        fun onSharedPreferenceChanged(preferences: SharedPreferences, key: String?)
    }

    private val values = mutableMapOf<String, Int>()
    private val listeners = mutableSetOf<OnSharedPreferenceChangeListener>()
    val listenerCount get() = listeners.size
    fun getInt(key: String, default: Int): Int = values[key] ?: default
    fun edit() = Editor()
    fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {
        check(listeners.add(listener))
    }
    fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {
        check(listeners.remove(listener))
    }

    inner class Editor {
        private val changes = mutableMapOf<String, Int>()
        fun putInt(key: String, value: Int) = apply { changes[key] = value }
        fun commit(): Boolean {
            values.putAll(changes)
            changes.keys.forEach { key -> listeners.toList().forEach { it.onSharedPreferenceChanged(this@SharedPreferences, key) } }
            return true
        }
    }
}
''',
    'Audio.kt': r'''
package android.media

class AudioAttributes(val usage: Int) {
    companion object { const val USAGE_MEDIA = 1 }
}

class AudioPlaybackConfiguration(val audioAttributes: AudioAttributes)

class AudioManager {
    open class AudioPlaybackCallback {
        open fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) = Unit
    }

    var callback: AudioPlaybackCallback? = null
        private set
    var activePlaybackConfigurations = emptyList<AudioPlaybackConfiguration>()
        private set

    fun registerAudioPlaybackCallback(observer: AudioPlaybackCallback, handler: Any?) {
        check(callback == null)
        callback = observer
    }
    fun unregisterAudioPlaybackCallback(observer: AudioPlaybackCallback) {
        check(callback === observer)
        callback = null
    }
    fun emit(configs: List<AudioPlaybackConfiguration>) {
        activePlaybackConfigurations = configs
        callback?.onPlaybackConfigChanged(configs)
    }
}
''',
    'PlaybackRateCheck.kt': r'''
package tv.orbit.shield

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration

fun main() {
    val media = listOf(AudioPlaybackConfiguration(AudioAttributes(AudioAttributes.USAGE_MEDIA)))
    val audio = AudioManager()
    val context = Context(audio)
    val rate = TorrDownloadPlaybackRate(context)
    audio.emit(media)
    check(rate.current() == 0 && audio.callback == null) { "Disabled cap must leave audio unobserved" }

    TorrDownloadWriteRate.writePlayback(context, 1024)
    check(rate.current() == 1024) { "Enabling the cap must read media already playing" }
    audio.emit(emptyList())
    check(rate.current() == 0) { "Stopping playback must restore the regular unlimited rate" }
    TorrDownloadWriteRate.write(context, 512)
    audio.emit(media)
    check(rate.current() == 512) { "The playback cap must not raise a lower regular limit" }
    TorrDownloadWriteRate.write(context, 2048)
    check(rate.current() == 1024) { "Changing the regular limit must recompute the lower cap" }

    val previousCallback = checkNotNull(audio.callback)
    TorrDownloadWriteRate.writePlayback(context, 0)
    check(rate.current() == 2048 && audio.callback == null)
    audio.emit(emptyList())
    TorrDownloadWriteRate.writePlayback(context, 512)
    check(rate.current() == 2048)
    previousCallback.onPlaybackConfigChanged(media)
    check(rate.current() == 2048) { "A callback from before re-enabling must not affect the new observer" }

    val currentCallback = checkNotNull(audio.callback)
    rate.close()
    check(context.prefs.listenerCount == 0 && audio.callback == null) { "Closing must detach both observers" }
    val closedRate = rate.current()
    currentCallback.onPlaybackConfigChanged(media)
    TorrDownloadWriteRate.writePlayback(context, 1024)
    check(rate.current() == closedRate && audio.callback == null) { "Late updates must not revive a closed observer" }
    println("PASS: live playback write caps, regular rate fallback, stale callbacks and cleanup")
}
''',
}

app = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield'
with tempfile.TemporaryDirectory(prefix='gostex-playback-rate-') as temporary:
    directory = Path(temporary)
    for name, source in sources.items():
        (directory / name).write_text(source)
    runtime = str(COMPILER[1])
    classes = directory / 'classes'
    subprocess.run([
        str(JAVA), '-cp', os.pathsep.join(map(str, COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', runtime, '-d', str(classes),
        str(app / 'TorrDownloadPlaybackRate.kt'), str(app / 'TorrDownloadWriteRate.kt'),
        *(str(directory / name) for name in sources),
    ], check=True, timeout=30)
    subprocess.run([
        str(JAVA), '-cp', os.pathsep.join([str(classes), runtime]), 'tv.orbit.shield.PlaybackRateCheckKt',
    ], check=True, timeout=12)
