package cloud.morris.wcast.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.morris.wcast.MainViewModel
import cloud.morris.wcast.SpeakingState
import cloud.morris.wcast.UiState
import cloud.morris.wcast.data.ArticleSection
import cloud.morris.wcast.data.SearchSuggestion

@Composable
fun ArticleScreen(viewModel: MainViewModel = viewModel()) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val speakingState by viewModel.speakingState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            query = query,
            onQueryChange = viewModel::onQueryChanged,
            onSearch = viewModel::onSearch
        )
        HorizontalDivider()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when (val state = uiState) {
                UiState.Idle -> IdleHint()
                UiState.Loading -> LoadingIndicator()
                is UiState.Suggestions -> SuggestionsList(state.items, viewModel::onSuggestionSelected)
                is UiState.Article -> ArticleContent(
                    title = state.title,
                    sections = state.sections,
                    speakingState = speakingState,
                    onPlayWholeArticle = viewModel::playWholeArticle,
                    onPlaySection = viewModel::playSection
                )
                is UiState.Error -> ErrorMessage(state.message)
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text("Search Wikipedia") },
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = onSearch) {
                Icon(imageVector = Icons.Filled.Search, contentDescription = "Search")
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() })
    )
}

@Composable
private fun IdleHint() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Search for a Wikipedia article to get started.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LoadingIndicator() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun SuggestionsList(
    suggestions: List<SearchSuggestion>,
    onSelect: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            Text(
                text = "No exact match found. Did you mean one of these?",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        items(suggestions, key = { it.url }) { suggestion ->
            SuggestionRow(suggestion = suggestion, onSelect = onSelect)
        }
    }
}

@Composable
private fun SuggestionRow(
    suggestion: SearchSuggestion,
    onSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(suggestion.title) }
            .padding(vertical = 12.dp)
    ) {
        Text(
            text = suggestion.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (suggestion.description.isNotBlank()) {
            Text(
                text = suggestion.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ArticleContent(
    title: String,
    sections: List<ArticleSection>,
    speakingState: SpeakingState,
    onPlayWholeArticle: () -> Unit,
    onPlaySection: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                PlayStopButton(
                    isSpeaking = speakingState == SpeakingState.WholeArticle,
                    contentDescription = if (speakingState == SpeakingState.WholeArticle) {
                        "Stop reading article"
                    } else {
                        "Read full article"
                    },
                    onClick = onPlayWholeArticle
                )
            }
        }
        sections.forEach { section ->
            item {
                Column {
                    section.heading?.let { heading ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SelectionContainer(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = heading,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            PlayStopButton(
                                isSpeaking = speakingState == SpeakingState.Section(section.id),
                                contentDescription = if (speakingState == SpeakingState.Section(section.id)) {
                                    "Stop reading section"
                                } else {
                                    "Read section"
                                },
                                onClick = { onPlaySection(section.id) }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    section.paragraphs.forEach { paragraph ->
                        SelectionContainer {
                            Text(
                                text = paragraph,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayStopButton(
    isSpeaking: Boolean,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (isSpeaking) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
    }
}