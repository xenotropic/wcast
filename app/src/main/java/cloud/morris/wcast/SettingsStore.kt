package cloud.morris.wcast

import android.content.Context
import android.content.SharedPreferences

/**
 * A tiny, dependency-free store for the handful of user preferences Wcast has
 * (backed by [SharedPreferences]). Kept small and explicit so both
 * [MainViewModel] and [SettingsActivity] can read and write the same values
 * without any extra libraries.
 *
 * New settings should each get a property here (with a sensible default) plus
 * a companion-object key, so defaults live in exactly one place.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * When true, tapping play on a section reads from that section to the end
     * of the article; when false (default) it reads just that section's subtree
     * (the section plus its subheadings).
     */
    var continuity: Boolean
        get() = prefs.getBoolean(KEY_CONTINUITY, false)
        set(value) = prefs.edit().putBoolean(KEY_CONTINUITY, value).apply()

    /**
     * Scales the size of the player-bar controls as a percentage (100 = the
     * default layout the app has always used). Larger values make the transport
     * buttons and icons bigger for drivers / low-vision users.
     */
    var playerBarScale: Int
        get() = prefs.getInt(KEY_PLAYER_BAR_SCALE, 100)
        set(value) = prefs.edit().putInt(KEY_PLAYER_BAR_SCALE, value.coerceIn(50, 250)).apply()

    /**
     * Whether the user has accepted the end-user license agreement; set on the
     * in-app first-launch dialog so it is never shown again (upgrades included).
     */
    var eulaAccepted: Boolean
        get() = prefs.getBoolean(KEY_EULA_ACCEPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_EULA_ACCEPTED, value).apply()

    /**
     * Whether the in-app debug logger ([DebugLog]) should capture lines. Toggled
     * from Settings → Debug; read back on activity creation so the flag survives
     * process restarts. See AGENTS.md "Debug logging" for how to add statements.
     */
    var debugLogsEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_LOGS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_LOGS_ENABLED, value).apply()

    private companion object {
        const val PREFS_NAME = "wcast_settings"
        const val KEY_CONTINUITY = "continuity"
        const val KEY_PLAYER_BAR_SCALE = "player_bar_scale"
        const val KEY_EULA_ACCEPTED = "eula_accepted"
        const val KEY_DEBUG_LOGS_ENABLED = "debug_logs_enabled"
    }
}
