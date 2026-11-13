package com.fem.linexa.service.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.124 — the pure decode-planning half of rendering a declared preview.
 *
 * This exists as its own object precisely so it can be tested here: the decode
 * itself is `BitmapFactory`, which is a throwing stub on the JVM, and this repo
 * runs no instrumented tests. The properties worth pinning are the two that
 * protect the process (bounds rejection before allocation) and the one that
 * decides image quality (sample size never dropping below the target).
 */
class RootFsPreviewScalingTest {

    // ── isDecodable: the pre-allocation gate ────────────────────────────

    @Test
    fun anOrdinaryPreviewIsDecodable() {
        assertTrue(RootFsPreviewScaling.isDecodable(512, 512))
        assertTrue(RootFsPreviewScaling.isDecodable(1920, 1080))
    }

    @Test
    fun zeroDimensionsAreNotDecodable() {
        // This is how BitmapFactory reports "these bytes are not an image":
        // outWidth/outHeight stay -1 (or 0) after a bounds-only pass. A `.png`
        // that is really a text file or a truncated download lands here.
        assertFalse(RootFsPreviewScaling.isDecodable(0, 0))
        assertFalse(RootFsPreviewScaling.isDecodable(512, 0))
        assertFalse(RootFsPreviewScaling.isDecodable(0, 512))
        assertFalse(RootFsPreviewScaling.isDecodable(-1, -1))
    }

    @Test
    fun anAbsurdlyLargeHeaderIsRejectedBeforeAnyPixelIsRead() {
        // The DoS this gate exists for: a ~40-byte PNG header can declare
        // 60000x60000, which BitmapFactory would try to allocate as ~14 GB.
        // The rootfs is guest-writable, so this is reachable from an installed
        // image at any time after import.
        assertFalse(RootFsPreviewScaling.isDecodable(60_000, 60_000))
    }

    @Test
    fun theLimitIsInclusiveAtItsBoundary() {
        // 50 MP exactly is allowed; one pixel more is not. Pinned because an
        // off-by-one here is invisible in practice and would only ever be
        // noticed as an inconsistency between the check and its documentation.
        assertTrue(RootFsPreviewScaling.isDecodable(10_000, 5_000))
        assertFalse(RootFsPreviewScaling.isDecodable(10_000, 5_001))
    }

    @Test
    fun theLimitIsComputedInLongArithmetic() {
        // 100000 * 100000 overflows Int to a *negative* number, which a naive
        // `w * h <= MAX` written in Int would happily accept — the single most
        // likely way for this gate to be silently bypassed.
        assertFalse(RootFsPreviewScaling.isDecodable(100_000, 100_000))
    }

    // ── sampleSizeFor: quality vs memory ───────────────────────────────

    @Test
    fun aSourceSmallerThanTheTileIsDecodedAtFullSize() {
        assertEquals(1, RootFsPreviewScaling.sampleSizeFor(64, 64, 156))
    }

    @Test
    fun aSourceAtTheTargetIsNotDownsampled() {
        assertEquals(1, RootFsPreviewScaling.sampleSizeFor(156, 156, 156))
    }

    @Test
    fun aLargeSourceIsDownsampledToAPowerOfTwo() {
        // 2048 -> /8 = 256, still >= 156; /16 = 128 would be smaller than the
        // tile and is therefore not taken.
        assertEquals(8, RootFsPreviewScaling.sampleSizeFor(2048, 2048, 156))
    }

    @Test
    fun theDecodedBitmapIsNeverSmallerThanTheTile() {
        // The property that matters visually: whatever the source size, the
        // sampled result must still cover the tile, or the avatar renders soft.
        val target = 156
        for (source in listOf(157, 200, 311, 313, 512, 1000, 2048, 4096, 8000)) {
            val sample = RootFsPreviewScaling.sampleSizeFor(source, source, target)
            assertTrue(
                "source=$source sample=$sample produced ${source / sample}px for a ${target}px tile",
                source / sample >= target
            )
        }
    }

    @Test
    fun theSampleStepIsBoundedByTheSmallerDimension() {
        // A wide banner (4096x200) must not be sampled on its width alone —
        // /16 would leave the height at 12px for a 156px tile.
        val sample = RootFsPreviewScaling.sampleSizeFor(4096, 200, 156)
        assertEquals(1, sample)
    }

    @Test
    fun degenerateInputsYieldALegalSampleSize() {
        // 0 is not a legal inSampleSize; the framework treats <1 as 1 anyway,
        // but returning it would be us relying on that.
        assertEquals(1, RootFsPreviewScaling.sampleSizeFor(0, 0, 156))
        assertEquals(1, RootFsPreviewScaling.sampleSizeFor(512, 512, 0))
        assertEquals(1, RootFsPreviewScaling.sampleSizeFor(-5, -5, 156))
    }

    @Test
    fun everySampleSizeIsAPowerOfTwo() {
        // BitmapFactory rounds any other value down to a power of two, so a
        // non-power-of-two here would mean our arithmetic disagrees with what
        // the decoder actually did.
        for (source in listOf(1, 100, 156, 157, 300, 999, 2048, 5000, 12_000)) {
            val sample = RootFsPreviewScaling.sampleSizeFor(source, source, 156)
            assertTrue("sample=$sample is not a power of two", sample > 0 && (sample and (sample - 1)) == 0)
        }
    }

    // ── cacheKeyOf: distinctness ───────────────────────────────────────

    @Test
    fun theCacheKeyChangesWhenTheFileIsRewritten() {
        val a = RootFsPreviewScaling.cacheKeyOf("/w1/rootfs/opt/preview.png", 1_000L, 512L)
        assertNotEquals(a, RootFsPreviewScaling.cacheKeyOf("/w1/rootfs/opt/preview.png", 2_000L, 512L))
        assertNotEquals(a, RootFsPreviewScaling.cacheKeyOf("/w1/rootfs/opt/preview.png", 1_000L, 999L))
    }

    @Test
    fun twoWorkspacesNeverShareACacheKey() {
        // Same relative path, same size, same mtime — which is the *expected*
        // shape when the same image is installed twice. The absolute path is
        // what keeps the decoded bitmaps distinct.
        val w1 = RootFsPreviewScaling.cacheKeyOf("/data/workspaces/w1/rootfs/opt/tiny/preview.png", 5L, 64L)
        val w2 = RootFsPreviewScaling.cacheKeyOf("/data/workspaces/w2/rootfs/opt/tiny/preview.png", 5L, 64L)
        assertNotEquals(w1, w2)
    }

    @Test
    fun anUnchangedFileKeepsAStableKey() {
        assertEquals(
            RootFsPreviewScaling.cacheKeyOf("/w1/p.png", 7L, 8L),
            RootFsPreviewScaling.cacheKeyOf("/w1/p.png", 7L, 8L)
        )
    }
}
