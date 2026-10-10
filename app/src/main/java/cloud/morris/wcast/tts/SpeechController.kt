package cloud.morris.wcast.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import cloud.morris.wcast.data.ArticleSection
import java.util.Locale

/**
 * Owns the Android [TextToSpeech] engine and turns structured
 * [ArticleSection]s into spoken audio.
 *
 * Phase 2: speaks either the entire article (title + all sections) or a
 * single section, sequentially, using [TextToSpeech.QUEUE_ADD] so playback
 * proceeds in order without blocking the caller.
 */
class SpeechController(context: Context) : TextToSpeech.OnInitListener {

    private val engine: TextToSpeech = TextToSpeech(context.applicationContext, this)

    /**
     * Invoked exactly once when the last utterance of a spoken stream finishes
     * (or errors out). Lets the caller clear any "now speaking" state.
     */
    var onStreamDone: (() -> Unit)? = null

    private var ready = false
    private var remaining = 0
    private var pendingText: List<String>? = null

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            engine.language = Locale.getDefault()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
                override fun onError(utteranceId: String?) = onUtteranceFinished()

                override fun onDone(utteranceId: String?) = onUtteranceFinished()
            })
        }
        pendingText?.let {
            pendingText = null
            if (ready) speak(it)
        }
    }

    private fun onUtteranceFinished() {
        if (remaining > 0) {
            remaining--
            if (remaining == 0) onStreamDone?.invoke()
        }
    }

    /** Speaks the whole article: title followed by each section in order. */
    fun speakArticle(title: String, sections: List<ArticleSection>) {
        val utterances = buildList {
            add(title)
            sections.forEach { s ->
                s.heading?.let { add(it) }
                s.paragraphs.forEach { add(it) }
            }
        }
        speak(utterances)
    }

    /** Speaks a single [ArticleSection] (heading + paragraphs). */
    fun speakSection(section: ArticleSection) {
        val utterances = buildList {
            section.heading?.let { add(it) }
            section.paragraphs.forEach { add(it) }
        }
        speak(utterances)
    }

    private fun speak(texts: List<String>) {
        val utterances = texts.filter { it.isNotBlank() }
        if (!ready) {
            pendingText = utterances
            return
        }
        engine.stop()
        remaining = utterances.size
        if (utterances.isEmpty()) {
            onStreamDone?.invoke()
            return
        }
        utterances.forEachIndexed { index, text ->
            val utteranceId = "tts-$index-${System.currentTimeMillis()}-${System.nanoTime()}"
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            engine.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
        }
    }

    /** Stops playback immediately and signals the stream as finished. */
    fun stop() {
        engine.stop()
        if (remaining > 0) {
            remaining = 0
            onStreamDone?.invoke()
        }
    }

    val isReady: Boolean get() = ready

    /** Releases the TTS engine. Call when the owner is destroyed. */
    fun shutdown() {
        engine.stop()
        engine.shutdown()
    }
}

