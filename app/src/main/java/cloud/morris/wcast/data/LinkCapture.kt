package cloud.morris.wcast.data

import org.jsoup.nodes.Element
import java.net.URLDecoder

/**
 * Extracts internal Wikipedia article links from an HTML element, together
 * with their character offsets within the element's plain text.
 *
 * Kept as a small, framework-free helper (only jsoup + JDK) so the
 * offset/link-resolution logic can be unit-tested in isolation from the
 * network-bound [WikipediaRepository].
 */
object LinkCapture {

    /**
     * Namespaces that are internal wiki links but not readable articles.
     * Links to any of these (and their localized "Talk:" variants) are dropped.
     */
    private val NON_ARTICLE_PREFIXES = listOf(
        "File:", "Special:", "Help:", "Wikipedia:", "Portal:", "Category:",
        "Template:", "Talk:", "User:", "User_talk:", "Wikipedia_talk:",
        "File_talk:", "Template_talk:", "Category_talk:", "Portal_talk:",
        "Help_talk:", "MediaWiki:", "MediaWiki_talk:", "Draft:", "Draft_talk:",
        "Module:", "Module_talk:", "Book:", "Book_talk:", "TimedText:", "TimedText_talk:"
    )

    /**
     * Walks [root] and returns, in document order, every internal Wikipedia
     * article link (its decoded target title and its character offsets within
     * [root]'s plain text).
     *
     * @param root The paragraph/list-item element being processed.
     * @param text The plain text of [root] (must equal `root.text()`).
     */
    fun extractLinks(root: Element, text: String): List<ArticleLink> {
        if (text.isEmpty()) return emptyList()
        val links = mutableListOf<ArticleLink>()
        var searchFrom = 0
        for (anchor in root.select("a[href]")) {
            val title = linkTitle(anchor) ?: continue
            val anchorText = anchor.text()
            if (anchorText.isEmpty()) continue

            // Resolve the anchor's visible text to its offset within the
            // paragraph text, skipping already-consumed regions so identical
            // repeated anchor text maps to the correct occurrence.
            val index = text.indexOf(anchorText, searchFrom)
            if (index < 0) continue
            links += ArticleLink(title = title, start = index, end = index + anchorText.length)
            searchFrom = index + anchorText.length
        }
        return links.toList()
    }

    /**
     * Decodes a jsoup anchor into an internal article title, or null if the
     * link should not be preserved (external, non-article namespace, red link,
     * or a fragment/self-link).
     */
    private fun linkTitle(anchor: Element): String? {
        if (anchor.hasClass("new")) return null // red link to a non-existent page

        val href = anchor.attr("href").trim()
        if (href.isEmpty()) return null

        // Fragment-only links (e.g. "#Foo") are in-page anchors, not articles.
        if (href.startsWith("#")) return null

        val slug = when {
            href.startsWith("/wiki/") -> href.removePrefix("/wiki/")
            href.startsWith(WIKI_PREFIX) -> href.removePrefix(WIKI_PREFIX)
            else -> return null // not an internal article link
        }

        // Drop trailing in-page fragments (#Section).
        val bare = slug.substringBefore('#')
        if (bare.isEmpty()) return null

        val decoded = try {
            URLDecoder.decode(bare, "UTF-8").replace('_', ' ')
        } catch (e: IllegalArgumentException) {
            bare.replace('_', ' ')
        }

        if (NON_ARTICLE_PREFIXES.any { decoded.startsWith(it, ignoreCase = true) }) return null

        return decoded.trim().ifEmpty { null }
    }

    private const val WIKI_PREFIX = "https://en.wikipedia.org/wiki/"
}
