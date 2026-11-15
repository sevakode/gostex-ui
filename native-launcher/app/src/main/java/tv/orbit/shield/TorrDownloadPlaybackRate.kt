package tv.orbit.shield

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration

/** Holds the active transfer's effective write limit. Reading it does not query Android services. */
internal class TorrDownloadPlaybackRate(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("orbit", Context.MODE_PRIVATE)
    private val audioManager by lazy { runCatching { this.context.getSystemService(AudioManager::class.java) }.getOrNull() }
    private val lock = Any()
    private var closed = false
    private var regularLimit = 0
    private var playbackLimit = 0
    private var playingMedia = false
    private var callback: AudioManager.AudioPlaybackCallback? = null
    @Volatile private var effectiveLimit = 0

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == TorrDownloadWriteRate.KEY || key == TorrDownloadWriteRate.PLAYBACK_KEY) refreshLimits()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        refreshLimits()
    }

    fun current(): Int = effectiveLimit

    private fun refreshLimits() = synchronized(lock) {
        if (closed) return@synchronized
        regularLimit = TorrDownloadWriteRate.read(context)
        playbackLimit = TorrDownloadWriteRate.readPlayback(context)
        if (playbackLimit == 0) stopObserving() else if (callback == null) startObserving()
        updateEffectiveLimit()
    }

    private fun startObserving() {
        val manager = audioManager ?: return
        val listener = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                synchronized(lock) {
                    if (callback !== this) return
                    playingMedia = configs.hasMediaPlayback()
                    updateEffectiveLimit()
                }
            }
        }
        callback = listener
        try {
            manager.registerAudioPlaybackCallback(listener, null)
            playingMedia = manager.activePlaybackConfigurations.hasMediaPlayback()
        } catch (_: RuntimeException) {
            stopObserving()
        }
    }

    private fun stopObserving() {
        val previous = callback
        callback = null
        playingMedia = false
        if (previous != null) runCatching { audioManager?.unregisterAudioPlaybackCallback(previous) }
    }

    private fun updateEffectiveLimit() {
        effectiveLimit = when {
            !playingMedia || playbackLimit == 0 -> regularLimit
            regularLimit == 0 -> playbackLimit
            else -> minOf(regularLimit, playbackLimit)
        }
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        stopObserving()
        updateEffectiveLimit()
    }

    private fun List<AudioPlaybackConfiguration>.hasMediaPlayback(): Boolean =
        any { it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA }
}
