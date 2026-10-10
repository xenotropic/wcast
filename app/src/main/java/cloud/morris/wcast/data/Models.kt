package cloud.morris.wcast.data

/**
 * A single cleaned section of a Wikipedia article.
 *
 * @property id A stable, URL-safe identifier for the section
 *   ("lead" for the intro, otherwise a slugified heading).
 * @property heading The section heading, or null for the introductory lead section.
 * @property paragraphs The cleaned paragraph text belonging to the section.
 */
data class ArticleSection(
    val id: String,
    val heading: String?,
    val paragraphs: List<String>
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