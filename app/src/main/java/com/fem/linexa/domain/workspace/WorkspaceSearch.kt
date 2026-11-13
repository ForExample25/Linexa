package com.fem.linexa.domain.workspace

import java.text.Normalizer
import java.util.Locale

/**
 * What a workspace offers to a search: the text the user gave it and the text that
 * describes the distribution behind it.
 *
 * Deliberately a plain value type rather than the UI `Workspace` model, which carries a
 * Compose `Color` and does not belong in the domain.
 *
 * @property name the user's own name for the workspace — the strongest signal.
 * @property extras everything else worth matching: distro name, version, codename,
 *   image description. Matched as substrings, after the name tiers.
 */
data class WorkspaceSearchEntry(
    val name: String,
    val extras: List<String> = emptyList()
)

/**
 * How well an entry matched a query. Lower is better; compared lexicographically.
 *
 * @property worst the weakest tier any query word needed to match (a query matches an
 *   entry only if *every* word does, so the entry is only as good as its worst word).
 * @property total the sum of the per-word tiers, which orders entries that tie on
 *   [worst] — "debian 12" prefers the entry where both words land high.
 */
data class WorkspaceSearchRank(val worst: Int, val total: Int) : Comparable<WorkspaceSearchRank> {
    override fun compareTo(other: WorkspaceSearchRank): Int =
        compareValuesBy(this, other, { it.worst }, { it.total })
}

/**
 * Ranked search over workspaces (P3).
 *
 * Tiers, best first, applied to each whitespace-separated query word:
 *
 *  0. the name **starts with** the word;
 *  1. a **word inside the name** starts with it;
 *  2. the name **contains** it;
 *  3. any of the entry's [WorkspaceSearchEntry.extras] **contains** it.
 *
 * Anything else is not a match. A blank query matches everything equally.
 *
 * ## Normalisation
 *
 * Both sides go through [normalize] before comparing, so matching ignores case and
 * accents and — because the app ships a Persian locale — the Arabic/Persian spelling
 * variants that keyboards and pasted text mix freely: Arabic Yeh (ي, ى) vs Persian Yeh
 * (ی), Arabic Kaf (ك) vs Persian Keheh (ک), the zero-width non-joiner inside compound
 * words, Arabic-Indic and Persian digits, harakat, and tatweel. Without this, a user
 * typing ک cannot find a workspace named with ك, and "می‌خواهم" does not match
 * "میخواهم".
 *
 * Pure Kotlin on `java.text.Normalizer`: no `android.*` imports, fully JVM-testable.
 */
object WorkspaceSearch {

    const val TIER_NAME_PREFIX = 0
    const val TIER_NAME_WORD_PREFIX = 1
    const val TIER_NAME_SUBSTRING = 2
    const val TIER_EXTRAS = 3

    private const val ARABIC_YEH = '\u064A'
    private const val ARABIC_ALEF_MAKSURA = '\u0649'
    private const val PERSIAN_YEH = '\u06CC'
    private const val ARABIC_KAF = '\u0643'
    private const val PERSIAN_KEHEH = '\u06A9'
    private const val ZWNJ = '\u200C'
    private const val ZWJ = '\u200D'
    private const val TATWEEL = '\u0640'
    private const val NBSP = '\u00A0'

    /**
     * [text] folded to the form searches compare in: compatibility-decomposed (which also
     * unfolds Arabic presentation forms and full-width letters), stripped of combining
     * marks, lower-cased in the root locale, with Arabic variants mapped to their Persian
     * forms, joiners and tatweel removed, digits mapped to ASCII, and whitespace collapsed.
     */
    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD)
        val out = StringBuilder(decomposed.length)
        var pendingSpace = false
        for (ch in decomposed) {
            val type = Character.getType(ch)
            when {
                // Accents on Latin letters, harakat and hamza marks on Arabic ones.
                type == Character.NON_SPACING_MARK.toInt() -> Unit
                ch == ZWNJ || ch == ZWJ || ch == TATWEEL -> Unit
                Character.isWhitespace(ch) || ch == NBSP -> pendingSpace = out.isNotEmpty()
                else -> {
                    if (pendingSpace) {
                        out.append(' ')
                        pendingSpace = false
                    }
                    out.append(
                        when (ch) {
                            ARABIC_YEH, ARABIC_ALEF_MAKSURA -> PERSIAN_YEH
                            ARABIC_KAF -> PERSIAN_KEHEH
                            in '\u0660'..'\u0669' -> '0' + (ch - '\u0660')
                            in '\u06F0'..'\u06F9' -> '0' + (ch - '\u06F0')
                            else -> ch
                        }
                    )
                }
            }
        }
        return out.toString().lowercase(Locale.ROOT)
    }

    /** The normalised, non-empty words of [query]. Empty for a blank query. */
    fun tokens(query: String): List<String> =
        normalize(query).split(' ').filter { it.isNotEmpty() }

    /**
     * The rank of [entry] for [tokens] (from [tokens]), or `null` when it does not match.
     * With no tokens every entry matches equally, so a blank query leaves the order to the
     * caller's sort.
     */
    fun rank(entry: WorkspaceSearchEntry, tokens: List<String>): WorkspaceSearchRank? {
        if (tokens.isEmpty()) return WorkspaceSearchRank(0, 0)
        val name = normalize(entry.name)
        val nameWords = words(name)
        val extras = entry.extras.map(::normalize)
        var worst = 0
        var total = 0
        for (token in tokens) {
            val tier = tierOf(token, name, nameWords, extras) ?: return null
            if (tier > worst) worst = tier
            total += tier
        }
        return WorkspaceSearchRank(worst, total)
    }

    private fun tierOf(token: String, name: String, nameWords: List<String>, extras: List<String>): Int? = when {
        name.startsWith(token) -> TIER_NAME_PREFIX
        nameWords.any { it.startsWith(token) } -> TIER_NAME_WORD_PREFIX
        name.contains(token) -> TIER_NAME_SUBSTRING
        extras.any { it.contains(token) } -> TIER_EXTRAS
        else -> null
    }

    /** The runs of letters/digits in an already-normalised [text]. */
    private fun words(text: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        for (ch in text) {
            if (Character.isLetterOrDigit(ch)) {
                current.append(ch)
            } else if (current.isNotEmpty()) {
                result += current.toString()
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }
}
