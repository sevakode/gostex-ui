package tv.orbit.shield

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.*

internal object NetworkPreferences {
    const val KEY = "status.networkVisible"
    fun visible(context: Context) = context.getSharedPreferences("orbit", 0).readBooleanOrDefault(KEY, true)
    fun visible(context: Context, value: Boolean) { context.getSharedPreferences("orbit", 0).edit().putBoolean(KEY, value).apply() }
}
@Composable internal fun rememberNetworkVisible(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("orbit", 0) }
    var visible by remember(context) { mutableStateOf(NetworkPreferences.visible(context)) }
    DisposableEffect(context, prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == NetworkPreferences.KEY) visible = NetworkPreferences.visible(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        visible = NetworkPreferences.visible(context)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return visible
}
private data class NetworkState(val transport: String = "offline", val validated: Boolean = false, val level: Int = -1)

/** Callbacks are registered only while the header is visible and its Activity is started. */
@Composable internal fun NetworkStatusButton(activity: MainActivity) {
    val context = activity.applicationContext
    val connectivity = remember(context) { context.getSystemService(ConnectivityManager::class.java) }
    val wifi = remember(context) { context.getSystemService(WifiManager::class.java) }
    var state by remember { mutableStateOf(NetworkState()) }
    DisposableEffect(activity, connectivity, wifi) {
        val handler = Handler(Looper.getMainLooper())
        var registered = false
        var receiverRegistered = false
        fun refresh() {
            if (!registered) return
            val caps = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
            val underlying = if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
                connectivity.allNetworks.mapNotNull { connectivity.getNetworkCapabilities(it) }
                    .firstOrNull { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) }
                else null
            val transport = when {
                caps == null -> "offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) || underlying?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || underlying?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                else -> "network"
            }
            @Suppress("DEPRECATION")
            val level = if (transport == "wifi") runCatching {
                wifi?.connectionInfo?.rssi?.takeIf { it in -126..0 }?.let { WifiManager.calculateSignalLevel(it, 4) } ?: -1
            }.getOrDefault(-1) else -1
            state = NetworkState(transport, caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true, level)
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { handler.post { refresh() } }
            override fun onLost(network: Network) { handler.post { refresh() } }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { handler.post { refresh() } }
        }
        val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { refresh() } }
        fun stop() {
            if (registered) runCatching { connectivity?.unregisterNetworkCallback(callback) }
            registered = false
            if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
            handler.removeCallbacksAndMessages(null)
        }
        fun start() {
            if (registered) return
            registered = true
            runCatching {
                if (Build.VERSION.SDK_INT >= 30)
                    connectivity?.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), callback, handler)
                else connectivity?.registerDefaultNetworkCallback(callback, handler)
            }.onFailure { registered = false }
            if (registered) {
                val filter = IntentFilter(WifiManager.RSSI_CHANGED_ACTION)
                runCatching {
                    if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    else { @Suppress("DEPRECATION") context.registerReceiver(receiver, filter) }
                    receiverRegistered = true
                }
                refresh()
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) start()
            if (event == Lifecycle.Event.ON_STOP) stop()
        }
        activity.lifecycle.addObserver(observer)
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { activity.lifecycle.removeObserver(observer); stop() }
    }
    val label = tr(when {
        state.transport == "offline" -> R.string.network_disconnected
        !state.validated -> R.string.network_no_internet
        state.transport == "ethernet" -> R.string.network_ethernet
        state.transport == "wifi" -> R.string.network_wifi
        else -> R.string.network_connected
    })
    val tint = if (state.transport == "offline") Color(0xFFADADB0) else if (!state.validated) Color(0xFFFFC45C) else Color(0xFFCCFF00)
    Surface(onClick = { activity.openNetworkSettings() }, modifier = Modifier.semantics { contentDescription = label },
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color(0xFF303033)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color(0xFFCCFF00))))) {
        Box(Modifier.padding(8.dp)) {
            Canvas(Modifier.size(24.dp)) {
                val w = size.width
                val h = size.height
                val stroke = w * .09f
                if (state.transport == "ethernet") {
                    drawRect(tint, Offset(w*.2f,h*.12f), Size(w*.6f,h*.5f), style=Stroke(stroke))
                    drawLine(tint, Offset(w*.5f,h*.62f), Offset(w*.5f,h*.88f), stroke, StrokeCap.Round)
                    drawLine(tint, Offset(w*.22f,h*.88f), Offset(w*.78f,h*.88f), stroke, StrokeCap.Round)
                } else {
                    repeat(3) { i ->
                        val radius = w * (.2f + i * .19f)
                        val active = state.transport != "offline" && (state.level < 0 || i < state.level)
                        drawArc(if (active) tint else tint.copy(alpha=.22f), 225f, 90f, false,
                            Offset(w*.5f-radius,h*.87f-radius), Size(radius*2,radius*2), style=Stroke(stroke, cap=StrokeCap.Round))
                    }
                    drawCircle(tint, stroke*.65f, Offset(w*.5f,h*.87f))
                    if (state.transport == "offline") drawLine(tint,Offset(w*.12f,h*.12f),Offset(w*.88f,h*.92f),stroke,StrokeCap.Round)
                    else if (!state.validated) drawCircle(Color(0xFFFFC45C), stroke, Offset(w*.9f,h*.78f))
                }
            }
        }
    }
}
