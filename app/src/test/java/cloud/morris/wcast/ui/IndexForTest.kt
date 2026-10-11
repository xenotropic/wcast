package cloud.morris.wcast.ui

import cloud.morris.wcast.ActivePosition
import cloud.morris.wcast.data.ArticleParagraph
import cloud.morris.wcast.data.ArticleSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Validates that [indexFor] maps an [ActivePosition] to the same flat
 * [LazyColumn] item index that [ArticleContent] renders — row 0 = title, then
 * per-section heading (if any) followed by that section's paragraphs in order.
 *
 * Sample article used throughout:
 *   section "lead"   -> heading = null,               2 paragraphs
 *   section "history" -> heading = "History",         1 paragraph
 *   section "legacy"  -> heading = "Legacy",          3 paragraphs
 */
class IndexForTest {

    private val sections = listOf(
        ArticleSection(
            id = "lead",
            heading = null,
            level = 1,
            paragraphs = listOf(paragraph("l0"), paragraph("l1"))
        ),
        ArticleSection(
            id = "history",
            heading = "History",
            level = 2,
            paragraphs = listOf(paragraph("h0"))
        ),
        ArticleSection(
            id = "legacy",
            heading = "Legacy",
            level = 2,
            paragraphs = listOf(paragraph("g0"), paragraph("g1"), paragraph("g2"))
        )
    )

    private fun paragraph(text: String) = ArticleParagraph(text = text, links = emptyList())

    @Test
    fun `title resolves to row 0`() {
        assertEquals(0, indexFor("T", sections, ActivePosition(null, null)))
    }

    @Test
    fun `null position resolves to null`() {
        assertNull(indexFor("T", sections, null))
    }

    @Test
    fun `lead section heading maps to its first paragraph row`() {
        // lead has no heading, so its heading (paragraphIndex == null) does not map
        // to a distinct row — it falls through to rows after the title.
        // Row layout: 0=title, then lead has NO heading row, so lead paragraph 0
        // is row 1.
        assertEquals(1, indexFor("T", sections, ActivePosition("lead", 0)))
        assertEquals(2, indexFor("T", sections, ActivePosition("lead", 1)))
    }

    @Test
    fun `history section heading and paragraph map correctly`() {
        // 0=title, 1=lead.p0, 2=lead.p1, then history heading at 3, history p0 at 4.
        assertEquals(3, indexFor("T", sections, ActivePosition("history", null)))
        assertEquals(4, indexFor("T", sections, ActivePosition("history", 0)))
    }

    @Test
    fun `legacy section paragraphs map after its heading`() {
        // 0=title, 1=lead.p0, 2=lead.p1, 3=history heading, 4=history.p0,
        // 5=legacy heading, 6..8 = legacy.p0..p2.
        assertEquals(5, indexFor("T", sections, ActivePosition("legacy", null)))
        assertEquals(6, indexFor("T", sections, ActivePosition("legacy", 0)))
        assertEquals(7, indexFor("T", sections, ActivePosition("legacy", 1)))
        assertEquals(8, indexFor("T", sections, ActivePosition("legacy", 2)))
    }

    @Test
    fun `unknown section id resolves to null`() {
        assertNull(indexFor("T", sections, ActivePosition("missing", 0)))
    }
}
