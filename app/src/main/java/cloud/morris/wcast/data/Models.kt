package cloud.morris.wcast.data

/**
 * A single cleaned section of a Wikipedia article.
 *
 * @property id A stable, URL-safe identifier for the section
 *   ("lead" for the intro, otherwise a slugified heading).
 * @property heading The section heading, or null for the introductory lead section.
 * @property paragraphs The cleaned paragraph text belonging to the section, each
 *   carrying its internal wiki links (for display/navigation) alongside the
 *   plain text (for TTS).
 */
data class ArticleSection(
    val id: String,
    val heading: String?,
    val paragraphs: List<ArticleParagraph>
)

/**
 * A single cleaned paragraph of an article section.
 *
 * @property text The full visible text of the paragraph, with link text inlined
 *   (the same string that is sent to TTS).
 * @property links Internal Wikipedia links contained in this paragraph, with
 *   character offsets into [text] so the UI can render them as tappable
 *   hyperlinks without leaking URLs into the spoken text.
 */
data class ArticleParagraph(
    val text: String,
    val links: List<ArticleLink>
)

/**
 * A single internal Wikipedia link within an [ArticleParagraph].
 *
 * @property title The decoded target page title (e.g. "Barack Obama").
 * @property start Inclusive character offset of the link text within the
 *   paragraph's [ArticleParagraph.text].
 * @property end Exclusive character offset of the link text within the
 *   paragraph's [ArticleParagraph.text].
 */
data class ArticleLink(
    val title: String,
    val start: Int,
    val end: Int
)

/** A single suggestion returned by Wikipedia's opensearch API. */
data class SearchSuggestion(
    val title: String,
    val description: String,
    val url: String
)

/** Outcome of resolving a search query against Wikipedia. */
sealed interface SearchResult {

    /** The query resolved to an exact article title. */
    data class Exact(val title: String) : SearchResult

    /** There was no exact match; a list of suggestions is returned instead. */
    data class Suggestions(val items: List<SearchSuggestion>) : SearchResult
}