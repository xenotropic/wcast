package cloud.morris.wcast.normalization

import java.text.Normalizer

/**
 * Converts article *display* text into *spoken* text before handing it to the
 * Android TextToSpeech engine.
 *
 * Why this exists: the article text is already well-scrubbed for reading on
 * screen, but a lot of Wikipedia's typography reads badly out loud — Unicode
 * dashes and quote marks, degrees and fractions, "km/h", "U.S.", "Henry VIII",
 * "$4 billion", and so on. This class rewrites only the text that is *spoken*;
 * the on-screen text is never touched.
 *
 * ## Design principles
 *
 *  • **Keep the readable rules in files.** The three rule tables
 *    (`bulk_replace.csv`, `pronounced_acronyms.csv`, `regex_rules.txt`) hold
 *    everything that is a plain "find -> replace". They are parsed by
 *    [NormalizationRules] and can be edited (or, later, updated remotely)
 *    without recompiling.
 *
 *  • **Semantic transforms stay in code.** Anything that needs more than a
 *    single match/replace — transliteration, money, ordinals, units, fractions,
 *    roman numerals, acronym letter-splitting — lives here, as small, clearly
 *    named, individually understandable functions.
 *
 *  • **Defer to the TTS engine where it is already good.** We do NOT expand
 *    ordinary integers, dates, or decimals: modern engines read "1974", "3.14",
 *    and "the 1980s" perfectly well. We only fix the things they get *wrong*.
 *
 * ## Order of operations
 *
 * Each stage sees the output of the stage before it, and the order is load
 * bearing:
 *
 *  1. transliterate      — fold non-ASCII down to ASCII first, so later rules see
 *                          plain text (no stray en-dashes, curly quotes, é, ²...).
 *  2. bulk replace       — one-to-one substitutions from `bulk_replace.csv`.
 *  3. regex rules        — ordered rules from `regex_rules.txt` (dash pauses,
 *                          number ranges, temperature).
 *  4. money              — "$4 billion" -> "four billion dollars".
 *  5. years              — "in 1846" -> "in 18 46" (1100–1999, contextual).
 *  6. roman numerals     — "Henry VIII" -> "Henry the eighth".
 *  7. units              — "5 km", "120 km/h", "80 mph" -> spoken unit words.
 *  8. fractions          — "3/4" -> "three quarters" (guarded against dates).
 *  9. ordinals           — "3rd" -> "third", "#7" -> "number seven".
 * 10. acronyms           — known pronunciations, then all-caps -> spaced letters.
 * 11. whitespace/punct   — collapse runs and tidy stray punctuation.
 */
class Normalizer(private val rules: NormalizationRules) {

    /**
     * Runs the full pipeline on [text] and returns the text that should be sent
     * to the TTS engine.
     */
    fun normalize(text: String): String {
        var out = transliterate(text)
        out = applyReplacements(out, rules.replacements)
        out = applyRegexRules(out, rules.regexRules)
        out = expandMoney(out)
        out = expandYears(out)
        out = expandRomanNumerals(out)
        out = expandUnits(out)
        out = expandFractions(out)
        out = expandOrdinals(out)
        out = expandAcronyms(out, rules.acronymPronunciations)
        out = cleanPunctuation(out)
        return out
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stage 1: Unicode -> ASCII transliteration
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Folds most common Unicode into ASCII so that a) later regex rules don't
     * have to care about invisible look-alike characters, and b) the TTS engine
     * doesn't stumble over accented or typographic characters.
     *
     * Strategy (high value, no APK bloat):
     *  • NFKD decomposition + strip combining marks handles Latin accents
     *    (e -> e, u -> u, ligatures -> ff).
     *  • A small explicit map covers the high-value symbols/ligatures NFKD
     *    doesn't reduce to clean ASCII text (curly quotes, dashes, +-, x, 1/2...).
     * Full unidecode-style transliteration for every script is deliberately NOT
     * done — English Wikipedia prose is overwhelmingly Latin.
     */
    private fun transliterate(text: String): String {
        val stripped = Normalizer.normalize(text, Normalizer.Form.NFKD)
            .replace(Regex("\\p{Mn}+"), "")

        val sb = StringBuilder(stripped.length)
        for (ch in stripped) {
            sb.append(ASCII_MAP[ch] ?: ch)
        }
        return sb.toString()
    }


    /**
     * Supplementary Unicode -> ASCII replacements not covered by NFKD stripping.
     * Values are words/short phrases for symbols that should be *spoken*, and
     * plain ASCII letters for ligatures/alphabet look-alikes.
     */
    private val ASCII_MAP: Map<Char, String> = buildMap {
        // Typographic punctuation and spacing.
        put('\u2018', "'"); put('\u2019', "'")   // left/right single quote
        put('\u201C', "\""); put('\u201D', "\"") // left/right double quote
        put('\u201A', "'"); put('\u201E', "\"")  // low single/double quote
        put('\u00AB', "\""); put('\u00BB', "\"") // guillemets
        put('\u2039', "'"); put('\u203A', "'")
        put('\u2010', "-"); put('\u2011', "-")   // hyphen / non-breaking hyphen
        put('\u2026', "...")                      // ellipsis
        put('\u00A0', " ")                        // non-breaking space
        put('\u202F', " ")                        // narrow no-break space
        put('\u2009', " "); put('\u200A', " ")    // thin/hair space

        // Symbols worth actually saying.
        // Note: '°' (degrees), en/em dash are handled by regex_rules.txt so that
        // number ranges like "1914–1918" win over a generic "dash -> pause" rule.
        put('\u00B1', " plus or minus ")
        put('\u00D7', " by ")       // multiplication sign
        put('\u00F7', " divided by ")
        put('\u2248', " approximately ")
        put('\u2260', " not equal to ")
        put('\u2264', " less than or equal to ")
        put('\u2265', " greater than or equal to ")
        put('\u2212', " minus ")    // U+2212 minus vs hyphen
        put('\u2022', ", ")         // bullet
        put('\u00B7', " ")          // middle dot

        // Superscripts / vulgar fractions that recur in measurements.
        put('\u00B2', " squared "); put('\u00B3', " cubed ")
        put('\u00BD', " one half ")
        put('\u00BC', " one quarter ")
        put('\u00BE', " three quarters ")
        put('\u2153', " one third "); put('\u2154', " two thirds ")

        // Remaining Latin ligatures / look-alikes NFKD doesn't fully cover.
        put('\u00C6', "Ae"); put('\u00E6', "ae")    // Ae / ae
        put('\u0152', "Oe"); put('\u0153', "oe")    // Oe / oe
        put('\u00D8', "O"); put('\u00F8', "o")      // O / o
        put('\u00DE', "Th"); put('\u00FE', "th")    // Th / th
        put('\u00DF', "ss")                         // sharp s
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stage 2/3: rule-file-driven substitutions
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Applies the one-to-one [replacements] (from `bulk_replace.csv`) in map
     * order. Longer, topically-similar keys are placed first in the file where
     * overlap matters (e.g. " World War I" before any generic "I" rule).
     */
    private fun applyReplacements(text: String, replacements: Map<String, String>): String {
        var out = text
        for ((source, replacement) in replacements) {
            out = out.replace(source, replacement)
        }
        return out
    }

    /** Applies ordered [RegexRule]s (from `regex_rules.txt`). */
    private fun applyRegexRules(text: String, regexRules: List<NormalizationRules.RegexRule>): String {
        var out = text
        for (rule in regexRules) {
            out = rule.pattern.replace(out, rule.replacement)
        }
        return out
    }


    // ─────────────────────────────────────────────────────────────────────────
    // Stage 4: money ("$4 billion" -> "four billion dollars")
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Rewrites leading currency amounts ("$5", "1.5 billion", "200 million")
     * into fully spoken words, because engines tend to read "$5 billion" as
     * "five dollars billion" rather than "five billion dollars".
     */
    private fun expandMoney(text: String): String {
        // "$1.5 billion" / "$20 million" / "$4 trillion": scale word after amount.
        val scaled = Regex("""([$\u00A3\u20AC])\s*(\d+(?:[.,]\d+)?)\s*(million|billion|trillion)""")
        val withScale = scaled.replace(text) { m ->
            val amount = NumberWords.toWords(parseAmount(m.groupValues[2]))
            val scale = m.groupValues[3]
            "${amount} $scale ${currencyName(m.groupValues[1])}"
        }
        // Plain "$5" with no scale word.
        val plain = Regex("""([$\u00A3\u20AC])\s*(\d+(?:[.,]\d+)?)""")
        return plain.replace(withScale) { m ->
            "${NumberWords.toWords(parseAmount(m.groupValues[2]))} ${currencyName(m.groupValues[1])}"
        }
    }

    private fun currencyName(symbol: String): String = when (symbol) {
        "$" -> "dollars"
        "\u00A3" -> "pounds"
        "\u20AC" -> "euros"
        else -> "dollars"
    }

    /** Parses an amount that may contain a decimal point or comma separator. */
    private fun parseAmount(raw: String): Long {
        val normalized = raw.replace(",", "")
        val dot = normalized.indexOf('.')
        return if (dot < 0) normalized.toLongOrNull() ?: 0
        else normalized.substring(0, dot).toLongOrNull() ?: 0
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stage 4b: years ("1846" -> "18 46", "1704" -> "17 o 4")
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Years in 1100..1999 are read by TTS engines as cardinal numbers ("one
     * thousand eight hundred forty six") rather than the conventional "eighteen
     * forty six" that a native speaker uses for a year. This stage rewrites such
     * four-digit tokens into two digit-groups, leaving a space so the engine
     * pronounces each group separately: "1846" -> "18 46".
     *
     * Years whose second group is 01..08 are spoken with an "o" where the zero
     * is ("1704" -> "seventeen o four"), so the leading zero is replaced with an
     * "o": "1704" -> "17 o 4".
     *
     * ## Why not just every four-digit number?
     *
     * Scientific and everyday prose is full of non-year four-digit integers
     * ("1836 times the mass", "a wavelength of 1216 Å", "1984 distinct words"),
     * so rewriting every `\d{4}` would corrupt those readings. Instead we only
     * rewrite when the surrounding context looks like a *year reference*, which
     * is overwhelmingly signalled in historical prose by a small set of lead-in
     * words ("in", "since", "around", "circa", "during", "AD", …) or by the
     * number appearing immediately after an opening paren/comma (birth–death
     * years such as "(1846)" or ", 1846,").
     *
     * ## Placement
     *
     * This runs *after* money and *before* units, so the lead-in-word guard is
     * what actually protects measurements ("the road is 1984 km long" has no
     * year lead-in word and is left alone by this stage).
     */
    private fun expandYears(text: String): String {
        val year = Regex("""(?<!\d)(\d{4})(?!\d)""")
        return year.replace(text) { m ->
            val digits = m.groupValues[1]
            val value = digits.toInt()
            if (value !in minYear..maxYear) return@replace m.value

            // Skip unambiguous non-year quantities: "1846 million", "1846 percent".
            val after = text.substring(m.range.last + 1)
            if (YEAR_NON_YEAR_SUFFIX.matchesAt(after, 0)) return@replace m.value

            val before = text.substring(0, m.range.first)
            if (isYearLeadIn(before) || isYearRangeStart(after)) toYearPairs(digits) else m.value
        }
    }

    /** Minimum/maximum year rewritten by [expandYears]. */
    private val minYear = 1100
    private val maxYear = 1999

    /**
     * Words that, immediately before a four-digit number, mark it as a year.
     * Kept deliberately narrow; notably *absent* are prepositions used for
     * measurements ("of 1216 Å") and copulas ("is 1984"). Months are included
     * because "November 1914" is an unambiguous year reference.
     */
    private val YEAR_LEAD_IN_WORDS = setOf(
        "in", "since", "until", "from", "around", "circa", "during", "between",
        "before", "after", "by", "ad", "ce", "bc", "bce", "early", "late", "mid",
        "to", "and", "or", "year", "years", "born", "died", "birth", "death",
        "reigned",
        // Months of the year ("November 1914" reads as a year).
        "january", "february", "march", "april", "may", "june", "july",
        "august", "september", "october", "november", "december"
    )

    /**
     * Quantities a four-digit number can legitimately modify but which are never
     * reasonably read as a year ("1846 million people").
     */
    private val YEAR_NON_YEAR_SUFFIX = Regex(
        """\s*(million|billion|trillion|hundred|thousand|percent|times)"""
    )

    /**
     * True when the run of letters immediately before the match is a year
     * lead-in word, or when the match is preceded directly by an opening paren
     * or comma (birth–death years "(1846)", ", 1846,").
     */
    private fun isYearLeadIn(before: String): Boolean {
        // Immediate punctuation: "(1846" or ", 1846" / "; 1846".
        if (before.endsWith("(") || before.endsWith(",") || before.endsWith(";")) {
            return true
        }
        val word = yearLeadInWord(before) ?: return false
        return word in YEAR_LEAD_IN_WORDS
    }

    /**
     * True when [after] (the text following a four-digit number) begins a year
     * range, e.g. " to 1918" or ", 1918" or " and 1918". Catches the *first*
     * year of a range where the second year would otherwise be split but the
     * first left bare ("1914–1918" becomes "1914 to 1918" by the earlier
     * number-range rule, then "1914" has no natural lead-in word).
     */
    private fun isYearRangeStart(after: String): Boolean =
        Regex("""\s*(?:to|through|and|or)\s+\d|\s*[,;-]\s*\d""").matchesAt(after, 0)

    /** Returns the trailing word immediately before the match, lowercased, or null. */
    private fun yearLeadInWord(before: String): String? {
        val match = Regex("""([A-Za-z]+)\s*$""").find(before) ?: return null
        return match.groupValues[1].lowercase()
    }

    /** Rewrites "1846" -> "18 46" and "1704" -> "17 o 4". */
    private fun toYearPairs(digits: String): String {
        val first = digits.substring(0, 2)
        val second = digits.substring(2)
        // "o" pronunciation applies to the 01..08 group ("17 o 4"), while 00
        // ("1700") and 09 ("1809") are left as ordinary digit groups.
        val useO = second[0] == '0' && second[1] in '1'..'8'
        return if (useO) {
            "$first o ${second[1]}"
        } else {
            "$first $second"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stage 5: roman numerals ("Henry VIII" -> "Henry the eighth")
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The leading words after which a trailing roman numeral is read as an
     * ordinal. We deliberately do NOT convert *every* roman-numeral-looking
     * token, because strings like "MM" (a date fragment) or "IX" inside URLs
     * would be mangled.
     */
    private val REGNAL_TITLES = setOf(
        "king", "queen", "emperor", "empress", "prince", "princess", "pope",
        "chapter", "part", "volume", "act", "book", "title"
    )

    private val REGNAL_GIVEN_NAMES = setOf(
        "henry", "charles", "george", "edward", "william", "richard", "louis",
        "philip", "james", "benedict", "gregory", "alexander", "nicholas"
    )

    /**
     * Converts "Henry VIII" -> "Henry the eighth" and "Pope John Paul II" ->
     * "Pope John Paul the second", but only when the numeral follows a known
     * regnal name/title so we don't corrupt arbitrary Roman-numeral text.
     */
    private fun expandRomanNumerals(text: String): String {
        val pattern = Regex("""\b([A-Z][a-zA-Z]*)\s+([IVXLCDM]{1,8})\b""")
        return pattern.replace(text) { m ->
            val name = m.groupValues[1]
            val numeral = m.groupValues[2]
            val shouldExpand =
                name.lowercase() in REGNAL_GIVEN_NAMES ||
                    name.lowercase() in REGNAL_TITLES
            if (shouldExpand && RomanNumerals.isRoman(numeral)) {
                val value = RomanNumerals.toInt(numeral)
                if (value in 1..100) "$name the ${NumberWords.toOrdinal(value.toLong())}" else m.value
            } else {
                m.value
            }
        }
    }


    // ─────────────────────────────────────────────────────────────────────────
    // Stage 6: units ("5 km" -> "five kilometers")
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Unit abbreviations mapped to their spoken word. Kept to a short, safe list
     * to avoid false positives ("m" alone, "in" as a preposition, "mi" as a
     * syllable) — each is expanded only when immediately preceded by a digit.
     *
     * The single-letter "m" and the preposition "in" are inherently ambiguous,
     * so they are only expanded when additionally followed by a non-letter
     * (see [AMBIGUOUS_UNITS] and [expandUnits]).
     */
    private val UNIT_WORDS: Map<String, String> = buildMap {
        put("km", "kilometers"); put("cm", "centimeters"); put("mm", "millimeters")
        put("ft", "feet"); put("mi", "miles")
        put("km/h", "kilometers per hour"); put("mph", "miles per hour")
        put("km2", "square kilometers"); put("m2", "square meters")
        put("kg", "kilograms")
        put("Hz", "hertz")    // e.g. "25 Hz" -> "25 hertz" (frequency; unambiguous)
        put("kHz", "kilohertz")  // e.g. "25 kHz" -> "25 kilohertz" (guarded like "m")
        put("MHz", "megahertz")  // e.g. "25 MHz" -> "25 megahertz" (guarded like "m")
        put("in", "inches")   // e.g. "25.2 in" -> "25.2 inches" (decimal-safe)
        put("m", "meters")    // e.g. "5 m" -> "5 meters"
        put("t", "tonnes")    // e.g. "190 t" -> "190 tonnes" (tonne; guarded like "m")
    }

    /**
     * Units that are a single letter, a common preposition, or a prefixed
     * frequency ("m", "in", "t", "kHz", "MHz"). These are only safe to expand
     * when the unit is *not* followed by another letter — e.g. "5 in total" /
     * "5 m tall" must stay untouched, while "5 in)", "5 m,", "200 t)", or
     * "25 MHz," measure inches/meters/tonnes/megahertz. [expandUnits] adds this
     * lookahead for these keys.
     */
    private val AMBIGUOUS_UNITS = setOf("m", "in", "t", "kHz", "MHz")

    /**
     * Rewrites a unit abbreviation into words when it directly follows a number
     * ("120 km/h" -> "120 kilometers per hour"). The `(?<=\d)` guard prevents
     * matching the letters inside unrelated words, and longest units are applied
     * first so "km/h" wins over "km".
     *
     * For the ambiguous single-letter/preposition units ("m", "in"), a further
     * lookahead requires the unit to be followed by a non-letter (end, space +
     * punctuation) so prepositions like "5 in total" and "chapter m" are not
     * mangled. This allows decimals ("25.2 in") and is deliberately imperfect:
     * a measurement written "5 m tall" is left alone (an accepted false
     * negative), trading a few misses for far fewer wrong readings.
     */
    private fun expandUnits(text: String): String {
        val unitPattern = UNIT_WORDS.keys.sortedByDescending { it.length }
        var out = text
        for (unit in unitPattern) {
            // Ambiguous units must not be followed by another letter; this
            // disambiguates "5 in" (inches) from "5 in total" (preposition) and
            // "5 m" (meters) from "5 m tall"/"m" inside a longer token.
            val suffixGuard = if (unit in AMBIGUOUS_UNITS) """(?!\s*[A-Za-z])""" else ""
            val regex = Regex("""(?i)(?<=\d)\s*${Regex.escape(unit)}\b$suffixGuard""")
            // Leading space keeps "5 km" -> "5 kilometers" (not "5kilometers");
            // the final cleanup collapses any resulting run of spaces.
            out = regex.replace(out) { " ${UNIT_WORDS[unit]!!}" }
        }
        return out
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stage 7: fractions ("3/4" -> "three quarters")
    // ─────────────────────────────────────────────────────────────────────────
    private val COMMON_FRACTIONS = mapOf(
        "1/2" to "one half",
        "1/3" to "one third",
        "2/3" to "two thirds",
        "1/4" to "one quarter",
        "3/4" to "three quarters",
        "1/5" to "one fifth",
        "1/8" to "one eighth",
        "3/8" to "three eighths"
    )

    /**
     * Expands a "numerator/denominator" fraction to words with a common-fraction
     * shortcut (1/2 -> "one half") and a general "N over M" fallback.
     *
     * Date safety: a `/` immediately following (as in "3/4/2025") or two
     * three-digit numbers (a ratio like "150/300") blocks the match, so dates
     * and measured ratios are not turned into fractions.
     */
    private fun expandFractions(text: String): String {
        val common = Regex("""(?<![0-9/])(\d{1,3})[/](\d{1,3})(?!/)""")
        return common.replace(text) { m ->
            val num = m.groupValues[1]
            val den = m.groupValues[2]
            COMMON_FRACTIONS["$num/$den"] ?: run {
                // Skip ratios of two three-digit numbers (e.g. "150/300").
                if (num.length >= 3 && den.length >= 3) {
                    m.value
                } else {
                    "${NumberWords.toWords(num.toLong())} over ${NumberWords.toWords(den.toLong())}"
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stage 8: ordinals ("3rd" -> "third", "#7" -> "number seven")
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Expands numeric ordinals to words, because some engines read "3rd" as
     * "three r d", and the "#7" convention as a bare "hash seven".
     */
    /** Expands "3rd" / "21st" / "42nd" to spoken ordinals ("third", "twenty first"). */
    private fun expandOrdinals(text: String): String {
        // "#7" -> "number seven"
        val withHash = Regex("""#(\d+)\b""").replace(text) { m ->
            "number ${NumberWords.toWords(m.groupValues[1].toLong())}"
        }
        // "3rd" / "21st" / "42nd" / "57th" -> spoken ordinal.
        return Regex("""\b(\d+)(st|nd|rd|th)\b""").replace(withHash) { m ->
            NumberWords.toOrdinal(m.groupValues[1].toLong())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stage 9: acronyms (pronunciations + all-caps -> spaced letters)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Handles all-caps abbreviations:
     *  • An explicit entry in `pronounced_acronyms.csv` wins ("NATO" -> "nayto").
     *  • Otherwise an all-caps token of 2+ letters is spelled letter-by-letter
     *    ("FBI" -> "F B I") so the engine doesn't read "fbi" as one word.
     *
     * Single "I"/"A", mixed alphanumeric tokens ("MP3"), and tokens already
     * handled earlier (roman numerals) are left alone.
     */
    private fun expandAcronyms(text: String, pronunciations: Map<String, String>): String {
        return Regex("""\b[A-Z]{2,}\b""").replace(text) { m ->
            val token = m.value
            pronunciations[token] ?: token.toCharArray().joinToString(" ")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stage 10: final cleanup
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Collapses the whitespace our own expansions introduced (e.g. the padded
     * " degrees " from transliteration) and removes stray duplicated punctuation.
     */
    private fun cleanPunctuation(text: String): String {
        return text
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([,.;:!?])"), "$1")       // no space before punctuation
            .replace(Regex("([,.;:!?])\\1+"), "$1")        // collapse ",," -> ","
            .trim()
    }
}

