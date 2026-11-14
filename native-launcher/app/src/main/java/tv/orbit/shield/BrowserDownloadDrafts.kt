package tv.orbit.shield

import android.os.SystemClock
import java.util.UUID

/** The Activity result carries only an opaque, one-use id. */
internal object BrowserDownloadDrafts {
    private class Draft(val id: String, val session: HttpDownloadSession, val expiresAt: Long)
    private var pending: Draft? = null

    @Synchronized fun offer(session: HttpDownloadSession): String {
        val id = UUID.randomUUID().toString()
        pending = Draft(id, session, SystemClock.elapsedRealtime() + 120_000L)
        return id
    }

    @Synchronized fun take(id: String): HttpDownloadSession? {
        val draft = pending ?: return null
        if (draft.expiresAt <= SystemClock.elapsedRealtime()) {
            pending = null
            return null
        }
        if (draft.id != id) return null
        pending = null
        return draft.session
    }
}
