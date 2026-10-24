package cloud.morris.wcast.data

/**
 * A single cleaned section of a Wikipedia article.
 *
 * @property id A stable, URL-safe identifier for the section
 *   ("lead" for the intro, otherwise a slugified heading).
 * @property heading The section heading, or null for the introductory lead section.
 * @property level The heading's HTML heading level (h2 = 2 … h6 = 6), with the
 *   lead section using level 1 (a sentinel that is always shallower than any
 *   real heading). Used to reconstruct the article's heading hierarchy so that
 *   playing a major heading also reads its subheadings.
 * @property paragraphs The cleaned paragraph text belonging to the section, each
 *   carrying its internal wiki links (for display/navigation) alongside the
 *   plain text (for TTS).
 */
data class ArticleSection(
    val id: String,
    val heading: String?,
    val level: Int,
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

/**
 * The canonical "main image" for an article (ticket 33): a low-resolution
 * thumbnail and its caption, shown at the top-right of the article and used as
 * the media-player artwork. [url] points at a small Wikimedia-rendered thumb.
 */
data class ArticleImage(
    val url: String,
    val caption: String?
)

/**
 * The result of fetching an article: its structured sections plus the optional
 * main image (never stored in a bookmark's section list).
 */
data class FetchedArticle(
    val sections: List<ArticleSection>,
    val image: ArticleImage?
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

/**
 * Returns the contiguous subtree rooted at the section with [rootId] in [sections].
 *
 * A section's subtree is the section itself plus every immediately-following
 * section whose heading level is strictly deeper than the root's level, stopping
 * at the first section whose level is equal to or shallower than the root's.
 * This is what makes "play on a major heading" read the heading together with
 * all of its subheadings, in order.
 *
 * Returns an empty list when [rootId] is not present in [sections].
 */
fun subtreeOf(sections: List<ArticleSection>, rootId: String): List<ArticleSection> {
    val rootIndex = sections.indexOfFirst { it.id == rootId }
    if (rootIndex < 0) return emptyList()
    val root = sections[rootIndex]
    val result = mutableListOf(root)
    for (next in sections.drop(rootIndex + 1)) {
        if (next.level <= root.level) break
        result += next
    }
    return result
}