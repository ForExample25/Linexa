package com.fem.linexa.ui.screens.install

import com.fem.linexa.service.installer.InstallFlowState
import com.fem.linexa.service.installer.InstallStep
import com.fem.linexa.service.installer.InstallSummary
import com.fem.linexa.service.installer.RootFsProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX Phase 1 — the mapping from [InstallFlowState] onto what the single
 * install screen renders.
 *
 * §1.88's three destinations each read one state, so "which state does this
 * screen handle" was answered by the nav graph. One screen has to render all
 * six, and two of the resulting properties are the ones the split was hiding:
 * the facts card must survive the synchronous `Ready → Installing` flip, and a
 * failure must be routed by whether a workspace row exists rather than shown in
 * place unconditionally.
 */
class InstallFlowUiStateTest {

    private fun summary(code: String = "debian-12", overwrites: Boolean = false) = InstallSummary(
        workspaceId = "ws-1",
        containerCode = code,
        distroLabel = "Debian 12",
        distroName = "Debian",
        version = "12",
        archAbi = "arm64-v8a",
        identity = RootFsProbe.Identity(
            distroId = RootFsProbe.DistroId.DEBIAN,
            distroName = "Debian",
            version = "12",
            arch = RootFsProbe.RootFsArch.ARM64_V8A,
            detected = true
        ),
        detected = true,
        requiredStorageBytes = 400L * 1024 * 1024,
        requiredFreeBytes = 900L * 1024 * 1024,
        archiveBytes = 120L * 1024 * 1024,
        totalEntries = 12_345L,
        availableBytes = 8L * 1024 * 1024 * 1024,
        overwritesExisting = overwrites
    )

    @Test
    fun idleRendersNothing() {
        val state = InstallFlowUiState.from(InstallFlowState.Idle, snapshot = null)

        assertEquals(InstallFlowPhase.IDLE, state.phase)
        assertNull(state.summary)
        assertFalse(state.cancellable)
        assertFalse(state.showsFailureInPlace)
    }

    @Test
    fun preparingCarriesTheRealStepAndProgressWithNoFactsYet() {
        val state = InstallFlowUiState.from(
            InstallFlowState.Preparing(InstallStep.DOWNLOADING, progress = 0.42f),
            snapshot = null
        )

        assertEquals(InstallFlowPhase.ANALYZING, state.phase)
        assertEquals(InstallStep.DOWNLOADING, state.step)
        assertEquals(0.42f, state.progress!!, 0.0001f)
        // Nothing has been read yet — the card shows its header only.
        assertNull(state.summary)
    }

    @Test
    fun anUnknownPreparingProgressStaysUnknown() {
        // The screen renders an indeterminate rail for null. A synthesized
        // number here would be a lie the UI could not distinguish from a real
        // one, which is the specific defect §1.88 set out to remove.
        val state = InstallFlowUiState.from(
            InstallFlowState.Preparing(InstallStep.ANALYZING, progress = null),
            snapshot = null
        )

        assertNull(state.progress)
    }

    @Test
    fun readyShowsTheLiveAnalysisResultNotTheSnapshot() {
        val live = summary(code = "live")
        val stale = summary(code = "stale")

        val state = InstallFlowUiState.from(InstallFlowState.Ready(live), snapshot = stale)

        assertEquals(InstallFlowPhase.CONFIRM, state.phase)
        assertSame("Ready is authoritative — the snapshot must never shadow it", live, state.summary)
    }

    @Test
    fun installingKeepsTheFactsFromTheConfirmTimeSnapshot() {
        // The coordinator flips Ready → Installing synchronously at confirm and
        // Installing carries no summary. On one screen the Root Filesystem card
        // stays on screen, so losing the snapshot would blank it mid-install.
        val pinned = summary()

        val state = InstallFlowUiState.from(
            InstallFlowState.Installing(InstallStep.EXTRACTING, progress = 0.1f, currentEntryName = "./usr/bin/env"),
            snapshot = pinned
        )

        assertEquals(InstallFlowPhase.INSTALLING, state.phase)
        assertSame(pinned, state.summary)
        assertEquals("./usr/bin/env", state.currentEntryName)
    }

    @Test
    fun installingWithoutASnapshotStillRenders() {
        // Only reachable after a process death mid-install: the coordinator is a
        // singleton that survives the ViewModel, so the screen can be recreated
        // against an Installing state it never saw the Ready for.
        val state = InstallFlowUiState.from(
            InstallFlowState.Installing(InstallStep.FIXING, progress = null),
            snapshot = null
        )

        assertEquals(InstallFlowPhase.INSTALLING, state.phase)
        assertNull(state.summary)
    }

    @Test
    fun onlyUncommittedPhasesAreCancellable() {
        val cancellable = listOf(
            InstallFlowState.Preparing(InstallStep.ANALYZING, null),
            InstallFlowState.Ready(summary())
        )
        val committed = listOf(
            InstallFlowState.Installing(InstallStep.EXTRACTING, null),
            InstallFlowState.Completed("ws-1"),
            InstallFlowState.Failed("boom"),
            InstallFlowState.Idle
        )

        cancellable.forEach {
            assertTrue("$it must be abandonable", InstallFlowUiState.from(it, null).cancellable)
        }
        committed.forEach {
            assertFalse("$it owns real work and must not be cancelled by leaving", InstallFlowUiState.from(it, null).cancellable)
        }
    }

    @Test
    fun aPreConfirmFailureIsShownOnThisScreen() {
        val state = InstallFlowUiState.from(InstallFlowState.Failed("archive is truncated"), snapshot = null)

        assertEquals(InstallFlowPhase.FAILED, state.phase)
        assertEquals("archive is truncated", state.failureReason)
        assertNull(state.failedWorkspaceId)
        assertTrue(state.showsFailureInPlace)
    }

    @Test
    fun aPostConfirmFailureDefersToTheWorkspaceDiagnostics() {
        // A workspace id means a Broken row was persisted; that row's existing
        // diagnostics route owns the explanation, so this screen must not also
        // render its own error body.
        val state = InstallFlowUiState.from(
            InstallFlowState.Failed("fixup stage failed", workspaceId = "ws-9"),
            snapshot = summary()
        )

        assertEquals("ws-9", state.failedWorkspaceId)
        assertFalse(state.showsFailureInPlace)
    }

    @Test
    fun completedExposesTheWorkspaceIdOnce() {
        val state = InstallFlowUiState.from(InstallFlowState.Completed("ws-7"), snapshot = summary())

        assertEquals(InstallFlowPhase.COMPLETED, state.phase)
        assertEquals("ws-7", state.completedWorkspaceId)
        assertNull(state.failedWorkspaceId)
    }
}

/**
 * The pre-analysis label for the Root Filesystem card's header.
 *
 * Worth its own tests because it is the only text on the screen derived from the
 * *selection* rather than from a coordinator event, and because returning null
 * (rather than an English fallback) is what keeps the empty case localized.
 */
class InstallSourceLabelTest {

    @Test
    fun aDistroLabelAppendsTheVersionWhenItAddsInformation() {
        assertEquals("Debian 12", InstallSourceLabel.forDistro("Debian", "12"))
    }

    @Test
    fun aDistroLabelDoesNotRepeatAVersionAlreadyInTheName() {
        assertEquals("Ubuntu 24.04 LTS", InstallSourceLabel.forDistro("Ubuntu 24.04 LTS", "24.04"))
    }

    @Test
    fun aDistroWithNoVersionIsJustItsName() {
        assertEquals("Alpine Linux", InstallSourceLabel.forDistro("Alpine Linux", null))
        assertEquals("Alpine Linux", InstallSourceLabel.forDistro("Alpine Linux", "  "))
    }

    @Test
    fun aBlankDistroNameYieldsNoLabel() {
        assertNull(InstallSourceLabel.forDistro("   ", "12"))
    }

    @Test
    fun aPickedFileIsNamedByItsDerivedName() {
        assertEquals("my-rootfs", InstallSourceLabel.forFile("my-rootfs"))
    }

    @Test
    fun theGenericPlaceholderNameYieldsNoLabel() {
        // "workspace" is Home's fallback when the document had no usable name —
        // echoing it back would read as a fact about the archive.
        assertNull(InstallSourceLabel.forFile("workspace"))
        assertNull(InstallSourceLabel.forFile("Workspace"))
        assertNull(InstallSourceLabel.forFile(null))
        assertNull(InstallSourceLabel.forFile(""))
    }
}
