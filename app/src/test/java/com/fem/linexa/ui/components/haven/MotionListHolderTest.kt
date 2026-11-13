package com.fem.linexa.ui.components.haven

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The item-lifecycle diff behind rememberMotionItems, exercised as plain
 * JVM logic (the composable wrapper only adds remember/LaunchedEffect).
 */
class MotionListHolderTest {

    private fun holder() = MotionListHolder<String>()
    private fun MotionListHolder<String>.submit(
        items: List<String>,
        scope: Any? = null,
        retain: Boolean = true,
        now: Long = 0L
    ) = update(items, scope, { it }, retain, now)

    private fun List<MotionItem<String>>.phases() = associate { it.key to it.phase }

    @Test
    fun firstSubmitNeverAnimates() {
        val out = holder().submit(listOf("a", "b", "c"))
        assertTrue(out.all { it.phase == ItemPhase.PRESENT })
    }

    @Test
    fun emptyThenPopulatedEntersWithBoundedOrdinals() {
        val h = holder()
        h.submit(emptyList())
        val out = h.submit(listOf("a", "b", "c"))
        assertTrue(out.all { it.phase == ItemPhase.ENTERING })
        assertEquals(listOf(0, 1, 2), out.map { it.enterOrdinal })
    }

    @Test
    fun newKeyEntersSurvivorsStayPresent() {
        val h = holder()
        h.submit(listOf("a", "b"))
        val out = h.submit(listOf("a", "b", "c"))
        assertEquals(ItemPhase.PRESENT, out.phases()["a"])
        assertEquals(ItemPhase.ENTERING, out.phases()["c"])
    }

    @Test
    fun removedKeyIsRetainedAsLeavingAtItsOldIndexThenSwept() {
        val h = holder()
        h.submit(listOf("a", "b", "c"), now = 0L)
        val out = h.submit(listOf("a", "c"), now = 1_000_000L)
        assertEquals(listOf("a", "b", "c"), out.map { it.key })
        assertEquals(ItemPhase.LEAVING, out[1].phase)

        // Not yet expired: nothing swept.
        assertFalse(h.sweep(nowNanos = 100_000_000L, exitMs = 200))
        // Expired: swept, and a re-run drops it.
        assertTrue(h.sweep(nowNanos = 300_000_000L, exitMs = 200))
        assertEquals(listOf("a", "c"), h.submit(listOf("a", "c")).map { it.key })
    }

    @Test
    fun keyThatReturnsWhileLeavingStaysAliveAndPresent() {
        val h = holder()
        h.submit(listOf("a", "b"))
        h.submit(listOf("a"))
        val out = h.submit(listOf("a", "b"))
        assertEquals(2, out.size)
        assertTrue(out.none { it.phase == ItemPhase.LEAVING })
    }

    @Test
    fun scopeChangeSwapsTheListWithoutEnterOrLeave() {
        val h = holder()
        h.submit(listOf("a", "b"), scope = "/home")
        val out = h.submit(listOf("x", "y", "z"), scope = "/tmp")
        assertEquals(listOf("x", "y", "z"), out.map { it.key })
        assertTrue(out.all { it.phase == ItemPhase.PRESENT })
    }

    @Test
    fun withoutRetentionRemovedKeysVanishImmediately() {
        val h = holder()
        h.submit(listOf("a", "b"), retain = false)
        val out = h.submit(listOf("b", "c"), retain = false)
        assertEquals(listOf("b", "c"), out.map { it.key })
        assertEquals(ItemPhase.ENTERING, out.phases()["c"])
    }

    @Test
    fun resubmittingTheSameListIsIdempotent() {
        val h = holder()
        h.submit(listOf("a", "b"))
        val first = h.submit(listOf("a", "b", "c"))
        val again = h.submit(listOf("a", "b", "c"))
        assertEquals(first.map { it.key }, again.map { it.key })
        // The entrance was decided on the first pass; a re-run is PRESENT.
        assertEquals(ItemPhase.PRESENT, again.phases()["c"])
    }
}
