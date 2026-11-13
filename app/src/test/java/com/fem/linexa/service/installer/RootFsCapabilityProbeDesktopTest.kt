package com.fem.linexa.service.installer

import com.fem.linexa.service.runtime.features.DesktopPreset
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for the desktop-capability probes (desktop prerequisites,
 * Phase 2, 2026-09-02): [RootFsCapabilityProbe.hasDesktopCapability] and
 * [DesktopEnvironmentProbe] against real temporary directory trees.
 */
class RootFsCapabilityProbeDesktopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── DesktopEnvironmentProbe ──────────────────────────────────────────

    @Test
    fun notInstalledWhenRootfsIsEmpty() {
        val rootfs = tmp.newFolder("empty-rootfs")
        assertEquals(
            DesktopEnvironmentProbe.State.NOT_INSTALLED,
            DesktopEnvironmentProbe.probe(rootfs, DesktopPreset.XFCE)
        )
        assertNull(DesktopEnvironmentProbe.anySessionPresent(rootfs, DesktopPreset.XFCE))
    }

    @Test
    fun notInstalledWhenRootfsPathDoesNotExist() {
        val rootfs = File(tmp.root, "does-not-exist")
        assertEquals(
            DesktopEnvironmentProbe.State.NOT_INSTALLED,
            DesktopEnvironmentProbe.probe(rootfs, DesktopPreset.XFCE)
        )
    }

    @Test
    fun installedWithoutPackageManagerIsInstalledNotReady() {
        val rootfs = tmp.newFolder("de-only")
        File(rootfs, "usr/bin").mkdirs()
        assertTrue(File(rootfs, "usr/bin/startxfce4").createNewFile())
        assertEquals(
            DesktopEnvironmentProbe.State.INSTALLED,
            DesktopEnvironmentProbe.probe(rootfs, DesktopPreset.XFCE)
        )
        assertEquals("usr/bin/startxfce4", DesktopEnvironmentProbe.anySessionPresent(rootfs, DesktopPreset.XFCE))
    }

    @Test
    fun installedWithPackageManagerIsReady() {
        val rootfs = tmp.newFolder("de-with-pm")
        File(rootfs, "usr/bin").mkdirs()
        assertTrue(File(rootfs, "usr/bin/startxfce4").createNewFile())
        assertTrue(File(rootfs, "usr/bin/apt-get").createNewFile())
        assertEquals(
            DesktopEnvironmentProbe.State.READY,
            DesktopEnvironmentProbe.probe(rootfs, DesktopPreset.XFCE)
        )
    }

    @Test
    fun familyBinariesAreMatchedPerPreset() {
        val rootfs = tmp.newFolder("families")
        File(rootfs, "bin").mkdirs()
        // LXQt: only lxqt-session present (the §E fix case — startlxqt absent).
        assertTrue(File(rootfs, "bin/lxqt-session").createNewFile())
        assertEquals("bin/lxqt-session", DesktopEnvironmentProbe.anySessionPresent(rootfs, DesktopPreset.LXQT))
        // Openbox: session launcher preferred over the bare WM.
        assertTrue(File(rootfs, "bin/openbox").createNewFile())
        assertEquals("bin/openbox", DesktopEnvironmentProbe.anySessionPresent(rootfs, DesktopPreset.OPENBOX))
        // XFCE: nothing of its family → not installed.
        assertEquals(
            DesktopEnvironmentProbe.State.NOT_INSTALLED,
            DesktopEnvironmentProbe.probe(rootfs, DesktopPreset.XFCE)
        )
    }

    @Test
    fun noneNeverMatches() {
        val rootfs = tmp.newFolder("none-preset")
        File(rootfs, "usr/bin").mkdirs()
        assertTrue(File(rootfs, "usr/bin/startxfce4").createNewFile())
        assertNull(DesktopEnvironmentProbe.anySessionPresent(rootfs, DesktopPreset.NONE))
        assertEquals(
            DesktopEnvironmentProbe.State.NOT_INSTALLED,
            DesktopEnvironmentProbe.probe(rootfs, DesktopPreset.NONE)
        )
    }

    @Test
    fun usrMergedSymlinkedBinResolves() {
        val rootfs = tmp.newFolder("usr-merged")
        val realBin = File(rootfs, "usr/bin")
        realBin.mkdirs()
        assertTrue(File(realBin, "openbox-session").createNewFile())
        // usr-merged: top-level bin is a symlink to usr/bin.
        assertTrue(File(rootfs, "bin").createNewFile() || true)
        realBin.isDirectory // no-op for clarity
        val binLink = File(rootfs, "bin")
        java.nio.file.Files.deleteIfExists(binLink.toPath())
        java.nio.file.Files.createSymbolicLink(binLink.toPath(), realBin.toPath())
        assertEquals(
            DesktopEnvironmentProbe.State.INSTALLED,
            DesktopEnvironmentProbe.probe(rootfs, DesktopPreset.OPENBOX)
        )
    }

    // ── RootFsCapabilityProbe.hasDesktopCapability ───────────────────────

    @Test
    fun desktopCapabilityRequiresBothPackageManagerAndSession() {
        val pmOnly = tmp.newFolder("pm-only")
        File(pmOnly, "usr/bin").mkdirs()
        assertTrue(File(pmOnly, "usr/bin/apt-get").createNewFile())
        // The Phase-2 change: package manager alone no longer satisfies it.
        assertFalse(RootFsCapabilityProbe.hasDesktopCapability(pmOnly))

        val sessionOnly = tmp.newFolder("session-only")
        File(sessionOnly, "bin").mkdirs()
        assertTrue(File(sessionOnly, "bin/lxqt-session").createNewFile())
        assertFalse(RootFsCapabilityProbe.hasDesktopCapability(sessionOnly))

        val both = tmp.newFolder("both")
        File(both, "bin").mkdirs()
        assertTrue(File(both, "bin/lxqt-session").createNewFile())
        assertTrue(File(both, "bin/apt-get").createNewFile())
        assertTrue(RootFsCapabilityProbe.hasDesktopCapability(both))
    }

    @Test
    fun desktopCapabilityStaysFalseWithoutRootfs() {
        assertFalse(RootFsCapabilityProbe.hasDesktopCapability(File(tmp.root, "missing")))
    }

    @Test
    fun packageManagerProbeKeepsItsOriginalContract() {
        // Pre-Phase-2 behavior preserved: hasSupportedPackageManager answers
        // the Xvnc auto-install dependency question exactly as before.
        val rootfs = tmp.newFolder("pm-contract")
        File(rootfs, "usr/local/bin").mkdirs()
        assertTrue(File(rootfs, "usr/local/bin/apk").createNewFile())
        assertTrue(RootFsCapabilityProbe.hasSupportedPackageManager(rootfs))
    }
}
