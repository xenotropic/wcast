package cloud.morris.wcast.normalization

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * End-to-end tests for [Normalizer], loading the *real* rule files from
 * `src/main/assets/normalization/` so the tests validate the same data the app
 * ships with.
 */
class NormalizerTest {

    companion object {
        private const val ASSETS = "src/main/assets/normalization"

        /** Builds a [Normalizer] from the bundled rule files. */
        private fun fromBundledAssets(): Normalizer {
            val replacements = NormalizationRules.parseCsv(
                File("$ASSETS/bulk_replace.csv").readText()
            )
            val pronunciations = NormalizationRules.parseCsv(
                File("$ASSETS/pronounced_acronyms.csv").readText()
            )
            val regexRules = NormalizationRules.parseRegexRules(
                File("$ASSETS/regex_rules.txt").readText()
            )
            return Normalizer(
                NormalizationRules(replacements, pronunciations, regexRules)
            )
        }
    }

    private val normalizer = fromBundledAssets()

    @Test
    fun `strips accents`() {
        assertEquals("cafe", normalizer.normalize("café"))
        assertEquals("naive", normalizer.normalize("naïve"))
    }

    @Test
    fun `converts typographic dashes to a pause`() {
        assertEquals("well, that.", normalizer.normalize("well\u2014that."))
    }

    @Test
    fun `converts degree sign`() {
        assertEquals("30 degrees celsius", normalizer.normalize("30°C"))
    }

    @Test
    fun `expands honorifics`() {
        assertEquals("Doctor Smith", normalizer.normalize("Dr. Smith"))
        assertEquals("Mister Jones", normalizer.normalize("Mr. Jones"))
    }

    @Test
    fun `expands world war numerals`() {
        assertEquals("World War One and World War Two", normalizer.normalize("World War I and World War II"))
    }

    @Test
    fun `expands number ranges`() {
        // A year range is split into year groups by the years stage (see year tests).
        assertEquals("19 14 to 19 18", normalizer.normalize("1914\u20131918"))
        assertEquals("pages 5 to 10", normalizer.normalize("pages 5-10"))
    }

    @Test
    fun `expands plain dollar amounts`() {
        assertEquals("five dollars", normalizer.normalize("\$5"))
        assertEquals("twenty dollars", normalizer.normalize("\$20"))
    }

    @Test
    fun `expands scaled dollar amounts`() {
        assertEquals("four billion dollars", normalizer.normalize("\$4 billion"))
        assertEquals("two million dollars", normalizer.normalize("\$2 million"))
    }

    @Test
    fun `expands regnal roman numerals`() {
        assertEquals("Henry the eighth", normalizer.normalize("Henry VIII"))
        assertEquals("Louis the fourteenth", normalizer.normalize("Louis XIV"))
    }

    @Test
    fun `leaves non-regnal roman numerals to the acronym stage`() {
        // "MM" is not a regnal name/title, so the numeral rule leaves it; the
        // acronym stage then letter-splits it like any other all-caps token.
        assertEquals("the M M format", normalizer.normalize("the MM format"))
    }

    @Test
    fun `expands units after a number`() {
        assertEquals("120 kilometers per hour", normalizer.normalize("120 km/h"))
        assertEquals("5 kilometers", normalizer.normalize("5 km"))
        assertEquals("80 miles per hour", normalizer.normalize("80 mph"))
    }

    @Test
    fun `expands square mile abbreviations`() {
        assertEquals("square miles", normalizer.normalize("sq mi"))
        assertEquals("2 square miles", normalizer.normalize("2 sq mi"))
        assertEquals("square kilometers", normalizer.normalize("sq. km."))
    }

    @Test
    fun `expands inches including decimals`() {
        assertEquals("25.2 inches", normalizer.normalize("25.2 in"))
        assertEquals("5 inches", normalizer.normalize("5 in"))
        assertEquals("3 inches)", normalizer.normalize("3 in)"))
    }

    @Test
    fun `does not expand preposition in after numbers`() {
        assertEquals("5 in total", normalizer.normalize("5 in total"))
        assertEquals("5 in the box", normalizer.normalize("5 in the box"))
    }

    @Test
    fun `expands meters following a number`() {
        assertEquals("5 meters", normalizer.normalize("5 m"))
        assertEquals("12.5 meters,", normalizer.normalize("12.5 m,"))
        assertEquals("the wall is 5 meters (16 feet) high", normalizer.normalize("the wall is 5 m (16 ft) high"))
    }

    @Test
    fun `expands solar mass symbol`() {
        assertEquals("8 solar masses black hole", normalizer.normalize("8 M☉ black hole"))
        assertEquals("108 solar masses black hole", normalizer.normalize("108 M☉ black hole"))
    }

    @Test
    fun `expands hertz after a number`() {
        assertEquals("8 to 25 hertz", normalizer.normalize("8 to 25 Hz"))
        assertEquals("60 hertz", normalizer.normalize("60 Hz"))
    }

    @Test
    fun `does not expand m when followed by a word`() {
        assertEquals("a 5 m tall building", normalizer.normalize("a 5 m tall building"))
    }

    @Test
    fun `does not expand unit letters inside words`() {
        assertEquals("in the middle", normalizer.normalize("in the middle"))
    }

    @Test
    fun `expands common fractions`() {
        assertEquals("one half", normalizer.normalize("1/2"))
        assertEquals("three quarters", normalizer.normalize("3/4"))
        assertEquals("two thirds", normalizer.normalize("2/3"))
    }

    @Test
    fun `expands general fractions as over`() {
        assertEquals("five over nine", normalizer.normalize("5/9"))
    }

    @Test
    fun `does not mangle slash dates`() {
        assertEquals("3/4/2025", normalizer.normalize("3/4/2025"))
    }

    @Test
    fun `expands ordinal suffixes`() {
        assertEquals("third", normalizer.normalize("3rd"))
        assertEquals("twenty first", normalizer.normalize("21st"))
        assertEquals("forty second", normalizer.normalize("42nd"))
    }

    @Test
    fun `expands hash references`() {
        assertEquals("number seven", normalizer.normalize("#7"))
    }

    @Test
    fun `uses known acronym pronunciation`() {
        assertEquals("nayto", normalizer.normalize("NATO"))
        assertEquals("askey", normalizer.normalize("ASCII"))
    }

    @Test
    fun `letter-splits unknown acronyms`() {
        assertEquals("F B I", normalizer.normalize("FBI"))
        assertEquals("D N A", normalizer.normalize("DNA"))
    }

    // ── Years (1100–1999 → two digit-groups) ────────────────────────────────

    @Test
    fun `reads years after lead-in words as two digit groups`() {
        assertEquals("in 18 46", normalizer.normalize("in 1846"))
        assertEquals("since 17 76", normalizer.normalize("since 1776"))
        assertEquals("around 15 00", normalizer.normalize("around 1500"))
        assertEquals("during 19 14", normalizer.normalize("during 1914"))
    }

    @Test
    fun `reads years after a month as two digit groups`() {
        assertEquals("November 19 14", normalizer.normalize("November 1914"))
        assertEquals("march 18 48", normalizer.normalize("march 1848"))
        assertEquals("in June 19 45", normalizer.normalize("in June 1945"))
    }

    @Test
    fun `reads years with a zero in the second group as o`() {
        assertEquals("in 17 o 4", normalizer.normalize("in 1704"))
        assertEquals("in 14 o 2", normalizer.normalize("in 1402"))
        assertEquals("in 18 o 1", normalizer.normalize("in 1801"))
        // 09 and plain 00 are left as ordinary groups.
        assertEquals("in 18 09", normalizer.normalize("in 1809"))
        assertEquals("in 17 00", normalizer.normalize("in 1700"))
    }

    @Test
    fun `reads parenthetical and comma years`() {
        assertEquals("(18 15) painter", normalizer.normalize("(1815) painter"))
        assertEquals("born 18 09, died 18 65", normalizer.normalize("born 1809, died 1865"))
    }

    @Test
    fun `reads year ranges as two digit groups`() {
        assertEquals("from 19 14 to 19 18", normalizer.normalize("from 1914 to 1918"))
    }

    @Test
    fun `leaves non-year four digit numbers alone`() {
        // A wavelength, a mass ratio, and a bare count have no year lead-in word.
        assertEquals("a wavelength of 1216 atoms", normalizer.normalize("a wavelength of 1216 atoms"))
        assertEquals("the mass is 1836 times", normalizer.normalize("the mass is 1836 times"))
        assertEquals("1984 distinct words", normalizer.normalize("1984 distinct words"))
    }

    @Test
    fun `leaves large scale quantities alone`() {
        assertEquals("1846 million people", normalizer.normalize("1846 million people"))
        assertEquals("1500 percent", normalizer.normalize("1500 percent"))
    }

    @Test
    fun `leaves years outside the rewrite range alone`() {
        // 2000+ are read well by engines already ("twenty twenty"), and <1100
        // are typically ordinary numbers.
        assertEquals("in 2024", normalizer.normalize("in 2024"))
        assertEquals("in 1066", normalizer.normalize("in 1066"))
    }
}
