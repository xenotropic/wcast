package cloud.morris.wcast.normalization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the pure parsers in [NormalizationRules]. */
class NormalizationRulesTest {

    @Test
    fun `csv parser skips header comments and blanks`() {
        val csv = "source,replacement\n# a comment\n\nA,B\nC,D\nA,last\n"
        val map = NormalizationRules.parseCsv(csv)
        assertEquals("last", map["A"]) // last occurrence wins
        assertEquals("D", map["C"])
        assertEquals(2, map.size)
    }

    @Test
    fun `csv parser preserves map order`() {
        val csv = "source,replacement\nB,two\nA,one\n"
        val keys = NormalizationRules.parseCsv(csv).keys.toList()
        assertEquals(listOf("B", "A"), keys)
    }

    @Test
    fun `regex parser reads replacement flags and preserves order`() {
        val text = "# comment\nFoo\\s+(\\d+)\tfoo \$1\ti\n°\\s*C\tdegrees celsius\t\n"
        val rules = NormalizationRules.parseRegexRules(text)
        assertEquals(2, rules.size)
        assertTrue(rules[0].caseInsensitive)
        assertEquals("foo \$1", rules[0].replacement)
        assertEquals("degrees celsius", rules[1].replacement)
        assertTrue(!rules[1].caseInsensitive)
    }

    @Test
    fun `regex parser ignores malformed lines`() {
        val text = "# c\nno_tab_here\nGood\\s+(\\d+)\treplaced \$1\t\n"
        val rules = NormalizationRules.parseRegexRules(text)
        assertEquals(1, rules.size)
    }
}
