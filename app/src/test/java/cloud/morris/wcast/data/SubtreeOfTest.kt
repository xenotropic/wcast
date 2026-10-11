package cloud.morris.wcast.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [subtreeOf], validating that playing a major heading expands
 * into itself plus all deeper following subheadings, stopping at the next
 * section at the same-or-shallower level.
 */
class SubtreeOfTest {

    private fun section(id: String, level: Int) =
        ArticleSection(id = id, heading = id, level = level, paragraphs = emptyList())

    /**
     * Mirrors the "Name and symbol" structure from a typical article:
     *   name (h2) -> etymology (h3) -> other-names (h3) -> history (h2)
     */
    private val sections = listOf(
        section("name", 2),
        section("etymology", 3),
        section("other-names", 3),
        section("history", 2)
    )

    @Test
    fun `leaf section resolves to itself`() {
        assertEquals(listOf("etymology"), subtreeOf(sections, "etymology").map { it.id })
    }

    @Test
    fun `major heading resolves to itself plus deeper subheadings`() {
        assertEquals(
            listOf("name", "etymology", "other-names"),
            subtreeOf(sections, "name").map { it.id }
        )
    }

    @Test
    fun `subtree stops at next section of equal level`() {
        assertEquals(listOf("history"), subtreeOf(sections, "history").map { it.id })
    }

    @Test
    fun `deeper subheading stops at next equal-or-shallower section`() {
        // "other-names" (h3) is followed by "history" (h2) which is shallower,
        // so its subtree is just itself.
        assertEquals(listOf("other-names"), subtreeOf(sections, "other-names").map { it.id })
    }

    @Test
    fun `unknown id resolves to empty`() {
        assertEquals(emptyList<String>(), subtreeOf(sections, "missing"))
    }

    @Test
    fun `h4 under h3 is included in h3 subtree`() {
        val nested = listOf(
            section("a", 2),
            section("b", 3),
            section("c", 4),
            section("d", 3),
            section("e", 2)
        )
        assertEquals(listOf("b", "c"), subtreeOf(nested, "b").map { it.id })
        assertEquals(listOf("a", "b", "c", "d"), subtreeOf(nested, "a").map { it.id })
    }
}
