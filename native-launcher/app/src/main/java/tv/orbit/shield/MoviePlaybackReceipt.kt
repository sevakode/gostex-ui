package tv.orbit.shield

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The system invokes this one-shot receipt only after a player is chosen. */
class MoviePlaybackReceipt : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID)?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return
        val episodeId = intent.getStringExtra(EXTRA_EPISODE_ID)
        if (intent.action != ACTION) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val movie = runCatching { MovieLibraryStore.read(context).firstOrNull { it.id == id } }.getOrNull()
                if (movie != null) mark(context, id, episodeId.takeIf { movie.mediaType == "series" })
            } finally { pending.finish() }
        }
    }

    internal companion object {
        const val ACTION = "tv.orbit.shield.MEDIA_PLAYER_SELECTED"
        const val EXTRA_ID = "media_id"
        const val EXTRA_EPISODE_ID = "episode_id"
        fun mark(context: Context, id: String, episodeId: String? = null) {
            val edit = context.getSharedPreferences("orbit", Context.MODE_PRIVATE).edit()
                .putLong("played:movie:$id", System.currentTimeMillis())
            decodeEpisodeHistory(id, episodeId)?.let { edit.putString(episodeHistoryKey(id), it.id) }
            edit.apply()
        }
    }
}
