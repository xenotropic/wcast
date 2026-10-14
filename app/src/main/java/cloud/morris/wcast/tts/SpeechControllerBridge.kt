package cloud.morris.wcast.tts

/**
 * A process-wide, single point of contact between the audio/notification plumbing
 * (owned by [WcastMediaService]) and the live [SpeechController] (owned by
 * [cloud.morris.wcast.MainViewModel]).
 *
 * The [WcastMediaService] is a Media3 `MediaSessionService` that mirrors playback
 * into a [TtsPlayer] for the media notification, lock-screen / drawer controls and
 * headset buttons. It exists independently of the UI so those controls keep working
 * even when the activity is not in the foreground. But the actual TTS engine lives
 * inside the ViewModel, which a service cannot hold a reference to. This bridge
 * lets the service ask "stop" / "resume" without knowing anything about ViewModels,
 * and lets the ViewModel tell the service whether there is content currently playing
 * (so a single press knows whether to stop or to resume, exactly like Spotify /
 * AntennaPod).
 *
 * It is intentionally tiny and one-directional: the service calls in (stop /
 * resume), the ViewModel reports state out (playing/stopped). No article data or
 * UI state crosses this boundary.
 */
object SpeechControllerBridge {

    /** Invoked by the service when a media button / transport control means "pause". */
    var onPause: (() -> Unit)? = null

    /** Invoked by the service when a media button / transport control means "resume". */
    var onResume: (() -> Unit)? = null

    /** Invoked for an explicit hard stop (notification "Stop", or losing focus). */
    var onStop: (() -> Unit)? = null

    /** Invoked by the service when a "skip to next" transport control fires. */
    var onSkipForward: (() -> Unit)? = null

    /** Invoked by the service when a "skip to previous" transport control fires. */
    var onSkipBack: (() -> Unit)? = null

    /**
     * Invoked by [cloud.morris.wcast.SettingsActivity]'s "Reload voice" button to
     * force the live [SpeechController] to rebuild its TTS engine. Returns whether
     * the rebuild actually happened (false while audio is actively playing).
     */
    var onReloadEngine: (() -> Boolean)? = null

    /**
     * Mirrors whether speech is currently in flight (playing or paused-quickly).
     * True while there is a stream to resume; false when fully idle, so a single
     * press maps to "start/resume" vs "stop".
     */
    var isActive: Boolean = false
        private set

    /**
     * When true (playing), a main-button press should pause; when false but
     * [isActive] (paused), it should resume. Used by the service to choose.
     */
    var isPlaying: Boolean = false
        private set

    /** Called by [cloud.morris.wcast.MainViewModel] whenever playback state changes. */
    fun updateState(active: Boolean, playing: Boolean) {
        isActive = active
        isPlaying = playing
    }

    /**
     * The single "main button" behavior shared by the session callback and the
     * notification play/pause action: pressing play-pause pauses when speaking
     * and resumes when paused.
     */
    fun togglePlayPause() {
        if (isPlaying) {
            onPause?.invoke()
        } else if (isActive) {
            onResume?.invoke()
        }
    }
}
