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
        assertEquals("1914 to 1918", normalizer.normalize("1914\u20131918"))
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
}
