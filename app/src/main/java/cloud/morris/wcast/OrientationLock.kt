package cloud.morris.wcast

import android.app.Activity
import android.content.pm.ActivityInfo

/**
 * Applies the "Lock to portrait" setting (ticket 27). Android supports this as a
 * per-activity override via [Activity.setRequestedOrientation], so the app can
 * stay vertical even when the user's system auto-rotate is on. Called from each
 * activity's `onCreate`/`onResume` so a toggle in Settings applies immediately.
 */
object OrientationLock {

    fun apply(activity: Activity) {
        val lock = SettingsStore(activity).portraitLock
        activity.requestedOrientation = if (lock) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}