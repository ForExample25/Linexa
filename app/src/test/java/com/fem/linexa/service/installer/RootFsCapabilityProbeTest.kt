package com.fem.linexa.service.installer

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for [RootFsCapabilityProbe] — the deterministic desktop-capability
 * source: does the live rootfs actually contain a supported package manager
 * (the dependency Xvnc auto-install needs)?
 */
class RootFsCapabilityProbeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun rootfs(): File = tmp.newFolder("rootfs")

    @Test
    fun debianRootfsWithAptIsDesktopCapable() {
        val rootfs = rootfs()
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/apt-get").writeText("binary")
        assertTrue(RootFsCapabilityProbe.hasSupportedPackageManager(rootfs))
    }

    @Test
    fun alpineRootfsWithApkIsDesktopCapable() {
        val rootfs = rootfs()
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/apk").writeText("binary")
        assertTrue(RootFsCapabilityProbe.hasSupportedPackageManager(rootfs))
    }

    @Test
    fun archRootfsWithPacmanIsDesktopCapable() {
        val rootfs = rootfs()
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/pacman").writeText("binary")
        assertTrue(RootFsCapabilityProbe.hasSupportedPackageManager(rootfs))
    }

    @Test
    fun emptyRootfsIsNotDesktopCapable() {
        assertFalse(RootFsCapabilityProbe.hasSupportedPackageManager(rootfs()))
    }

    @Test
    fun rootfsWithoutPackageManagerIsNotDesktopCapable() {
        val rootfs = rootfs()
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/vim").writeText("binary")
        assertFalse(RootFsCapabilityProbe.hasSupportedPackageManager(rootfs))
    }

    @Test
    fun missingRootfsDirectoryIsNotDesktopCapable() {
        assertFalse(RootFsCapabilityProbe.hasSupportedPackageManager(File(tmp.root, "nope")))
    }

    @Test
    fun binaryInSbinIsDetected() {
        val rootfs = rootfs()
        File(rootfs, "usr/sbin").mkdirs()
        File(rootfs, "usr/sbin/zypper").writeText("binary")
        assertTrue(RootFsCapabilityProbe.hasSupportedPackageManager(rootfs))
    }
}
