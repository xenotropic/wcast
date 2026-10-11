package cloud.morris.wcast.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.view.KeyEvent
import cloud.morris.wcast.R

/**
 * Owns the audio-focus and media-session plumbing needed so that a press of the
 * wired/Bluetooth headset "main" button (a media key: `KEYCODE_HEADSETHOOK` /
 * `KEYCODE_MEDIA_PLAY_PAUSE`) stops this app's speech, even while the screen is
 * off or another media app is around.
 *
 * The approach is deliberately minimal and uses only the core `android.media`
 * framework — no Bluetooth/HID APIs and no new dependencies. Three pieces do
 * all the work:
 *
 *  1. [AudioManager] focus. When playback starts we greedily request permanent
 *     focus ([AudioManager.AUDIOFOCUS_GAIN]) so any other audio fully stops (no
 *     ducking), matching how a music/podcast app behaves. Focus is retained even
 *     after stop (we hold it like a paused podcast) so the previous media app
 *     doesn't immediately resume; it is released on an incoming focus request or
 *     at teardown. Losing focus mid-stream (an incoming call, or another app
 *     taking over) stops speech immediately.
 *
 *  2. [MediaSession]. Being the active focus holder is what routes the headset
 *     button to us; the session's callback delivers it as media key events. We
 *     only care about the "main" button and map it to [onMainButtonPressed], a
 *     hard stop. We never start playback from the button.
 *
 *  3. A manifest-registered [MediaButtonReceiver], wired via
 *     [MediaSession.setMediaButtonReceiver], so headset/Bluetooth presses reach
 *     us as a broadcast even when the in-process session callback races against
 *     Spotify or the Assistant.
 */
class MediaButtonHandler(context: Context) {

    companion object {
        /**
         * The single live handler instance, exposed for [MediaButtonReceiver], which
         * is a separate process/component that receives the `ACTION_MEDIA_BUTTON`
         * broadcast and needs to reach this handler's [onMainButtonPressed].
         */
        var active: MediaButtonHandler? = null
            private set

        const val CHANNEL_ID = "wcast_playback"
        const val NOTIFICATION_ID = 1
        val ACTIONS: Long = PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS
    }

    /** Invoked when the headset main/media button is pressed while speaking. */
    var onMainButtonPressed: (() -> Unit)? = null

    /** Invoked when audio focus is lost (call, or another app takes over). */
    var onFocusLost: (() -> Unit)? = null

    // ── Media-notification button callbacks ──────────────────────────────────
    // These are driven by the media-style notification / drawer controls and
    // mirror the in-app player bar (see MediaControlReceiver).
    var onStopPressed: (() -> Unit)? = null
    var onPreviousPressed: (() -> Unit)? = null
    var onNextPressed: (() -> Unit)? = null
    var onPlayPausePressed: (() -> Unit)? = null

    private val appContext = context.applicationContext

    private val notificationManager: NotificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** The article currently being read, shown in the media notification. */
    private var currentTitle: String? = null

    init {
        // Expose this instance to MediaButtonReceiver so the manifest-registered
        // receiver can forward headset presses back here.
        active = this
        ensureNotificationChannel()
    }

    /** Creates the (API 26+) notification channel for the media notification. */
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows the article currently being read and transport controls."
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val focusRequest: AudioFocusRequest? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(::onAudioFocusChanged)
                .build()
        } else {
            null
        }

    private val mediaSession = MediaSession(context, "wcast").apply {
        isActive = true
        setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: android.content.Intent): Boolean {
                val keyEvent = mediaButtonIntent.keyEvent()
                    ?: return super.onMediaButtonEvent(mediaButtonIntent)
                if (keyEvent.action != KeyEvent.ACTION_DOWN) {
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
                when (keyEvent.keyCode) {
                    KeyEvent.KEYCODE_HEADSETHOOK,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_STOP -> {
                        onMainButtonPressed?.invoke()
                        return true
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent)
            }

            // ── Transport callbacks for the media-style notification ─────────
            // When a MediaStyle notification is tied to this session token, the
            // system can deliver its transport button taps (play/pause/stop/
            // skip) directly to these callbacks *instead of* (or in addition
            // to) our `PendingIntent.getBroadcast` actions. Without these
            // overrides those taps hit the no-op defaults and the drawer
            // controls appear dead. We forward each to the same handlers the
            // broadcast path uses, so both delivery routes behave identically.

            override fun onPlay() {
                onPlayPausePressed?.invoke()
            }

            override fun onPause() {
                onPlayPausePressed?.invoke()
            }

            override fun onStop() {
                onStopPressed?.invoke()
            }

            override fun onSkipToNext() {
                onNextPressed?.invoke()
            }

            override fun onSkipToPrevious() {
                onPreviousPressed?.invoke()
            }
        })
        setMediaButtonReceiver(mediaButtonPendingIntent(context))
    }

    private fun mediaButtonPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MediaButtonReceiver::class.java)
        intent.action = Intent.ACTION_MEDIA_BUTTON
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun onAudioFocusChanged(focusChange: Int) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            abandonFocus()
            onFocusLost?.invoke()
        }
    }

    /** Extracts the [KeyEvent] from a media-button intent across API levels. */
    private fun android.content.Intent.keyEvent(): KeyEvent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(android.content.Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(android.content.Intent.EXTRA_KEY_EVENT)
        }

    private var hasFocus = false

    private fun requestFocus() {
        if (hasFocus) return
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        hasFocus = granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        hasFocus = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    /**
     * Marks the session as the active media player (playback state "playing"),
     * acquires audio focus, and shows the media notification. Call when speech
     * is about to start or resume.
     *
     * @param title The article currently being read, shown in the notification.
     */
    fun onPlaybackStarted(title: String? = null) {
        if (title != null) currentTitle = title
        requestFocus()
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        notify(paused = false)
    }

    /**
     * Marks the session as paused; audio focus is retained so the headset button
     * still reaches us while paused mid-stream.
     */
    fun onPlaybackPaused() {
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        notify(paused = true)
    }

    /**
     * Marks the session paused and *retains* audio focus. Call when speech fully
     * ends (via the button or a UI stop).
     *
     * Retaining focus here — instead of abandoning it — is deliberate: abandoning
     * focus the instant we stop causes the system to hand the media button and
     * playback routing straight back to the previous media app (e.g. a paused
     * Spotify), which then auto-resumes. Holding focus after stop lets us behave
     * like a paused podcast: the button stays ours and Spotify stays paused.
     *
     * The focus is only truly released when another app requests it
     * ([onAudioFocusChanged] -> LOSS) or when [release] runs at teardown, both of
     * which abandon it correctly.
     */
    fun onPlaybackStopped() {
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        // Nothing is playing, so dismiss the media notification.
        notificationManager.cancel(NOTIFICATION_ID)
        currentTitle = null
    }

    /**
     * Shows (or updates) the media-style notification reflecting the current
     * playback state, with transport controls for stop / previous / play-pause /
     * next.
     */
    private fun notify(paused: Boolean) {
        val builder = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(currentTitle ?: "Wcast")
            .setContentText(if (paused) "Paused" else "Now playing")
            .setOngoing(!paused)
            .setOnlyAlertOnce(true)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(1, 2, 3)
            )
            .addAction(previousAction())
            .addAction(playPauseAction(paused))
            .addAction(nextAction())
            .addAction(stopAction())
            .setContentIntent(appContentIntent())

        try {
            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted (API 33+); media button handling
            // still works, we simply cannot show the notification.
        }
    }

    private fun appContentIntent(): PendingIntent {
        val intent = Intent(appContext, cloud.morris.wcast.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun mediaAction(action: String, icon: Int, title: String): Notification.Action {
        val intent = Intent(appContext, MediaControlReceiver::class.java).setAction(action)
        val pi = PendingIntent.getBroadcast(
            appContext,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(Icon.createWithResource(appContext, icon), title, pi).build()
    }

    private fun previousAction() =
        mediaAction(MediaControlReceiver.ACTION_PREVIOUS, android.R.drawable.ic_media_previous, "Previous")

    private fun nextAction() =
        mediaAction(MediaControlReceiver.ACTION_NEXT, android.R.drawable.ic_media_next, "Next")

    private fun playPauseAction(paused: Boolean) =
        mediaAction(
            MediaControlReceiver.ACTION_PLAY_PAUSE,
            if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
            if (paused) "Play" else "Pause"
        )

    private fun stopAction() =
        mediaAction(MediaControlReceiver.ACTION_STOP, android.R.drawable.ic_menu_close_clear_cancel, "Stop")

    /** Releases the session. Call from the owner's destroy path. */
    fun release() {
        abandonFocus()
        notificationManager.cancel(NOTIFICATION_ID)
        mediaSession.release()
        if (active === this) {
            active = null
        }
    }
}
