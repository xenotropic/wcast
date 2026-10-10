package cloud.morris.wcast.normalization

/**
 * Converts an integer into its English word form, e.g. 42 -> "forty two".
 *
 * This is a deliberately small, self-contained helper (no heavyweight number
 * library) used by the numeric transforms in [Normalizer]: money, ordinals,
 * fractions, roman numerals, and "number N" references. The spoken text is fed
 * to the Android TTS engine, which otherwise handles everyday digits itself.
 *
 * Range: 0 through 999,999,999,999 inclusive. Values outside this range are
 * returned as their plain decimal digits rather than crashing.
 *
 * Style note: hundreds are rendered without the British "and" (e.g. "one hundred
 * five") to stay consistent with the engine's own digit-to-speech readings.
 */
object NumberWords {

    private val UNITS = listOf(
        "zero", "one", "two", "three", "four", "five",
        "six", "seven", "eight", "nine", "ten", "eleven",
        "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen"
    )

    private val TENS = listOf(
        "", "", "twenty", "thirty", "forty", "fifty",
        "sixty", "seventy", "eighty", "ninety"
    )

    private val SCALES = listOf(
        "thousand", "million", "billion", "trillion"
    )

    /** Maximum value that can be spelled out; larger values stay as digits. */
    private const val MAX = 999_999_999_999L

    /** Returns the English word form of [value], or its digits if out of range. */
    fun toWords(value: Long): String {
        if (value < 0) return "minus " + toWords(-value)
        if (value > MAX) return value.toString()

        var n = value
        if (n < 20) return UNITS[n.toInt()]

        val parts = mutableListOf<String>()

        // Build from the largest scale down so the words come out left-to-right.
        var scaleIndex = SCALES.size - 1
        while (n >= 1000) {
            val divisor = pow1000(scaleIndex + 1)
            val chunk = (n / divisor).toInt()
            if (chunk > 0) {
                parts.add(threeDigits(chunk))
                parts.add(SCALES[scaleIndex])
                n %= divisor
            }
            scaleIndex--
        }
        if (n > 0) parts.add(threeDigits(n.toInt()))

        return parts.joinToString(" ")
    }

    /** Spells a 0..999 chunk without scaling words. */
    private fun threeDigits(value: Int): String {
        require(value in 0..999) { "chunk out of range: $value" }
        val parts = mutableListOf<String>()
        if (value >= 100) {
            parts.add("${UNITS[value / 100]} hundred")
        }
        val rem = value % 100
        if (rem in 1..19) {
            parts.add(UNITS[rem])
        } else if (rem >= 20) {
            val tens = TENS[rem / 10]
            val ones = rem % 10
            parts.add(if (ones == 0) tens else "$tens ${UNITS[ones]}")
        }
        return parts.joinToString(" ")
    }

    /**
     * Returns [value] as an ordinal word, e.g. 8 -> "eighth", 21 -> "twenty
     * first", 100 -> "one hundredth". Used for "Henry VIII" -> "the eighth" and
     * "3rd" -> "third".
     */
    fun toOrdinal(value: Long): String {
        if (value < 0) return "minus " + toOrdinal(-value)
        if (value > MAX) return value.toString()
        val words = toWords(value).split(' ')
        val last = ordinalizeWord(words.last())
        return (words.dropLast(1) + last).joinToString(" ")
    }

    /**
     * Turns a *base* number word into its ordinal form. Base words are the
     * output of [toWords], e.g. "twenty" -> "twentieth", "nine" -> "ninth".
     */
    private fun ordinalizeWord(word: String): String = when (word) {
        "one" -> "first"
        "two" -> "second"
        "three" -> "third"
        "five" -> "fifth"
        "eight" -> "eighth"
        "nine" -> "ninth"
        "twelve" -> "twelfth"
        else -> when {
            word.endsWith("y") -> word.dropLast(1) + "ieth" // twenty -> twentieth
            else -> word + "th"
        }
    }

    /** Returns 1000 raised to [scale] using Long arithmetic. */
    private fun pow1000(scale: Int): Long {
        var result = 1L
        repeat(scale) { result *= 1000L }
        return result
    }
}
