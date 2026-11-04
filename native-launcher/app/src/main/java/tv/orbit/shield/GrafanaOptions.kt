package tv.orbit.shield

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import java.net.URI

internal enum class ScreensaverMode(val id: String, val titleRes: Int) {
    ART("art", R.string.grafana_mode_art),
    GRAFANA("grafana", R.string.grafana_mode_dashboards);

    companion object {
        fun fromSavedId(value: String?): ScreensaverMode = entries.firstOrNull { it.id == value } ?: ART
    }
}

internal enum class GrafanaScrollMode(val id: String, val titleRes: Int) {
    OFF("off", R.string.grafana_scroll_off),
    SLOW("slow", R.string.grafana_scroll_slow),
    MEDIUM("medium", R.string.grafana_scroll_medium),
    FAST("fast", R.string.grafana_scroll_fast);

    companion object {
        fun fromSavedId(value: String?): GrafanaScrollMode = entries.firstOrNull { it.id == value } ?: OFF
    }
}

internal enum class GrafanaRotationMode(val id: String, val titleRes: Int) {
    SINGLE("single", R.string.grafana_rotation_single),
    ALL("all", R.string.grafana_rotation_all),
    FAVORITES("favorites", R.string.grafana_rotation_favorites);

    companion object {
        fun fromSavedId(value: String?): GrafanaRotationMode = entries.firstOrNull { it.id == value } ?: SINGLE
    }
}

internal data class GrafanaOptions(
    val mode: ScreensaverMode = ScreensaverMode.ART,
    val url: String = "",
    val refreshSeconds: Int = 0,
    val scrollMode: GrafanaScrollMode = GrafanaScrollMode.OFF,
    val rotationMode: GrafanaRotationMode = GrafanaRotationMode.SINGLE,
    val rotationSeconds: Int = 120
)

internal object GrafanaPreferences {
    const val KEY_PREFIX = "grafana."
    const val MAX_URL_LENGTH = 8192
    val refreshChoices = listOf(0, 60, 300, 900)
    val rotationChoices = listOf(30, 60, 120, 300)
    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)

    /** Keep the dashboard/playlist query intact, including its own refresh and kiosk choices. */
    fun normalizeUrl(value: String): String? {
        if (value.length > MAX_URL_LENGTH || value.any { it.isISOControl() }) return null
        val candidate = value.trim()
        if (candidate.isEmpty()) return ""
        if (candidate.any { it.isWhitespace() } || '\\' in candidate) return null
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.isOpaque ||
            uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            (uri.port != -1 && uri.port !in 1..65535)) return null
        return candidate
    }

    fun read(context: Context): GrafanaOptions {
        val preferences = prefs(context)
        return GrafanaOptions(
            mode = ScreensaverMode.fromSavedId(runCatching { preferences.getString(KEY_PREFIX + "mode", null) }.getOrNull()),
            url = normalizeUrl(runCatching { preferences.getString(KEY_PREFIX + "url", "") }.getOrNull().orEmpty()).orEmpty(),
            refreshSeconds = runCatching { preferences.getInt(KEY_PREFIX + "refreshSeconds", 0) }.getOrDefault(0)
                .takeIf { it in refreshChoices } ?: 0,
            scrollMode = GrafanaScrollMode.fromSavedId(runCatching { preferences.getString(KEY_PREFIX + "scrollMode", null) }.getOrNull()),
            rotationMode = GrafanaRotationMode.fromSavedId(runCatching { preferences.getString(KEY_PREFIX + "rotationMode", null) }.getOrNull()),
            rotationSeconds = runCatching { preferences.getInt(KEY_PREFIX + "rotationSeconds", 120) }.getOrDefault(120)
                .takeIf { it in rotationChoices } ?: 120
        )
    }

    fun setMode(context: Context, value: ScreensaverMode) {
        prefs(context).edit().putString(KEY_PREFIX + "mode", value.id).apply()
    }

    fun setUrl(context: Context, value: String): Boolean {
        val normalized = normalizeUrl(value) ?: return false
        prefs(context).edit().putString(KEY_PREFIX + "url", normalized).apply()
        return true
    }

    fun setRefreshSeconds(context: Context, value: Int) {
        if (value in refreshChoices) prefs(context).edit().putInt(KEY_PREFIX + "refreshSeconds", value).apply()
    }

    fun setScrollMode(context: Context, value: GrafanaScrollMode) {
        prefs(context).edit().putString(KEY_PREFIX + "scrollMode", value.id).apply()
    }

    fun setRotationMode(context: Context, value: GrafanaRotationMode) {
        prefs(context).edit().putString(KEY_PREFIX + "rotationMode", value.id).apply()
    }

    fun setRotationSeconds(context: Context, value: Int) {
        if (value in rotationChoices) prefs(context).edit().putInt(KEY_PREFIX + "rotationSeconds", value).apply()
    }
}

/** The drawer owns one listener; no background work remains after it closes. */
@Composable internal fun rememberGrafanaOptions(): GrafanaOptions {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("orbit", Context.MODE_PRIVATE) }
    var options by remember(context) { mutableStateOf(GrafanaPreferences.read(context)) }
    DisposableEffect(context, preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith(GrafanaPreferences.KEY_PREFIX)) options = GrafanaPreferences.read(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        options = GrafanaPreferences.read(context)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return options
}

@Composable internal fun GrafanaSettings(initialFocus: FocusRequester) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val options = rememberGrafanaOptions()
    val urlFocus = remember { FocusRequester() }
    val pickerFocus = remember { FocusRequester() }
    var editingUrl by remember { mutableStateOf(false) }
    var returnToUrl by remember { mutableStateOf(false) }
    var pickingDashboard by remember { mutableStateOf(false) }
    var returnToPicker by remember { mutableStateOf(false) }
    Text(tr(R.string.grafana_settings_hint), color = Color(0xFFADADB0), fontSize = 13.sp, lineHeight = 19.sp)
    SettingRow(tr(R.string.grafana_mode_title), tr(options.mode.titleRes), modifier = Modifier.focusRequester(initialFocus), onClick = {
        GrafanaPreferences.setMode(context, ScreensaverMode.entries[(options.mode.ordinal + 1) % ScreensaverMode.entries.size])
    })
    val addressSummary = remember(options.url) { runCatching { URI(options.url).host }.getOrNull().orEmpty() }
    SettingRow(tr(R.string.grafana_url_title), addressSummary.ifEmpty { tr(R.string.grafana_not_configured) }, modifier = Modifier.focusRequester(urlFocus), onClick = {
        editingUrl = true
    })
    Text(tr(R.string.grafana_url_hint), color = Color(0xFFADADB0), fontSize = 12.sp, lineHeight = 17.sp)
    SettingRow(tr(R.string.grafana_choose_dashboard), modifier = Modifier.focusRequester(pickerFocus), onClick = {
        pickingDashboard = true
    })
    SettingRow(tr(R.string.grafana_rotation_title), tr(options.rotationMode.titleRes), onClick = {
        val choices = GrafanaRotationMode.entries
        GrafanaPreferences.setRotationMode(context, choices[(options.rotationMode.ordinal + 1) % choices.size])
    })
    SettingRow(tr(R.string.grafana_rotation_interval),
        if (options.rotationSeconds < 60) tr(R.string.grafana_rotation_seconds, options.rotationSeconds)
        else tr(R.string.grafana_rotation_minutes, options.rotationSeconds / 60), onClick = {
            val choices = GrafanaPreferences.rotationChoices
            GrafanaPreferences.setRotationSeconds(context, choices[(choices.indexOf(options.rotationSeconds) + 1) % choices.size])
        })
    Text(tr(R.string.grafana_rotation_hint), color = Color(0xFFADADB0), fontSize = 12.sp, lineHeight = 17.sp)
    SettingRow(tr(R.string.grafana_open_login), onClick = {
        if (options.url.isBlank()) Toast.makeText(context, resources.getString(R.string.grafana_url_missing), Toast.LENGTH_LONG).show()
        else context.startActivity(Intent(context, GrafanaPreviewActivity::class.java))
    })
    Text(tr(R.string.grafana_login_hint), color = Color(0xFFADADB0), fontSize = 12.sp, lineHeight = 17.sp)
    SettingRow(tr(R.string.grafana_scroll_title), tr(options.scrollMode.titleRes), onClick = {
        GrafanaPreferences.setScrollMode(context, GrafanaScrollMode.entries[(options.scrollMode.ordinal + 1) % GrafanaScrollMode.entries.size])
    })
    Text(tr(R.string.grafana_scroll_hint), color = Color(0xFFADADB0), fontSize = 12.sp, lineHeight = 17.sp)
    SettingRow(tr(R.string.grafana_refresh_title), if (options.refreshSeconds == 0) tr(R.string.grafana_refresh_off)
        else tr(R.string.grafana_refresh_minutes, options.refreshSeconds / 60), onClick = {
        val choices = GrafanaPreferences.refreshChoices
        GrafanaPreferences.setRefreshSeconds(context, choices[(choices.indexOf(options.refreshSeconds) + 1) % choices.size])
    })
    Text(tr(R.string.grafana_refresh_hint), color = Color(0xFFADADB0), fontSize = 12.sp, lineHeight = 17.sp)
    if (options.url.isBlank()) Text(tr(R.string.grafana_url_missing), color = Color(0xFFADADB0), fontSize = 12.sp, lineHeight = 17.sp)

    if (pickingDashboard) GrafanaDashboardPicker(onDismiss = {
        pickingDashboard = false
        returnToPicker = true
    })
    LaunchedEffect(returnToPicker) {
        if (returnToPicker) {
            withFrameNanos { }
            runCatching { pickerFocus.requestFocus() }
            returnToPicker = false
        }
    }
    LaunchedEffect(returnToUrl) {
        if (returnToUrl) {
            withFrameNanos { }
            runCatching { urlFocus.requestFocus() }
            returnToUrl = false
        }
    }
    if (editingUrl) DisposableEffect(context, resources) {
        val field = EditText(context).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            hint = resources.getString(R.string.grafana_url_placeholder)
            setText(options.url)
            setSelection(text.length)
            val inset = (20 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset / 2, inset, inset / 2)
        }
        val dialog = AlertDialog.Builder(context).setTitle(R.string.grafana_url_title)
            .setMessage(resources.getString(R.string.grafana_url_dialog_hint)).setView(field)
            .setPositiveButton(R.string.grafana_save, null).setNegativeButton(R.string.cancel, null).create()
        dialog.setOnDismissListener {
            editingUrl = false
            returnToUrl = true
        }
        dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (GrafanaPreferences.setUrl(context, field.text.toString())) dialog.dismiss()
            else {
                field.error = resources.getString(R.string.grafana_url_error)
                field.requestFocus()
            }
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                true
            } else false
        }
        field.requestFocus()
        field.post {
            if (dialog.isShowing && field.isAttachedToWindow) {
                context.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        onDispose {
            dialog.setOnDismissListener(null)
            dialog.dismiss()
        }
    }
}
