package cloud.morris.wcast

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cloud.morris.wcast.data.ArticleSection
import cloud.morris.wcast.data.BookmarkStore
import cloud.morris.wcast.data.SearchResult
import cloud.morris.wcast.data.SearchSuggestion
import cloud.morris.wcast.data.WikipediaRepository
import cloud.morris.wcast.data.subtreeOf
import cloud.morris.wcast.tts.MediaButtonHandler
import cloud.morris.wcast.tts.SpeechController
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI state for the main screen (unidirectional data flow). */
sealed interface UiState {
    data object Idle : UiState
    data object Loading : UiState
    data class Suggestions(val query: String, val items: List<SearchSuggestion>) : UiState
    data class Article(val title: String, val sections: List<ArticleSection>) : UiState
    data class Error(val message: String) : UiState
}

/**
 * Identifies which part of the article is currently being read aloud, or
 * [SpeakingState.None] when nothing is playing. Used to render Play/Stop
 * affordances next to the title and each section, and to drive the player bar.
 *
 * [isActive] mirrors whether any speech is currently in flight (playing or
 * paused) and conveniently distinguishes the title/lead (whose section id is
 * unknown here) from "nothing is playing".
 */
sealed interface SpeakingState {
    data object None : SpeakingState
    data object WholeArticle : SpeakingState
    data object WholeArticlePaused : SpeakingState
    data class Section(val id: String, val paused: Boolean = false) : SpeakingState

    /** True when some speech is in flight (playing or paused), false when idle. */
    val isActive: Boolean
        get() = when (this) {
            None -> false
            else -> true
        }
}

/**
 * Pinpoints the exact chunk of an article currently being read aloud, so the UI
 * can draw a left-margin indicator and scroll to it on demand.
 *
 * @property sectionId The id of the [ArticleSection] being read, or null while
 *   the article title is spoken (it always comes first).
 * @property paragraphIndex The index of the paragraph within its section, or
 *   null while a section heading is spoken.
 */
data class ActivePosition(
    val sectionId: String?,
    val paragraphIndex: Int?
)

/** A pending "resume from saved location?" prompt for a bookmarked article. */
data class ResumePrompt(
    val sectionId: String,
    val paragraphIndex: Int
)

class MainViewModel : AndroidViewModel {

    private val repository: WikipediaRepository

    /** Constructor used by [androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory]. */
    constructor(application: Application) : this(application, WikipediaRepository())

    /** Constructor allowing a custom repository to be injected (e.g. in tests). */
    constructor(application: Application, repository: WikipediaRepository) : super(application) {
        this.repository = repository
    }

    private val speechController = SpeechController(getApplication<Application>())

    private val mediaButtonHandler = MediaButtonHandler(getApplication<Application>())

    private val settings = SettingsStore(getApplication<Application>())

    private val bookmarkStore = BookmarkStore(getApplication<Application>())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _speakingState = MutableStateFlow<SpeakingState>(SpeakingState.None)
    val speakingState: StateFlow<SpeakingState> = _speakingState.asStateFlow()

    /** The exact position in the article currently being read (null when idle). */
    private val _activePosition = MutableStateFlow<ActivePosition?>(null)
    val activePosition: StateFlow<ActivePosition?> = _activePosition.asStateFlow()

    /** Player-bar control size as a percentage (100 = default), from settings. */
    private val _playerBarScale = MutableStateFlow(settings.playerBarScale)
    val playerBarScale: StateFlow<Int> = _playerBarScale.asStateFlow()

    /** Whether the currently-open article is bookmarked (drives the search-box icon). */
    private val _bookmarked = MutableStateFlow(false)
    val bookmarked: StateFlow<Boolean> = _bookmarked.asStateFlow()

    /** Non-null while a "resume from saved location?" prompt should be shown. */
    private val _resumePrompt = MutableStateFlow<ResumePrompt?>(null)
    val resumePrompt: StateFlow<ResumePrompt?> = _resumePrompt.asStateFlow()

    private var searchJob: Job? = null

    init {
        // When a spoken stream finishes, clear the speaking state and position.
        speechController.onStreamDone = {
            _speakingState.value = SpeakingState.None
            _activePosition.value = null
            mediaButtonHandler.onPlaybackStopped()
        }
        // Track which section is actively being spoken so skips highlighting and
        // per-section affordances stay accurate during whole-article playback.
        speechController.onUtteranceStarted = { _, utterance ->
            val sectionId = utterance.sectionId
            _speakingState.value = if (sectionId != null) {
                SpeakingState.Section(sectionId)
            } else {
                SpeakingState.WholeArticle
            }
            _activePosition.value = ActivePosition(sectionId, utterance.paragraphIndex)
            // Maintain the saved play location for a bookmarked article as it plays.
            persistPositionIfBookmarked(sectionId, utterance.paragraphIndex)
        }
        // A press of the wired/Bluetooth headset "main" button stops speech.
        mediaButtonHandler.onMainButtonPressed = { stopSpeaking() }
        // Losing audio focus (call, or another app taking over) also stops speech.
        mediaButtonHandler.onFocusLost = { stopSpeaking() }
        // Media-notification / drawer transport controls route back here.
        mediaButtonHandler.onStopPressed = { stopSpeaking() }
        mediaButtonHandler.onPreviousPressed = { skipBack() }
        mediaButtonHandler.onNextPressed = { skipForward() }
        mediaButtonHandler.onPlayPausePressed = { togglePause() }
    }

    /** Re-reads user preferences so the UI reflects changes made in Settings. */
    fun refreshSettingsFromStore() {
        _playerBarScale.value = settings.playerBarScale
    }

    fun onQueryChanged(text: String) {
        _query.value = text
    }

    fun onSearch() {
        val term = _query.value.trim()
        if (term.isEmpty()) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                when (val result = repository.search(term)) {
                    is SearchResult.Exact -> loadArticle(result.title)
                    is SearchResult.Suggestions ->
                        if (result.items.isEmpty()) {
                            _uiState.value = UiState.Error("No results found for \"$term\".")
                        } else {
                            _uiState.value = UiState.Suggestions(term, result.items)
                        }
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Something went wrong.")
            }
        }
    }

    fun onSuggestionSelected(title: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                loadArticle(title)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Something went wrong.")
            }
        }
    }

    /**
     * Opens an article tapped via an internal wiki link. Behaves identically to
     * [onSuggestionSelected]: the link target is loaded as if it had been
     * searched for (flat replacement — no back stack).
     */
    fun openArticle(title: String) {
        onSuggestionSelected(title)
    }

    /** Reads the entire article from the title through the last section. */
    fun playWholeArticle() {
        val state = _uiState.value
        if (state !is UiState.Article) return

        if (_speakingState.value == SpeakingState.WholeArticle) {
            stopSpeaking()
            return
        }
        if (_speakingState.value == SpeakingState.WholeArticlePaused) {
            resumeSpeaking()
            return
        }
        stopSpeaking()
        // For a bookmarked article with a saved position, the play control resumes
        // from that paragraph (the user's "even if they hit No on the popup" case).
        val saved = bookmarkStore.load(state.title)
        if (saved != null && saved.sectionId != null && saved.paragraphIndex != null) {
            resumeFromSavedLocation(saved.sectionId, saved.paragraphIndex)
            return
        }
        speechController.speakArticle(state.title, state.sections)
        _speakingState.value = SpeakingState.WholeArticle
        mediaButtonHandler.onPlaybackStarted(state.title)
    }

    /** Reads a single section by its id, or stops if it is already playing. */
    fun playSection(id: String) {
        val state = _uiState.value
        if (state !is UiState.Article) return
        if (state.sections.none { it.id == id }) return

        val current = _speakingState.value
        if (current == SpeakingState.Section(id)) {
            stopSpeaking()
            return
        }
        if (current == SpeakingState.Section(id, paused = true)) {
            resumeSpeaking()
            return
        }
        stopSpeaking()
        // Playing a heading reads its whole subtree: the section itself plus any
        // deeper following sections (its subheadings), in document order. Leaf
        // sections simply resolve to themselves. When the "continue to the end"
        // setting is enabled, instead read from this section through the end of
        // the article.
        val sectionsToSpeak = if (settings.continuity) {
            state.sections.drop(state.sections.indexOfFirst { it.id == id })
        } else {
            subtreeOf(state.sections, id)
        }
        speechController.speakSections(sectionsToSpeak)
        _speakingState.value = SpeakingState.Section(id)
        mediaButtonHandler.onPlaybackStarted(state.title)
    }

    /**
     * Starts playback from a specific paragraph within a section, following the
     * continuity setting: with it off, reads from that paragraph through the end
     * of its own section; with it on, continues through the end of the article.
     */
    fun playParagraph(sectionId: String, paragraphIndex: Int) {
        val state = _uiState.value
        if (state !is UiState.Article) return
        val index = state.sections.indexOfFirst { it.id == sectionId }
        if (index < 0) return
        val section = state.sections[index]
        if (paragraphIndex !in section.paragraphs.indices) return

        stopSpeaking()
        val following = if (settings.continuity) state.sections.drop(index + 1) else emptyList()
        speechController.speakFromParagraph(section, paragraphIndex, following)
        _speakingState.value = SpeakingState.Section(sectionId)
        mediaButtonHandler.onPlaybackStarted(state.title)
    }

    /** Stops any ongoing speech and clears the speaking state. */
    fun stopSpeaking() {
        speechController.stop()
        _speakingState.value = SpeakingState.None
        _activePosition.value = null
        mediaButtonHandler.onPlaybackStopped()
    }

    /** Pauses the current playback in place (resume restarts the current chunk). */
    fun pauseSpeaking() {
        when (val current = _speakingState.value) {
            is SpeakingState.Section -> {
                speechController.pause()
                _speakingState.value = SpeakingState.Section(current.id, paused = true)
            }
            SpeakingState.WholeArticle -> {
                speechController.pause()
                _speakingState.value = SpeakingState.WholeArticlePaused
            }
            else -> Unit
        }
        // Pause is "stop-like" (resume restarts the current chunk), so the
        // reading indicator disappears while paused, mirroring that behavior.
        _activePosition.value = null
        mediaButtonHandler.onPlaybackPaused()
    }

    /** Resumes paused playback from the chunk that was current when paused. */
    fun resumeSpeaking() {
        when (val current = _speakingState.value) {
            is SpeakingState.Section -> {
                speechController.resume()
                _speakingState.value = SpeakingState.Section(current.id, paused = false)
            }
            SpeakingState.WholeArticlePaused -> {
                speechController.resume()
                _speakingState.value = SpeakingState.WholeArticle
            }
            else -> Unit
        }
        // Re-acquire focus (it may have been abandoned) and mark as playing.
        mediaButtonHandler.onPlaybackStarted((_uiState.value as? UiState.Article)?.title)
    }

    /** Toggles between paused and playing for the current stream. */
    fun togglePause() {
        when (val current = _speakingState.value) {
            is SpeakingState.Section -> if (current.paused) resumeSpeaking() else pauseSpeaking()
            SpeakingState.WholeArticle -> pauseSpeaking()
            SpeakingState.WholeArticlePaused -> resumeSpeaking()
            SpeakingState.None -> Unit
        }
    }

    /** Skips to the start of the next section relative to what's playing. */
    fun skipForward() = skipRelative(delta = 1)

    /** Skips to the start of the previous section relative to what's playing. */
    fun skipBack() = skipRelative(delta = -1)

    /**
     * Advances by [delta] sections (+1 forward, -1 back). Behaves the same in
     * both playback modes:
     *   - Whole-article: jumps within the already-queued article ([SpeechController.skipSections]).
     *   - Single section: stops the current section and starts the neighbouring
     *     section in the article, reusing the proven [playSection] path.
     * No-ops at either boundary or when idle.
     */
    private fun skipRelative(delta: Int) {
        when (val current = _speakingState.value) {
            SpeakingState.WholeArticle,
            SpeakingState.WholeArticlePaused -> speechController.skipSections(delta)

            is SpeakingState.Section -> {
                val state = _uiState.value
                if (state !is UiState.Article) return
                val index = state.sections.indexOfFirst { it.id == current.id }
                if (index < 0) return
                val target = state.sections.getOrNull(index + delta) ?: return
                // Skip steps one flat section at a time (mirroring the keyboard
                // next/previous affordance), so it speaks exactly the neighbouring
                // section rather than expanding it into its subtree the way the
                // play button does.
                stopSpeaking()
                speechController.speakSection(target)
                _speakingState.value = SpeakingState.Section(target.id)
            }

            SpeakingState.None -> Unit
        }
    }

    private suspend fun loadArticle(title: String) {
        stopSpeaking()
        // Stick with the bookmarked (offline) copy by default; only fetch when no
        // stored copy exists for this title.
        val stored = bookmarkStore.load(title)
        if (stored != null) {
            presentArticle(stored.title, stored.sections)
            _bookmarked.value = true
            maybeOfferResume(stored)
        } else {
            val sections = repository.fetchArticle(title)
            presentArticle(title, sections)
            _bookmarked.value = false
        }
    }

    /** Opens an article directly from the bookmarks list (uses the stored copy). */
    fun openBookmarkedArticle(title: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val stored = bookmarkStore.load(title)
                    ?: run {
                        // No longer stored (shouldn't happen from the list), fall back to fetch.
                        val sections = repository.fetchArticle(title)
                        presentArticle(title, sections)
                        _bookmarked.value = false
                        return@launch
                    }
                stopSpeaking()
                presentArticle(stored.title, stored.sections)
                _bookmarked.value = true
                maybeOfferResume(stored)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Something went wrong.")
            }
        }
    }

    /** Shows an article without resetting the scroll state. */
    private fun presentArticle(title: String, sections: List<ArticleSection>) {
        _uiState.value = UiState.Article(title, sections)
        _resumePrompt.value = null
    }

    /** Offers "resume from saved location?" when a bookmarked article has a position. */
    private fun maybeOfferResume(bookmark: BookmarkStore.Bookmark) {
        if (bookmark.sectionId != null && bookmark.paragraphIndex != null) {
            _resumePrompt.value = ResumePrompt(bookmark.sectionId, bookmark.paragraphIndex)
        }
    }

    /** Toggles the current article in/out of bookmarks. */
    fun toggleBookmark() {
        val state = _uiState.value
        if (state !is UiState.Article) return
        if (bookmarkStore.isBookmarked(state.title)) {
            bookmarkStore.delete(state.title)
            _bookmarked.value = false
        } else {
            bookmarkStore.saveCopy(state.title, state.sections)
            // Capture the current play location (if any) at the moment of saving.
            val pos = _activePosition.value
            if (pos != null && (pos.sectionId != null || pos.paragraphIndex != null)) {
                bookmarkStore.savePosition(state.title, pos.sectionId, pos.paragraphIndex)
            }
            _bookmarked.value = true
        }
    }

    /** Resumes playback from the saved paragraph (used by the resume prompt's Yes). */
    fun resumeSavedPosition() {
        val prompt = _resumePrompt.value ?: return
        _resumePrompt.value = null
        resumeFromSavedLocation(prompt.sectionId, prompt.paragraphIndex)
    }

    /**
     * Resumes from [sectionId]/[paragraphIndex] through the end of the article,
     * independent of the continuity setting — the "resume" action implies the user
     * wants to keep listening to the rest, unlike a plain section/paragraph play.
     */
    private fun resumeFromSavedLocation(sectionId: String, paragraphIndex: Int) {
        val state = _uiState.value
        if (state !is UiState.Article) return
        val index = state.sections.indexOfFirst { it.id == sectionId }
        if (index < 0) return
        val section = state.sections[index]
        if (paragraphIndex !in section.paragraphs.indices) return

        stopSpeaking()
        val following = state.sections.drop(index + 1)
        speechController.speakFromParagraph(section, paragraphIndex, following)
        _speakingState.value = SpeakingState.Section(sectionId)
        mediaButtonHandler.onPlaybackStarted(state.title)
    }

    /** Dismisses the resume prompt without starting playback. */
    fun dismissResumePrompt() {
        _resumePrompt.value = null
    }

    /** Persists the current play location for a bookmarked article (no-op otherwise). */
    private fun persistPositionIfBookmarked(sectionId: String?, paragraphIndex: Int?) {
        val state = _uiState.value
        if (state is UiState.Article && bookmarkStore.isBookmarked(state.title)) {
            bookmarkStore.savePosition(state.title, sectionId, paragraphIndex)
        }
    }

    override fun onCleared() {
        speechController.shutdown()
        mediaButtonHandler.release()
        super.onCleared()
    }
}