package cloud.morris.wcast

/**
 * Holds the most recently spoken normalized text so the Settings → Voice →
 * "View normalized text" screen can show exactly what the text-to-speech
 * engine was asked to read (ticket 34), for debugging normalizer output.
 *
 * Written by [cloud.morris.wcast.tts.SpeechController] every time a new stream
 * starts. The two fields are volatile because capture happens on the main
 * thread while synthesis/playback callbacks run on other threads.
 */
object NormalizedTextStore {

    /** The first spoken line: the article title, or a section heading. */
    @Volatile
    var heading: String = ""
        private set

    /** The full normalized stream, one spoken unit per double-newline block. */
    @Volatile
    var text: String = ""
        private set

    /** Captures the normalized stream for the viewer. */
    fun capture(heading: String, text: String) {
        this.heading = heading
        this.text = text
    }
}