package cloud.morris.wcast.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import cloud.morris.wcast.DebugLog
import com.google.common.collect.ImmutableList
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

        /**
         * Must match Media3's [DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID]
         * so our bootstrap notification is replaced (not duplicated) once Media3's
         * async notification path completes.
         */
        const val NOTIFICATION_ID = DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID

        private const val TAG = "WcastMedia"

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

    /** True once [startForegroundNow] has promoted the service to the foreground. */
    private var foregroundStarted = false

    /** Set in [onDestroy] to prevent any late foreground/notification work. */
    private var destroyed = false

    // ── Service lifecycle ──────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        active = this
        DebugLog.d(TAG, "onCreate")

        // Enter the foreground synchronously (before any async Media3 work): the
        // startForegroundService() -> startForeground() deadline is measured from
        // onCreate, and Media3's notification path completes only after an async
        // controller connect, which can miss the deadline.
        startForegroundNow()

        val mediaNotificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.playback_channel_name)
            .build()
            .apply { setSmallIcon(R.drawable.ic_notification) }
        setMediaNotificationProvider(DebugMediaNotificationProvider(mediaNotificationProvider))
        player = TtsPlayer()
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(appContentIntent())
            .build()
        // Register the session with the service ourselves: Wcast's UI drives the
        // TTS engine directly and never opens a MediaController, so no controller
        // connection would otherwise add the session. This is what connects Media3's
        // internal notification controller and makes the media card appear.
        addSession(mediaSession)
        DebugLog.d(TAG, "onCreate done: sessionAdded=${isSessionAdded(mediaSession)}")
    }

    /**
     * Promotes the service to the foreground with a minimal, self-built notification
     * so Android's foreground-service deadline is met even though Media3's own
     * notification path is asynchronous. Reuses Media3's default notification id so
     * [DefaultMediaNotificationProvider] overwrites this card (rather than stacking a
     * second one) once playback state arrives.
     */
    private fun startForegroundNow() {
        if (destroyed) return
        createPlaybackChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(appContentIntent())
            .build()
        DebugLog.d(TAG, "startForeground id=$NOTIFICATION_ID")
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        foregroundStarted = true
    }

    private fun createPlaybackChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.playback_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
        DebugLog.d(
            TAG,
            "channel importance=${channel.importance} notificationsEnabled=" +
                NotificationManagerCompat.from(this).areNotificationsEnabled()
        )
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val player = session.player
        val timelineCount = player.currentTimeline.windowCount
        // Media3 only requests the foreground while actively playing/buffering;
        // when paused it drops to a plain notification and lets the service out of
        // the foreground. Once out of the foreground, the process is fair game for
        // Doze/LMK, so a few minutes of pause killed the service and the media card
        // vanished. Keep the service foreground for as long as a stream is loaded
        // (playing OR paused), and only release it when the stream fully finishes /
        // teardown returns an empty timeline (ticket 26).
        val keepForeground = startInForegroundRequired || timelineCount > 0
        DebugLog.d(
            TAG,
            "onUpdateNotification foreground=$startInForegroundRequired keepForeground=$keepForeground " +
                "state=${player.playbackState} playWhenReady=${player.playWhenReady} timeline=$timelineCount"
        )
        super.onUpdateNotification(session, keepForeground)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        DebugLog.d(TAG, "onGetSession caller=${controllerInfo.packageName}")
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLog.d(TAG, "onStartCommand action=${intent?.action} flags=$flags startId=$startId")
        if (intent?.action == ACTION_PLAY) {
            onPlaybackStarted(intent.getStringExtra(EXTRA_TITLE))
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        destroyed = true
        DebugLog.d(TAG, "onDestroy foregroundStarted=$foregroundStarted")
        if (active === this) active = null
        abandonFocus()
        if (::mediaSession.isInitialized) mediaSession.release()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    // ── Audio focus (Media3's SimpleBasePlayer does not manage focus) ──────────

    private fun onAudioFocusChanged(focusChange: Int) {
        DebugLog.d(TAG, "audio focus change=$focusChange hasFocus=$hasFocus")
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
        DebugLog.d(TAG, "requestFocus granted=$granted hasFocus=$hasFocus")
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        hasFocus = false
        audioManager.abandonAudioFocusRequest(focusRequest)
        DebugLog.d(TAG, "abandonFocus")
    }

    // ── State pushed from the app's SpeechController ───────────────────────────

    fun onPlaybackStarted(title: String?) {
        DebugLog.d(TAG, "onPlaybackStarted title=$title")
        pausedByFocusLoss = false
        requestFocus()
        player.onStarted(title)
    }

    fun onPlaybackPaused() {
        DebugLog.d(TAG, "onPlaybackPaused")
        player.onPaused()
    }

    /**
     * Marks the stream stopped. As before, this keeps a paused-style card so Wcast
     * remains the "top" media activity; the next media-button press reaches us
     * instead of handing control back to Spotify/AntennaPod.
     */
    fun onPlaybackStopped() {
        DebugLog.d(TAG, "onPlaybackStopped")
        player.onStopped()
    }

    /** Called when the stream fully finishes (no resume target); tears down cleanly. */
    fun onPlaybackFinished() {
        DebugLog.d(TAG, "onPlaybackFinished")
        abandonFocus()
        player.onFinished()
    }

    private fun appContentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Thin [MediaNotification.Provider] that wraps [DefaultMediaNotificationProvider] and traces
     * Media3's notification-creation path for the media-card regression (ticket 23). The default
     * provider's `createNotification` is final in Media3 1.4.1, so we intercept at the interface
     * level rather than by subclassing.
     */
    private class DebugMediaNotificationProvider(
        private val delegate: DefaultMediaNotificationProvider
    ) : MediaNotification.Provider {

        override fun createNotification(
            mediaSession: MediaSession,
            customLayout: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            onNotificationChangedCallback: MediaNotification.Provider.Callback
        ): MediaNotification {
            val player = mediaSession.player
            DebugLog.d(
                TAG,
                "createNotification state=${player.playbackState} playWhenReady=${player.playWhenReady} " +
                    "timeline=${player.currentTimeline.windowCount} customLayout=${customLayout.size}"
            )
            val notification = delegate.createNotification(
                mediaSession,
                customLayout,
                actionFactory,
                onNotificationChangedCallback
            )
            DebugLog.d(TAG, "createNotification -> notificationId=${notification.notificationId}")
            return notification
        }

        override fun handleCustomCommand(
            session: MediaSession,
            action: String,
            extras: Bundle
        ): Boolean = delegate.handleCustomCommand(session, action, extras)
    }
}