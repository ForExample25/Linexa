package com.fem.linexa.domain.whatsnew

/**
 * What one released version changed, as data.
 *
 * This is the shape the What's New card renders, and it is deliberately a plain
 * Kotlin type in `domain/` with no Android and no Compose on it: the interesting
 * cases (a release with no summary, a release with twenty changes, a version the
 * catalog has no entry for) are *data* cases, and this repo has no Compose UI test
 * harness (§1.124(a)). Keeping the payload pure is what lets every one of them be
 * asserted on the JVM instead of inspected in a screenshot of whichever build the
 * developer happens to have installed.
 *
 * Deliberately **not** carrying string resources. A [ChangeCategory] is an enum with
 * no `@StringRes` on it and the entry text is plain literal prose, so this file never
 * imports `R` — see [ChangeCategory] for why that split is load-bearing rather than
 * stylistic.
 */
data class ReleaseNotes(
    /**
     * The `versionCode` this entry describes — the authoritative update identifier.
     *
     * `versionCode`, never `versionName`, for the same reason Android itself uses it
     * to decide whether an APK may replace another: it is monotonic and unique per
     * build, whereas `versionName` is a display string that two different builds can
     * and do share (every Linexa build so far is named `1.0`). Matching on the name
     * would make two releases indistinguishable to this feature.
     */
    val versionCode: Int,
    /**
     * The human-readable version, as shown in the card's header (`1.0`).
     *
     * Stored per entry rather than read from `BuildConfig` at render time: the card
     * describes *the version this entry is about*, and those are only the same value
     * while the entry being shown is the running build's. Keeping it in the data
     * means a future "see previous releases" view cannot mislabel an older entry.
     */
    val versionName: String,
    /**
     * One line stating what the release is *for*, or null when there is nothing to
     * say beyond the changes themselves.
     *
     * Optional on purpose. A summary that merely restates the first change entry is
     * worse than no summary — it costs a line of the card and adds no information —
     * so the card omits it entirely rather than rendering a placeholder.
     */
    val summary: String? = null,
    /**
     * The individual changes, in the order they should be read.
     *
     * Order is the catalog author's, not sorted by [ChangeCategory]: the most
     * significant change in a release is usually the reason for the release, and
     * grouping by category would bury it under whichever bucket happened to sort
     * first. May be empty — see [hasContent].
     */
    val changes: List<ReleaseChange> = emptyList()
) {
    /**
     * Whether this entry says anything at all.
     *
     * The card must never appear empty. An entry with no summary and no changes is a
     * catalog mistake rather than a legitimate state, but it is a mistake that would
     * otherwise render as a titled card with a blank body, so it is treated as
     * absent — see `WhatsNewDecision`, which refuses to show it.
     */
    val hasContent: Boolean
        get() = changes.isNotEmpty() || !summary.isNullOrBlank()
}

/**
 * A single changelog line, and which kind of change it is.
 *
 * The category is a required field rather than a nullable one because every change
 * genuinely is one of the four kinds, and "uncategorized" would immediately become
 * the path of least resistance when adding an entry — leaving the card with a mix of
 * labelled and unlabelled lines, which reads as a rendering bug.
 */
data class ReleaseChange(
    val category: ChangeCategory,
    val text: String
)

/**
 * The four kinds of change a release can contain.
 *
 * **No `@StringRes` label on the enum, on purpose.** Putting one here would drag `R`
 * into `domain/` and, more importantly, would make this type un-instantiable in a
 * plain JVM test — resource ids are generated per variant and are not meaningfully
 * on the unit-test classpath. The label lookup lives in the UI layer
 * (`WhatsNewCard`), which is the layer that already owns every other
 * `stringResource` call. This mirrors the split `HomeChromeState` uses in the other
 * direction, and the reasoning `AppVersionLabel` gives for taking its values as
 * parameters rather than reading `BuildConfig` itself.
 *
 * Persisted nowhere, so unlike `ThemeMode` these entries carry no stable string key:
 * the catalog is compiled into the build that ships it, and nothing reads a category
 * back off disk.
 */
enum class ChangeCategory {
    /** Something that did not exist before. */
    NEW,

    /** Something that existed and now works better. */
    IMPROVED,

    /** Something that was broken and no longer is. */
    FIXED,

    /** Something that behaves differently than it used to — including removals. */
    CHANGED
}
