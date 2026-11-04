package tv.orbit.shield

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.*
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** System minute broadcasts while the launcher is visible; no timer runs behind an emulator. */
@Composable internal fun rememberClockInstant(enabled: Boolean): Instant {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var instant by remember { mutableStateOf(Instant.now()) }
    DisposableEffect(context, owner, enabled) {
        var registered = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { instant = Instant.now() }
        }
        fun stop() {
            if (registered) {
                context.unregisterReceiver(receiver)
                registered = false
            }
        }
        fun start() {
            if (!enabled || registered) return
            instant = Instant.now()
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            registered = true
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { owner.lifecycle.removeObserver(observer); stop() }
    }
    return instant
}

@Composable internal fun HeaderClock(options: ClockOptions, instant: Instant) {
    if (!options.visible || options.zones.size != 1) return
    val context = LocalContext.current
    val zone = options.zones.single()
    val time = formatClockTime(context, zone, options.format, instant)
    val dateFormat = rememberClockDateFormatter(options.showDate)
    val date = dateFormat?.format(instant.atZone(clockZone(zone)))
    Column(Modifier.widthIn(max = 130.dp)) {
        Text(time, color = Color(0xFFF5F5F5), fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        if (zone != LOCAL_CLOCK_ZONE) Text(zoneTitle(context, zone), color = Color(0xFFADADB0), fontSize = 9.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (date != null) Text(date, color = Color(0xFFADADB0), fontSize = 9.sp, maxLines = 1)
    }
}

/** Compact dates are optional and contain no weekday; hidden dates do no formatting work. */
@Composable private fun rememberClockDateFormatter(showDate: Boolean): DateTimeFormatter? {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale, showDate) {
        if (showDate) DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale) else null
    }
}

/** The multi-clock strip scrolls with D-pad focus instead of squeezing navigation off screen. */
@Composable internal fun WorldClockStrip(options: ClockOptions, instant: Instant, onConfigure: (String, FocusRequester) -> Unit) {
    if (!options.visible || options.zones.size < 2) return
    val context = LocalContext.current
    val dateFormat = rememberClockDateFormatter(options.showDate)
    LazyRow(contentPadding = PaddingValues(horizontal = 36.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        items(options.zones, key = { it }) { zone ->
            val focus = remember { FocusRequester() }
            val title = zoneTitle(context, zone)
            val time = formatClockTime(context, zone, options.format, instant)
            val date = dateFormat?.format(instant.atZone(clockZone(zone)))
            Surface(onClick = {
                onConfigure(zone, focus)
            }, modifier = Modifier.width(160.dp).focusRequester(focus).semantics {
                contentDescription = listOfNotNull(title, time, date).joinToString(", ")
            }, shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
                colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1F1F21), focusedContainerColor = Color(0xFF303033)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color(0xFFCCFF00))))) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(time, color = Color(0xFFF5F5F5), fontSize = 14.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, modifier = Modifier.weight(1f), color = Color(0xFFCCFF00), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (date != null) Text(date, color = Color(0xFFADADB0), fontSize = 9.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}
