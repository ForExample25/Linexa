package com.fem.linexa.service.runtime.features

import com.fem.linexa.service.installer.DesktopEnvironmentProbe
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 2026-09-02 (guided desktop installer, Phase 3) — JVM tests for
 * [DesktopInstallPolicy]: the Debian/Ubuntu + APT gate, the storage
 * decision, and the os-release parse. These are the decisions the user
 * sees, so the honest behaviors (unsupported stays unsupported; an
 * unknown requirement never blocks; a known shortage always blocks) are
 * pinned here.
 */
class DesktopInstallPolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── os-release parsing ───────────────────────────────────────────────

    @Test
    fun osReleaseIdsParseWithQuotes() {
        assertEquals("debian", DesktopInstallPolicy.parseOsReleaseId("PRETTY_NAME=\"Debian GNU/Linux 12\"\nID=debian\n"))
        assertEquals("ubuntu", DesktopInstallPolicy.parseOsReleaseId("ID=ubuntu\nID_LIKE=debian\n"))
        assertEquals("kali", DesktopInstallPolicy.parseOsReleaseId("ID=\"kali\""))
        assertNull(DesktopInstallPolicy.parseOsReleaseId("PRETTY_NAME=Fedora"))
        assertNull(DesktopInstallPolicy.parseOsReleaseId(""))
        assertNull(DesktopInstallPolicy.parseOsReleaseId(null))
    }

    @Test
    fun onlyDebianAndUbuntuAreSupported() {
        // Unsupported distributions remain unsupported (phase scope).
        assertTrue(DesktopInstallPolicy.SUPPORTED_OS_IDS.contains("debian"))
        assertTrue(DesktopInstallPolicy.SUPPORTED_OS_IDS.contains("ubuntu"))
        assertFalse(DesktopInstallPolicy.SUPPORTED_OS_IDS.contains("kali"))
        assertFalse(DesktopInstallPolicy.SUPPORTED_OS_IDS.contains("raspbian"))
        assertFalse(DesktopInstallPolicy.SUPPORTED_OS_IDS.contains("fedora"))
    }

    // ── static prerequisites ─────────────────────────────────────────────

    @Test
    fun debianRootfsWithAptPassesStaticGate() {
        val rootfs = tmp.newFolder("debian")
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/os-release").writeText("ID=debian\n")
        File(rootfs, "usr/bin/apt-get").createNewFile()
        val ws = tmp.newFolder("ws")

        val prereqs = DesktopInstallPolicy.staticPrerequisites(rootfs, ws, DesktopPreset.XFCE)
        assertTrue(prereqs.rootfsPresent)
        assertEquals("debian", prereqs.osReleaseId)
        assertTrue(prereqs.aptDistro)
        assertEquals("usr/bin/apt-get", prereqs.aptBinary)
        assertEquals(
            DesktopEnvironmentProbe.State.NOT_INSTALLED,
            prereqs.desktopState
        )
        assertNull(prereqs.hardBlockReason(DesktopPreset.XFCE))
    }

    @Test
    fun nonDebianRootfsIsHardBlocked() {
        val rootfs = tmp.newFolder("fedora")
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/os-release").writeText("ID=fedora\n")
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/dnf").createNewFile()

        val prereqs = DesktopInstallPolicy.staticPrerequisites(rootfs, tmp.newFolder("ws"), DesktopPreset.XFCE)
        assertTrue(prereqs.rootfsPresent)
        assertFalse(prereqs.aptDistro)
        assertEquals(
            DesktopInstallPolicy.BlockReason.NOT_APT_DISTRO,
            prereqs.hardBlockReason(DesktopPreset.XFCE)
        )
    }

    @Test
    fun missingRootfsIsHardBlocked() {
        val prereqs = DesktopInstallPolicy.staticPrerequisites(
            File(tmp.root, "missing"), tmp.newFolder("ws"), DesktopPreset.XFCE
        )
        assertFalse(prereqs.rootfsPresent)
        assertEquals(
            DesktopInstallPolicy.BlockReason.NO_ROOTFS,
            prereqs.hardBlockReason(DesktopPreset.XFCE)
        )
    }

    @Test
    fun aptWithoutDebianIdIsHardBlocked() {
        // An apt binary in an undeclared rootfs is not proof of Debian/Ubuntu.
        val rootfs = tmp.newFolder("undeclared")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/apt").createNewFile()
        val prereqs = DesktopInstallPolicy.staticPrerequisites(rootfs, tmp.newFolder("ws"), DesktopPreset.XFCE)
        assertEquals("bin/apt", prereqs.aptBinary)
        assertFalse(prereqs.aptDistro)
        assertEquals(
            DesktopInstallPolicy.BlockReason.NOT_APT_DISTRO,
            prereqs.hardBlockReason(DesktopPreset.XFCE)
        )
    }

    // ── storage gate ─────────────────────────────────────────────────────

    @Test
    fun knownShortageBlocks() {
        // 284 MB installed + 84.5 MB download vs 100 MB free → block.
        assertEquals(
            DesktopInstallPolicy.BlockReason.INSUFFICIENT_STORAGE,
            DesktopInstallPolicy.storageBlock(
                downloadBytes = 84_500_000L,
                installedBytes = 284_000_000L,
                freeBytes = 100_000_000L
            )
        )
    }

    @Test
    fun sufficientSpacePasses() {
        assertNull(
            DesktopInstallPolicy.storageBlock(
                downloadBytes = 84_500_000L,
                installedBytes = 284_000_000L,
                freeBytes = 500_000_000L
            )
        )
    }

    @Test
    fun unknownRequirementNeverBlocks() {
        // No quote → no gate; the UI must surface "size unknown" instead.
        assertNull(DesktopInstallPolicy.storageBlock(null, null, 0L))
        assertNull(DesktopInstallPolicy.storageBlock(0L, 0L, 0L))
    }
}
