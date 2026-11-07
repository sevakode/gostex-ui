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
        if (intent.action != ACTION) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (runCatching { MovieLibraryStore.read(context).any { it.id == id } }.getOrDefault(false)) mark(context, id)
            } finally { pending.finish() }
        }
    }

    internal companion object {
        const val ACTION = "tv.orbit.shield.MEDIA_PLAYER_SELECTED"
        const val EXTRA_ID = "media_id"
        fun mark(context: Context, id: String) {
            context.getSharedPreferences("orbit", Context.MODE_PRIVATE).edit()
                .putLong("played:movie:$id", System.currentTimeMillis()).apply()
        }
    }
}
