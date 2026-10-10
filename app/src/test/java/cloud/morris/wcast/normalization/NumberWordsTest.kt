package cloud.morris.wcast.normalization

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises [NumberWords], the small integer-to-words helper used by the
 * numeric transforms.
 */
class NumberWordsTest {

    @Test
    fun `spells units`() {
        assertEquals("zero", NumberWords.toWords(0))
        assertEquals("seven", NumberWords.toWords(7))
        assertEquals("ten", NumberWords.toWords(10))
        assertEquals("nineteen", NumberWords.toWords(19))
    }

    @Test
    fun `spells tens and compound numbers`() {
        assertEquals("twenty", NumberWords.toWords(20))
        assertEquals("twenty three", NumberWords.toWords(23))
        assertEquals("forty two", NumberWords.toWords(42))
        assertEquals("ninety nine", NumberWords.toWords(99))
    }

    @Test
    fun `spells hundreds`() {
        assertEquals("one hundred", NumberWords.toWords(100))
        assertEquals("one hundred five", NumberWords.toWords(105))
        assertEquals("three hundred twenty", NumberWords.toWords(320))
        assertEquals("nine hundred ninety nine", NumberWords.toWords(999))
    }

    @Test
    fun `spells large scales`() {
        assertEquals("one thousand", NumberWords.toWords(1000))
        assertEquals("twelve thousand five hundred", NumberWords.toWords(12_500))
        assertEquals("one million", NumberWords.toWords(1_000_000))
        assertEquals("four billion", NumberWords.toWords(4_000_000_000))
    }

    @Test
    fun `falls back to digits beyond range`() {
        assertEquals("1000000000000", NumberWords.toWords(1_000_000_000_000L))
    }

    @Test
    fun `spells ordinals`() {
        assertEquals("first", NumberWords.toOrdinal(1))
        assertEquals("second", NumberWords.toOrdinal(2))
        assertEquals("third", NumberWords.toOrdinal(3))
        assertEquals("fifth", NumberWords.toOrdinal(5))
        assertEquals("eighth", NumberWords.toOrdinal(8))
        assertEquals("ninth", NumberWords.toOrdinal(9))
        assertEquals("twelfth", NumberWords.toOrdinal(12))
        assertEquals("twentieth", NumberWords.toOrdinal(20))
        assertEquals("twenty first", NumberWords.toOrdinal(21))
        assertEquals("one hundredth", NumberWords.toOrdinal(100))
    }
}
