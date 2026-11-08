package tv.orbit.shield

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import java.security.MessageDigest

internal class VideoPlayerChooser(private val activity: ComponentActivity) {
    private val prefs by lazy { activity.getSharedPreferences("orbit", Context.MODE_PRIVATE) }
    private val pendingKey = "video.pending." + activity.javaClass.simpleName
    private var request by mutableStateOf<Request?>(null)
    private var onPlayerReturned: (() -> Unit)? = null
    private val result = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { response ->
        val expected = prefs.getString(pendingKey, null)
        prefs.edit().remove(pendingKey).apply()
        val data = response.data
        val uri = data?.getStringExtra("extra_uri")
        if (expected != null && uri != null && digest(uri) == expected && data.hasExtra("extra_position")) {
            val position = data.getLongExtra("extra_position", 0L)
            val duration = data.getLongExtra("extra_duration", 0L)
            val editor = prefs.edit()
            if (position >= 10_000 && duration > 0 && position >= duration - 10_000) editor.remove("video.position.$expected")
            else if (position >= 10_000 && (duration <= 0 || position < duration)) editor.putLong("video.position.$expected", position)
            editor.apply()
        }
        val callback = onPlayerReturned.also { onPlayerReturned = null }
        if (!activity.isFinishing && !activity.isDestroyed) callback?.invoke()
    }
    private data class Player(val component: ComponentName, val label: String)
    private data class Request(val intent: Intent, val players: List<Player>, val onStarted: () -> Unit, val onError: () -> Unit, val onReturned: () -> Unit)

    fun open(intent: Intent, force: Boolean = false, onStarted: () -> Unit = {}, onError: () -> Unit, onReturned: () -> Unit = {}) {
        val players = activity.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { item ->
                val info = item.activityInfo ?: return@mapNotNull null
                if (!info.exported || !info.enabled || !info.applicationInfo.enabled) return@mapNotNull null
                Player(ComponentName(info.packageName, info.name), item.loadLabel(activity.packageManager).toString())
            }.distinctBy { it.component }.sortedBy { it.label.lowercase() }
        if (players.isEmpty()) { onError(); return }
        val next = Request(Intent(intent), players, onStarted, onError, onReturned)
        val preferred = prefs.getString("video.default_player", null)
        val selected = players.firstOrNull { it.component.flattenToString() == preferred }
        if (!force && selected != null) launch(next, selected, null) else request = next
    }

    private fun launch(req: Request, player: Player, rememberDefault: Boolean?) {
        // VLC's StartActivity can demand broad file access even for network
        // streams. Its exported video activity can open a stream directly.
        val directVlc = ComponentName("org.videolan.vlc", "org.videolan.vlc.gui.video.VideoPlayerActivity")
        val isNetworkStream = req.intent.data?.scheme in setOf("http", "https")
        val canOpenDirectly = player.component.packageName == directVlc.packageName && isNetworkStream && runCatching {
            activity.packageManager.getActivityInfo(directVlc, 0).let { it.exported && it.enabled && it.applicationInfo.enabled }
        }.getOrDefault(false)
        val intent = Intent(req.intent).setComponent(if (canOpenDirectly) directVlc else player.component)
        val key = intent.dataString?.let(::digest)
        if (player.component.packageName == "org.videolan.vlc" && key != null) {
            val position = prefs.getLong("video.position.$key", 0L)
            if (position > 0) intent.putExtra("position", position)
            prefs.edit().putString(pendingKey, key).apply()
        } else prefs.edit().remove(pendingKey).apply()
        try {
            onPlayerReturned = req.onReturned
            result.launch(intent)
            if (rememberDefault == true) prefs.edit().putString("video.default_player", player.component.flattenToString()).apply()
            else if (rememberDefault == false && prefs.getString("video.default_player", null) == player.component.flattenToString())
                prefs.edit().remove("video.default_player").apply()
            req.onStarted()
        } catch (_: Exception) {
            onPlayerReturned = null
            prefs.edit().remove(pendingKey).apply()
            req.onError()
        }
    }

    @Composable fun Render() {
        val current = request ?: return
        var rememberDefault by remember(current) { mutableStateOf(false) }
        val focus = remember(current) { FocusRequester() }
        Dialog(onDismissRequest = { request = null }) {
            Column(Modifier.fillMaxWidth(0.85f).background(Color(0xFF161E24)).padding(20.dp)) {
                Text(activity.getString(R.string.video_choose_player), color = Color.White, fontSize = 22.sp)
                Spacer(Modifier.height(12.dp))
                SettingRow(activity.getString(R.string.video_remember_player), modifier = Modifier.focusRequester(focus),
                    isSelected = rememberDefault, trailing = if (rememberDefault) "☑" else "☐") { rememberDefault = !rememberDefault }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(current.players, key = { it.component.flattenToString() }) { player ->
                        val isDefault = prefs.getString("video.default_player", null) == player.component.flattenToString()
                        SettingRow(player.label, value = if (isDefault) activity.getString(R.string.video_current_default) else "", trailing = "▶") {
                            request = null
                            launch(current, player, rememberDefault)
                        }
                    }
                }
            }
        }
        LaunchedEffect(current) { withFrameNanos { }; runCatching { focus.requestFocus() } }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
