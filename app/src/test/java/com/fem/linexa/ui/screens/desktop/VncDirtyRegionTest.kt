package com.fem.linexa.ui.screens.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.147 — JVM tests for [VncDirtyRegion], the scoped-upload bookkeeping, extended
 * by §1.154 to the per-generation release.
 *
 * These exist for the same reason [VncPixelsTest] does: every defect here is
 * *silent*. An under-reported region does not throw or log — it leaves stale
 * pixels on a desktop that is otherwise connected and responsive, which reads as
 * "VNC is glitchy" rather than as a bug with a location. The properties asserted
 * below are the ones whose violation produces exactly that symptom:
 *
 *  - a region survives frames the `StateFlow` conflates away;
 *  - a late acknowledgement releases what it covers and **nothing newer** (§1.154 —
 *    the two halves of that sentence are the two failure modes, one a wrong screen
 *    and the other the audit's §7.2 ratchet);
 *  - a resize reports the whole framebuffer;
 *  - "nothing described" is distinguishable from "nothing changed", because the
 *    consumer must read the former as *upload everything*.
 */
class VncDirtyRegionTest {

    private fun region(width: Int = 100, height: Int = 100) =
        VncDirtyRegion().apply { resize(width, height) }

    /**
     * Publishes and immediately acknowledges [generation], leaving the region
     * clean.
     *
     * Most cases below are about what happens to a *subsequent* rectangle, and a
     * fresh region is fully dirty by construction ([resize]), which would swallow
     * every later union into a full-framebuffer box. This is the "consumer has
     * caught up" starting state.
     */
    private fun VncDirtyRegion.settle(generation: Long) {
        publish(generation)
        acknowledge(generation)
    }

    // ---- resize ----

    @Test
    fun resizeMarksTheWholeFramebufferDirty() {
        // The consumer reallocates its bitmap on a geometry change, so it holds no
        // pixels at all — a scoped upload would leave the remainder transparent.
        assertEquals(VncRect(0, 0, 80, 60), region(80, 60).peek())
    }

    @Test
    fun resizeDiscardsRegionsFromThePreviousGeometry() {
        val dirty = region(100, 100)
        dirty.union(90, 90, 10, 10)
        dirty.resize(40, 40)
        // Not (0,0,100,100) clipped — the old union's coordinates are meaningless
        // against the new bounds, and the new bounds are fully dirty anyway.
        assertEquals(VncRect(0, 0, 40, 40), dirty.peek())
    }

    @Test
    fun unionBeforeAnyGeometryIsKnownContributesNothing() {
        // No bounds means nothing can be clamped, and a region that cannot be
        // clamped must not be trusted. ServerInit always resizes first.
        val dirty = VncDirtyRegion()
        dirty.union(0, 0, 10, 10)
        assertTrue(dirty.peek().isEmpty)
    }

    // ---- union ----

    @Test
    fun unionOfTwoRectanglesIsTheirBoundingBox() {
        val dirty = region()
        dirty.settle(1)
        dirty.union(10, 10, 5, 5)
        dirty.union(50, 20, 10, 30)
        assertEquals(VncRect(10, 10, 50, 40), dirty.peek())
    }

    @Test
    fun unionClipsToTheFramebuffer() {
        val dirty = region(100, 100)
        dirty.settle(1)
        dirty.union(90, 90, 50, 50)
        // Clipped rather than rejected: the pixels that DID land still need
        // uploading — the same rule VncPixels.decodeArgbInto follows.
        assertEquals(VncRect(90, 90, 10, 10), dirty.peek())
    }

    @Test
    fun aFullyOutOfBoundsRectangleContributesNothing() {
        val dirty = region(100, 100)
        dirty.settle(1)
        dirty.union(200, 200, 10, 10)
        assertTrue(dirty.peek().isEmpty)
    }

    @Test
    fun anEmptyRectangleContributesNothing() {
        val dirty = region()
        dirty.settle(1)
        dirty.union(10, 10, 0, 5)
        dirty.union(10, 10, 5, 0)
        assertTrue(dirty.peek().isEmpty)
    }

    @Test
    fun aNegativeOriginIsClampedRatherThanShiftingTheRegion() {
        val dirty = region()
        dirty.settle(1)
        dirty.union(-10, -10, 20, 20)
        assertEquals(VncRect(0, 0, 10, 10), dirty.peek())
    }

    // ---- publish / acknowledge ----

    @Test
    fun acknowledgingThePublishedGenerationClearsTheRegion() {
        val dirty = region()
        dirty.settle(1)
        dirty.union(10, 10, 5, 5)
        val published = dirty.publish(2)
        assertEquals(VncRect(10, 10, 5, 5), published)
        dirty.acknowledge(2)
        assertTrue(dirty.peek().isEmpty)
    }

    @Test
    fun aLateAcknowledgementReleasesItsOwnRegionAndKeepsTheNewerOne() {
        // The load-bearing property, and the §1.154 revision of it. The consumer is
        // paced, so it can acknowledge generation N after the producer has already
        // published N+1. Releasing everything would drop N+1's rectangles and leave
        // those pixels stale forever; releasing nothing — §1.147's behaviour — leaves
        // N's in place too, and because the box only grows, every later upload is
        // larger, which widens the very interval that caused the miss. That ratchet is
        // the audit's §7.2 ghosting mechanism.
        val dirty = region()
        dirty.settle(1)
        dirty.union(10, 10, 5, 5)
        dirty.publish(2)
        dirty.union(60, 60, 5, 5)
        dirty.publish(3)

        dirty.acknowledge(2)

        assertEquals(VncRect(60, 60, 5, 5), dirty.peek())
    }

    @Test
    fun regionsAccumulateAcrossFramesTheConsumerNeverSaw() {
        // StateFlow conflates and the consumer is deliberately slower than the
        // producer, so unacknowledged updates MUST keep accumulating. A per-frame
        // dirty rectangle would describe only the last update of the burst.
        val dirty = region()
        dirty.settle(1)
        dirty.union(0, 0, 5, 5)
        dirty.publish(2)
        dirty.union(95, 95, 5, 5)
        dirty.publish(3)
        dirty.union(40, 40, 5, 5)

        assertEquals(VncRect(0, 0, 100, 100), dirty.publish(4))
    }

    @Test
    fun publishingWithNoChangeReportsAnEmptyRegion() {
        val dirty = region()
        dirty.settle(1)
        assertTrue(dirty.publish(2).isEmpty)
    }

    @Test
    fun acknowledgingTwiceIsHarmless() {
        val dirty = region()
        dirty.settle(1)
        dirty.union(10, 10, 5, 5)
        dirty.publish(2)
        dirty.acknowledge(2)
        dirty.acknowledge(2)
        assertTrue(dirty.peek().isEmpty)
    }

    @Test
    fun aRegionUnionedAfterItsAcknowledgementSurvives() {
        // The producer/consumer race is self-healing only if this holds: pixels
        // written while an upload was in flight are re-reported afterwards.
        val dirty = region()
        dirty.union(10, 10, 5, 5)
        dirty.publish(2)
        dirty.acknowledge(2)
        dirty.union(20, 20, 5, 5)
        assertEquals(VncRect(20, 20, 5, 5), dirty.peek())
    }

    // ---- §1.153: the acknowledgement reports whether it released anything ----

    @Test
    fun anAppliedAcknowledgementReportsTrue() {
        val dirty = region()
        dirty.settle(1)
        dirty.union(10, 10, 5, 5)
        dirty.publish(2)
        assertTrue(dirty.acknowledge(2))
    }

    @Test
    fun aLateAcknowledgementStillReportsTrueBecauseItReleasedItsOwnGeneration() {
        // §1.154 changes what this call means. Under §1.147 it returned false and
        // released nothing, which is the ghosting ratchet; now the older generation is
        // retired and only the newer one is held, so the honest answer is true. The
        // counter's meaning narrows with it — see VncRenderStats.recordAcknowledge.
        val dirty = region()
        dirty.settle(1)
        dirty.union(10, 10, 5, 5)
        dirty.publish(2)
        dirty.union(60, 60, 5, 5)
        dirty.publish(3)
        assertTrue(dirty.acknowledge(2))
        assertEquals(1, dirty.pendingGenerations())
    }

    @Test
    fun aRepeatedAcknowledgementReportsFalseBecauseNothingWasLeftToRelease() {
        // Not a failure, and not counted as the audit's mechanism either: the first
        // call retired the generation, so the second is bookkeeping noise. It reports
        // false because false now means "released nothing", which is what it did.
        val dirty = region()
        dirty.settle(1)
        dirty.union(10, 10, 5, 5)
        dirty.publish(2)
        assertTrue(dirty.acknowledge(2))
        assertFalse(dirty.acknowledge(2))
    }

    @Test
    fun anAcknowledgementBeforeAnyPublishReportsFalse() {
        val dirty = region()
        assertFalse(dirty.acknowledge(0))
    }

    // ---- §1.154: the per-generation history ----

    @Test
    fun eachPublishOwesItsOwnGenerationUntilAcknowledged() {
        val dirty = region()
        dirty.settle(1)
        dirty.union(10, 10, 5, 5)
        dirty.publish(2)
        dirty.union(60, 60, 5, 5)
        dirty.publish(3)
        assertEquals(2, dirty.pendingGenerations())
    }

    @Test
    fun aPublishWithNothingNewDoesNotOweAnExtraGeneration() {
        // Otherwise a producer that published on an empty update would add an entry
        // that no acknowledgement of an earlier generation could release, and the
        // history would fill with empty boxes.
        val dirty = region()
        dirty.settle(1)
        dirty.union(10, 10, 5, 5)
        dirty.publish(2)
        dirty.publish(3)
        assertEquals(1, dirty.pendingGenerations())
    }

    @Test
    fun acknowledgingTheNewestGenerationReleasesEveryOlderOne() {
        // The consumer uploads the union publish() returned, which covers every
        // generation up to that point, so acknowledging it must retire all of them —
        // not just the newest, or the older boxes would be uploaded forever.
        val dirty = region()
        dirty.settle(1)
        dirty.union(10, 10, 5, 5)
        dirty.publish(2)
        dirty.union(60, 60, 5, 5)
        dirty.publish(3)

        assertTrue(dirty.acknowledge(3))

        assertEquals(0, dirty.pendingGenerations())
        assertTrue(dirty.peek().isEmpty)
    }

    @Test
    fun rectanglesArrivingAfterThePublishAreNotReleasedByItsAcknowledgement() {
        // The self-healing half of the framebuffer race: pixels blitted while the
        // upload was in flight were never offered to the consumer, so its
        // acknowledgement must not retire them.
        val dirty = region()
        dirty.settle(1)
        dirty.union(10, 10, 5, 5)
        dirty.publish(2)
        dirty.union(70, 70, 5, 5)

        dirty.acknowledge(2)

        assertEquals(VncRect(70, 70, 5, 5), dirty.peek())
    }

    @Test
    fun anUnpublishedRectangleIsNotReleasedByAnAcknowledgementOfAFutureGeneration() {
        // A consumer cannot acknowledge what it was never handed. Sweeping by
        // generation alone — rather than by what was published — would clear the open
        // entry here and lose those pixels permanently.
        val dirty = region()
        dirty.settle(1)
        dirty.union(30, 30, 5, 5)

        dirty.acknowledge(99)

        assertEquals(VncRect(30, 30, 5, 5), dirty.peek())
    }

    @Test
    fun theUploadedRegionShrinksAsAcknowledgementsCatchUp() {
        // The property the whole §1.154 change exists for. Under §1.147 the box
        // published here never got smaller while acknowledgements kept losing the
        // race, and a monotonically growing box is a monotonically growing upload.
        val dirty = region()
        dirty.settle(1)
        dirty.union(0, 0, 10, 10)
        dirty.publish(2)
        dirty.union(90, 90, 10, 10)
        val wide = dirty.publish(3)
        assertEquals(VncRect(0, 0, 100, 100), wide)

        dirty.acknowledge(2)

        assertEquals(VncRect(90, 90, 10, 10), dirty.publish(4))
    }

    @Test
    fun aBurstDeeperThanTheHistoryCollapsesConservativelyRatherThanLosingPixels() {
        // Overflow keeps coverage and loses precision: the oldest two entries merge
        // and the merged box takes the newer generation, so acknowledging the oldest
        // no longer releases it. That is §1.147's over-reporting, which is a cost —
        // the alternative, dropping the oldest box, is a wrong screen.
        val dirty = region()
        dirty.settle(1)
        repeat(40) { i ->
            dirty.union(i, i, 2, 2)
            dirty.publish(2L + i)
        }

        dirty.acknowledge(2)

        // Generation 2's rectangle was merged forward, so it is still owed.
        val owed = dirty.peek()
        assertEquals(0, owed.x)
        assertEquals(0, owed.y)
        assertEquals(41, owed.width)
        assertEquals(41, owed.height)
    }

    @Test
    fun theHistoryIsBoundedNoMatterHowFarTheConsumerFallsBehind() {
        val dirty = region()
        dirty.settle(1)
        repeat(500) { i ->
            dirty.union(i % 100, i % 100, 1, 1)
            dirty.publish(2L + i)
        }
        assertTrue("the pending history must stay bounded", dirty.pendingGenerations() <= 16)
    }

    @Test
    fun acknowledgingTheNewestGenerationAfterACollapseStillClearsEverything() {
        // The collapse must not leave an entry no acknowledgement can reach: if it
        // did, the region would be permanently non-empty and every upload full-frame,
        // which is worse than the defect being fixed.
        val dirty = region()
        dirty.settle(1)
        repeat(40) { i ->
            dirty.union(i, i, 2, 2)
            dirty.publish(2L + i)
        }

        assertTrue(dirty.acknowledge(41))

        assertEquals(0, dirty.pendingGenerations())
        assertTrue(dirty.peek().isEmpty)
    }

    @Test
    fun aResizeDiscardsThePendingHistoryWithTheOldGeometry() {
        // Those coordinates were measured against bounds that no longer exist, and
        // the new geometry is fully dirty anyway — so keeping them could only produce
        // a box that is wrong rather than merely large.
        val dirty = region()
        dirty.union(90, 90, 10, 10)
        dirty.publish(2)
        dirty.resize(40, 40)

        assertEquals(0, dirty.pendingGenerations())
        assertEquals(VncRect(0, 0, 40, 40), dirty.peek())
    }

    // ---- VncRect ----

    @Test
    fun emptyIsTheUndescribedSentinelAndReadsAsEmpty() {
        // The consumer reads EMPTY as "upload everything", so it must be
        // recognisable — not merely a zero-area rectangle somewhere.
        assertTrue(VncRect.EMPTY.isEmpty)
        assertEquals(0, VncRect.EMPTY.width)
        assertEquals(0, VncRect.EMPTY.height)
    }

    @Test
    fun aRectangleWithAreaIsNotEmpty() {
        assertFalse(VncRect(0, 0, 1, 1).isEmpty)
    }

    @Test
    fun aZeroOrNegativeExtentRectangleIsEmpty() {
        assertTrue(VncRect(5, 5, 0, 10).isEmpty)
        assertTrue(VncRect(5, 5, 10, 0).isEmpty)
        assertTrue(VncRect(5, 5, -1, 10).isEmpty)
    }
}
