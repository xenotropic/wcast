package cloud.morris.wcast.tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/**
 * Receives `ACTION_MEDIA_BUTTON` broadcasts for this app's [MediaSession] and
 * forwards the button presses to the active [MediaButtonHandler].
 *
 * A [MediaButtonHandler] registers this receiver as its media-button target via
 * [android.media.session.MediaSession.setMediaButtonReceiver]. That makes
 * headset/Bluetooth media-key delivery far more reliable than depending on the
 * in-process [MediaSession] callback alone: the system can route the button to
 * us as a broadcast even when the session-callback path races against other
 * media apps (Spotify) or the Assistant.
 *
 * A press of any "main/play-pause/stop" media key maps to the handler's hard
 * stop. We never start playback from the button.
 */
class MediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val keyEvent = intent.keyEvent() ?: return
        if (keyEvent.action != KeyEvent.ACTION_DOWN) return

        when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                MediaButtonHandler.active?.onMainButtonPressed?.invoke()
            }
        }
    }

    /** Extracts the [KeyEvent] from a media-button intent across API levels. */
    private fun Intent.keyEvent(): KeyEvent? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
}
