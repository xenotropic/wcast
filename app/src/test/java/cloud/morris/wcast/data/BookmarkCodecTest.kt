package cloud.morris.wcast.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round-trip tests for [BookmarkCodec], verifying a bookmarked article's
 * structured sections (including links and a null heading) survive a
 * serialize -> deserialize cycle intact.
 */
class BookmarkCodecTest {

    private val sections = listOf(
        ArticleSection(
            id = "lead",
            heading = null,
            level = 1,
            paragraphs = listOf(
                ArticleParagraph(
                    text = "The blue whale is the largest animal.",
                    links = listOf(ArticleLink(title = "Baleen whale", start = 4, end = 14))
                )
            )
        ),
        ArticleSection(
            id = "Taxonomy",
            heading = "Taxonomy",
            level = 2,
            paragraphs = listOf(ArticleParagraph(text = "Named by Linnaeus.", links = emptyList()))
        )
    )

    @Test
    fun `round trip preserves title and sections`() {
        val json = BookmarkCodec.encode("Blue whale", sections)
        val (title, decoded) = BookmarkCodec.decode(json)

        assertEquals("Blue whale", title)
        assertEquals(sections, decoded)
    }

    @Test
    fun `round trip preserves null heading and empty links`() {
        val json = BookmarkCodec.encode("Article", sections)
        val (_, decoded) = BookmarkCodec.decode(json)

        assertEquals(null, decoded[0].heading)
        assertEquals("Taxonomy", decoded[1].heading)
        assertEquals(emptyList<ArticleLink>(), decoded[1].paragraphs[0].links)
    }
}
