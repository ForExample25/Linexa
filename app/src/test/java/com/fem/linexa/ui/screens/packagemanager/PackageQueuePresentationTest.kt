package com.fem.linexa.ui.screens.packagemanager

import com.fem.linexa.domain.packagemanager.PackageOperationCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PKG-1 — the UI projection of the coordinator's queue.
 *
 * The domain owns scheduling; these tests only pin how its snapshot becomes
 * the small strip/panel line. No test here creates a second queue.
 */
class PackageQueuePresentationTest {

    private fun snapshot(
        active: String? = null,
        waiting: Int = 0
    ) = PackageOperationCoordinator.Snapshot(
        workspaceId = "ws",
        activeCommand = active,
        activeSinceElapsedMs = if (active == null) null else 250L,
        waiting = waiting
    )

    @Test
    fun anIdleQueueRendersNothing() {
        val ui = PackageQueuePresentation.from(snapshot(), ownOperationRunning = false)
        assertEquals(null, ui.activeCommand)
        assertEquals(0, ui.waiting)
        assertFalse(ui.busy)
        assertFalse(ui.hasOtherActivity)
    }

    @Test
    fun activeOperationIsDistinguishedFromPendingOnes() {
        val ui = PackageQueuePresentation.from(
            snapshot(active = "apt install", waiting = 2),
            ownOperationRunning = false
        )
        assertEquals("apt install", ui.activeCommand)
        assertEquals(2, ui.waiting)
        assertTrue(ui.busy)
        assertTrue(ui.hasOtherActivity)
    }

    @Test
    fun pendingOnlyStillSurfaces() {
        val ui = PackageQueuePresentation.from(snapshot(waiting = 3), ownOperationRunning = false)
        assertEquals(null, ui.activeCommand)
        assertEquals(3, ui.waiting)
        assertTrue(ui.hasOtherActivity)
    }

    @Test
    fun theScreensOwnPreAcquisitionFrameIsNotAQueue() {
        // runExclusive increments `waiting` immediately before locking. With
        // no active owner and a single waiter, that waiter is this screen's
        // operation about to start — not something queued behind it.
        val ui = PackageQueuePresentation.from(snapshot(waiting = 1), ownOperationRunning = true)
        assertEquals(0, ui.waiting)
        assertFalse(ui.hasOtherActivity)
    }

    @Test
    fun aScreensOperationWaitingBehindAnotherIsStillCounted() {
        val ui = PackageQueuePresentation.from(
            snapshot(active = "apt install", waiting = 1),
            ownOperationRunning = true
        )
        assertEquals(1, ui.waiting)
        assertTrue(ui.hasOtherActivity)
    }

    @Test
    fun aSettledOwnOperationDoesNotHideOtherSurfacesActivity() {
        val ui = PackageQueuePresentation.from(
            snapshot(active = "pacman -S", waiting = 1),
            ownOperationRunning = false
        )
        assertTrue(ui.hasOtherActivity)
        assertEquals("pacman -S", ui.activeCommand)
    }

    @Test
    fun queueEmissionsAreProjectedInOrder() {
        // The shape of a real session: idle → another surface starts →
        // this screen queues → the other finishes → this screen runs → idle.
        val emissions = listOf(
            snapshot(),
            snapshot(active = "apt install"),
            snapshot(active = "apt install", waiting = 1),
            snapshot(waiting = 1),
            snapshot(active = "apt install"),
            snapshot()
        ).map { PackageQueuePresentation.from(it, ownOperationRunning = false) }

        assertEquals(listOf(0, 0, 1, 1, 0, 0), emissions.map { it.waiting })
        assertEquals(
            listOf(false, true, true, false, true, false),
            emissions.map { it.busy }
        )
    }

    @Test
    fun activeCommandLabelComesStraightFromTheDomain() {
        val ui = PackageQueuePresentation.from(
            snapshot(active = "dpkg --configure -a"),
            ownOperationRunning = true
        )
        assertEquals("dpkg --configure -a", ui.activeCommand)
    }
}
