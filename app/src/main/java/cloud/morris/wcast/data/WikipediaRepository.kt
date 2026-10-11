package cloud.morris.wcast.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONArray
import java.net.URLEncoder

/**
 * Fetches Wikipedia articles and scrubs them down to clean, readable text.
 *
 * Phase 1: resolve a query to an exact slug (or suggestions) via opensearch,
 * then fetch the article HTML, delete unwanted DOM nodes, strip citation
 * markers, normalize whitespace, and split the body into [ArticleSection]s.
 */
class WikipediaRepository {

    suspend fun search(query: String): SearchResult = withContext(Dispatchers.IO) {
        val term = query.trim()
        require(term.isNotEmpty()) { "Search query must not be blank." }

        val response = Jsoup.connect(API_BASE)
            .data("action", "opensearch")
            .data("search", term)
            .data("limit", "10")
            .data("namespace", "0")
            .data("format", "json")
            .ignoreContentType(true)
            .userAgent(USER_AGENT)
            .timeout(DEFAULT_TIMEOUT_MS)
            .execute()

        // opensearch format=json -> [query, [titles], [descriptions], [urls]]
        val root = JSONArray(response.body())
        val titles = root.optJSONArray(1) ?: JSONArray()
        val descriptions = root.optJSONArray(2) ?: JSONArray()
        val urls = root.optJSONArray(3) ?: JSONArray()

        val suggestions = buildList {
            for (i in 0 until titles.length()) {
                add(
                    SearchSuggestion(
                        title = titles.optString(i),
                        description = descriptions.optString(i),
                        url = urls.optString(i)
                    )
                )
            }
        }

        val exact = suggestions.firstOrNull { it.title.equals(term, ignoreCase = true) }
        if (exact != null) SearchResult.Exact(exact.title) else SearchResult.Suggestions(suggestions)
    }

    suspend fun fetchArticle(title: String): List<ArticleSection> = withContext(Dispatchers.IO) {
        val document = Jsoup.connect(buildArticleUrl(title))
            .userAgent(USER_AGENT)
            .timeout(DEFAULT_TIMEOUT_MS)
            .get()

        val content = document.selectFirst(ARTICLE_CONTENT_SELECTOR)
            ?: throw IllegalStateException("Could not locate article content for \"${title.trim()}\".")

        scrubUnwantedNodes(content)
        scrubPronunciationNodes(content)
        structureSections(content)
    }

    private fun buildArticleUrl(title: String): String {
        val slug = URLEncoder.encode(title.trim().replace(' ', '_'), "UTF-8")
        return "$BASE_URL/wiki/$slug"
    }

    private fun scrubUnwantedNodes(content: Element) {
        content.select(SCRUB_SELECTORS).forEach { it.remove() }
    }

    /**
     * Removes pronunciation helper nodes (IPA transcriptions, phonemic/phonetic
     * annotations, and audio-switch wrappers) from the DOM before text is
     * extracted, so the article body reads cleanly for TTS.
     */
    private fun scrubPronunciationNodes(content: Element) {
        content.select(PRONUNCIATION_SELECTORS).forEach { it.remove() }
    }

    /**
     * Walks the scrubbed content in document order, grouping headings
     * (h2..h6) and paragraph text into [ArticleSection]s.
     *
     * Once an appendix section (e.g. "See also", "References") is reached, all
     * remaining content is skipped, since bottom matter is not useful for
     * reading the article aloud.
     */
    private fun structureSections(content: Element): List<ArticleSection> {
        val sections = mutableListOf<ArticleSection>()
        var heading: String? = null
        var level = LEAD_LEVEL
        var paragraphs = mutableListOf<ArticleParagraph>()
        var inAppendix = false

        fun flush() {
            if (heading != null || paragraphs.isNotEmpty()) {
                sections += ArticleSection(
                    id = sectionId(heading, sections),
                    heading = heading,
                    level = level,
                    paragraphs = paragraphs.toList()
                )
            }
            heading = null
            level = LEAD_LEVEL
            paragraphs = mutableListOf()
        }

        fun visit(element: Element) {
            for (child in element.children()) {
                if (inAppendix) return
                when (child.tagName()) {
                    "h2", "h3", "h4", "h5", "h6" -> {
                        if (isAppendixHeader(child)) {
                            // Drop this header and everything after it.
                            flush()
                            inAppendix = true
                            return
                        }
                        flush()
                        heading = cleanText(child.text())
                        level = headingLevel(child.tagName())
                    }
                    "p" -> paragraph(child, bullet = null)?.let { paragraphs += it }
                    "li" -> paragraph(child, bullet = "\u2022 ")?.let { paragraphs += it }
                }
                visit(child)
            }
        }

        visit(content)
        flush()
        return sections
    }

    /** Maps an HTML heading tag name to its numeric level (h2 -> 2 … h6 -> 6). */
    private fun headingLevel(tagName: String): Int = when (tagName) {
        "h2" -> 2
        "h3" -> 3
        "h4" -> 4
        "h5" -> 5
        "h6" -> 6
        else -> LEAD_LEVEL
    }

    /**
     * Converts a `<p>` or `<li>` element into an [ArticleParagraph], preserving
     * internal wiki links (with offsets) for display while keeping the plain
     * text available for TTS.
     */
    private fun paragraph(element: Element, bullet: String?): ArticleParagraph? {
        val rawText = element.text()
        val cleaned = cleanText(rawText) ?: return null
        // Offsets below are computed against the *cleaned* text. Recompose the
        // display text including any list bullet so link offsets line up.
        val fullText = bullet?.plus(cleaned) ?: cleaned
        val links = LinkCapture.extractLinks(element, cleaned).map { link ->
            if (bullet != null) {
                link.copy(start = link.start + bullet.length, end = link.end + bullet.length)
            } else {
                link
            }
        }
        return ArticleParagraph(text = fullText, links = links)
    }

    /**
     * Determines whether a heading (h2..h6) marks the start of an appendix
     * section. Matches either the element's own `id` attribute or the `id` of
     * a child `<span class="mw-headline">` against a set of known appendix
     * identifiers.
     */
    private fun isAppendixHeader(heading: Element): Boolean {
        val ids = buildList {
            heading.attr("id").trim().takeIf { it.isNotEmpty() }?.let { add(it) }
            heading.select("span.mw-headline[id]").forEach { span ->
                span.attr("id").trim().takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }
        return ids.any { APPENDIX_SECTION_IDS.contains(it) }
    }

    private fun cleanText(raw: String): String? {
        val withoutCitations = raw.replace(CITATION_REGEX, "")
        val withoutLanguageLabels = withoutCitations.replace(LANGUAGE_LABEL_REGEX, "")
        val withoutEmptyBrackets = withoutLanguageLabels.replace(EMPTY_BRACKETS_REGEX, "")
        val withoutPronunciations = withoutEmptyBrackets.replace(PRONUNCIATION_PAREN_REGEX, "")
        return withoutPronunciations.replace(WHITESPACE_REGEX, " ").trim().ifEmpty { null }
    }

    /**
     * Builds a stable, unique section id. For the lead (no heading) this is the
     * fixed literal "lead". Otherwise the heading is slugified, and if that slug
     * (or its fallback) would collide with an id already emitted for an earlier
     * section, an `-<index>` suffix is appended so every section's id is unique.
     * Uniqueness matters because ids are the keys used by playback, skip, and
     * the active-position indicator.
     */
    private fun sectionId(heading: String?, sections: List<ArticleSection>): String {
        if (heading == null) return "lead"
        val slug = heading.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString(separator = "")
            .replace(Regex("-+"), "-")
            .trim('-')
        val base = slug.ifEmpty { "section" }

        val used = sections.mapTo(mutableSetOf()) { it.id }
        var candidate = base
        var n = sections.size // ensure a fresh suffix that can't equal a prior id
        while (candidate in used) {
            candidate = "$base-$n"
            n++
        }
        return candidate
    }

    companion object {
        private const val BASE_URL = "https://en.wikipedia.org"
        private const val API_BASE = "$BASE_URL/w/api.php"
        private const val ARTICLE_CONTENT_SELECTOR = "#mw-content-text"
        private const val DEFAULT_TIMEOUT_MS = 15_000
        private const val USER_AGENT = "Wcast/1.0 (Android; Wikipedia reader)"

        /** Heading level used for the lead (no-heading) section; shallower than h2. */
        private const val LEAD_LEVEL = 1

        /** CSS selectors for DOM nodes removed before display (Phase 1 scrub list). */
        private val SCRUB_SELECTORS = listOf(
            ".noprint", ".reference", ".reflist", ".refbegin", ".infobox", ".IPA",
            ".hatnote", ".wikitable", ".gallery", ".sidebar", ".metadata", ".ambox",
            ".thumb", ".mwe-math-element", ".toc", ".mw-editsection", "figure", "table",
            ".navbox", ".vertical-navbox", ".catlinks", ".printfooter"
        ).joinToString(", ")

        /** Pronunciation helpers removed from the DOM before text extraction. */
        private val PRONUNCIATION_SELECTORS = listOf(
            "span.IPA", ".IPA-comment", "span.phonemic", "span.phonetic",
            ".pronunciation-audio-switch"
        ).joinToString(", ")

        /**
         * Section identifiers that mark the start of an appendix (bottom matter)
         * which should be excluded entirely from the structured article text.
         */
        private val APPENDIX_SECTION_IDS = setOf(
            "See_also", "References", "External_links", "Notes",
            "Further_reading", "Works_cited"
        )

        /** Removes inline citation markers such as "[1]", "[22]", "[citation needed]". */
        private val CITATION_REGEX = Regex(
            """\[\s*(?:\d+\s*|citation needed)\s*]""",
            RegexOption.IGNORE_CASE
        )

        /** Removes empty language-label brackets left behind by pronunciation nodes. */
        private val LANGUAGE_LABEL_REGEX = Regex(
            """\(\s*(?:(?:US|UK|Spanish|en-US|en-GB|Listen):\s*[,;]?\s*)*\)"""
        )

        /**
         * Removes pronunciation helper parentheticals that survive DOM-level
         * scrubbing. Wikipedia renders these inline as, e.g.
         * "Illinois ( ⓘ IL-ih-NOY) is…" or
         * "Timbuktu ( ⓘ TIM-buk-TOO; French: Tombouctou; … )".
         *
         * The circled-info marker U+24D8 (ⓘ) is the reliable signal: it only
         * appears ahead of a pronunciation. Matching non-greedily up to the next
         * closing parenthesis strips the whole pronunciation gloss in one go,
         * including the multi-language forms that carry no IPA spans the DOM
         * scrubbers would have already removed.
         */
        private val PRONUNCIATION_PAREN_REGEX = Regex(
            """\(\s*ⓘ.*?\)"""
        )

        /** Cleans up any remaining hollow brackets containing only punctuation. */
        private val EMPTY_BRACKETS_REGEX = Regex("""\(\s*[,;:\s]*\)""")

        /** Collapses runs of whitespace into a single space. */
        private val WHITESPACE_REGEX = Regex("""\s+""")
    }
}