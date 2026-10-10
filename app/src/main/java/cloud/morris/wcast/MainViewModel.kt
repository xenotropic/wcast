package cloud.morris.wcast

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cloud.morris.wcast.data.ArticleSection
import cloud.morris.wcast.data.SearchResult
import cloud.morris.wcast.data.SearchSuggestion
import cloud.morris.wcast.data.WikipediaRepository
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
 * affordances next to the title and each section.
 */
sealed interface SpeakingState {
    data object None : SpeakingState
    data object WholeArticle : SpeakingState
    data class Section(val id: String) : SpeakingState
}

class MainViewModel : AndroidViewModel {

    private val repository: WikipediaRepository

    /** Constructor used by [androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory]. */
    constructor(application: Application) : this(application, WikipediaRepository())

    /** Constructor allowing a custom repository to be injected (e.g. in tests). */
    constructor(application: Application, repository: WikipediaRepository) : super(application) {
        this.repository = repository
    }

    private val speechController = SpeechController(getApplication<Application>())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _speakingState = MutableStateFlow<SpeakingState>(SpeakingState.None)
    val speakingState: StateFlow<SpeakingState> = _speakingState.asStateFlow()

    private var searchJob: Job? = null

    init {
        // When a spoken stream finishes, clear the speaking state.
        speechController.onStreamDone = {
            _speakingState.value = SpeakingState.None
        }
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

    /** Reads the entire article from the title through the last section. */
    fun playWholeArticle() {
        val state = _uiState.value
        if (state !is UiState.Article) return

        if (_speakingState.value == SpeakingState.WholeArticle) {
            stopSpeaking()
            return
        }
        stopSpeaking()
        speechController.speakArticle(state.title, state.sections)
        _speakingState.value = SpeakingState.WholeArticle
    }

    /** Reads a single section by its id, or stops if it is already playing. */
    fun playSection(id: String) {
        val state = _uiState.value
        if (state !is UiState.Article) return
        val section = state.sections.firstOrNull { it.id == id } ?: return

        if (_speakingState.value == SpeakingState.Section(id)) {
            stopSpeaking()
            return
        }
        stopSpeaking()
        speechController.speakSection(section)
        _speakingState.value = SpeakingState.Section(id)
    }

    /** Stops any ongoing speech and clears the speaking state. */
    fun stopSpeaking() {
        speechController.stop()
        _speakingState.value = SpeakingState.None
    }

    private suspend fun loadArticle(title: String) {
        val sections = repository.fetchArticle(title)
        stopSpeaking()
        _uiState.value = UiState.Article(title, sections)
    }

    override fun onCleared() {
        speechController.shutdown()
        super.onCleared()
    }
}