package com.fem.linexa.domain.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The What's New update-detection rule.
 *
 * This is the whole feature. Every case below is a *sequence over time* — install,
 * update, relaunch, dismiss, update again — which is exactly what cannot be verified by
 * running the app once or by looking at a screenshot: reproducing one of them by hand
 * means building an APK, installing it, building another, and installing that over it.
 * `WhatsNewDecision` is a pure function precisely so the whole sequence is assertable
 * here, and these tests are the reason it takes four parameters instead of reading
 * `BuildConfig` and `SharedPreferences` itself.
 */
class WhatsNewDecisionTest {

    private val notesV3 = ReleaseNotes(
        versionCode = 3,
        versionName = "1.0",
        summary = "A summary.",
        changes = listOf(ReleaseChange(ChangeCategory.FIXED, "Something was fixed."))
    )

    private fun decide(
        acknowledged: Int,
        current: Int,
        notes: ReleaseNotes? = notesV3,
        firstInstall: Boolean = false
    ) = WhatsNewDecision.decide(
        lastAcknowledgedVersionCode = acknowledged,
        currentVersionCode = current,
        notes = notes,
        isFirstInstall = firstInstall
    )

    // ── First installation ─────────────────────────────────────────────────

    @Test
    fun aFreshInstallShowsNothingAndRecordsTheBaseline() {
        // The requirement: a first installation is not an update. But it must not do
        // *nothing* either — with no baseline recorded, the launch after the first real
        // update would have nothing to compare against.
        val outcome = decide(
            acknowledged = WhatsNewDecision.NOTHING_ACKNOWLEDGED,
            current = 3,
            firstInstall = true
        )
        assertEquals(WhatsNewDecision.Outcome.EstablishBaseline(3), outcome)
    }

    @Test
    fun aFreshInstallOfAVersionWithNoNotesStillRecordsTheBaseline() {
        // Ordering guard: the fresh-install branch runs BEFORE the notes check. If it
        // did not, installing a build that ships no changelog would leave nothing
        // stored, and that user's first update would be indistinguishable from a first
        // install — swallowing the changelog the feature exists to show.
        val outcome = decide(
            acknowledged = WhatsNewDecision.NOTHING_ACKNOWLEDGED,
            current = 7,
            notes = null,
            firstInstall = true
        )
        assertEquals(WhatsNewDecision.Outcome.EstablishBaseline(7), outcome)
    }

    @Test
    fun theBaselineIsEstablishedOnlyOnceAndTheNextLaunchIsSilent() {
        // Simulates what the ViewModel does: the baseline write re-emits the stored
        // value, and the decision must settle rather than oscillate.
        val first = decide(
            acknowledged = WhatsNewDecision.NOTHING_ACKNOWLEDGED,
            current = 3,
            firstInstall = true
        )
        val stored = (first as WhatsNewDecision.Outcome.EstablishBaseline).versionCode
        assertEquals(
            WhatsNewDecision.Outcome.Hide,
            decide(acknowledged = stored, current = 3, firstInstall = true)
        )
    }

    // ── Update from an older version ───────────────────────────────────────

    @Test
    fun anUpdateOverAnAcknowledgedVersionShowsTheNewNotes() {
        val outcome = decide(acknowledged = 2, current = 3)
        assertEquals(WhatsNewDecision.Outcome.Show(notesV3), outcome)
    }

    @Test
    fun anUpgradeFromABuildThatPredatesThisFeatureShowsItsNotes() {
        // The real rollout case, and the reason `isFirstInstall` is asked of the OS
        // rather than inferred from stored state: this user has nothing stored (the
        // preference did not exist in the build they were running) yet is unambiguously
        // not a fresh install. Treating "nothing stored" as "fresh install" would make
        // the feature invisible until the SECOND update after it shipped.
        val outcome = decide(
            acknowledged = WhatsNewDecision.NOTHING_ACKNOWLEDGED,
            current = 3,
            firstInstall = false
        )
        assertEquals(WhatsNewDecision.Outcome.Show(notesV3), outcome)
    }

    // ── Same version launched repeatedly ───────────────────────────────────

    @Test
    fun theSameVersionNeverShowsTwice() {
        // Ten launches after the acknowledgement. The card must stay gone — this is the
        // "do not show it on every launch" requirement.
        repeat(10) {
            assertEquals(
                WhatsNewDecision.Outcome.Hide,
                decide(acknowledged = 3, current = 3)
            )
        }
    }

    // ── Dismissal persistence ──────────────────────────────────────────────

    @Test
    fun dismissingTheCardHidesItForThatVersionOnly() {
        // Before: shown. After storing the shown version: hidden. That is dismissal.
        val shown = decide(acknowledged = 2, current = 3)
        assertTrue(shown is WhatsNewDecision.Outcome.Show)
        val acknowledgedCode = (shown as WhatsNewDecision.Outcome.Show).notes.versionCode
        assertEquals(
            WhatsNewDecision.Outcome.Hide,
            decide(acknowledged = acknowledgedCode, current = 3)
        )
    }

    @Test
    fun aNewerVersionReappearsAfterAPreviousOneWasAcknowledged() {
        // The "do not permanently disable the feature after one dismissal" requirement.
        val notesV4 = notesV3.copy(versionCode = 4)
        val outcome = decide(acknowledged = 3, current = 4, notes = notesV4)
        assertEquals(WhatsNewDecision.Outcome.Show(notesV4), outcome)
    }

    // ── Upgrading across several versions at once ───────────────────────────

    @Test
    fun jumpingSeveralVersionsShowsOnlyTheRunningVersionsNotes() {
        // 2 → 5 directly. The card must describe version 5 and must not claim 3 and 4
        // were ever installed: the decision is handed only the running build's notes and
        // never consults the intervening entries.
        val notesV5 = ReleaseNotes(
            versionCode = 5,
            versionName = "1.2",
            summary = "Version five.",
            changes = listOf(ReleaseChange(ChangeCategory.NEW, "A version five feature."))
        )
        val outcome = decide(acknowledged = 2, current = 5, notes = notesV5)
        assertEquals(WhatsNewDecision.Outcome.Show(notesV5), outcome)
        assertEquals(5, (outcome as WhatsNewDecision.Outcome.Show).notes.versionCode)
        assertEquals("Version five.", outcome.notes.summary)
    }

    @Test
    fun acknowledgingAfterAMultiVersionJumpRecordsTheRunningVersion()
    {
        // And not the version in between: after 2 → 5 is dismissed, a later 6 shows and
        // a re-launch of 5 does not.
        val notesV5 = notesV3.copy(versionCode = 5)
        val notesV6 = notesV3.copy(versionCode = 6)
        assertEquals(
            WhatsNewDecision.Outcome.Hide,
            decide(acknowledged = 5, current = 5, notes = notesV5)
        )
        assertEquals(
            WhatsNewDecision.Outcome.Show(notesV6),
            decide(acknowledged = 5, current = 6, notes = notesV6)
        )
    }

    // ── Missing or empty changelog ──────────────────────────────────────────

    @Test
    fun aVersionWithNoChangelogEntryShowsNothing() {
        // A hotfix or a local bump with no catalog entry. Must not show an empty card,
        // and must specifically not substitute another version's notes.
        assertEquals(
            WhatsNewDecision.Outcome.Hide,
            decide(acknowledged = 2, current = 4, notes = null)
        )
    }

    @Test
    fun aVersionWithNoNotesLeavesTheStoredBaselineUntouched() {
        // `Hide` carries no version, which is how "write nothing" is expressed. It
        // matters: consuming the baseline here would compare the NEXT version that does
        // ship notes against this note-less build instead of against what the user last
        // actually saw.
        val outcome = decide(acknowledged = 2, current = 4, notes = null)
        assertTrue(outcome is WhatsNewDecision.Outcome.Hide)
        // Nothing to read off the outcome — asserted structurally so a future
        // `Hide(versionCode)` cannot silently start writing.
        assertNull(
            "Hide must not carry a version to store",
            (outcome as? WhatsNewDecision.Outcome.EstablishBaseline)?.versionCode
        )
    }

    @Test
    fun anEntryWithNeitherSummaryNorChangesIsTreatedAsAbsent() {
        // A catalog typo. It would otherwise render as a titled card with a blank body,
        // which reads as a rendering bug rather than as an empty release.
        val empty = ReleaseNotes(versionCode = 3, versionName = "1.0")
        assertEquals(
            WhatsNewDecision.Outcome.Hide,
            decide(acknowledged = 2, current = 3, notes = empty)
        )
    }

    @Test
    fun anEntryWithOnlyASummaryIsEnoughToShow() {
        // The converse: a release whose notes are one line and no itemized changes is a
        // legitimate release, not an empty one.
        val summaryOnly = ReleaseNotes(
            versionCode = 3,
            versionName = "1.0",
            summary = "Stability fixes."
        )
        assertEquals(
            WhatsNewDecision.Outcome.Show(summaryOnly),
            decide(acknowledged = 2, current = 3, notes = summaryOnly)
        )
    }

    @Test
    fun anEntryWithOnlyChangesIsEnoughToShow() {
        val changesOnly = ReleaseNotes(
            versionCode = 3,
            versionName = "1.0",
            changes = listOf(ReleaseChange(ChangeCategory.NEW, "A thing."))
        )
        assertEquals(
            WhatsNewDecision.Outcome.Show(changesOnly),
            decide(acknowledged = 2, current = 3, notes = changesOnly)
        )
    }

    @Test
    fun aBlankSummaryDoesNotCountAsContent() {
        val blank = ReleaseNotes(versionCode = 3, versionName = "1.0", summary = "   ")
        assertEquals(
            WhatsNewDecision.Outcome.Hide,
            decide(acknowledged = 2, current = 3, notes = blank)
        )
    }

    // ── Downgrades ─────────────────────────────────────────────────────────

    @Test
    fun aDowngradeDoesNotPresentAnOlderReleaseAsNews() {
        // Reachable with `adb install -r -d` and by sideloading an older APK. The rule
        // is strictly-greater rather than not-equal precisely for this.
        val notesV2 = notesV3.copy(versionCode = 2)
        assertEquals(
            WhatsNewDecision.Outcome.Hide,
            decide(acknowledged = 5, current = 2, notes = notesV2)
        )
    }

    // ── The sentinel ───────────────────────────────────────────────────────

    @Test
    fun theSentinelSortsBelowEveryRealVersionCode() {
        // Android requires versionCode >= 1, so 0 can never collide with a genuine
        // acknowledgement. If this were ever changed to a real code, that version's
        // card would silently never appear.
        assertEquals(0, WhatsNewDecision.NOTHING_ACKNOWLEDGED)
    }

    @Test
    fun aNegativeStoredValueIsTreatedAsNothingRecorded() {
        // Defensive: nothing writes a negative code today, but the stored value is a raw
        // Int from disk and "unrecognized" must degrade to the absent case rather than
        // to a comparison that happens to pass.
        assertEquals(
            WhatsNewDecision.Outcome.EstablishBaseline(3),
            decide(acknowledged = -1, current = 3, firstInstall = true)
        )
    }
}
