package cloud.morris.wcast.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import cloud.morris.wcast.data.ArticleSection
import cloud.morris.wcast.normalization.Normalizer
import cloud.morris.wcast.normalization.loadNormalizationRules
import java.util.Locale

/**
 * A single unit of speech, tied back to where it came from in the article so
 * the caller can highlight what is being read and skip between sections.
 *
 * @property sectionId The id of the [ArticleSection] this utterance belongs to,
 *   or null for the article title (which is spoken first).
 * @property paragraphIndex The index of the paragraph within its section, or
 *   null for the title/heading.
 * @property text The display text (pre-normalization), kept for reference.
 */
data class Utterance(
    val sectionId: String?,
    val paragraphIndex: Int?,
    val text: String
)

/**
 * Owns the Android [TextToSpeech] engine and turns structured
 * [ArticleSection]s into spoken audio.
 *
 * Speaks either the entire article (title + all sections) or a single section,
 * sequentially, using [TextToSpeech.QUEUE_ADD] so playback proceeds in order
 * without blocking the caller.
 *
 * The engine gives no notion of a clock or current playback position, so all
 * "pause", "resume", and "skip" operations are implemented as *stop + re-queue
 * from a retained index* rather than a true seek. The current utterance being
 * spoken is tracked via [onUtteranceStarted] so the caller can reflect it in
 * the UI (e.g. highlight the active paragraph).
 */
class SpeechController(context: Context) : TextToSpeech.OnInitListener {

    private val engine: TextToSpeech = TextToSpeech(context.applicationContext, this)

    /**
     * Normalizes display text into spoken text (Phase 3). Loaded lazily from the
     * bundled rule assets; only applied to the text we *speak*, never to what is
     * shown on screen.
     */
    private val normalizer: Normalizer by lazy {
        Normalizer(loadNormalizationRules(context.applicationContext))
    }

    /**
     * Invoked exactly once when the last utterance of a spoken stream finishes
     * (or errors out). Lets the caller clear any "now speaking" state.
     */
    var onStreamDone: (() -> Unit)? = null

    /**
     * Invoked each time a new utterance actually begins speaking (i.e. its audio
     * starts, not merely when it is queued). Carries the current utterance and
     * its index, so the caller can track "what is being read" in real time.
     */
    var onUtteranceStarted: ((index: Int, utterance: Utterance) -> Unit)? = null

    private var ready = false

    /** The full, structured queue built for the current stream, in speak order. */
    private var queue: List<Utterance> = emptyList()

    /**
     * Index into [queue] of the utterance currently being spoken. Advances to the
     * next utterance when the current one finishes.
     */
    private var currentIndex = 0

    /**
     * Monotonically increasing generation, bumped on every (re-)enqueue. Embedded
     * in each utteranceId so late/in-flight callbacks from a *previous* queue can
     * be recognized and ignored (they carry a stale generation).
     */
    private var generation = 0

    /** Stream requested before the engine initialized; replayed in [onInit]. */
    private var pendingQueue: List<Utterance>? = null

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            engine.language = Locale.getDefault()
            // Play as ordinary *media* (music stream), not as a navigation/TTS
            // guidance prompt. By default many TTS engines request a "duck"
            // focus (AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK), which lowers other
            // apps instead of stopping them — producing the two-streams-at-once
            // symptom. Declaring media usage lets Wcast (via the media session +
            // audio focus in WcastMediaService) behave like Spotify/AntennaPod:
            // starting our stream stops the other one.
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = onUtteranceStarted(utteranceId)

                @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
                override fun onError(utteranceId: String?) = onUtteranceFinished(utteranceId)

                override fun onDone(utteranceId: String?) = onUtteranceFinished(utteranceId)
            })
        }
        pendingQueue?.let { queued ->
            pendingQueue = null
            if (ready) startQueue(queued)
        }
    }

    private fun onUtteranceStarted(utteranceId: String?) {
        val (gen, index) = parseId(utteranceId) ?: return
        if (gen != generation || index !in queue.indices) return
        // Note: we deliberately do NOT update currentIndex here. Some engines
        // fire onStart for the next utterance before onDone of the previous one,
        // so writing currentIndex from onStart made it non-monotonic and broke
        // skip targeting. currentIndex is advanced only by onUtteranceFinished.
        onUtteranceStarted?.invoke(index, queue[index])
    }

    private fun onUtteranceFinished(utteranceId: String?) {
        val (gen, index) = parseId(utteranceId) ?: return
        if (gen != generation) return

        // Monotonic: callbacks can arrive out of order, so never move backwards.
        currentIndex = maxOf(currentIndex, index + 1)
        if (currentIndex >= queue.size) {
            queue = emptyList()
            currentIndex = 0
            onStreamDone?.invoke()
        }
    }

    /**
     * Parses an utteranceId of the form `tts-<generation>-<index>-<time>-<nano>`
     * produced by [enqueueFrom], returning the (generation, index) pair.
     */
    private fun parseId(utteranceId: String?): Pair<Int, Int>? {
        if (utteranceId == null) return null
        val parts = utteranceId.split('-')
        if (parts.size < 3 || parts[0] != "tts") return null
        val generation = parts[1].toIntOrNull() ?: return null
        val index = parts[2].toIntOrNull() ?: return null
        return generation to index
    }

    /** Speaks the whole article: title followed by each section in order. */
    fun speakArticle(title: String, sections: List<ArticleSection>) {
        val utterances = buildList {
            add(Utterance(sectionId = null, paragraphIndex = null, text = title))
            sections.forEach { s -> addSectionUtterances(s, this) }
        }
        start(utterances)
    }

    /** Speaks a single [ArticleSection] (heading + paragraphs). */
    fun speakSection(section: ArticleSection) {
        start(buildList { addSectionUtterances(section, this) })
    }

    /** Speaks an ordered list of [ArticleSection]s as one continuous stream. */
    fun speakSections(sections: List<ArticleSection>) {
        start(buildList { sections.forEach { s -> addSectionUtterances(s, this) } })
    }

    /**
     * Speaks [section] starting at [paragraphIndex] (the rest of that section's
     * paragraphs), optionally continuing into [followingSections] afterwards.
     * The section heading is skipped — this is used when the user starts
     * playback from a specific paragraph rather than from the heading.
     */
    fun speakFromParagraph(
        section: ArticleSection,
        paragraphIndex: Int,
        followingSections: List<ArticleSection> = emptyList()
    ) {
        val utterances = buildList {
            section.paragraphs.drop(paragraphIndex).forEachIndexed { i, p ->
                add(Utterance(sectionId = section.id, paragraphIndex = paragraphIndex + i, text = p.text))
            }
            followingSections.forEach { s -> addSectionUtterances(s, this) }
        }
        start(utterances)
    }

    /** Appends a section's heading and paragraph utterances, in order. */
    private fun addSectionUtterances(section: ArticleSection, dest: MutableList<Utterance>) {
        section.heading?.let {
            dest.add(Utterance(sectionId = section.id, paragraphIndex = null, text = it))
        }
        section.paragraphs.forEachIndexed { index, p ->
            dest.add(Utterance(sectionId = section.id, paragraphIndex = index, text = p.text))
        }
    }

    private fun start(utterances: List<Utterance>) {
        // Phase 3: normalize display text into spoken text before synthesizing.
        // The normalization is applied here so title, headings, and paragraphs
        // all go through the same pipeline, while the UI still shows raw text.
        val normalized = utterances
            .filter { it.text.isNotBlank() }
            .map { it.copy(text = normalizer.normalize(it.text)) }
        if (!ready) {
            pendingQueue = normalized
            return
        }
        startQueue(normalized)
    }

    private fun startQueue(utterances: List<Utterance>) {
        engine.stop()
        queue = utterances
        currentIndex = 0
        if (utterances.isEmpty()) {
            onStreamDone?.invoke()
            return
        }
        enqueueFrom(0)
    }

    /** (Re-)enqueues [queue] starting at [fromIndex] (inclusive). */
    private fun enqueueFrom(fromIndex: Int) {
        engine.stop()
        generation++
        val gen = generation
        for (index in fromIndex until queue.size) {
            val utterance = queue[index]
            val utteranceId = "tts-$gen-$index-${System.currentTimeMillis()}-${System.nanoTime()}"
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            // QUEUE_FLUSH on the first item guarantees any stale queued audio is
            // discarded; subsequent items append with QUEUE_ADD.
            val queueMode = if (index == fromIndex) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(utterance.text, queueMode, params, utteranceId)
        }
    }

    /**
     * Pauses playback. TTS has no true pause, so this stops the engine and
     * remembers [currentIndex]; [resume] re-queues from there, which restarts the
     * *current* utterance from its beginning (an accepted limitation).
     */
    fun pause() {
        if (queue.isEmpty()) return
        engine.stop()
        // Bump generation so the interrupted utterance's late onDone/onError
        // (from the stop above) is ignored and doesn't advance currentIndex.
        generation++
    }

    /** Resumes playback from the utterance that was current when paused. */
    fun resume() {
        if (queue.isEmpty()) return
        enqueueFrom(currentIndex)
    }

    /**
     * Skips playback by whole sections. [delta] of +1 advances to the start of
     * the next section relative to the current utterance; -1 returns to the start
     * of the previous one. The title (sectionId == null) is its own "section".
     * No-ops at either boundary.
     */
    fun skipSections(delta: Int) {
        if (queue.isEmpty() || delta == 0) return

        val sectionStartIndices = mutableListOf<Int>()
        var lastKey: Any? = null
        for ((index, utterance) in queue.withIndex()) {
            val key: Any = utterance.sectionId ?: TITLE_KEY
            if (key != lastKey) {
                sectionStartIndices.add(index)
                lastKey = key
            }
        }

        val currentSectionPos = sectionStartIndices.indexOfLast { it <= currentIndex }
            .coerceAtLeast(0)
        val targetPos = currentSectionPos + delta
        if (targetPos !in sectionStartIndices.indices) return

        val targetIndex = sectionStartIndices[targetPos]
        currentIndex = targetIndex
        enqueueFrom(targetIndex)
    }

    /** Stops playback immediately and signals the stream as finished. */
    fun stop() {
        engine.stop()
        generation++
        if (queue.isNotEmpty()) {
            queue = emptyList()
            currentIndex = 0
            onStreamDone?.invoke()
        }
    }

    val isReady: Boolean get() = ready

    /** Releases the TTS engine. Call when the owner is destroyed. */
    fun shutdown() {
        engine.stop()
        engine.shutdown()
    }

    private companion object {
        /** Sentinel key marking the article title as its own "section". */
        private val TITLE_KEY = Any()
    }
}

