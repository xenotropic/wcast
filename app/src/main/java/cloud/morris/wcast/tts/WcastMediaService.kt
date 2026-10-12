package cloud.morris.wcast.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.view.KeyEvent
import cloud.morris.wcast.R

/**
 * A foreground [Service] (started as a media-playback foreground service) that
 * owns the audio plumbing — an active framework [MediaSession], audio focus, and
 * the media-style notification — independent of the UI.
 *
 * This is what makes a wired/Bluetooth headset button behave like Spotify and
 * AntennaPod: while this service holds the active [MediaSession] with
 * [MediaSession.FLAG_HANDLES_MEDIA_BUTTONS] and runs as a
 * `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` foreground service, Android routes
 * the media key to us instead of the previously-playing app. (`MediaSessionService`
 * — the dedicated framework base class — is unavailable in this project's SDK,
 * so we use the equivalent `Service` + foreground type directly, which is the
 * same mechanism that class wraps.)
 *
 * The service never synthesizes speech itself. It reaches the live TTS engine in
 * the app process through [SpeechControllerBridge], so a single press of the
 * "main"/play-pause key toggles pause/resume exactly like a podcast app:
 *
 *   - speaking -> pause (the stream is retained so a subsequent press resumes
 *     from the current chunk, mirroring Spotify/AntennaPod).
 *   - paused -> resume from where it left off.
 *
 * Transport controls (stop/previous/play-pause/next) shown in the notification
 * and drawer are forwarded the same way.
 */
class WcastMediaService : Service() {

    companion object {
        var active: WcastMediaService? = null
            private set

        const val CHANNEL_ID = "wcast_playback"
        const val NOTIFICATION_ID = 1

        /** Intent action telling a running service to (re-)apply the "playing" state. */
        private const val ACTION_PLAY = "cloud.morris.wcast.action.PLAY"
        private const val EXTRA_TITLE = "cloud.morris.wcast.extra.TITLE"

        val ACTIONS: Long = PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS

        /**
         * Starts (or restarts) the foreground service and asks it to enter the
         * "playing" state for [title]. Passing the command through the intent —
         * rather than relying on [active] being non-null — matters because on the
         * very first play the service process is still starting up, so `active` is
         * not yet set when the caller returns. The intent is delivered in
         * [onStartCommand], which runs only after [onCreate] (session built), so
         * audio focus and session state are always applied in time for Wcast to
         * become the current media player.
         */
        fun startService(context: Context, title: String?) {
            val intent = Intent(context, WcastMediaService::class.java)
                .setAction(ACTION_PLAY)
                .putExtra(EXTRA_TITLE, title)
            context.startForegroundService(intent)
        }
    }

    private val notificationManager: NotificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private val audioManager: AudioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private var currentTitle: String? = null
    private var hasFocus = false

    /** True when the stream was paused by a *transient* focus loss (phone call) and
     *  should auto-resume once focus is regained, mirroring a podcast app. */
    private var pausedByFocusLoss = false

    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(::onAudioFocusChanged)
            .build()

    private lateinit var mediaSession: MediaSession

    private fun buildMediaSession() {
        mediaSession = MediaSession(this, "wcast").apply {
            isActive = true
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(sessionCallback)
        }
    }

    private val sessionCallback: MediaSession.Callback by lazy {
        object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
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
                        toggleStopResume()
                        return true
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent)
            }

            override fun onPlay() {
                SpeechControllerBridge.onResume?.invoke()
            }

            override fun onPause() {
                SpeechControllerBridge.onPause?.invoke()
            }

            override fun onStop() {
                SpeechControllerBridge.onStop?.invoke()
            }

            override fun onSkipToNext() {
                SpeechControllerBridge.onSkipForward?.invoke()
            }

            override fun onSkipToPrevious() {
                SpeechControllerBridge.onSkipBack?.invoke()
            }
        }
    }

    private fun toggleStopResume() {
        SpeechControllerBridge.togglePlayPause()
    }

    private fun onAudioFocusChanged(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                // Auto-resume if we were only interrupted transiently.
                if (pausedByFocusLoss) {
                    pausedByFocusLoss = false
                    SpeechControllerBridge.onResume?.invoke()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent loss (user started another player): behave like Spotify/
                // AntennaPod and stop, so the other app becomes the sole stream.
                hasFocus = false
                pausedByFocusLoss = false
                SpeechControllerBridge.onStop?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Short interruption (incoming call / alarm): pause and plan to resume.
                hasFocus = false
                pausedByFocusLoss = true
                SpeechControllerBridge.onPause?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // We request full GAIN (not duck), so this should not occur; if it
                // does we ignore it rather than ducking, since we want to be the
                // sole stream while reading.
            }
        }
    }

    private fun requestFocus() {
        if (hasFocus) return
        val granted = audioManager.requestAudioFocus(focusRequest)
        hasFocus = granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        hasFocus = false
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    fun onPlaybackStarted(title: String?) {
        if (title != null) currentTitle = title
        pausedByFocusLoss = false
        requestFocus()
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        notify(paused = false)
    }

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
     * Marks the stream stopped. Unlike the old behavior we do NOT cancel the
     * notification or release the session: keeping a paused notification means
     * Wcast remains the "top" media activity so the next button press resumes us
     * instead of handing control back to Spotify/AntennaPod.
     */
    fun onPlaybackStopped() {
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        notify(paused = true)
    }

    /** Called when the stream fully finishes (no resume target); tears down cleanly. */
    fun onPlaybackFinished() {
        notificationManager.cancel(NOTIFICATION_ID)
        if (::mediaSession.isInitialized) mediaSession.release()
        abandonFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureNotificationChannel() {
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

    private fun notify(paused: Boolean) {
        val builder = Notification.Builder(this, CHANNEL_ID)
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
        val intent = Intent(this, cloud.morris.wcast.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun mediaAction(action: String, icon: Int, title: String): Notification.Action {
        val intent = Intent(this, MediaControlReceiver::class.java).setAction(action)
        val pi = PendingIntent.getBroadcast(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()
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

    private fun Intent.keyEvent(): KeyEvent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }

    // ── Service lifecycle ──────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        active = this
        ensureNotificationChannel()
        buildMediaSession()
        startForegroundServiceNotification()
    }

    private fun startForegroundServiceNotification() {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Wcast")
            .setContentText("Ready to play")
            .setOngoing(true)
            .setContentIntent(appContentIntent())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PLAY) {
            onPlaybackStarted(intent.getStringExtra(EXTRA_TITLE))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (active === this) active = null
        abandonFocus()
        if (::mediaSession.isInitialized) mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
