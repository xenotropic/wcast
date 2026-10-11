package cloud.morris.wcast.tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives button taps from the media-style notification (and lock-screen /
 * drawer controls) and forwards them back to the active [MediaButtonHandler].
 *
 * Each notification action is a `PendingIntent.getBroadcast` carrying a distinct
 * `ACTION_*` string, so a single receiver can dispatch all of them. The
 * controls are: stop, previous, next, and play/pause.
 */
class MediaControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val handler = MediaButtonHandler.active ?: return
        when (intent.action) {
            ACTION_STOP -> handler.onStopPressed?.invoke()
            ACTION_PREVIOUS -> handler.onPreviousPressed?.invoke()
            ACTION_NEXT -> handler.onNextPressed?.invoke()
            ACTION_PLAY_PAUSE -> handler.onPlayPausePressed?.invoke()
        }
    }

    companion object {
        const val ACTION_STOP = "cloud.morris.wcast.action.STOP"
        const val ACTION_PREVIOUS = "cloud.morris.wcast.action.PREVIOUS"
        const val ACTION_NEXT = "cloud.morris.wcast.action.NEXT"
        const val ACTION_PLAY_PAUSE = "cloud.morris.wcast.action.PLAY_PAUSE"
    }
}
