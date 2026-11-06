package tv.orbit.shield

import android.app.Application
import android.content.Context

/** Recovery runs before ordinary app components begin using launcher state. */
internal object PortableRestoreStartup {
    @Volatile var ready: Boolean = false
        private set
    @Synchronized fun requireRecovery() { ready = false }
    @Synchronized fun initialize(context: Context): Boolean {
        ready = try {
            PortableAndroidRestoreRecovery.recoverUnderExclusiveAccess(context.applicationContext)
            true
        } catch (_: Exception) {
            false
        }
        return ready
    }
}

class GostexApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PortableRestoreStartup.initialize(this)
    }
}
