package tv.orbit.shield

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** An explicit, optional shortcut; the service never stores or transmits key events. */
internal object ScreensaverShortcutPreferences {
    const val KEY = "art.netflixShortcut"
    fun enabled(context: Context): Boolean = runCatching {
        context.getSharedPreferences("orbit", Context.MODE_PRIVATE).getBoolean(KEY, false)
    }.getOrDefault(false)
    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences("orbit", Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
    }
}

class ScreensaverShortcutService : AccessibilityService() {
    private var preferences: SharedPreferences? = null
    private var consumedDown = false
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == ScreensaverShortcutPreferences.KEY) updateKeyFilter()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        preferences = getSharedPreferences("orbit", Context.MODE_PRIVATE).also {
            it.registerOnSharedPreferenceChangeListener(listener)
        }
        updateKeyFilter()
    }

    private fun updateKeyFilter() {
        val info = serviceInfo ?: return
        info.flags = if (ScreensaverShortcutPreferences.enabled(this)) {
            info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        } else {
            info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        }
        serviceInfo = info
        if (!ScreensaverShortcutPreferences.enabled(this)) consumedDown = false
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val device = event.device
        if (!ScreensaverShortcutPreferences.enabled(this) || device?.vendorId != 0x0955 ||
            device.productId != 0x7217 || event.keyCode != KeyEvent.KEYCODE_BUTTON_12) return false
        if (event.action == KeyEvent.ACTION_UP) return consumedDown.also { consumedDown = false }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount > 0) return consumedDown
        if (!PortableRestoreStartup.ready || PortableMaintenance.running) return false
        consumedDown = runCatching {
            startActivity(Intent(this, ShortcutScreensaverActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            true
        }.getOrDefault(false)
        return consumedDown
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        preferences?.unregisterOnSharedPreferenceChangeListener(listener)
        preferences = null
        super.onDestroy()
    }
}

/** Displays the selected screensaver without requesting audio focus or controlling playback. */
class ShortcutScreensaverActivity : Activity() {
    private var art: PixelArtView? = null
    private var grafana: GrafanaScreen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!PortableRestoreStartup.ready || !PortableMaintenance.registerParticipant(this) { finish() }) {
            finish(); return
        }
        ArtSchedule.install(this)
        val context = Language.context(this)
        when (GrafanaPreferences.read(this).mode) {
            ScreensaverMode.ART -> PixelArtView(context).also { art = it; setContentView(it) }
            ScreensaverMode.GRAFANA -> GrafanaScreen(context, interactive = false).also {
                grafana = it; it.configure(GrafanaPreferences.read(this)); setContentView(it)
            }
        }
        window.hidePreviewSystemBars()
    }
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); finish() }
    override fun onResume() { super.onResume(); if (PortableMaintenance.running) { finish(); return }; art?.start(); grafana?.start() }
    override fun onPause() { art?.stop(); grafana?.stop(); super.onPause() }
    override fun onDestroy() {
        PortableMaintenance.unregisterParticipant(this)
        art?.stop(); art = null
        grafana?.destroy(); grafana = null
        super.onDestroy()
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) finish()
        return true
    }
}
