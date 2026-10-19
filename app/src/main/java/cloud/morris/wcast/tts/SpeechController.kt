package cloud.morris.wcast.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.SystemClock
import android.util.Log
import cloud.morris.wcast.DebugLog
import cloud.morris.wcast.NormalizedTextStore
import cloud.morris.wcast.SettingsStore
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
 * A single synthesis/playback unit (ticket 30). One queue entry may produce
 * several chunks during the cold-start priming ramp; in steady state a chunk
 * corresponds one-to-one with an [Utterance].
 *
 * @property utteranceIndex Index into the paragraph-level queue this chunk
 *   belongs to, so tracking (blue line, bookmarks) stays paragraph-level.
 * @property text The normalized text to synthesize, already split.
 * @property isFirstOfParagraph True for the first chunk of its owning queue
 *   entry, so [onUtteranceStarted] fires once per entry.
 */
private data class Chunk(
    val utteranceIndex: Int,
    val text: String,
    val isFirstOfParagraph: Boolean
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

    /** Read at queue-build time so a Settings toggle takes effect on the next play. */
    private val settings: SettingsStore = SettingsStore(appContext)

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
     * Synthesis/playback chunks derived from [queue] (ticket 30). The play head,
     * look-ahead, and synthesis index into THIS list; tracking (blue line, skip,
     * bookmarks) still indexes into [queue] via [Chunk.utteranceIndex].
     */
    private var chunks: List<Chunk> = emptyList()

    /** Index into [chunks] of the chunk currently being rendered/spoken. */
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

    /** The chunk index whose synthesis is in flight, valid only when [synthInFlight]. */
    private var synthIndex: Int? = null

    /**
     * Finished chunks that have been rendered (look-ahead) but are not yet
     * playing because an earlier chunk is still audible, kept in ascending index
     * order (a FIFO of the play head). This is what removes the per-paragraph
     * synthesis gap: several chunks are rendered while the current one plays,
     * instead of only one (ticket 02). Bounded by [LOOK_AHEAD_CHUNKS] via
     * [topUpLookAhead].
     */
    private val readyIndices = ArrayDeque<Int>()

    /**
     * Monotonic timestamp (`elapsedRealtime`) of when the current in-flight
     * synthesis began, used to measure per-chunk render latency. Synthesis is
     * serialized, so a single slot is enough.
     */
    private var synthStartedAtMs: Long = 0L

    /**
     * Monotonic timestamp of when the previous chunk's audio ended (or, before
     * the first chunk, the stream start). [playFile] diffs against it to report
     * the inter-utterance "gap" — the very delay ticket 02 is about.
     */
    private var lastUtteranceEndedAtMs: Long = SystemClock.elapsedRealtime()

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
        // Expose the exact text handed to the TTS engine (ticket 34).
        NormalizedTextStore.capture(
            heading = normalized.firstOrNull()?.text.orEmpty(),
            text = normalized.joinToString("\n\n") { it.text }
        )
        if (!ready) {
            pendingQueue = normalized
            return
        }
        startQueue(normalized)
    }

    private fun startQueue(utterances: List<Utterance>) {
        resetPlayback()
        queue = utterances
        chunks = buildChunks(utterances)
        currentIndex = 0
        Log.d(TAG, "startQueue utterances=${utterances.size} chunks=${chunks.size}")
        lastUtteranceEndedAtMs = SystemClock.elapsedRealtime()
        DebugLog.d(TAG, "stream start utterances=${utterances.size} chunks=${chunks.size} progressive=${settings.progressiveChunking}")
        if (utterances.isEmpty()) {
            onStreamDone?.invoke()
            return
        }
        beginSynthesis(0)
    }

    /**
     * (Re-)renders [queue] starting at utterance [fromIndex] (inclusive), rebuilding
     * the chunk list so the new start's first paragraph begins with a single
     * sentence (ticket 30: a new start in a new place starts sentence-by-sentence).
     */
    private fun enqueueFrom(fromIndex: Int) {
        resetPlayback()
        chunks = buildChunks(queue, fromIndex)
        currentIndex = chunks.indexOfFirst { it.utteranceIndex == fromIndex }.coerceAtLeast(0)
        beginSynthesis(currentIndex)
    }

    // ── Progressive sentence-first chunking (ticket 30) ───────────────────────

    /**
     * Derives synthesis/playback chunks from the paragraph-level [queue]. Without
     * chunking enabled, one chunk per entry — today's behavior. With it enabled,
     * the first [PRIME_PARAGRAPHS] paragraphs from a (re)start are split into a
     * sentence ladder so first audio is a single sentence while ever-larger chunks
     * render behind it; titles/headings stay their own single chunk and everything
     * after the prime window graduates to whole paragraphs (steady state).
     */
    private fun buildChunks(utterances: List<Utterance>, fromUtteranceIndex: Int = 0): List<Chunk> {
        val progressive = settings.progressiveChunking
        val maxLen = TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(1)
        val chunks = mutableListOf<Chunk>()
        var rampedParagraphs = 0
        for ((i, utterance) in utterances.withIndex()) {
            val isParagraph = utterance.paragraphIndex != null
            val rampThis = progressive && isParagraph &&
                i >= fromUtteranceIndex && rampedParagraphs < PRIME_PARAGRAPHS
            val rawGroups = if (rampThis) {
                sentenceGroups(splitIntoSentences(utterance.text))
            } else {
                listOf(utterance.text)
            }
            if (rampThis) rampedParagraphs++
            val groups = if (progressive) rawGroups.flatMap { splitToFit(it, maxLen) } else rawGroups
            groups.forEachIndexed { j, text ->
                chunks.add(Chunk(i, text, isFirstOfParagraph = j == 0))
            }
        }
        return chunks
    }

    /** Splits [text] into sentences, keeping each sentence's trailing punctuation. */
    private fun splitIntoSentences(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val sentences = SENTENCE.findAll(trimmed)
            .map { it.value.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        return sentences.ifEmpty { listOf(trimmed) }
    }

    /**
     * Groups [sentences] into the priming ladder 1, 1, 2, 4, then the rest: a
     * 10-sentence paragraph becomes `s1`, `s2`, `s3..s4`, `s5..s8`, `s9..s10`; a
     * short paragraph collapses naturally (`s1`, `s1..s2`, ...).
     */
    private fun sentenceGroups(sentences: List<String>): List<String> {
        val groups = mutableListOf<String>()
        var i = 0
        for (size in intArrayOf(1, 1, 2, 4)) {
            if (i >= sentences.size) break
            val end = minOf(i + size, sentences.size)
            groups.add(sentences.subList(i, end).joinToString(" "))
            i = end
        }
        if (i < sentences.size) {
            groups.add(sentences.subList(i, sentences.size).joinToString(" "))
        }
        return groups
    }

    /**
     * Enforces the engine's per-synthesis length limit
     * (TextToSpeech.getMaxSpeechInputLength()) by splitting [text] on sentence
     * boundaries, hard-splitting only a single sentence that is itself over-long.
     */
    private fun splitToFit(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val sentences = splitIntoSentences(text).ifEmpty { listOf(text) }
        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in sentences) {
            if (sentence.length > maxLen) {
                if (current.isNotEmpty()) { pieces.add(current.toString()); current.clear() }
                var k = 0
                while (k < sentence.length) {
                    val end = minOf(k + maxLen, sentence.length)
                    pieces.add(sentence.substring(k, end).trim())
                    k = end
                }
            } else if (current.isNotEmpty() && current.length + 1 + sentence.length > maxLen) {
                pieces.add(current.toString())
                current.setLength(0)
                current.append(sentence)
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
            }
        }
        if (current.isNotEmpty()) pieces.add(current.toString())
        return pieces.ifEmpty { listOf(text) }
    }

    /**
     * Renders chunk [index] to a WAV file; playback begins in [onSynthDone].
     *
     * Does NOT touch [currentIndex]: the play head is the caller's
     * responsibility ([startQueue], [enqueueFrom], [advanceAfterUtterance]). This
     * lets the look-ahead path pre-render chunk N+1 while chunk N is still the
     * play head (ticket 02).
     */
    private fun beginSynthesis(index: Int) {
        if (index !in chunks.indices) {
            finishStream()
            return
        }
        val gen = generation
        val chunk = chunks[index]
        val utteranceId = "tts-$gen-$index-${System.currentTimeMillis()}-${System.nanoTime()}"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        val file = outputFile(gen, index)
        synthInFlight = true
        synthIndex = index
        Log.d(TAG, "beginSynthesis index=$index/${chunks.size} textLen=${chunk.text.length} file=${file.name}")
        synthStartedAtMs = SystemClock.elapsedRealtime()
        DebugLog.d(TAG, "synth start index=$index/${chunks.size} textLen=${chunk.text.length}")
        val result = engine.synthesizeToFile(chunk.text, params, file, utteranceId)
        Log.d(TAG, "synthesizeToFile index=$index result=$result exists=${file.exists()} size=${file.length()}")
        DebugLog.d(TAG, "synthesizeToFile index=$index result=$result renderMs=${SystemClock.elapsedRealtime() - synthStartedAtMs} bytes=${file.length()}")
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
        DebugLog.d(TAG, "synth done index=$index renderMs=${SystemClock.elapsedRealtime() - synthStartedAtMs} buffered=${player != null} readyIndices=${readyIndices.size}")
        if (player == null) {
            // Nothing is audible yet, so this rendered chunk becomes the play head.
            playFile(gen, index)
        } else {
            // A look-ahead render finished while an earlier chunk is still
            // playing; buffer it in play order (ticket 02).
            readyIndices.addLast(index)
            topUpLookAhead()
        }
    }

    private fun onSynthError(utteranceId: String?) {
        val (gen, index) = parseId(utteranceId) ?: return
        if (gen != generation) return
        if (!synthInFlight || synthIndex != index) return
        synthInFlight = false
        synthIndex = null
        Log.w(TAG, "onSynthError gen=$gen index=$index")
        DebugLog.d(TAG, "synth error index=$index renderMs=${SystemClock.elapsedRealtime() - synthStartedAtMs}")
        if (player == null) {
            // The chunk we were about to play failed to render; skip past it.
            advanceAfterUtterance(gen, index)
        }
        // Else: a look-ahead pre-render failed. Nothing to do — the chunk is
        // simply not pre-rendered and will be synthesized on demand when the
        // current chunk finishes (advanceAfterUtterance's fallback branch).
    }

    /** Plays the previously rendered WAV for chunk [index] from our own process. */
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
            DebugLog.d(TAG, "play start index=$index gapMs=${SystemClock.elapsedRealtime() - lastUtteranceEndedAtMs} durationMs=${mp.duration} bytes=${file.length()}")
            val chunk = chunks[index]
            if (chunk.isFirstOfParagraph) {
                onUtteranceStarted?.invoke(chunk.utteranceIndex, queue[chunk.utteranceIndex])
            }
        } catch (e: Exception) {
            // Missing/corrupt WAV or a prepare/start failure. Release and treat the
            // utterance as unrecoverable so reading is never stuck on one chunk.
            Log.e(TAG, "playFile failed index=$index path=${file.absolutePath}", e)
            try { mp.release() } catch (_: Exception) {}
            player = null
            onFileError(gen, index)
            return
        }
        // Top up the look-ahead buffer now that this chunk's audio is actually
        // going, so the paragraph boundaries have no synthesis gap (ticket 02).
        // Kept OUT of the try block above so a surprise pre-render exception
        // can't be mistaken for a failure of the chunk we just started playing.
        topUpLookAhead()
    }

    /**
     * Starts one look-ahead render if the serialized synthesis slot is free and
     * the frontier is still under the horizon. Called after a chunk starts
     * playing ([playFile]) and after each synthesis completes ([onSynthDone]) so
     * the render pipeline never idles while there's room under the horizon
     * (ticket 02).
     *
     * The engine serializes [TextToSpeech.synthesizeToFile], so only one chunk
     * renders at a time and a single conditional is enough — the next render is
     * kicked off by the following [onSynthDone]. The frontier is the highest
     * index already rendered or in flight plus one; the play head ([currentIndex])
     * is always rendered, so the frontier never re-renders the current chunk.
     */
    private fun topUpLookAhead() {
        if (synthInFlight) return
        val horizon = minOf(currentIndex + LOOK_AHEAD_CHUNKS, chunks.size - 1)
        val highest = maxOf(currentIndex, readyIndices.lastOrNull() ?: -1)
        val frontier = highest + 1
        if (frontier > horizon) return
        DebugLog.d(TAG, "topUpLookAhead begin frontier=$frontier horizon=$horizon buffer=${readyIndices.size}")
        beginSynthesis(frontier)
    }

    private fun onFileDone(gen: Int, index: Int) = advanceAfterUtterance(gen, index)

    private fun onFileError(gen: Int, index: Int) = advanceAfterUtterance(gen, index)

    /** Moves the stream past chunk [index], or finishes the stream at the end. */
    private fun advanceAfterUtterance(gen: Int, index: Int) {
        if (gen != generation) return
        releasePlayer()
        lastUtteranceEndedAtMs = SystemClock.elapsedRealtime()
        DebugLog.d(TAG, "utterance ended index=$index readyIndices=${readyIndices.size} synthInFlight=$synthInFlight synthIndex=$synthIndex")
        currentIndex = maxOf(currentIndex, index + 1)
        if (currentIndex >= chunks.size) {
            Log.d(TAG, "stream finished after index=$index (${chunks.size} chunks)")
            finishStream()
        } else if (readyIndices.isNotEmpty() && readyIndices.first() == currentIndex) {
            // The look-ahead pre-render for this chunk already finished (ticket 02);
            // play it immediately so the paragraph boundary has no synthesis gap.
            readyIndices.removeFirst()
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
        readyIndices.clear()
        engine.stop()
        clearSynthDir()
    }

    /** Marks the end of the stream (all utterances spoken or unrecoverable). */
    private fun finishStream() {
        Log.d(TAG, "finishStream")
        teardownAudio()
        queue = emptyList()
        chunks = emptyList()
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
        DebugLog.d(TAG, "pause player=${player != null} synthInFlight=$synthInFlight index=$currentIndex")
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
        DebugLog.d(TAG, "resume paused=$paused index=$currentIndex")
        if (!paused) {
            player?.let { if (!it.isPlaying) it.start() }
            return
        }
        paused = false
        val currentChunk = chunks.getOrNull(currentIndex)
        if (player != null) {
            try {
                player?.start()
                // Resuming the same rendered chunk: re-announce its owning
                // paragraph so the UI restores the active-position / left-margin
                // highlight that pause() cleared.
                if (currentChunk != null) {
                    DebugLog.d(TAG, "resume re-announce chunk=$currentIndex utterance=${currentChunk.utteranceIndex}")
                    onUtteranceStarted?.invoke(currentChunk.utteranceIndex, queue[currentChunk.utteranceIndex])
                }
            } catch (_: Exception) {
                currentChunk?.let { enqueueFrom(it.utteranceIndex) }
            }
        } else {
            currentChunk?.let { enqueueFrom(it.utteranceIndex) }
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

        val currentUtteranceIndex = chunks.getOrNull(currentIndex)?.utteranceIndex ?: return
        val currentSectionPos = sectionStartIndices.indexOfLast { it <= currentUtteranceIndex }
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
            chunks = emptyList()
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
        readyIndices.clear()
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

        /** Number of finished chunks to keep buffered ahead of the play head (ticket 02). */
        private const val LOOK_AHEAD_CHUNKS = 8

        /**
         * Roughly how many paragraphs from a fresh (re)start use the sentence
         * ladder before graduating to whole paragraphs. Approximates "ramp until
         * the look-ahead buffer is primed" (ticket 30, ramp scope 2b).
         */
        private const val PRIME_PARAGRAPHS = 3

        /** Sentence enders for the chunking splitter (`.` / `!` / `?`, incl. repeats). */
        private val SENTENCE = Regex("""[^.!?]+[.!?]*""")
    }
}
