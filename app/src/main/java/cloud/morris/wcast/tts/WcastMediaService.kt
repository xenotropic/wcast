package cloud.morris.wcast.tts

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import cloud.morris.wcast.MainActivity
import cloud.morris.wcast.R

/**
 * The Media3 [MediaSessionService] that owns Wcast's media session and media-style
 * notification, replacing the previous hand-rolled framework `MediaSession` +
 * foreground `Service`. The service never synthesizes speech itself; it reaches the
 * live TTS engine through [SpeechControllerBridge], while [TtsPlayer] mirrors that
 * state into Media3 so the media card and headset/Bluetooth buttons stay in sync.
 * Audio focus is still managed here by hand, matching the previous stop-on-loss /
 * auto-resume-on-transient-loss behavior.
 */
class WcastMediaService : MediaSessionService() {

    companion object {
        var active: WcastMediaService? = null
            private set

        const val CHANNEL_ID = "wcast_playback"

        /** Intent action telling a running service to (re-)apply the "playing" state. */
        private const val ACTION_PLAY = "cloud.morris.wcast.action.PLAY"
        private const val EXTRA_TITLE = "cloud.morris.wcast.extra.TITLE"

        /**
         * Starts (or restarts) the foreground service and asks it to enter the
         * "playing" state for [title]. The command goes through the intent — rather
         * than [active] — because on the very first play the service is still
         * starting up, so `active` is not yet set when the caller returns.
         */
        fun startService(context: Context, title: String?) {
            val intent = Intent(context, WcastMediaService::class.java)
                .setAction(ACTION_PLAY)
                .putExtra(EXTRA_TITLE, title)
            context.startForegroundService(intent)
        }
    }

    private val audioManager: AudioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private var hasFocus = false

    /** True when paused by a *transient* focus loss (phone call) and due to auto-resume. */
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

    private lateinit var player: TtsPlayer
    private lateinit var mediaSession: MediaSession

    // ── Service lifecycle ──────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        active = this
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.playback_channel_name)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification) }
        )
        player = TtsPlayer()
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(appContentIntent())
            .build()
        // Register the session with the service ourselves: Wcast's UI drives the
        // TTS engine directly and never opens a MediaController, so no controller
        // connection would otherwise add the session. This is what connects Media3's
        // internal notification controller and makes the media card appear.
        addSession(mediaSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PLAY) {
            onPlaybackStarted(intent.getStringExtra(EXTRA_TITLE))
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        if (active === this) active = null
        abandonFocus()
        if (::mediaSession.isInitialized) mediaSession.release()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    // ── Audio focus (Media3's SimpleBasePlayer does not manage focus) ──────────

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
                // Permanent loss (user started another player): stop so the other
                // app becomes the sole stream.
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
                // does we ignore it rather than ducking.
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

    // ── State pushed from the app's SpeechController ───────────────────────────

    fun onPlaybackStarted(title: String?) {
        pausedByFocusLoss = false
        requestFocus()
        player.onStarted(title)
    }

    fun onPlaybackPaused() {
        player.onPaused()
    }

    /**
     * Marks the stream stopped. As before, this keeps a paused-style card so Wcast
     * remains the "top" media activity; the next media-button press reaches us
     * instead of handing control back to Spotify/AntennaPod.
     */
    fun onPlaybackStopped() {
        player.onStopped()
    }

    /** Called when the stream fully finishes (no resume target); tears down cleanly. */
    fun onPlaybackFinished() {
        abandonFocus()
        player.onFinished()
    }

    private fun appContentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}