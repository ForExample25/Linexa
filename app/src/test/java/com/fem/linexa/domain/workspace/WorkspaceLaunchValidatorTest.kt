package com.fem.linexa.domain.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-3 (2026-11-23 performance remediation): the launch validator must ask
 * "does a real file exist?" — a short-circuiting probe — instead of forcing a
 * full recursive size walk with `getRootfsSize() <= 0`.
 */
class WorkspaceLaunchValidatorTest {

    private class FakeInspector(
        var check: RootfsCheck = RootfsCheck.Ready,
        var hasFile: Boolean = true
    ) : RootfsInspector {
        var sizeCalls = 0
        var probeCalls = 0

        override fun checkRootfs(workspaceId: String): RootfsCheck = check

        override fun hasValidRootfs(workspaceId: String): Boolean = check is RootfsCheck.Ready

        override fun getRootfsSize(workspaceId: String): Long {
            sizeCalls++
            return 999L
        }

        override fun hasAnyRegularFile(workspaceId: String): Boolean {
            probeCalls++
            return hasFile
        }
    }

    private fun metadata(id: String, status: WorkspaceStatus = WorkspaceStatus.Ready) = WorkspaceMetadata(
        id = id,
        status = status,
        distro = DistroInfo(name = "Debian", version = "13", arch = "arm64"),
        displayName = id,
        avatarLetter = id.take(1).uppercase(),
        avatarColorHex = "#112233",
        rootfsPath = "/tmp/$id/rootfs",
        createdAt = 0L,
        runtimeConfig = RuntimeConfig(),
        terminalSettings = TerminalSettings(),
        featureToggles = emptyMap()
    )

    @Test
    fun `a ready rootfs with a real file is Ready`() {
        val inspector = FakeInspector(hasFile = true)
        val readiness = WorkspaceLaunchValidator(inspector).evaluate(metadata("ws"))
        assertEquals(WorkspaceReadiness.Ready, readiness)
        assertEquals(1, inspector.probeCalls)
        assertEquals("the full size walk must not run on the readiness path", 0, inspector.sizeCalls)
    }

    @Test
    fun `a rootfs with no regular file is Blocked`() {
        val inspector = FakeInspector(hasFile = false)
        val readiness = WorkspaceLaunchValidator(inspector).evaluate(metadata("ws"))
        assertTrue(readiness is WorkspaceReadiness.Blocked)
        assertTrue(
            "the reason must describe an empty rootfs: ${(readiness as WorkspaceReadiness.Blocked).reason}",
            readiness.reason.contains("no files")
        )
        assertEquals(0, inspector.sizeCalls)
    }

    @Test
    fun `a missing rootfs is Blocked before the probe runs`() {
        val inspector = FakeInspector(check = RootfsCheck.Missing("rootfs missing"))
        val readiness = WorkspaceLaunchValidator(inspector).evaluate(metadata("ws"))
        assertTrue(readiness is WorkspaceReadiness.Blocked)
        assertEquals("the layout check is authoritative", 0, inspector.probeCalls)
        assertEquals(0, inspector.sizeCalls)
    }

    @Test
    fun `a damaged rootfs is Blocked before the probe runs`() {
        val inspector = FakeInspector(check = RootfsCheck.Damaged("shell missing"))
        val readiness = WorkspaceLaunchValidator(inspector).evaluate(metadata("ws"))
        assertTrue(readiness is WorkspaceReadiness.Blocked)
        assertEquals(0, inspector.probeCalls)
        assertEquals(0, inspector.sizeCalls)
    }

    @Test
    fun `non-disk statuses never reach the probe`() {
        val inspector = FakeInspector()
        val validator = WorkspaceLaunchValidator(inspector)
        assertTrue(validator.evaluate(metadata("a", WorkspaceStatus.Created)) is WorkspaceReadiness.Blocked)
        assertTrue(validator.evaluate(metadata("b", WorkspaceStatus.Importing(0.5f))) is WorkspaceReadiness.Installing)
        assertFalse(validator.evaluate(metadata("c", WorkspaceStatus.Deleting)) is WorkspaceReadiness.Ready)
        assertEquals(0, inspector.probeCalls)
        assertEquals(0, inspector.sizeCalls)
    }
}
