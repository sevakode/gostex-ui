package tv.orbit.shield

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Network activity occurs only when the user selects this row. */
@Composable internal fun TorrServerStatusSetting(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<TorrServerStatus?>(null) }
    val summary = when {
        checking -> tr(R.string.torr_status_checking)
        status is TorrServerStatus.Online -> {
            val online = status as TorrServerStatus.Online
            val base = tr(R.string.torr_status_online, online.version, online.torrents, online.bytesPerSecond / 1024)
            val cache = online.cache?.let { info ->
                val mode = if (info.disk) tr(R.string.torr_status_cache_disk) else tr(R.string.torr_status_cache_memory)
                val size = info.sizeBytes?.let { tr(R.string.torr_status_cache_size, it / (1024 * 1024)) }
                listOfNotNull(mode, size).joinToString(" · ")
            }
            listOfNotNull(base, cache).joinToString(" · ")
        }
        status is TorrServerStatus.AuthenticationRequired -> tr(R.string.torr_status_auth)
        status is TorrServerStatus.Unavailable -> tr(R.string.torr_status_unavailable)
        else -> tr(R.string.torr_status_hint)
    }
    SettingRow(tr(R.string.torr_status_title), summary, modifier, onClick = {
        if (!checking) {
            checking = true
            scope.launch {
                status = withContext(Dispatchers.IO) { TorrServerStatusClient.probe() }
                checking = false
            }
        }
    })
}
