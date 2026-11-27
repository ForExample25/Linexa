package com.fem.linexa.service.installer

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopAssessmentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun touch(root: File, rel: String) {
        val f = File(root, rel)
        f.parentFile!!.mkdirs()
        f.writeText("#!/bin/sh\n")
    }

    private fun fullXfce(root: File) {
        touch(root, "usr/bin/startxfce4")
        touch(root, "usr/bin/xfwm4")
        touch(root, "usr/bin/Xvnc")
        File(root, "usr/share/X11/xkb/rules").mkdirs()
        touch(root, "usr/bin/apt-get")
    }

    @Test
    fun `rootfs with a complete XFCE is READY`() {
        val root = tmp.newFolder()
        fullXfce(root)
        val a = DesktopAssessor.assess(root)
        assertEquals(DesktopReadiness.READY, a.readiness)
        assertEquals("XFCE", a.environment)
        assertTrue(a.missing.isEmpty())
    }

    @Test
    fun `rootfs without a desktop is NONE`() {
        val root = tmp.newFolder()
        touch(root, "usr/bin/apt-get")
        assertEquals(DesktopReadiness.NONE, DesktopAssessor.assess(root).readiness)
    }

    @Test
    fun `launcher without its window manager is PARTIAL and names what is missing`() {
        val root = tmp.newFolder()
        touch(root, "usr/bin/startxfce4")
        val a = DesktopAssessor.assess(root)
        assertEquals(DesktopReadiness.PARTIAL, a.readiness)
        assertTrue(a.missing.any { it.contains("xfwm4") })
    }

    @Test
    fun `desktop present but Xvnc and XKB missing is MISSING_RUNTIME_DEPENDENCIES`() {
        val root = tmp.newFolder()
        touch(root, "usr/bin/startxfce4")
        touch(root, "usr/bin/xfwm4")
        val a = DesktopAssessor.assess(root)
        assertEquals(DesktopReadiness.MISSING_RUNTIME_DEPENDENCIES, a.readiness)
        assertTrue(a.missing.any { it.contains("Xvnc") })
        assertTrue(a.missing.any { it.contains("XKB") })
    }

    @Test
    fun `MATE and KDE rootfs are recognised although they are not installable presets`() {
        val mate = tmp.newFolder()
        touch(mate, "usr/bin/mate-session")
        touch(mate, "usr/bin/marco")
        assertEquals("MATE", DesktopAssessor.assess(mate).environment)

        val kde = tmp.newFolder()
        touch(kde, "usr/bin/startplasma-x11")
        assertEquals("KDE Plasma", DesktopAssessor.assess(kde).environment)
    }

    @Test
    fun `an absolute guest symlink is resolved inside the rootfs not against the host`() {
        val root = tmp.newFolder()
        touch(root, "etc/alternatives/startxfce4-real")
        Files.createDirectories(File(root, "usr/bin").toPath())
        Files.createSymbolicLink(
            File(root, "usr/bin/startxfce4").toPath(),
            java.nio.file.Paths.get("/etc/alternatives/startxfce4-real")
        )
        assertEquals(DesktopReadiness.PARTIAL, DesktopAssessor.assess(root).readiness)
        assertTrue(RootFsCapabilityProbe.hasDesktopCapability(root).not()) // no package manager yet
        touch(root, "usr/bin/apt-get")
        assertTrue(RootFsCapabilityProbe.hasDesktopCapability(root))
    }

    @Test
    fun `a symlink loop does not hang and is not a file`() {
        val root = tmp.newFolder()
        Files.createDirectories(File(root, "usr/bin").toPath())
        Files.createSymbolicLink(File(root, "usr/bin/a").toPath(), java.nio.file.Paths.get("/usr/bin/b"))
        Files.createSymbolicLink(File(root, "usr/bin/b").toPath(), java.nio.file.Paths.get("/usr/bin/a"))
        assertEquals(false, RootfsFiles.isFile(root, File(root, "usr/bin"), "a"))
    }

    @Test
    fun `cache returns the stored assessment until the rootfs changes then recomputes`() {
        val root = tmp.newFolder()
        touch(root, "usr/bin/apt-get")
        val none = DesktopDetectionCache.assess(root)
        assertEquals(DesktopReadiness.NONE, none.readiness)
        assertTrue("unchanged rootfs serves the cached instance", none === DesktopDetectionCache.assess(root))

        // A package-manager run rewrites the dpkg status file and bin dir mtime.
        touch(root, "usr/bin/startxfce4")
        File(root, "usr/bin").setLastModified(System.currentTimeMillis() + 5_000)
        touch(root, "var/lib/dpkg/status")
        assertEquals(DesktopReadiness.PARTIAL, DesktopDetectionCache.assess(root).readiness)
    }

    @Test
    fun `explicit invalidation forces a fresh assessment`() {
        val root = tmp.newFolder()
        val first = DesktopDetectionCache.assess(root)
        DesktopDetectionCache.invalidate(root)
        assertTrue(first !== DesktopDetectionCache.assess(root))
    }
}
