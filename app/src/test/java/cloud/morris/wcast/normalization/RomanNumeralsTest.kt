package cloud.morris.wcast.normalization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises [RomanNumerals]. */
class RomanNumeralsTest {

    @Test
    fun `detects valid numerals`() {
        assertTrue(RomanNumerals.isRoman("I"))
        assertTrue(RomanNumerals.isRoman("VIII"))
        assertTrue(RomanNumerals.isRoman("XLVII"))
        assertTrue(RomanNumerals.isRoman("MCMLXXXIV"))
        assertFalse(RomanNumerals.isRoman(""))
        assertFalse(RomanNumerals.isRoman("ABC"))
    }

    @Test
    fun `parses additive numerals`() {
        assertEquals(3, RomanNumerals.toInt("III"))
        assertEquals(16, RomanNumerals.toInt("XVI"))
        assertEquals(58, RomanNumerals.toInt("LVIII"))
    }

    @Test
    fun `parses subtractive numerals`() {
        assertEquals(4, RomanNumerals.toInt("IV"))
        assertEquals(9, RomanNumerals.toInt("IX"))
        assertEquals(40, RomanNumerals.toInt("XL"))
        assertEquals(90, RomanNumerals.toInt("XC"))
        assertEquals(1984, RomanNumerals.toInt("MCMLXXXIV"))
    }

    @Test
    fun `returns zero for invalid input`() {
        assertEquals(0, RomanNumerals.toInt(""))
        assertEquals(0, RomanNumerals.toInt("abc"))
    }
}
