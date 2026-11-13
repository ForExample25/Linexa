package com.fem.linexa.ui.components

import com.fem.linexa.domain.whatsnew.ChangeCategory
import com.fem.linexa.domain.whatsnew.ReleaseChange
import com.fem.linexa.domain.whatsnew.ReleaseNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the What's New card shows, for changelogs of every length.
 *
 * Same rationale as [WorkspaceCardContentTest] (§1.127(c)): the requirement is that the
 * card "works correctly with both short and long changelogs" and does not overwhelm
 * Home, which is a statement about truncation arithmetic on data — and the only way to
 * see it in the running app is to ship a release with fifteen entries.
 */
class WhatsNewCardContentTest {

    private fun changes(count: Int): List<ReleaseChange> =
        (1..count).map { ReleaseChange(ChangeCategory.NEW, "Change $it") }

    private fun notes(
        changeCount: Int,
        summary: String? = "A summary."
    ) = ReleaseNotes(
        versionCode = 3,
        versionName = "1.0",
        summary = summary,
        changes = changes(changeCount)
    )

    private fun content(
        changeCount: Int,
        expanded: Boolean = false,
        summary: String? = "A summary."
    ) = WhatsNewCardContent.from(
        notes = notes(changeCount, summary),
        expanded = expanded,
        versionLabel = "Version 1.0 (3)"
    )

    // ── Short changelogs ───────────────────────────────────────────────────

    @Test
    fun aShortChangelogIsShownInFullWithNoExpandControl() {
        // At or under the limit there is nothing to reveal, so offering "Show 0 more"
        // would be a control that does nothing.
        (1..WhatsNewCardContent.COLLAPSED_CHANGE_LIMIT).forEach { count ->
            val c = content(changeCount = count)
            assertEquals(count, c.visibleChanges.size)
            assertEquals(0, c.hiddenChangeCount)
            assertFalse("$count changes need no expand control", c.canExpand)
        }
    }

    @Test
    fun aReleaseWithNoItemizedChangesStillRendersItsSummary() {
        val c = content(changeCount = 0)
        assertTrue(c.visibleChanges.isEmpty())
        assertEquals("A summary.", c.summary)
        assertFalse(c.canExpand)
    }

    // ── Long changelogs ────────────────────────────────────────────────────

    @Test
    fun aLongChangelogIsTruncatedSoItDoesNotOverwhelmHome() {
        // The requirement this constant exists for: the card sits above the workspace
        // list, and a fifteen-entry release would push every workspace off the first
        // screen on the one launch after an update.
        val c = content(changeCount = 15)
        assertEquals(WhatsNewCardContent.COLLAPSED_CHANGE_LIMIT, c.visibleChanges.size)
        assertEquals(15 - WhatsNewCardContent.COLLAPSED_CHANGE_LIMIT, c.hiddenChangeCount)
        assertTrue(c.canExpand)
    }

    @Test
    fun theCollapsedExcerptKeepsTheCatalogsOwnOrder() {
        // Not sorted by category: the most significant change in a release is usually
        // the reason for the release, and grouping would bury it under whichever bucket
        // sorted first. So the excerpt must be the FIRST n, in order.
        val c = content(changeCount = 10)
        assertEquals(listOf("Change 1", "Change 2", "Change 3"), c.visibleChanges.map { it.text })
    }

    @Test
    fun expandingRevealsEverythingAndNothingRemainsHidden() {
        val c = content(changeCount = 15, expanded = true)
        assertEquals(15, c.visibleChanges.size)
        assertEquals(0, c.hiddenChangeCount)
        // Still true: the control must remain composed so the list can be collapsed
        // again rather than only ever growing.
        assertTrue(c.canExpand)
        assertTrue(c.isExpanded)
    }

    @Test
    fun expandingAShortChangelogChangesNothing() {
        // Guards against the expanded flag being able to produce a different list when
        // there was never anything withheld.
        val collapsed = content(changeCount = 2, expanded = false)
        val expanded = content(changeCount = 2, expanded = true)
        assertEquals(collapsed.visibleChanges, expanded.visibleChanges)
        assertEquals(0, expanded.hiddenChangeCount)
        assertFalse(expanded.canExpand)
    }

    @Test
    fun oneOverTheLimitHidesExactlyOne() {
        // The boundary. Off-by-one here would either drop a change silently or offer to
        // reveal nothing.
        val count = WhatsNewCardContent.COLLAPSED_CHANGE_LIMIT + 1
        val c = content(changeCount = count)
        assertEquals(1, c.hiddenChangeCount)
        assertTrue(c.canExpand)
    }

    @Test
    fun theHiddenCountAndVisibleListAlwaysAccountForEveryChange() {
        // The invariant behind the "Show N more" label being truthful.
        listOf(0, 1, 3, 4, 15, 40).forEach { count ->
            listOf(false, true).forEach { expanded ->
                val c = content(changeCount = count, expanded = expanded)
                assertEquals(
                    "$count changes, expanded=$expanded must be fully accounted for",
                    count,
                    c.visibleChanges.size + c.hiddenChangeCount
                )
            }
        }
    }

    // ── The summary ────────────────────────────────────────────────────────

    @Test
    fun anAbsentSummaryIsNull() {
        assertNull(content(changeCount = 2, summary = null).summary)
    }

    @Test
    fun aBlankSummaryIsTreatedAsAbsentRatherThanRendered() {
        // Whitespace would still cost the card its summary spacing while showing nothing.
        assertNull(content(changeCount = 2, summary = "   ").summary)
        assertNull(content(changeCount = 2, summary = "").summary)
    }

    @Test
    fun aSummaryIsTrimmedSoStrayWhitespaceDoesNotShiftTheText() {
        assertEquals("Real text.", content(changeCount = 1, summary = "  Real text.  ").summary)
    }

    // ── Pass-through ───────────────────────────────────────────────────────

    @Test
    fun theVersionLabelIsRenderedExactlyAsGiven() {
        // Formatting belongs to AppVersionLabel and the surrounding wording to a string
        // resource; this type must not re-derive or decorate either.
        val c = WhatsNewCardContent.from(
            notes = notes(changeCount = 1),
            expanded = false,
            versionLabel = "Version 2.5 (9)"
        )
        assertEquals("Version 2.5 (9)", c.versionLabel)
    }

    @Test
    fun everyCategorySurvivesToTheCard() {
        // The pill's `when` is exhaustive over the enum, so a category that could not
        // reach the card would be a silent gap.
        val all = ChangeCategory.entries.map { ReleaseChange(it, "text for $it") }
        val c = WhatsNewCardContent.from(
            notes = ReleaseNotes(
                versionCode = 3,
                versionName = "1.0",
                changes = all
            ),
            expanded = true,
            versionLabel = "Version 1.0 (3)"
        )
        assertEquals(
            ChangeCategory.entries.toSet(),
            c.visibleChanges.map { it.category }.toSet()
        )
    }

    @Test
    fun theCollapsedLimitLeavesRoomForTheWorkspaceListBelow() {
        // Stated as an assertion because it is a product decision, not an implementation
        // detail: a limit high enough to fill the screen would defeat the truncation.
        assertTrue(
            "a collapsed card showing more than 5 changes would fill Home's first screen",
            WhatsNewCardContent.COLLAPSED_CHANGE_LIMIT in 1..5
        )
    }
}
