package com.fem.linexa.service.runtime.container

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P0-1 (2026-11-23 performance remediation): the one-shot exec convergence
 * stamp. These tests pin exactly the conditions under which an exec may skip
 * the idempotent guest-repair battery, and every condition that must force a
 * re-run.
 */
class GuestConvergenceRegistryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var registry: GuestConvergenceRegistry

    @Before
    fun setUp() {
        registry = GuestConvergenceRegistry()
    }

    @Test
    fun `a marked rootfs is converged`() {
        val rootfs = tmp.newFolder("rootfs")
        assertFalse(registry.isConverged("ws", rootfs))
        registry.markConverged("ws", rootfs)
        assertTrue(registry.isConverged("ws", rootfs))
    }

    @Test
    fun `invalidate drops the stamp`() {
        val rootfs = tmp.newFolder("rootfs")
        registry.markConverged("ws", rootfs)
        registry.invalidate("ws")
        assertFalse(registry.isConverged("ws", rootfs))
    }

    @Test
    fun `stamps are per workspace`() {
        val rootfs = tmp.newFolder("rootfs")
        registry.markConverged("ws-a", rootfs)
        assertFalse(registry.isConverged("ws-b", rootfs))
    }

    @Test
    fun `a different rootfs path does not match`() {
        val first = tmp.newFolder("first")
        val second = tmp.newFolder("second")
        registry.markConverged("ws", first)
        assertFalse(registry.isConverged("ws", second))
    }

    @Test
    fun `a replaced rootfs with a new filesystem identity does not match`() {
        val rootfs = tmp.newFolder("rootfs")
        var key = "inode-1"
        registry.fileKeyProvider = { key }

        registry.markConverged("ws", rootfs)
        assertTrue(registry.isConverged("ws", rootfs))

        // Same path, different tree behind it (promotion/reinstall).
        key = "inode-2"
        assertFalse(registry.isConverged("ws", rootfs))
    }

    @Test
    fun `missing identity falls back to path validation`() {
        val rootfs = tmp.newFolder("rootfs")
        registry.fileKeyProvider = { null }
        registry.markConverged("ws", rootfs)
        assertTrue(registry.isConverged("ws", rootfs))
    }

    @Test
    fun `a regular file is never a converged rootfs`() {
        val file = tmp.newFile("not-a-rootfs")
        registry.markConverged("ws", file)
        assertFalse(registry.isConverged("ws", file))
    }

    @Test
    fun `deleting the rootfs invalidates by existence`() {
        val rootfs = tmp.newFolder("rootfs")
        registry.markConverged("ws", rootfs)
        assertTrue(rootfs.delete())
        assertFalse(registry.isConverged("ws", rootfs))
    }

    @Test
    fun `invalidateAll clears every workspace`() {
        val rootfs = tmp.newFolder("rootfs")
        registry.markConverged("ws-a", rootfs)
        registry.markConverged("ws-b", rootfs)
        registry.invalidateAll()
        assertFalse(registry.isConverged("ws-a", rootfs))
        assertFalse(registry.isConverged("ws-b", rootfs))
        assertTrue(registry.stampedWorkspaceIds().isEmpty())
    }

    @Test
    fun `marking a second rootfs replaces the first stamp`() {
        val first = tmp.newFolder("first")
        val second = tmp.newFolder("second")
        registry.markConverged("ws", first)
        registry.markConverged("ws", second)
        assertFalse(registry.isConverged("ws", first))
        assertTrue(registry.isConverged("ws", second))
    }
}
