package cloud.morris.wcast.normalization

import android.content.Context

/**
 * Loads the three rule files bundled in the app's `assets/normalization/` and
 * parses them into [NormalizationRules].
 *
 * Kept as a tiny Android-only shim so that [NormalizationRules] itself stays
 * framework-free and unit-testable. When remote rule updates are implemented,
 * they can provide an alternative source (e.g. a downloaded copy) and feed the
 * same [NormalizationRules.parseCsv]/[NormalizationRules.parseRegexRules] entry
 * points without touching any normalization logic.
 */
fun loadNormalizationRules(context: Context): NormalizationRules {
    val assets = context.assets
    return NormalizationRules(
        replacements = NormalizationRules.parseCsv(
            readAsset(assets, "normalization/bulk_replace.csv")
        ),
        acronymPronunciations = NormalizationRules.parseCsv(
            readAsset(assets, "normalization/pronounced_acronyms.csv")
        ),
        regexRules = NormalizationRules.parseRegexRules(
            readAsset(assets, "normalization/regex_rules.txt")
        )
    )
}

/** Reads an asset as UTF-8 text. */
private fun readAsset(assets: android.content.res.AssetManager, path: String): String =
    assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
