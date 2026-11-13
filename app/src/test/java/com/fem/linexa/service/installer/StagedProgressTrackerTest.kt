package com.fem.linexa.service.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #5 (2026-09-03) — the staged weighted monotonic progress model.
 * These tests pin the exact regressions the field reported: the bar must
 * never go backwards within an operation, stage boundaries must not restart
 * it, byte-less events must not drop it to a stage boundary, and unknown
 * progress must be an explicit indeterminate state rather than a fabricated
 * percentage.
 */
class StagedProgressTrackerTest {

    private fun tracker(): StagedProgressTracker = StagedProgressTracker(
        listOf(
            StagedProgressTracker.Stage("download", 0.40f),
            StagedProgressTracker.Stage("analyze", 0.15f),
            StagedProgressTracker.Stage("extract", 0.40f),
            StagedProgressTracker.Stage("fix", 0.05f)
        )
    )

    @Test
    fun `indeterminate until a stage measures something`() {
        val t = tracker()
        assertNull("no fabricated zero before any real measurement", t.update("download", null))
        assertNull(t.update("extract", null))
        // The first real fraction flips the tracker to a measurable state.
        assertNotNull(t.update("download", 0f))
    }

    @Test
    fun `download advances the bar on its own scale`() {
        val t = tracker()
        assertEquals(0.20f, t.update("download", 0.5f)!!, 1e-6f)
        assertEquals(0.40f, t.update("download", 1.0f)!!, 1e-6f)
    }

    @Test
    fun `stage handover never moves the bar backwards`() {
        val t = tracker()
        t.update("download", 1.0f)
        val atHandover = t.update("analyze", 0f)!!
        assertEquals(
            "analyze starting from 0 continues from the download's committed 40%",
            0.40f, atHandover, 1e-6f
        )
        assertEquals(0.55f, t.update("analyze", 1.0f)!!, 1e-6f)
        assertEquals(0.55f, t.update("extract", 0f)!!, 1e-6f)
    }

    @Test
    fun `byte-less events keep the bar where it was`() {
        val t = tracker()
        t.update("download", 1.0f)
        t.update("analyze", 1.0f)
        t.update("extract", 0.5f) // 0.55 base + 0.40*0.5 = 0.75
        val before = t.update("extract", null)!!
        assertEquals("entry-name event must not drop the bar", 0.75f, before, 1e-6f)
    }

    @Test
    fun `in-stage restart freezes instead of going backwards`() {
        val t = tracker()
        t.update("extract", 0.8f)
        val highWater = t.overallNullable!!
        // Seccomp retry: extraction restarts from ~0. The bar must hold.
        val afterRestart = t.update("extract", 0.1f)!!
        assertEquals("monotonic clamp", highWater, afterRestart, 1e-6f)
        assertFalse(afterRestart > highWater)
        // And it must advance again once the work passes the old mark.
        val recovered = t.update("extract", 1.0f)!!
        assertTrue(recovered > highWater)
    }

    @Test
    fun `downloader range restart does not snap the bar to zero`() {
        val t = tracker()
        t.update("download", 1.0f)
        assertEquals(0.40f, t.overallNullable!!, 1e-6f)
        // HTTP 200 to a Range request: downloaded restarts at 0.
        val after = t.update("download", 0f)!!
        assertEquals("restart clamps to the committed value", 0.40f, after, 1e-6f)
    }

    @Test
    fun `unmeasurable bounded stages complete on signal`() {
        val t = tracker()
        t.update("download", 1.0f)
        t.update("analyze", 1.0f)
        t.update("extract", 1.0f)
        // download .40 + analyze .15 + extract .40 + fix .05 = 1.0
        assertEquals(1.0f, t.complete("fix")!!, 1e-6f)
    }

    @Test
    fun `full pipeline reaches exactly 1`() {
        val t = tracker()
        for (stage in listOf("download", "analyze", "extract")) t.update(stage, 1.0f)
        assertEquals(1.0f, t.complete("fix")!!, 1e-6f)
    }

    @Test
    fun `unknown stage names fail fast`() {
        val t = tracker()
        try {
            t.update("nope", 0.5f)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("nope"))
        }
    }

    @Test
    fun `weights must sum to one`() {
        try {
            StagedProgressTracker(
                listOf(
                    StagedProgressTracker.Stage("a", 0.5f),
                    StagedProgressTracker.Stage("b", 0.2f)
                )
            )
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sum to 1"))
        }
    }

    @Test
    fun `duplicate stage names fail fast`() {
        try {
            StagedProgressTracker(
                listOf(
                    StagedProgressTracker.Stage("a", 0.5f),
                    StagedProgressTracker.Stage("a", 0.5f)
                )
            )
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("duplicate"))
        }
    }
}
