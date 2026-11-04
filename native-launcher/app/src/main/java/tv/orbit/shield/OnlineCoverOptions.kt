package tv.orbit.shield

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.widget.EditText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

internal data class OnlineCoverOptions(val providers: List<CoverProvider> = CoverProvider.entries.toList(), val apiKey: String = "", val landscapeFirst: Boolean = true)
internal object OnlineCoverPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("orbit",0)
    fun read(context: Context): OnlineCoverOptions {
        val p = prefs(context)
        return OnlineCoverOptions(coverProviders(p.readStringOrDefault("covers.onlineSources",null)),
            p.readStringOrDefault("covers.steamgriddbKey","").orEmpty().takeIf { it.isNotEmpty() && coverApiKeyValid(it) }.orEmpty(), p.readBooleanOrDefault("covers.onlineLandscape",true))
    }
    fun providers(context: Context, value: List<CoverProvider>) { prefs(context).edit().putString("covers.onlineSources",value.joinToString(",") { it.id }.ifEmpty { "off" }).apply() }
    fun landscape(context: Context, value: Boolean) { prefs(context).edit().putBoolean("covers.onlineLandscape",value).apply() }
    fun key(context: Context, value: String) { require(coverApiKeyValid(value)); prefs(context).edit().putString("covers.steamgriddbKey",value).apply() }
}
@Composable internal fun rememberOnlineCoverOptions(): OnlineCoverOptions {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("orbit",0) }
    var state by remember(context) { mutableStateOf(OnlineCoverPreferences.read(context)) }
    DisposableEffect(prefs,context) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in setOf("covers.onlineSources","covers.steamgriddbKey","covers.onlineLandscape")) state = OnlineCoverPreferences.read(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        state = OnlineCoverPreferences.read(context)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}
internal fun editCoverApiKey(context: Context) {
    val field = EditText(context).apply {
        setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setText(OnlineCoverPreferences.read(context).apiKey); setSelection(text.length)
        hint = context.getString(R.string.cover_api_key)
    }
    val dialog = AlertDialog.Builder(context).setTitle(R.string.cover_api_key)
        .setMessage(R.string.cover_key_hint).setView(field)
        .setPositiveButton(R.string.grafana_save,null).setNegativeButton(R.string.cancel,null).create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = field.text.toString().trim()
            if (coverApiKeyValid(value)) { OnlineCoverPreferences.key(context,value); dialog.dismiss() }
            else field.error = context.getString(R.string.cover_key_invalid)
        }
    }
    dialog.show()
}
@Composable internal fun OnlineCoverSourceSettings(activity: MainActivity, initialFocus: FocusRequester) {
    val options = rememberOnlineCoverOptions()
    SettingRow(tr(R.string.cover_sources), options.providers.joinToString(" → ") { it.label }.ifEmpty { tr(R.string.disabled) }, modifier=Modifier.focusRequester(initialFocus), onClick={
        val modes = listOf(CoverProvider.entries.toList(), CoverProvider.entries.reversed(), listOf(CoverProvider.STEAMGRIDDB),listOf(CoverProvider.LIBRETRO),emptyList())
        val labels = modes.map { if (it.isEmpty()) activity.getString(R.string.disabled) else it.joinToString(" → ") { p -> p.label } }.toTypedArray()
        AlertDialog.Builder(activity).setTitle(R.string.cover_sources).setSingleChoiceItems(labels,modes.indexOf(options.providers)) { dialog,which ->
            OnlineCoverPreferences.providers(activity,modes[which]);dialog.dismiss()
        }.setNegativeButton(R.string.cancel,null).show()
    })
    SettingRow(tr(R.string.cover_landscape_first),tr(if(options.landscapeFirst) R.string.enabled else R.string.disabled),
        onClick={ OnlineCoverPreferences.landscape(activity,!options.landscapeFirst) })
    SettingRow(tr(R.string.cover_api_key),tr(if(options.apiKey.isEmpty()) R.string.cover_key_missing else R.string.cover_key_saved),
        onClick={ editCoverApiKey(activity) })
}
