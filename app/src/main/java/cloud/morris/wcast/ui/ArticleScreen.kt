package cloud.morris.wcast.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.ClickableText
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import cloud.morris.wcast.ActivePosition
import cloud.morris.wcast.MainViewModel
import cloud.morris.wcast.SettingsActivity
import cloud.morris.wcast.SpeakingState
import cloud.morris.wcast.UiState
import cloud.morris.wcast.data.ArticleParagraph
import cloud.morris.wcast.data.ArticleSection
import cloud.morris.wcast.data.SearchSuggestion

@Composable
fun ArticleScreen(viewModel: MainViewModel = viewModel()) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val speakingState by viewModel.speakingState.collectAsStateWithLifecycle()
    val activePosition by viewModel.activePosition.collectAsStateWithLifecycle()
    val playerBarScale by viewModel.playerBarScale.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Re-read user preferences (e.g. player-bar size) whenever the user returns
    // from Settings, so the change applies immediately without a restart.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshSettingsFromStore()
        onPauseOrDispose { }
    }

    // Survives across article loads; reset when a new article is opened below.
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Follow mode: while true, the view auto-scrolls so the currently-read
    // paragraph stays pinned to the top. Entered by tapping "jump to current"
    // or pressing play; left by any manual scroll (drag) gesture.
    var followReading by remember { mutableStateOf(false) }

    // Detects user-initiated scrolling. Programmatic scrolls (scrollToItem /
    // animateScrollToItem) do NOT emit drag interactions, so a real finger drag
    // is the only thing that sets this off — exactly what we want for exiting
    // follow mode.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                followReading = false
            }
        }
    }

    fun scrollToCurrentTop() {
        val state = uiState as? UiState.Article ?: return
        val offset = if (hasOutline(state.sections)) 1 else 0
        val index = indexFor(state.title, state.sections, activePosition, offset) ?: return
        followReading = true
        scope.launch { listState.animateScrollToItem(index, scrollOffset = 0) }
    }

    // While following, keep the currently-read paragraph pinned to the top as
    // the reading position advances.
    LaunchedEffect(activePosition, followReading) {
        if (!followReading) return@LaunchedEffect
        val state = uiState as? UiState.Article ?: return@LaunchedEffect
        val offset = if (hasOutline(state.sections)) 1 else 0
        val index = indexFor(state.title, state.sections, activePosition, offset) ?: return@LaunchedEffect
        listState.animateScrollToItem(index, scrollOffset = 0)
    }

    // Reset scroll when a different article is opened so a stale offset from the
    // previous (possibly much longer/shorter) article is never kept.
    val articleTitle = (uiState as? UiState.Article)?.title
    LaunchedEffect(articleTitle) {
        if (articleTitle != null) {
            followReading = false
            listState.scrollToItem(0)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            query = query,
            onQueryChange = viewModel::onQueryChanged,
            onSearch = viewModel::onSearch
        )
        PlayerBar(
            speakingState = speakingState,
            playerBarScale = playerBarScale,
            onJumpToCurrent = ::scrollToCurrentTop,
            onPlayPause = viewModel::togglePause,
            onSkipBack = viewModel::skipBack,
            onSkipForward = viewModel::skipForward,
            onStop = viewModel::stopSpeaking,
            onOpenSettings = {
                context.startActivity(Intent(context, SettingsActivity::class.java))
            }
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
                    activePosition = activePosition,
                    listState = listState,
                    onPlayWholeArticle = {
                        followReading = true
                        viewModel.playWholeArticle()
                    },
                    onPlaySection = { id ->
                        followReading = true
                        viewModel.playSection(id)
                    },
                    onPlayParagraph = { sectionId, paragraphIndex ->
                        followReading = true
                        viewModel.playParagraph(sectionId, paragraphIndex)
                    },
                    onLinkClick = viewModel::openArticle
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Clear search text"
                        )
                    }
                }
                IconButton(onClick = onSearch) {
                    Icon(imageVector = Icons.Filled.Search, contentDescription = "Search")
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() })
    )
}

@Composable
private fun PlayerBar(
    speakingState: SpeakingState,
    playerBarScale: Int,
    onJumpToCurrent: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val isActive = speakingState.isActive
    val isPaused = when (speakingState) {
        is SpeakingState.Section -> speakingState.paused
        SpeakingState.WholeArticlePaused -> true
        else -> false
    }

    // Player-bar size is a user setting expressed as a percentage (100 = the
    // original layout). Buttons stay on the platform's 48dp touch target so the
    // bar remains tappable; only the drawn bar height and the icon glyphs scale,
    // so larger values give bigger controls without shrinking the hit areas.
    val scale = playerBarScale.coerceIn(50, 250) / 100f
    val barHeight = (48f * scale).dp
    val iconSize = (24f * scale).dp

    // The bar is always visible (fixed height) so the page never reflows; when
    // nothing is playing the controls are simply disabled.
    //
    // Layout: the "where am I" control is pinned to the left edge and the
    // settings gear to the right edge; the four transport controls sit in a
    // cluster centered between them.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(barHeight)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp),
    ) {
        IconButton(
            onClick = onJumpToCurrent,
            enabled = isActive,
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            Icon(
                imageVector = Icons.Filled.CenterFocusStrong,
                contentDescription = "Jump to current reading position",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(iconSize)
            )
        }
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onSkipBack, enabled = isActive) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = "Previous section",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(iconSize)
                )
            }
            IconButton(onClick = onPlayPause, enabled = isActive) {
                Icon(
                    imageVector = if (isActive && !isPaused) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isActive && !isPaused) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(iconSize)
                )
            }
            IconButton(onClick = onSkipForward, enabled = isActive) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = "Next section",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(iconSize)
                )
            }
            IconButton(onClick = onStop, enabled = isActive) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = "Stop",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
        // Settings gear, always available regardless of playback state.
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(iconSize)
            )
        }
    }
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
    activePosition: ActivePosition?,
    listState: LazyListState,
    onPlayWholeArticle: () -> Unit,
    onPlaySection: (String) -> Unit,
    onPlayParagraph: (String, Int) -> Unit,
    onLinkClick: (String) -> Unit
) {
    val isTitleActive = activePosition?.sectionId == null

    // Top-level (h2 / level 2) sections with a heading. When there are more
    // than three, show a "Contents" outline as the first scrollable item so it
    // is visible on open but scrolls away with the rest of the article.
    val topLevelHeadings = topLevelHeadings(sections)
    val showOutline = topLevelHeadings.size > 3

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (showOutline) {
            item(key = "outline") {
                ContentsOutline(topLevelHeadings) { section ->
                    // Tapping a contents entry jumps to that section AND starts
                    // reading it (saving the user a second tap on the heading's
                    // play button). onPlaySection also turns on follow mode.
                    onPlaySection(section.id)
                }
            }
        }
        item(key = "title") {
            ArticleRow(
                isActive = isTitleActive,
                content = {
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
            )
        }
        sections.forEach { section ->
            section.heading?.let { heading ->
                val headingActive =
                    activePosition?.sectionId == section.id && activePosition.paragraphIndex == null
                item(key = "${section.id}/heading") {
                    ArticleRow(
                        isActive = headingActive,
                        content = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SelectionContainer(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = heading,
                                        style = if (section.level <= 2) {
                                            MaterialTheme.typography.headlineSmall
                                        } else {
                                            MaterialTheme.typography.titleLarge
                                        },
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
                        }
                    )
                }
            }
            section.paragraphs.forEachIndexed { index, paragraph ->
                val paragraphActive =
                    activePosition?.sectionId == section.id && activePosition.paragraphIndex == index
                item(key = "${section.id}/paragraph/$index") {
                    ArticleRow(
                        isActive = paragraphActive,
                        content = {
                            // Double-tapping a paragraph starts reading from it
                            // (following the continuity setting). A single tap /
                            // drag still selects text and taps links as before.
                            SelectionContainer(
                                modifier = Modifier.pointerInput(paragraph) {
                                    detectTapGestures(
                                        onDoubleTap = { onPlayParagraph(section.id, index) }
                                    )
                                }
                            ) {
                                ParagraphText(
                                    paragraph = paragraph,
                                    onLinkClick = onLinkClick
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * A compact, tap-to-jump "Contents" list shown as the first scrollable item of
 * long articles (those with more than three top-level h2 headings). Each row is
 * a heading; tapping it jumps to that section and starts reading it (the
 * playback is handled by the caller, not here).
 */
@Composable
private fun ContentsOutline(
    topLevelHeadings: List<ArticleSection>,
    onSelect: (ArticleSection) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Contents",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        topLevelHeadings.forEach { section ->
            Text(
                text = section.heading ?: return@forEach,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(section) }
                    .padding(vertical = 6.dp)
            )
        }
    }
}

/**
 * Top-level (h2 / level 2) sections with a heading, used to build the
 * "Contents" outline for long articles.
 */
private fun topLevelHeadings(sections: List<ArticleSection>): List<ArticleSection> =
    sections.filter { it.heading != null && it.level == 2 }

/**
 * Whether to show the contents outline for [sections]: true when there are
 * more than three top-level headings (short articles don't need it).
 */
private fun hasOutline(sections: List<ArticleSection>): Boolean =
    topLevelHeadings(sections).size > 3

/**
 * Maps an [ActivePosition] to the flat item index used by the [LazyColumn] in
 * [ArticleContent], or null when the position does not resolve to a row. The
 * index layout is: (optional contents outline), row 0 = title, then for each
 * section its heading (if any) followed by its paragraphs in order.
 * [outlineOffset] is the number of leading items (0 or 1) occupied by the
 * optional contents outline, so the same function works whether or not the
 * outline is shown. Kept in lock-step with the rendering loop in
 * [ArticleContent] so the "jump to current" control scrolls to the right place.
 */
internal fun indexFor(
    title: String,
    sections: List<ArticleSection>,
    position: ActivePosition?,
    outlineOffset: Int = 0
): Int? {
    val pos = position ?: return null

    // Title row.
    if (pos.sectionId == null) return outlineOffset

    var index = 1 // account for the title row
    for (section in sections) {
        val isMatch = section.id == pos.sectionId
        if (section.heading != null) {
            if (isMatch && pos.paragraphIndex == null) return index + outlineOffset
            index++
        }
        if (isMatch && pos.paragraphIndex != null) {
            return index + pos.paragraphIndex + outlineOffset
        }
        index += section.paragraphs.size
    }
    return null
}

/**
 * Wraps a list row in a left margin gutter that shows a blue vertical bar when
 * [isActive], otherwise an equal-width blank gutter (preserving alignment).
 */
@Composable
private fun ArticleRow(
    isActive: Boolean,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        Box(
            modifier = Modifier
                .width(ActiveBarGutterWidth)
                .fillMaxHeight()
        ) {
            MarginBar(isActive)
        }
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

/** The vertical blue bar shown in the gutter beside the row being read. */
@Composable
private fun MarginBar(isActive: Boolean) {
    if (isActive) {
        Box(
            modifier = Modifier
                .width(ActiveBarWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(ActiveBarWidth / 2))
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

private val ActiveBarWidth = 4.dp
private val ActiveBarGutterWidth = 14.dp

@Composable
private fun ParagraphText(
    paragraph: ArticleParagraph,
    onLinkClick: (String) -> Unit
) {
    if (paragraph.links.isEmpty()) {
        Text(
            text = paragraph.text,
            style = MaterialTheme.typography.bodyLarge
        )
        return
    }

    val annotated = buildAnnotatedString {
        append(paragraph.text)
        paragraph.links.forEach { link ->
            if (link.start in 0..paragraph.text.length && link.end in 0..paragraph.text.length && link.start < link.end) {
                addStyle(
                    style = SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline
                    ),
                    start = link.start,
                    end = link.end
                )
                addStringAnnotation(
                    tag = "LINK",
                    annotation = link.title,
                    start = link.start,
                    end = link.end
                )
            }
        }
    }

    ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyLarge,
        onClick = { offset ->
            annotated.getStringAnnotations("LINK", offset, offset)
                .firstOrNull()
                ?.item
                ?.let(onLinkClick)
        }
    )
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