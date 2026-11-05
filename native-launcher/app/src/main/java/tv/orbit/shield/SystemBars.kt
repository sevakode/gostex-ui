package tv.orbit.shield

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets

/** Keep preview activities immersive on both Android 9 and newer devices. */
internal fun Window.hidePreviewSystemBars() {
    decorView.post {
        if (Build.VERSION.SDK_INT >= 30) {
            insetsController?.hide(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }
}
