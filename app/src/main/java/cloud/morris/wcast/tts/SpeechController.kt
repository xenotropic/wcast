package cloud.morris.wcast.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import cloud.morris.wcast.data.ArticleSection
import cloud.morris.wcast.normalization.Normalizer
import cloud.morris.wcast.normalization.loadNormalizationRules
import java.io.File
import java.util.Locale

private const val TAG = "WcastTTS"

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
 * Turns structured [ArticleSection]s into spoken audio, and plays the result
 * from *this* process.
 *
 * The Android [TextToSpeech] engine is used only to render text to a WAV file
 * ([TextToSpeech.synthesizeToFile]); it never plays audio itself. The rendered
 * files are then played back sequentially by a local [MediaPlayer] owned by
 * Wcast. This is the crux of headset-button routing: when the TTS engine plays
 * audio via `speak()`, the engine's *own* process (a different uid) owns the
 * AudioTrack, so Android attributes the media session to the engine — not to
 * Wcast — and the wired/Bluetooth "play/pause" button keeps routing to the
 * previously-playing app (Spotify, AntennaPod). By rendering to files and
 * playing them here, Wcast's uid owns the AudioTrack, matching the active
 * [WcastMediaService] media session.
 *
 * Speaks either the entire article (title + all sections) or a single section,
 * sequentially. "Pause" and "resume" use [MediaPlayer]'s true pause/resume when
 * a file is actively playing; a pause that lands while the current chunk is still
 * being synthesized cancels it and resumes by re-rendering that chunk. "Skip" is
 * implemented as *re-render from a retained index* rather than a true seek. The
 * current utterance being spoken is tracked so the caller can reflect it in the
 * UI (e.g. highlight the active paragraph).
 */
class SpeechController(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private var engine: TextToSpeech = TextToSpeech(appContext, this)

    /**
     * The package of the TTS engine this controller was (re)built for, read from
     * the system's current default. Compared against the live system setting on
     * app resume so we can swap to a newly-selected engine without a force-close.
     */
    private var enginePackage: String? = currentDefaultEnginePackage()

    /**
     * Normalizes display text into spoken text (Phase 3). Loaded lazily from the
     * bundled rule assets; only applied to the text we *speak*, never to what is
     * shown on screen.
     */
    private val normalizer: Normalizer by lazy {
        Normalizer(loadNormalizationRules(appContext))
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
     * Index into [queue] of the utterance currently being rendered/spoken.
     * Advances past each utterance as it finishes.
     */
    private var currentIndex = 0

    /**
     * Monotonically increasing generation, bumped on every (re-)enqueue/stop.
     * Embedded in each synthesized utteranceId and captured by each [MediaPlayer]
     * callback so late/in-flight callbacks from a *previous* queue can be
     * recognized and ignored (they carry a stale generation).
     */
    private var generation = 0

    /** Stream requested before the engine initialized; replayed in [onInit]. */
    private var pendingQueue: List<Utterance>? = null

    /** The [MediaPlayer] currently playing a rendered WAV file, if any. */
    private var player: MediaPlayer? = null

    /** True while the user has paused and we retain the current chunk for resume. */
    private var paused = false

    /** True while a [TextToSpeech.synthesizeToFile] request is outstanding. */
    private var synthInFlight = false

    /** The queue index whose synthesis is in flight, valid only when [synthInFlight]. */
    private var synthIndex: Int? = null

    /**
     * The queue index of a chunk that has finished rendering (look-ahead) but is
     * not yet playing because the previous chunk is still audible. Null when no
     * pre-rendered chunk is waiting. This is what removes the per-paragraph
     * synthesis gap: the next chunk is rendered while the current one plays
     * rather than only after it finishes (ticket 02).
     */
    private var readyToPlayIndex: Int? = null

    /** Directory holding the rendered WAV files for the current stream. */
    private val synthDir: File = File(appContext.cacheDir, "synth")

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        Log.d(TAG, "onInit status=$status ready=$ready")
        if (ready) {
            engine.language = Locale.getDefault()
            // We only ever render to files, so the listener reports synthesis
            // completion/failure — never audio playback. `onStart` is ignored on
            // purpose: for synthesizeToFile it is inconsistent across engines and
            // does not mean "audio is now audible". Audio becomes audible in
            // playFile, which is where onUtteranceStarted fires.
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
                override fun onError(utteranceId: String?) = onSynthError(utteranceId)

                override fun onDone(utteranceId: String?) = onSynthDone(utteranceId)
            })
        }
        pendingQueue?.let { queued ->
            pendingQueue = null
            if (ready) startQueue(queued)
        }
    }

    // ── Public speaking API (call sites unchanged) ─────────────────────────────

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
        resetPlayback()
        queue = utterances
        currentIndex = 0
        Log.d(TAG, "startQueue utterances=${utterances.size}")
        if (utterances.isEmpty()) {
            onStreamDone?.invoke()
            return
        }
        beginSynthesis(0)
    }

    /** (Re-)renders [queue] starting at [fromIndex] (inclusive). */
    private fun enqueueFrom(fromIndex: Int) {
        resetPlayback()
        currentIndex = fromIndex
        beginSynthesis(fromIndex)
    }

    /**
     * Renders utterance [index] to a WAV file; playback begins in [onSynthDone].
     *
     * Does NOT touch [currentIndex]: the play head is the caller's
     * responsibility ([startQueue], [enqueueFrom], [advanceAfterUtterance]). This
     * lets the look-ahead path pre-render chunk N+1 while chunk N is still the
     * play head (ticket 02).
     */
    private fun beginSynthesis(index: Int) {
        if (index !in queue.indices) {
            finishStream()
            return
        }
        val gen = generation
        val utterance = queue[index]
        val utteranceId = "tts-$gen-$index-${System.currentTimeMillis()}-${System.nanoTime()}"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        val file = outputFile(gen, index)
        synthInFlight = true
        synthIndex = index
        Log.d(TAG, "beginSynthesis index=$index/${queue.size} textLen=${utterance.text.length} file=${file.name}")
        val result = engine.synthesizeToFile(utterance.text, params, file, utteranceId)
        Log.d(TAG, "synthesizeToFile index=$index result=$result exists=${file.exists()} size=${file.length()}")
        if (result != TextToSpeech.SUCCESS) {
            // Rejected synchronously; an async onError may or may not also fire,
            // but onSynthError guards against a double-advance via synthInFlight.
            Log.w(TAG, "synthesizeToFile returned non-SUCCESS index=$index result=$result")
            onSynthError(utteranceId)
        }
    }

    private fun onSynthDone(utteranceId: String?) {
        val (gen, index) = parseId(utteranceId) ?: return
        if (gen != generation) return
        if (!synthInFlight || synthIndex != index) return
        synthInFlight = false
        synthIndex = null
        Log.d(TAG, "onSynthDone gen=$gen index=$index")
        if (player == null) {
            // Nothing is audible yet, so this rendered chunk becomes the play head.
            playFile(gen, index)
        } else {
            // This was a look-ahead render (chunk N+1) that finished while chunk N
            // is still playing; hold it until N ends (ticket 02).
            readyToPlayIndex = index
        }
    }

    private fun onSynthError(utteranceId: String?) {
        val (gen, index) = parseId(utteranceId) ?: return
        if (gen != generation) return
        if (!synthInFlight || synthIndex != index) return
        synthInFlight = false
        synthIndex = null
        Log.w(TAG, "onSynthError gen=$gen index=$index")
        if (player == null) {
            // The chunk we were about to play failed to render; skip past it.
            advanceAfterUtterance(gen, index)
        }
        // Else: a look-ahead pre-render failed. Nothing to do — the chunk is
        // simply not pre-rendered and will be synthesized on demand when the
        // current chunk finishes (advanceAfterUtterance's fallback branch).
    }

    /** Plays the previously rendered WAV for utterance [index] from our own process. */
    private fun playFile(gen: Int, index: Int) {
        if (gen != generation) return
        releasePlayer()
        // NOTE: use renderedFile(), NOT outputFile(). outputFile() deletes any
        // existing file so the TTS engine can safely overwrite it *before*
        // synthesis; calling it here would delete the just-rendered WAV and make
        // every chunk fail silently (no sound).
        val file = renderedFile(gen, index)
        Log.d(TAG, "playFile gen=$gen index=$index exists=${file.exists()} size=${file.length()} path=${file.absolutePath}")
        if (!file.exists() || file.length() == 0L) {
            Log.e(TAG, "playFile: missing/empty render index=$index (exists=${file.exists()} size=${file.length()})")
            onFileError(gen, index)
            return
        }
        val mp = MediaPlayer()
        try {
            // Play as ordinary *media* (music stream) so Wcast's own uid owns the
            // AudioTrack and the framework routes headset buttons to our session.
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { onFileDone(gen, index) }
            mp.setOnErrorListener { _, _, _ ->
                onFileError(gen, index)
                true
            }
            mp.prepare()
            player = mp
            paused = false
            mp.start()
            Log.d(TAG, "playFile started index=$index duration=${mp.duration}ms")
            onUtteranceStarted?.invoke(index, queue[index])
        } catch (e: Exception) {
            // Missing/corrupt WAV or a prepare/start failure. Release and treat the
            // utterance as unrecoverable so reading is never stuck on one chunk.
            Log.e(TAG, "playFile failed index=$index path=${file.absolutePath}", e)
            try { mp.release() } catch (_: Exception) {}
            player = null
            onFileError(gen, index)
            return
        }
        // Prime the next chunk's render now that this one's audio is actually
        // going, so the paragraph boundary has no synthesis gap (ticket 02). Kept
        // OUT of the try block above so a surprise pre-render exception can't be
        // mistaken for a failure of the chunk we just started playing.
        maybePrimeLookAhead(index)
    }

    /**
     * Starts rendering the chunk after [playedIndex] while it is still playing, so
     * the next paragraph is ready the moment the current one ends (ticket 02).
     * Only one chunk is pre-rendered at a time; if a synthesis is already in
     * flight, or [playedIndex + 1] is already rendered, nothing extra is started.
     */
    private fun maybePrimeLookAhead(playedIndex: Int) {
        val next = playedIndex + 1
        if (next >= queue.size) return
        if (synthInFlight) return
        if (readyToPlayIndex == next) return
        beginSynthesis(next)
    }

    private fun onFileDone(gen: Int, index: Int) = advanceAfterUtterance(gen, index)

    private fun onFileError(gen: Int, index: Int) = advanceAfterUtterance(gen, index)

    /** Moves the stream past utterance [index], or finishes the stream at the end. */
    private fun advanceAfterUtterance(gen: Int, index: Int) {
        if (gen != generation) return
        releasePlayer()
        currentIndex = maxOf(currentIndex, index + 1)
        if (currentIndex >= queue.size) {
            Log.d(TAG, "stream finished after index=$index (${queue.size} utterances)")
            finishStream()
        } else if (readyToPlayIndex == currentIndex) {
            // The look-ahead pre-render for this chunk already finished (ticket 02);
            // play it immediately so the paragraph boundary has no synthesis gap.
            readyToPlayIndex = null
            playFile(gen, currentIndex)
        } else if (synthInFlight && synthIndex == currentIndex) {
            // The pre-render is still in flight (slow-synthesis engines): wait for
            // onSynthDone to hand us a playable file rather than re-synthesizing.
            Log.d(TAG, "advanceAfterUtterance: awaiting in-flight render index=$currentIndex")
        } else {
            beginSynthesis(currentIndex)
        }
    }

    /** Cancels all in-flight synthesis/playback and invalidates stale callbacks. */
    private fun resetPlayback() {
        generation++ // invalidate outstanding TTS/MediaPlayer callbacks first
        Log.d(TAG, "resetPlayback gen=$generation")
        teardownAudio()
    }

    /** Releases the player and discards any in-flight synthesis and rendered files. */
    private fun teardownAudio() {
        releasePlayer()
        synthInFlight = false
        synthIndex = null
        readyToPlayIndex = null
        engine.stop()
        clearSynthDir()
    }

    /** Marks the end of the stream (all utterances spoken or unrecoverable). */
    private fun finishStream() {
        Log.d(TAG, "finishStream")
        teardownAudio()
        queue = emptyList()
        currentIndex = 0
        paused = false
        onStreamDone?.invoke()
    }

    private fun releasePlayer() {
        player?.let { mp ->
            player = null
            try {
                mp.setOnCompletionListener(null)
                mp.setOnErrorListener(null)
                if (mp.isPlaying) mp.stop()
                mp.release()
            } catch (_: Exception) {
                // Already released / never prepared; nothing to do.
            }
        }
    }

    /** Absolute path of the rendered WAV for utterance [index] (no side effects). */
    private fun renderedFile(gen: Int, index: Int): File {
        if (!synthDir.exists()) synthDir.mkdirs()
        return File(synthDir, "utt-$gen-$index.wav")
    }

    /**
     * Path used as the *synthesis target*. Deletes any prior file first: some
     * engines refuse to overwrite an existing file. Call only BEFORE
     * [TextToSpeech.synthesizeToFile]. The playback path reads the just-written
     * file via [renderedFile], which must never delete it.
     */
    private fun outputFile(gen: Int, index: Int): File {
        val file = renderedFile(gen, index)
        file.delete()
        return file
    }

    private fun clearSynthDir() {
        synthDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Parses an utteranceId of the form `tts-<generation>-<index>-<time>-<nano>`
     * produced by [beginSynthesis], returning the (generation, index) pair.
     */
    private fun parseId(utteranceId: String?): Pair<Int, Int>? {
        if (utteranceId == null) return null
        val parts = utteranceId.split('-')
        if (parts.size < 3 || parts[0] != "tts") return null
        val generation = parts[1].toIntOrNull() ?: return null
        val index = parts[2].toIntOrNull() ?: return null
        return generation to index
    }

    /**
     * Pauses playback. When a file is actively playing this is a true pause via
     * [MediaPlayer.pause] (resume continues from the same position); when the
     * pause lands while the current chunk is still being synthesized, the
     * synthesis is cancelled and resume re-renders that chunk from its start.
     */
    fun pause() {
        if (queue.isEmpty() || paused) return
        paused = true
        Log.d(TAG, "pause (player=${player != null} synthInFlight=$synthInFlight) at index=$currentIndex")
        if (player != null) {
            try { player?.pause() } catch (_: Exception) { /* resume will re-render */ }
        } else if (synthInFlight) {
            resetPlayback() // cancels the in-flight synthesis; resume re-renders
        }
    }

    /** Resumes paused playback from the chunk that was current when paused. */
    fun resume() {
        if (queue.isEmpty()) return
        Log.d(TAG, "resume (paused=$paused) at index=$currentIndex")
        if (!paused) {
            player?.let { if (!it.isPlaying) it.start() }
            return
        }
        paused = false
        if (player != null) {
            try { player?.start() } catch (_: Exception) { enqueueFrom(currentIndex) }
        } else {
            enqueueFrom(currentIndex)
        }
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
        enqueueFrom(targetIndex)
    }

    /** Stops playback immediately and signals the stream as finished. */
    fun stop() {
        Log.d(TAG, "stop (queue=${queue.size})")
        resetPlayback()
        if (queue.isNotEmpty()) {
            queue = emptyList()
            currentIndex = 0
            paused = false
            onStreamDone?.invoke()
        }
    }

    val isReady: Boolean get() = ready

    /** Releases the TTS engine and any in-flight playback. Call when the owner is destroyed. */
    fun shutdown() {
        Log.d(TAG, "shutdown")
        resetPlayback()
        engine.shutdown()
    }

    /**
     * Rebuilds the [TextToSpeech] engine if the system's default engine has
     * changed since this controller was built (or last rebuilt). There is no
     * system broadcast for TTS engine changes, so this is the closest thing to a
     * listener: the main screen calls it every time it resumes and the engine is
     * recreated only when the default-engine package differs. Voice changes
     * *within* the same engine are not reflected by this package check; those are
     * covered by the explicit [reloadEngine] button in Settings.
     */
    fun reloadEngineIfChanged(): Boolean {
        val current = currentDefaultEnginePackage() ?: return false
        if (current == enginePackage) return false
        return rebuildEngine()
    }

    /** Force-rebuilds the [TextToSpeech] engine (used by the Settings reload button). */
    fun reloadEngine(): Boolean = rebuildEngine()

    private fun rebuildEngine(): Boolean {
        // Never tear down the engine while a rendered chunk is actively playing;
        // the swap wouldn't be audible until the next synthesis anyway, and the
        // resume auto-detect (or a later button tap) will pick it up when idle.
        if (player != null && !paused) {
            Log.w(TAG, "rebuildEngine: refusing while actively playing")
            return false
        }
        val previous = enginePackage
        enginePackage = currentDefaultEnginePackage()
        Log.d(TAG, "rebuildEngine: engine $previous -> $enginePackage")
        ready = false
        synthInFlight = false
        synthIndex = null
        readyToPlayIndex = null
        try { engine.stop() } catch (_: Exception) {}
        try { engine.shutdown() } catch (_: Exception) {}
        engine = TextToSpeech(appContext, this)
        return true
    }

    /** Reads the package name of the system's current default TTS engine, if any. */
    private fun currentDefaultEnginePackage(): String? =
        Settings.Secure.getString(appContext.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)

    private companion object {
        /** Sentinel key marking the article title as its own "section". */
        private val TITLE_KEY = Any()
    }
}
