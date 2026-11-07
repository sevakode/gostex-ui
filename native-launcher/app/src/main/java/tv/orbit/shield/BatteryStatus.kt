package tv.orbit.shield

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.tv.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

internal fun batteryPercent(level: Int, scale: Int): Int? =
    if (level < 0 || scale <= 0 || level > scale) null else ((level.toLong() * 100) / scale).toInt()

internal object BatteryPreferences {
    const val KEY = "status.batteryVisible"
    fun visible(context: Context) = context.getSharedPreferences("orbit", 0).readBooleanOrDefault(KEY, true)
    fun visible(context: Context, value: Boolean) { context.getSharedPreferences("orbit", 0).edit().putBoolean(KEY, value).apply() }
}

@Composable internal fun rememberBatteryVisible(): Boolean {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("orbit", 0) }
    var visible by remember(context) { mutableStateOf(BatteryPreferences.visible(context)) }
    DisposableEffect(context, prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == BatteryPreferences.KEY) visible = BatteryPreferences.visible(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        visible = BatteryPreferences.visible(context)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return visible
}

private data class BatteryState(val percent: Int, val charging: Boolean)

/** Observe the system battery only while this indicator is composed and the activity is started. */
@Composable internal fun BatteryStatus(activity: MainActivity, label: (Int, Boolean) -> String) {
    val context = activity.applicationContext
    var state by remember(activity) { mutableStateOf<BatteryState?>(null) }
    DisposableEffect(activity, context) {
        var registered = false
        fun update(intent: Intent?) {
            if (intent == null || !intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) {
                state = null
                return
            }
            val percent = batteryPercent(intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1))
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            state = percent?.let { BatteryState(it, status == BatteryManager.BATTERY_STATUS_CHARGING || (status == BatteryManager.BATTERY_STATUS_FULL && plugged)) }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { update(intent) }
        }
        fun stop() {
            if (registered) runCatching { context.unregisterReceiver(receiver) }
            registered = false
            state = null
        }
        fun start() {
            if (registered) return
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            runCatching {
                val sticky = if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    else { @Suppress("DEPRECATION") context.registerReceiver(receiver, filter) }
                registered = true
                update(sticky)
            }.onFailure { state = null }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) start()
            if (event == Lifecycle.Event.ON_STOP) stop()
        }
        activity.lifecycle.addObserver(observer)
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { activity.lifecycle.removeObserver(observer); stop() }
    }
    state?.let { battery ->
        val tint = if (battery.percent <= 15 && !battery.charging) Color(0xFFFF8B80) else Color.White
        Row(Modifier.semantics(mergeDescendants = true) { contentDescription = label(battery.percent, battery.charging) }, verticalAlignment = Alignment.CenterVertically) {
            Text("${battery.percent}%", color = tint, fontSize = 12.sp)
            Spacer(Modifier.width(4.dp))
            Canvas(Modifier.size(width = 21.dp, height = 12.dp)) {
                val w = size.width
                val h = size.height
                val stroke = h * .11f
                drawRect(tint, Offset(stroke / 2, stroke / 2), Size(w * .85f - stroke, h - stroke), style = Stroke(stroke))
                drawRect(tint, Offset(w * .87f, h * .3f), Size(w * .13f, h * .4f))
                drawRect(tint, Offset(w * .1f, h * .2f), Size(w * .65f * battery.percent / 100f, h * .6f))
            }
        }
    }
}
