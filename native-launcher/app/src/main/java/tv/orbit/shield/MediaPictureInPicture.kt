package tv.orbit.shield

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** The video app owns its PiP window; HOME must remain a normal full-screen launcher. */
internal object MediaPictureInPicture {
    // Android TV exposes this action even though Settings has no public SDK constant for it.
    private const val SETTINGS_ACTION = "android.settings.PICTURE_IN_PICTURE_SETTINGS"

    fun isSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun openSettings(context: Context): Boolean = isSupported(context) && runCatching {
        // TV Settings accepts no package URI. Let the owner choose which player to allow.
        context.startActivity(Intent(SETTINGS_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}
