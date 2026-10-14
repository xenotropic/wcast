package cloud.morris.wcast.tts

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * A Media3 [SimpleBasePlayer] that bridges the platform media session to the live
 * TTS engine. It never owns or plays audio itself — [SpeechController] keeps
 * rendering and playing each per-utterance WAV through a framework `MediaPlayer`.
 *
 * This class only mirrors the TTS playback state into a fixed, single-item,
 * non-seekable playlist so that `MediaSessionService` can drive the media card,
 * lock-screen controls and headset/Bluetooth media keys. All of those surfaces
 * route back through [handleSetPlayWhenReady] (play/pause) and [handleSeek]
 * (skip forward/back), which in turn reach the engine via [SpeechControllerBridge].
 */
class TtsPlayer : SimpleBasePlayer(Looper.getMainLooper()) {

    /** Playback status mirrored from the live engine via [WcastMediaService]. */
    private enum class Status {
        /** Nothing playing and nothing to resume; the notification is hidden. */
        IDLE,

        /** Actively producing speech. */
        PLAYING,

        /** Paused mid-stream; playback can resume from here. */
        PAUSED,

        /** Explicitly stopped. Keeps a paused-style card so Wcast stays the media target. */
        STOPPED,
    }

    private var status: Status = Status.IDLE
    private var title: String? = null

    /** Called when speech starts (or resumes), carrying the article title. */
    fun onStarted(newTitle: String?) {
        if (newTitle != null) title = newTitle
        status = Status.PLAYING
        invalidateState()
    }

    /** Called when speech pauses mid-stream. */
    fun onPaused() {
        status = Status.PAUSED
        invalidateState()
    }

    /** Called when speech is explicitly stopped (no resume target). */
    fun onStopped() {
        status = Status.STOPPED
        invalidateState()
    }

    /** Called when the stream fully finishes; tears down the media card. */
    fun onFinished() {
        status = Status.IDLE
        title = null
        invalidateState()
    }

    override fun getState(): State {
        val builder = State.Builder().setAvailableCommands(COMMANDS)
        if (status == Status.IDLE) {
            return builder
                .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(Player.STATE_IDLE)
                .setPlaylist(emptyList())
                .build()
        }

        val mediaItemData = MediaItemData.Builder(UID)
            .setMediaItem(mediaItem())
            .setIsSeekable(false)
            .build()
        return builder
            .setPlayWhenReady(
                status == Status.PLAYING,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            )
            .setPlaybackState(Player.STATE_READY)
            .setPlaylist(listOf(mediaItemData))
            .setCurrentMediaItemIndex(0)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) {
            if (status != Status.PLAYING) {
                // Resuming a paused stream works; resuming a fully-stopped stream
                // is a no-op in MainViewModel and leaves the card paused.
                SpeechControllerBridge.onResume?.invoke()
            }
        } else if (status == Status.PLAYING) {
            SpeechControllerBridge.onPause?.invoke()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT -> SpeechControllerBridge.onSkipForward?.invoke()

            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS -> SpeechControllerBridge.onSkipBack?.invoke()

            else -> {
                // Position-based seeks don't apply to the lazy TTS pipeline; ignore.
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        SpeechControllerBridge.onStop?.invoke()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private fun mediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(MEDIA_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title ?: WCAST_FALLBACK_TITLE)
                    .setArtist(WCAST_DEFAULT_ARTIST)
                    .build()
            )
            .build()

    private companion object {
        const val UID = "wcast-tts-stream"
        const val MEDIA_ID = "wcast-tts-stream"
        const val WCAST_FALLBACK_TITLE = "Wcast"
        const val WCAST_DEFAULT_ARTIST = "Wcast"

        val COMMANDS: Player.Commands = Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(Player.COMMAND_GET_METADATA)
            .add(Player.COMMAND_RELEASE)
            .build()
    }
}