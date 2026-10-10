package cloud.morris.wcast.data

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [LinkCapture], validating that internal Wikipedia article
 * links are preserved with correct offsets while external, non-article, red,
 * and fragment links are dropped.
 */
class LinkCaptureTest {

    @Test
    fun `extracts a single internal link with correct offsets`() {
        val root = Jsoup.parse(
            "<p>Barack Obama was born in <a href=\"/wiki/Hawaii\">Hawaii</a>.</p>"
        ).selectFirst("p")!!
        val links = LinkCapture.extractLinks(root, root.text())

        assertEquals(1, links.size)
        val link = links[0]
        assertEquals("Hawaii", link.title)
        val expectedStart = "Barack Obama was born in ".length
        assertEquals(expectedStart, link.start)
        assertEquals(expectedStart + "Hawaii".length, link.end)
    }

    @Test
    fun `decodes underscore and URL encoding in titles`() {
        val root = Jsoup.parse(
            "<p>See <a href=\"/wiki/New_York_City\">New York City</a> and " +
                "<a href=\"/wiki/Caf%C3%A9\">Café</a>.</p>"
        ).selectFirst("p")!!
        val links = LinkCapture.extractLinks(root, root.text())

        assertEquals(2, links.size)
        assertEquals("New York City", links[0].title)
        assertEquals("Café", links[1].title)
    }

    @Test
    fun `drops external links`() {
        val root = Jsoup.parse(
            "<p>Read <a class=\"external\" href=\"https://example.com\">here</a>.</p>"
        ).selectFirst("p")!!
        assertEquals(0, LinkCapture.extractLinks(root, root.text()).size)
    }

    @Test
    fun `drops non-article namespace links`() {
        val root = Jsoup.parse(
            "<p>" +
                "<a href=\"/wiki/Category:People\">Category</a> " +
                "<a href=\"/wiki/Help:Editing\">Help</a> " +
                "<a href=\"/wiki/Special:Random\">Special</a> " +
                "<a href=\"/wiki/File:Photo.jpg\">File</a>" +
                "</p>"
        ).selectFirst("p")!!
        assertEquals(0, LinkCapture.extractLinks(root, root.text()).size)
    }

    @Test
    fun `drops red links`() {
        val root = Jsoup.parse(
            "<p><a href=\"/wiki/Nope\" class=\"new\">Nope</a> is not an article yet.</p>"
        ).selectFirst("p")!!
        assertEquals(0, LinkCapture.extractLinks(root, root.text()).size)
    }

    @Test
    fun `drops fragment-only links`() {
        val root = Jsoup.parse(
            "<p>Jump <a href=\"#History\">to history</a> here.</p>"
        ).selectFirst("p")!!
        assertEquals(0, LinkCapture.extractLinks(root, root.text()).size)
    }

    @Test
    fun `strips trailing fragment from article link`() {
        val root = Jsoup.parse(
            "<p>See <a href=\"/wiki/Barack_Obama#Early_life\">early life</a>.</p>"
        ).selectFirst("p")!!
        val links = LinkCapture.extractLinks(root, root.text())
        assertEquals(1, links.size)
        assertEquals("Barack Obama", links[0].title)
    }

    @Test
    fun `resolves repeated identical anchor text to distinct offsets`() {
        val root = Jsoup.parse(
            "<p>Apple and <a href=\"/wiki/Fruit\">Apple</a> are different from " +
                "<a href=\"/wiki/Apple_Inc.\">Apple</a> Inc.</p>"
        ).selectFirst("p")!!
        val text = root.text()
        val links = LinkCapture.extractLinks(root, text)

        assertEquals(2, links.size)
        // Verify each link's span actually reads "Apple".
        assertEquals("Apple", text.substring(links[0].start, links[0].end))
        assertEquals("Apple", text.substring(links[1].start, links[1].end))
        // Offsets must be distinct occurrences.
        assertEquals("Fruit", links[0].title)
        assertEquals("Apple Inc.", links[1].title)
    }
}
