package com.fem.linexa.domain.update

/**
 * Semantic-ish comparison of human version strings — the decision core of the
 * in-app update checker.
 *
 * A release tag and a `versionName` are DISPLAY strings; string equality is
 * not enough to order them. Real release tags carry prefixes and
 * pre-release suffixes the installed `versionName` may spell differently
 * (`v0.1.0-beta` tag vs `0.1.0-beta` `versionName`), and a plain string
 * compare would order `"2.0.0" < "1.1.0"` and call the same version an
 * update. This comparator is deliberately small and pure so every one of
 * those cases is a one-line test.
 *
 * Grammar it accepts (everything after the first digit run is the suffix):
 *
 *     [anything] <digits ("." digits)*> [suffix]
 *
 *   * Leading non-digit characters are dropped, which strips the conventional
 *     `v`/`V` tag prefix and release-name prefixes ("Linexa 0.1.0-beta")
 *     without special-casing either.
 *   * The numeric core is compared component-wise; missing components read as
 *     zero, so `1.0` == `1.0.0` and `1.10 > 1.2`.
 *   * A suffix (pre-release marker) sorts BEFORE no suffix, so
 *     `1.0.0 > 1.0.0-beta`: a stable release always outranks its own beta.
 *   * Two suffixes are compared run-wise, digits numerically and letters
 *     lexicographically (`beta2 > beta1`, `rc > beta`).
 *
 * Unparseable input (no digits at all) sorts as the LOWEST version, never as
 * an update: an unreadable remote tag must fail toward "no update offered",
 * not toward "install this".
 */
object AppVersionComparator {

    private const val LOWER = -1
    private const val EQUAL = 0
    private const val HIGHER = 1

    /**
     * Negative when [a] sorts before [b], positive when after, zero when the
     * two spellings denote the same version.
     */
    fun compare(a: String, b: String): Int {
        val left = parse(a)
        val right = parse(b)

        val core = compareCores(left.core, right.core)
        if (core != EQUAL) return core
        return compareSuffixes(left.suffix, right.suffix)
    }

    /** True when [candidate] names a strictly NEWER version than [current]. */
    fun isNewer(candidate: String, current: String): Boolean =
        compare(candidate, current) == HIGHER

    /** True when the two spellings denote the same version. */
    fun isSame(candidate: String, current: String): Boolean =
        compare(candidate, current) == EQUAL

    /**
     * True when [version] carries any numeric component at all — the minimum
     * an orderable version needs. Tags like `latest` or `nightly` are NOT
     * parseable, and callers must treat them as "no update", never as a
     * comparable version.
     */
    fun isParseable(version: String): Boolean =
        version.indexOfFirst { it.isDigit() } >= 0

    // ── Parsing ────────────────────────────────────────────────────────────

    private data class Parsed(val core: List<Long>, val suffix: String?)

    private fun parse(raw: String): Parsed {
        val text = raw.trim()
        val firstDigit = text.indexOfFirst { it.isDigit() }
        if (firstDigit < 0) return Parsed(emptyList(), null)
        val body = text.substring(firstDigit)

        // The numeric core is the leading run of digits and separators that
        // form dot-components; the rest — from the first character that is
        // neither a digit nor a dot following a digit — is the suffix.
        val core = mutableListOf<Long>()
        var index = 0
        while (index < body.length) {
            val digitStart = index
            while (index < body.length && body[index].isDigit()) index++
            if (index == digitStart) break
            core.add(body.substring(digitStart, index).toLongOrNull() ?: 0L)
            if (index < body.length && body[index] == '.') index++ else break
        }
        val suffix = if (index < body.length) body.substring(index) else null
        return Parsed(core, suffix?.trimStart('-', '.', '_', '+', ' ')?.takeIf { it.isNotEmpty() })
    }

    // ── Comparison ─────────────────────────────────────────────────────────

    private fun compareCores(a: List<Long>, b: List<Long>): Int {
        val width = maxOf(a.size, b.size)
        for (i in 0 until width) {
            val left = a.getOrElse(i) { 0L }
            val right = b.getOrElse(i) { 0L }
            if (left != right) return if (left < right) LOWER else HIGHER
        }
        return EQUAL
    }

    private fun compareSuffixes(a: String?, b: String?): Int {
        if (a == b) return EQUAL
        if (a == null) return HIGHER // stable > pre-release of the same core
        if (b == null) return LOWER
        return compareRuns(a, b)
    }

    /** Digit runs compare numerically, letter runs lexicographically. */
    private fun compareRuns(a: String, b: String): Int {
        val left = tokenize(a)
        val right = tokenize(b)
        val width = maxOf(left.size, right.size)
        for (i in 0 until width) {
            val l = left.getOrNull(i) ?: return LOWER // "beta" < "beta1"
            val r = right.getOrNull(i) ?: return HIGHER // "beta" < "beta1"
            when {
                l.isNumeric && r.isNumeric -> {
                    val delta = l.text.toLong().compareTo(r.text.toLong())
                    if (delta < 0L) return LOWER
                    if (delta > 0L) return HIGHER
                }
                l.isNumeric -> return HIGHER // numeric runs outrank alphabetic ones
                r.isNumeric -> return LOWER
                else -> when {
                    // String.compareTo's raw sign is NOT the result: callers
                    // (isNewer) test against the normalized HIGHER constant.
                    l.text < r.text -> return LOWER
                    l.text > r.text -> return HIGHER
                    // Same spelling — keep scanning the next run.
                }
            }
        }
        return EQUAL
    }

    private data class Run(val text: String, val isNumeric: Boolean)

    /** Maximal digit runs and maximal letter runs — separators are dropped. */
    private fun tokenize(suffix: String): List<Run> {
        val runs = mutableListOf<Run>()
        var index = 0
        while (index < suffix.length) {
            val isDigitRun = suffix[index].isDigit()
            val start = index
            while (index < suffix.length && suffix[index].isDigit() == isDigitRun) index++
            val text = suffix.substring(start, index).filter { it.isLetterOrDigit() }
            if (text.isNotEmpty()) runs.add(Run(text, isDigitRun))
        }
        return runs
    }
}
