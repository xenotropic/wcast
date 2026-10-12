package cloud.morris.wcast.tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.KeyEvent

private const val TAG = "WcastMedia"

/**
 * Receives the `ACTION_MEDIA_BUTTON` broadcast that Android routes to Wcast's
 * [android.media.session.MediaSession] because [WcastMediaService] registered it
 * via `MediaSession.setMediaButtonReceiver`. This broadcast hand-off is what tells
 * the system that Wcast — not the previously-playing app (Spotify, AntennaPod) —
 * is the current media-button target.
 *
 * A press of the wired/Bluetooth headset "main"/play-pause key is forwarded to
 * [SpeechControllerBridge.togglePlayPause], so one press pauses speech (retaining
 * the stream) and the next press resumes it, exactly like a podcast app.
 */
class MediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val keyEvent = intent.keyEvent() ?: return
        if (keyEvent.action != KeyEvent.ACTION_DOWN) return

        Log.d(TAG, "MediaButtonReceiver: keyCode=${keyEvent.keyCode} action=${keyEvent.action}")

        when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                if (WcastMediaService.active != null) {
                    SpeechControllerBridge.togglePlayPause()
                }
            }
        }
    }

    /** Extracts the [KeyEvent] from a media-button intent across API levels. */
    private fun Intent.keyEvent(): KeyEvent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
}