package cloud.morris.wcast.normalization

/**
 * Converts a Roman numeral string (e.g. "IV", "XLII", "1984"?) to its integer
 * value. Used by [Normalizer] to render regnal numbers as ordinals: the spoken
 * text for "Henry VIII" should be "Henry the eighth", not "Henry vee eye eye".
 *
 * Only the standard uppercase letters I, V, X, L, C, D, M are recognised.
 * Returns 0 for empty/invalid input rather than throwing, so callers can simply
 * ignore recogniser failures.
 */
object RomanNumerals {

    /** The value of a single Roman numeral symbol, or 0 if it is not one. */
    private fun symbolValue(ch: Char): Int = when (ch) {
        'I' -> 1
        'V' -> 5
        'X' -> 10
        'L' -> 50
        'C' -> 100
        'D' -> 500
        'M' -> 1000
        else -> 0
    }

    /** Returns true if every character in [s] is a valid Roman numeral symbol. */
    fun isRoman(s: String): Boolean = s.isNotEmpty() && s.all { symbolValue(it) != 0 }

    /** Parses [s] into an integer using the standard subtractive rules. */
    fun toInt(s: String): Int {
        var total = 0
        var prev = 0
        // Walk right-to-left: subtract if the current symbol is smaller than the
        // one already seen, otherwise add (handles IV, IX, XL, XC, ...).
        for (ch in s.reversed()) {
            val value = symbolValue(ch)
            if (value == 0) return 0 // invalid character -> treat whole thing as 0
            if (value < prev) total -= value else total += value
            prev = value
        }
        return total
    }
}
