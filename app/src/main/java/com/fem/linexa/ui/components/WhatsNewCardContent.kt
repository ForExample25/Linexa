package com.fem.linexa.ui.components

import com.fem.linexa.domain.whatsnew.ReleaseChange
import com.fem.linexa.domain.whatsnew.ReleaseNotes

/**
 * Everything the What's New card *says*, computed as a pure function of the
 * [ReleaseNotes] it renders.
 *
 * Same split, and the same reason, as [com.fem.linexa.ui.components.WorkspaceCardContent]
 * (§1.127(c)): the composable owns where things go, this type owns what is present at
 * all. The decisions worth asserting are the ones a real changelog produces — a
 * release with no summary, a release with more changes than belong on a Home card —
 * and there is no Compose UI harness in this repo (§1.124(a)) to check them in place.
 */
data class WhatsNewCardContent(
    /** The version being described, formatted for the card's subtitle (`Version 1.0 (3)`). */
    val versionLabel: String,
    /** The one-line summary, or null when the release declared none. */
    val summary: String?,
    /**
     * The changes to render right now — at most [COLLAPSED_CHANGE_LIMIT] of them while
     * collapsed, all of them once expanded.
     */
    val visibleChanges: List<ReleaseChange>,
    /**
     * How many changes are being withheld, or 0 when everything is shown.
     *
     * Carried as a count rather than a boolean because the expand affordance names it
     * ("Show 4 more"), which tells the user whether tapping is worth it.
     */
    val hiddenChangeCount: Int,
    /**
     * Whether the expand/collapse control should be composed at all.
     *
     * False for a short changelog *and* for an already-fully-expanded one — in the
     * latter case a collapse control would be offered instead, which is why the card
     * reads [isExpanded] as well.
     */
    val canExpand: Boolean,
    /** Whether the card is currently showing everything. Drives the control's label. */
    val isExpanded: Boolean
) {
    companion object {

        /**
         * How many changes a collapsed card shows.
         *
         * Three, because the requirement is that a long changelog must not overwhelm
         * Home — and Home's job is the workspace list, which this card sits above. A
         * release with fifteen entries would otherwise push every workspace off the
         * first screen on the one launch after an update. Three lines is enough to
         * convey what a release was for while leaving the list visible; the rest are
         * one tap away.
         */
        const val COLLAPSED_CHANGE_LIMIT: Int = 3

        /**
         * @param notes the release to render.
         * @param expanded whether the user has tapped to see everything.
         * @param versionLabel the already-formatted version string. Passed in rather
         *   than built here because the formatting rules live in
         *   [com.fem.linexa.ui.screens.settings.AppVersionLabel], which is already
         *   tested, and because the surrounding "Version …" wording is a string
         *   resource that only the composable can resolve.
         */
        fun from(
            notes: ReleaseNotes,
            expanded: Boolean,
            versionLabel: String
        ): WhatsNewCardContent {
            val overflows = notes.changes.size > COLLAPSED_CHANGE_LIMIT
            val visible = if (expanded || !overflows) {
                notes.changes
            } else {
                notes.changes.take(COLLAPSED_CHANGE_LIMIT)
            }
            return WhatsNewCardContent(
                versionLabel = versionLabel,
                // Blank is treated as absent, not rendered: a summary line of
                // whitespace would still cost the card its vertical spacing.
                summary = notes.summary?.trim()?.takeIf { it.isNotEmpty() },
                visibleChanges = visible,
                hiddenChangeCount = notes.changes.size - visible.size,
                canExpand = overflows,
                isExpanded = expanded
            )
        }
    }
}
