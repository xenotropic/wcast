package cloud.morris.wcast.normalization

import java.util.LinkedHashMap

/**
 * The three editable rule files that drive normalization, in parsed form.
 *
 * These are intentionally kept as plain text resources (in
 * `src/main/assets/normalization/`) rather than compiled into Kotlin, so that
 * the pronunciation and replacement rules can be reviewed and updated without
 * touching code — including, eventually, fetched remotely. They are:
 *
 *  • `bulk_replace.csv`       simple one-to-one string substitutions.
 *  • `pronounced_acronyms.csv` all-caps acronyms and how to say them.
 *  • `regex_rules.txt`         ordered regular-expression rules (see its header
 *                              for the exact self-documenting format).
 *
 * This class is deliberately free of any Android dependency so its parsing can
 * be unit-tested on a plain JVM; the reading of the asset files lives in
 * [cloud.morris.wcast.normalization.loadNormalizationRules]. Applying the rules
 * is [Normalizer]'s job.
 */
class NormalizationRules(
    /** Ordered, case-preserving one-to-one substitutions (longest-first is NOT assumed). */
    val replacements: Map<String, String>,
    /** All-caps token -> how to pronounce it, matched before generic letter-splitting. */
    val acronymPronunciations: Map<String, String>,
    /** Ordered regex substitution rules, in application order. */
    val regexRules: List<RegexRule>
) {

    /** A single immutable regex replacement rule parsed from `regex_rules.txt`. */
    data class RegexRule(
        val pattern: Regex,
        val replacement: String,
        val caseInsensitive: Boolean
    )

    companion object {

        /**
         * Parses a two-column CSV (header + key,value rows) into an ordered map.
         * Empty rows, comment lines (`#`), and rows whose source is blank are
         * skipped. If a key appears more than once, the last occurrence wins
         * (documented as intentional).
         */
        fun parseCsv(csv: String): Map<String, String> {
            val map = LinkedHashMap<String, String>()
            csv.lineSequence()
                .drop(1) // skip header row
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { line ->
                    val comma = line.indexOf(',')
                    if (comma > 0) {
                        val key = line.substring(0, comma).trim()
                        val value = line.substring(comma + 1).trim()
                        if (key.isNotEmpty()) map[key] = value
                    }
                }
            return map
        }

        /**
         * Parses `regex_rules.txt` into ordered [RegexRule]s.
         *
         * Format: `pattern<TAB>replacement<TAB>flags`, where the flags column is
         * optional (currently only "i" for case-insensitive). Lines whose first
         * non-space character is `#` are comments and ignored, as are blanks.
         */
        fun parseRegexRules(text: String): List<RegexRule> {
            val rules = mutableListOf<RegexRule>()
            text.lineSequence().forEach { raw ->
                val line = raw.trimEnd('\r')
                val content = line.trimStart()
                if (content.isEmpty() || content.startsWith("#")) return@forEach

                val parts = line.split('\t')
                if (parts.size < 2) return@forEach // malformed line -> ignore

                val pattern = parts[0].trim()
                val replacement = parts[1]
                // A third column is the flags (optional). Trim only whitespace.
                val flags = parts.getOrNull(2)?.trim() ?: ""
                val caseInsensitive = flags.contains('i', ignoreCase = true)

                if (pattern.isEmpty()) return@forEach
                val options = if (caseInsensitive) {
                    setOf(RegexOption.IGNORE_CASE)
                } else {
                    emptySet()
                }
                rules += RegexRule(Regex(pattern, options), replacement, caseInsensitive)
            }
            return rules
        }
    }
}
