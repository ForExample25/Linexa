package com.fem.linexa.service.runtime.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [DesktopPreset] (§16): fail-open id resolution, the
 * probe-before-install contract, and the full preset × package-manager
 * install-command matrix (every combination must be a single line that
 * names the right packages and always includes dbus-x11).
 */
class DesktopPresetTest {

    @Test
    fun fromIdIsExactForLowercaseIds() {
        assertEquals(DesktopPreset.NONE, DesktopPreset.fromId("none"))
        assertEquals(DesktopPreset.XFCE, DesktopPreset.fromId("xfce"))
        assertEquals(DesktopPreset.LXQT, DesktopPreset.fromId("lxqt"))
        assertEquals(DesktopPreset.OPENBOX, DesktopPreset.fromId("openbox"))
    }

    @Test
    fun fromIdAcceptsCasingAndSurroundingWhitespace() {
        assertEquals(DesktopPreset.XFCE, DesktopPreset.fromId("XFCE"))
        assertEquals(DesktopPreset.LXQT, DesktopPreset.fromId(" LxQt "))
        assertEquals(DesktopPreset.OPENBOX, DesktopPreset.fromId("OPENBOX"))
    }

    @Test
    fun fromIdFailsOpenToNone() {
        // Unknown/blank/null must never pick a desktop to install on the
        // user's behalf.
        assertEquals(DesktopPreset.NONE, DesktopPreset.fromId(null))
        assertEquals(DesktopPreset.NONE, DesktopPreset.fromId(""))
        assertEquals(DesktopPreset.NONE, DesktopPreset.fromId("   "))
        assertEquals(DesktopPreset.NONE, DesktopPreset.fromId("gnome"))
        assertEquals(DesktopPreset.NONE, DesktopPreset.fromId("kde-plasma"))
    }

    @Test
    fun presenceProbeIsNullForNoneAndSingleLineOtherwise() {
        assertNull(DesktopPreset.presentCheckCommand(DesktopPreset.NONE))
        for (preset in listOf(DesktopPreset.XFCE, DesktopPreset.LXQT, DesktopPreset.OPENBOX)) {
            val cmd = DesktopPreset.presentCheckCommand(preset)!!
            assertTrue("probe($preset) uses command -v", cmd.contains("command -v"))
            assertFalse("probe($preset) must be one line", cmd.contains('\n'))
        }
    }

    @Test
    fun presenceProbesNameTheRightSessionBinaries() {
        val xfce = DesktopPreset.presentCheckCommand(DesktopPreset.XFCE)!!
        assertTrue(xfce.contains("startxfce4"))
        assertTrue(xfce.contains("xfce4-session"))
        val lxqt = DesktopPreset.presentCheckCommand(DesktopPreset.LXQT)!!
        assertTrue(lxqt.contains("startlxqt"))
        assertTrue(lxqt.contains("lxsession"))
        val openbox = DesktopPreset.presentCheckCommand(DesktopPreset.OPENBOX)!!
        assertTrue(openbox.contains("openbox-session"))
        assertTrue(openbox.contains("openbox"))
    }

    @Test
    fun installCommandMatrixIsCompleteAndNamedCorrectly() {
        val pms = listOf("apt", "pacman", "apk", "dnf", "zypper")
        for (preset in listOf(DesktopPreset.XFCE, DesktopPreset.LXQT, DesktopPreset.OPENBOX)) {
            for (pm in pms) {
                val cmd = DesktopPreset.installCommand(preset, pm)
                assertTrue("install($preset, $pm) must exist", cmd != null)
                // dbus-x11 in every list: without a session bus the desktop
                // starts broken, which reads as a preset bug, not a missing
                // dependency.
                assertTrue("install($preset, $pm) must include dbus-x11", cmd!!.contains("dbus-x11"))
                assertFalse("install($preset, $pm) must be one line", cmd.contains('\n'))
            }
        }
    }

    @Test
    fun xfceInstallCommandsNameXfcePackages() {
        assertTrue(DesktopPreset.installCommand(DesktopPreset.XFCE, "apt")!!.contains("xfce4 xfce4-terminal"))
        assertTrue(DesktopPreset.installCommand(DesktopPreset.XFCE, "apt")!!.contains("apt-get update"))
        assertTrue(DesktopPreset.installCommand(DesktopPreset.XFCE, "apt")!!.contains("DEBIAN_FRONTEND=noninteractive"))
        assertTrue(DesktopPreset.installCommand(DesktopPreset.XFCE, "pacman")!!.contains("--needed xfce4"))
        assertTrue(DesktopPreset.installCommand(DesktopPreset.XFCE, "apk")!!.contains("apk add xfce4"))
        assertTrue(DesktopPreset.installCommand(DesktopPreset.XFCE, "dnf")!!.contains("@xfce-desktop-environment"))
        assertTrue(DesktopPreset.installCommand(DesktopPreset.XFCE, "zypper")!!.contains("-t pattern xfce"))
    }

    @Test
    fun lxqtInstallCommandsNameLxqtPackages() {
        assertTrue(DesktopPreset.installCommand(DesktopPreset.LXQT, "apt")!!.contains("lxqt-core lxqt-terminal"))
        assertTrue(DesktopPreset.installCommand(DesktopPreset.LXQT, "pacman")!!.contains("--needed lxqt"))
        assertTrue(DesktopPreset.installCommand(DesktopPreset.LXQT, "apk")!!.contains("apk add lxqt-core"))
        assertTrue(DesktopPreset.installCommand(DesktopPreset.LXQT, "dnf")!!.contains("@lxqt-desktop-environment"))
        assertTrue(DesktopPreset.installCommand(DesktopPreset.LXQT, "zypper")!!.contains("-t pattern lxqt"))
    }

    @Test
    fun openboxInstallCommandsNameOpenboxPackages() {
        assertTrue(DesktopPreset.installCommand(DesktopPreset.OPENBOX, "apt")!!.contains("openbox xterm"))
        assertTrue(DesktopPreset.installCommand(DesktopPreset.OPENBOX, "pacman")!!.contains("--needed openbox"))
        assertTrue(DesktopPreset.installCommand(DesktopPreset.OPENBOX, "apk")!!.contains("apk add openbox"))
        assertTrue(DesktopPreset.installCommand(DesktopPreset.OPENBOX, "dnf")!!.contains("dnf install -y openbox"))
        assertTrue(DesktopPreset.installCommand(DesktopPreset.OPENBOX, "zypper")!!.contains("zypper --non-interactive install -y openbox"))
    }

    @Test
    fun nonePresetAndUnknownPackageManagerFailClosed() {
        assertNull(DesktopPreset.installCommand(DesktopPreset.NONE, "apt"))
        assertNull(DesktopPreset.installCommand(DesktopPreset.NONE, "pacman"))
        assertNull(DesktopPreset.installCommand(DesktopPreset.XFCE, "unknown"))
        assertNull(DesktopPreset.installCommand(DesktopPreset.LXQT, ""))
        assertNull(DesktopPreset.installCommand(DesktopPreset.OPENBOX, "portage"))
    }

    @Test
    fun packageManagerKeyMatchingIsCaseInsensitive() {
        assertEquals(
            DesktopPreset.installCommand(DesktopPreset.XFCE, "apt"),
            DesktopPreset.installCommand(DesktopPreset.XFCE, "APT")
        )
    }

    /**
     * 2026-09-02 (desktop prerequisites, Phase 2): [DesktopPreset]
     * .sessionBinaryNames is the single source of truth for the static
     * filesystem probe (`DesktopEnvironmentProbe`); this pins that every
     * name it yields is genuinely present in this preset's
     * [DesktopPreset.presentCheckCommand], so the static probe and the
     * live `command -v` probe can never drift apart.
     */
    @Test
    fun sessionBinaryNamesStayInParityWithPresentCheckCommand() {
        for (preset in DesktopPreset.entries) {
            val cmd = DesktopPreset.presentCheckCommand(preset)
            val names = DesktopPreset.sessionBinaryNames(preset)
            if (cmd == null) {
                assertEquals("NONE yields no binaries", 0, names.size)
            } else {
                assertTrue("preset ${preset.id} must yield binaries", names.isNotEmpty())
                for (name in names) {
                    assertTrue(
                        "'$name' of preset ${preset.id} must appear in its presentCheckCommand",
                        cmd.contains("command -v $name ")
                    )
                }
            }
        }
    }

    // ── 2026-09-02 guided desktop installer (Phase 3) ──────────────────────

    @Test
    fun aptPackageListsMatchTheAptInstallCommand() {
        // The guided installer's `apt-get -s` simulation must name exactly
        // the packages the real install does — one list, two consumers.
        for (preset in listOf(DesktopPreset.XFCE, DesktopPreset.LXQT, DesktopPreset.OPENBOX)) {
            val cmd = DesktopPreset.installCommand(preset, "apt")!!
            for (pkg in DesktopPreset.aptPackageNames(preset)) {
                assertTrue(
                    "apt install for ${preset.id} must contain '$pkg'",
                    cmd.contains(" $pkg ") || cmd.endsWith(" $pkg")
                )
            }
            assertTrue("dbus-x11 is in every list", DesktopPreset.aptPackageNames(preset).contains("dbus-x11"))
        }
        assertEquals(0, DesktopPreset.aptPackageNames(DesktopPreset.NONE).size)
    }

    @Test
    fun simulateCommandIsAptOnlyAndReadonly() {
        val xfce = DesktopPreset.simulateCommand(DesktopPreset.XFCE, "apt")!!
        assertTrue(xfce.startsWith("apt-get -s install "))
        assertTrue(xfce.contains("xfce4"))
        assertNull(DesktopPreset.simulateCommand(DesktopPreset.XFCE, "pacman"))
        assertNull(DesktopPreset.simulateCommand(DesktopPreset.XFCE, "apk"))
        assertNull(DesktopPreset.simulateCommand(DesktopPreset.XFCE, "dnf"))
        assertNull(DesktopPreset.simulateCommand(DesktopPreset.XFCE, "zypper"))
        assertNull(DesktopPreset.simulateCommand(DesktopPreset.XFCE, "unknown"))
        assertNull(DesktopPreset.simulateCommand(DesktopPreset.NONE, "apt"))
    }
}
