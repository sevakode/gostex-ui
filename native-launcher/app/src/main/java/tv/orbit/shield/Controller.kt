package tv.orbit.shield

import android.content.Context
import android.content.SharedPreferences
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

internal data class ControllerState(val preference: String = "auto", val style: String = "remote", val name: String = "")
internal val LocalController = staticCompositionLocalOf { ControllerState() }
private val styles = listOf("auto", "xbox", "playstation", "nintendo", "remote")

// Hints follow Android logical A/B actions; this never remaps emulator input.
internal fun detectedControllerStyle(vendor: Int, name: String): String = when {
    vendor == 0x054c || name.contains("dualsense", true) || name.contains("dualshock", true) || name.contains("playstation", true) -> "playstation"
    vendor == 0x057e || name.contains("nintendo", true) -> "nintendo"
    else -> "xbox"
}

@Composable internal fun ControllerProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("orbit", Context.MODE_PRIVATE) }
    val manager = remember { context.getSystemService(Context.INPUT_SERVICE) as InputManager }
    var state by remember { mutableStateOf(ControllerState()) }
    DisposableEffect(manager, prefs) {
        fun refresh() {
            val preference = prefs.readStringOrDefault("controllerIcons", "auto").takeIf { it in styles } ?: "auto"
            val device = manager.inputDeviceIds.toList().mapNotNull(manager::getInputDevice).firstOrNull {
                !it.isVirtual && !it.name.contains("virtual", true) && it.name != "shield-ask-remote" && it.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
            }
            state = ControllerState(preference, if (preference != "auto") preference else device?.let { detectedControllerStyle(it.vendorId, it.name) } ?: "remote", device?.name.orEmpty())
        }
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = refresh()
            override fun onInputDeviceRemoved(deviceId: Int) = refresh()
            override fun onInputDeviceChanged(deviceId: Int) = refresh()
        }
        val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "controllerIcons") refresh() }
        manager.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        refresh()
        onDispose { manager.unregisterInputDeviceListener(listener); prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener) }
    }
    CompositionLocalProvider(LocalController provides state, content = content)
}

@Composable internal fun controllerStyleName(style: String): String = when (style) {
    "xbox" -> "Xbox / SHIELD"
    "playstation" -> "PlayStation"
    "nintendo" -> "Nintendo"
    "remote" -> tr(R.string.controller_remote)
    else -> tr(R.string.controller_auto)
}

@Composable internal fun ControlHints(
    action: Int? = null,
    back: Int,
    showHomeNavigation: Boolean = false,
    showHoldMenu: Boolean = false
) {
    val style = LocalController.current.style
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (action != null) {
            ButtonGlyph(style, false)
            Text(tr(action), color = Color(0xFFADADB0), fontSize = 11.sp)
            Spacer(Modifier.width(10.dp))
        }
        ButtonGlyph(style, true)
        Text(tr(back), color = Color(0xFFADADB0), fontSize = 11.sp)
        if (showHoldMenu) {
            Spacer(Modifier.width(10.dp))
            ButtonGlyph(style, false)
            Text(tr(R.string.home_control_hold_options), color = Color(0xFFADADB0), fontSize = 11.sp)
        }
        if (showHomeNavigation && style != "remote") {
            Spacer(Modifier.width(10.dp))
            val shoulderLabels = when (style) {
                "playstation" -> "L1 / R1"
                "nintendo" -> "L / R"
                else -> "LB / RB"
            }
            Box(Modifier.border(1.dp, Color(0xFFADADB0), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                Text(shoulderLabels, color = Color(0xFFF5F5F5), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text(tr(R.string.home_control_shelves), color = Color(0xFFADADB0), fontSize = 11.sp)
        }
    }
}

@Composable private fun ButtonGlyph(style: String, back: Boolean) {
    val symbol = when (style) {
        "playstation" -> if (back) "○" else "×"
        "remote" -> if (back) "↶" else "OK"
        else -> if (back) "B" else "A"
    }
    val color = when (style) {
        "xbox" -> if (back) Color(0xFFFF8585) else Color(0xFF9CDD72)
        "playstation" -> if (back) Color(0xFFFF8585) else Color(0xFF8CBFFF)
        else -> Color(0xFFF5F5F5)
    }
    Box(Modifier.size(23.dp).border(1.dp, color, CircleShape), contentAlignment = Alignment.Center) {
        Text(symbol, color = color, fontSize = if (style == "remote") 10.sp else 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable internal fun ControllerSettings(initialFocus: FocusRequester) {
    val state = LocalController.current
    val context = LocalContext.current
    Text(tr(R.string.controller_hint), color = Color(0xFFADADB0), fontSize = 13.sp)
    Text(if (state.name.isEmpty()) tr(R.string.controller_none) else tr(R.string.controller_detected, state.name), color = Color(0xFFADADB0), fontSize = 12.sp)
    Text(tr(R.string.button_icons), color = Color(0xFFCCFF00), fontSize = 12.sp)
    styles.forEach { style ->
        val current = style == state.preference
        SettingRow(controllerStyleName(style), modifier = if (current) Modifier.focusRequester(initialFocus) else Modifier,
            isSelected = current, trailing = if (current) "✓" else "", onClick = {
                context.getSharedPreferences("orbit", Context.MODE_PRIVATE).edit().putString("controllerIcons", style).apply()
            })
    }
    ControlHints(R.string.control_select, R.string.back)
}
