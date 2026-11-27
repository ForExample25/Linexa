package com.fem.linexa.ui.screens.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.114 — JVM tests for [DesktopSessionProvisioner].
 *
 * The behaviour under test is exactly the kind that "compiles and looks right"
 * while being useless on a device: an ordering bug in the candidate preference
 * hands the user `xterm` when they have XFCE installed, and a lax path filter
 * hands a whitespace-containing string to the guest daemon host as a command.
 */
class DesktopSessionProvisionerTest {

    // ---- probe command ----

    @Test
    fun probeAsksForEveryCandidateInOneExec() {
        val command = DesktopSessionProvisioner.DETECT_SESSION_COMMAND
        assertTrue(command.startsWith("for "))
        assertTrue("must use the POSIX builtin, not which", command.contains("command -v "))
        assertFalse("which may be absent on minimal images", command.contains("which "))
        DesktopSessionProvisioner.SESSION_CANDIDATES.forEach { candidate ->
            assertTrue("probe is missing $candidate", command.contains("'$candidate'"))
        }
    }

    @Test
    fun probeDoesNotHeadOneBecausePreferenceIsAppliedInKotlin() {
        // The probe emits every hit in candidate order; truncating to the first
        // line would move the preference decision into the guest's shell where
        // it cannot be tested.
        assertFalse(DesktopSessionProvisioner.DETECT_SESSION_COMMAND.contains("head"))
    }

    @Test
    fun probeInstallsNothing() {
        // A desktop environment is the user's choice (unlike Xvnc, §1.85).
        val command = DesktopSessionProvisioner.DETECT_SESSION_COMMAND
        listOf("apt", "apk", "pacman", "dnf", "zypper", "install").forEach {
            assertFalse("probe must not install: $it", command.contains(it))
        }
    }

    // ---- parseSessionPath ----

    @Test
    fun findsTheOnlyAvailableSession() {
        assertEquals(
            "/usr/bin/openbox",
            DesktopSessionProvisioner.parseSessionPath("/usr/bin/openbox\n")
        )
    }

    @Test
    fun prefersAFullSessionOverAWindowManager() {
        // A session manager brings a panel and a WM, so it is strictly better.
        val output = "/usr/bin/twm\n/usr/bin/startxfce4\n/usr/bin/openbox\n"
        assertEquals("/usr/bin/startxfce4", DesktopSessionProvisioner.parseSessionPath(output))
    }

    @Test
    fun prefersAWindowManagerOverABareXterm() {
        val output = "/usr/bin/xterm\n/usr/bin/openbox\n"
        assertEquals("/usr/bin/openbox", DesktopSessionProvisioner.parseSessionPath(output))
    }

    @Test
    fun fallsBackToXtermSoAVisibleWindowStillProvesThePipeline() {
        assertEquals("/usr/bin/xterm", DesktopSessionProvisioner.parseSessionPath("/usr/bin/xterm\n"))
    }

    @Test
    fun prefersTheDebianAlternativesSymlinkOverAConcreteWm() {
        // x-session-manager is whatever the guest's admin selected.
        val output = "/usr/bin/openbox\n/usr/bin/x-session-manager\n"
        assertEquals("/usr/bin/x-session-manager", DesktopSessionProvisioner.parseSessionPath(output))
    }

    @Test
    fun ignoresTheOrderWhichHappenedToPrint() {
        val forward = DesktopSessionProvisioner.parseSessionPath("/usr/bin/startxfce4\n/usr/bin/twm\n")
        val reversed = DesktopSessionProvisioner.parseSessionPath("/usr/bin/twm\n/usr/bin/startxfce4\n")
        assertEquals(forward, reversed)
    }

    @Test
    fun returnsNullWhenTheGuestHasNoXClientAtAll() {
        assertNull(DesktopSessionProvisioner.parseSessionPath(""))
        assertNull(DesktopSessionProvisioner.parseSessionPath("\n\n"))
    }

    @Test
    fun returnsNullRatherThanAnUnrelatedBinary() {
        assertNull(DesktopSessionProvisioner.parseSessionPath("/usr/bin/bash\n/usr/bin/vim\n"))
    }

    @Test
    fun rejectsARelativePath() {
        // A relative path would resolve against the guest's PATH inside the
        // daemon host — the ambiguity the absolute probe exists to remove.
        assertNull(DesktopSessionProvisioner.parseSessionPath("openbox\n"))
    }

    @Test
    fun rejectsAPathContainingWhitespace() {
        // It becomes a daemon command; a space would split it into arguments.
        assertNull(DesktopSessionProvisioner.parseSessionPath("/opt/my wm/openbox\n"))
    }

    @Test
    fun ignoresAnErrorLineMixedIntoTheProbeOutput() {
        val output = "which: no startxfce4 in (/usr/bin)\n/usr/bin/openbox\n"
        assertEquals("/usr/bin/openbox", DesktopSessionProvisioner.parseSessionPath(output))
    }

    @Test
    fun toleratesDuplicatePathsFromAPathWithRepeats() {
        val output = "/usr/bin/openbox\n/usr/local/bin/openbox\n"
        assertEquals("/usr/bin/openbox", DesktopSessionProvisioner.parseSessionPath(output))
    }

    // ---- daemon command ----

    @Test
    fun daemonCommandExportsTheDisplayXvncServes() {
        assertEquals(
            "env DISPLAY=:1 /usr/bin/openbox",
            DesktopSessionProvisioner.daemonCommand("/usr/bin/openbox")
        )
    }

    @Test
    fun daemonCommandDoesNotBackgroundTheSession() {
        // §1.83: the session must remain the initial tracee of its PRoot host;
        // a self-backgrounding command would exit the tracee and, with
        // --kill-on-exit, tear the host down immediately.
        val command = DesktopSessionProvisioner.daemonCommand("/usr/bin/openbox")
        assertFalse(command.trimEnd().endsWith("&"))
        assertFalse(command.contains("nohup"))
        assertFalse(command.contains("setsid"))
    }

    @Test
    fun daemonCommandUsesTheSameDisplayAsTheExportPrefix() {
        // A mismatch would leave one-shot commands talking to a display nothing
        // is serving — silent, since both "succeed".
        assertTrue(
            DesktopSessionProvisioner.daemonCommand("/usr/bin/openbox")
                .contains(DesktopSessionProvisioner.exportDisplayPrefix())
        )
    }

    @Test
    fun theDaemonNameIsDistinctFromTheXServerDaemon() {
        // Both run concurrently under the §1.83 daemon host; one name would
        // make the second start replace the first.
        assertFalse(DesktopSessionProvisioner.DAEMON_NAME.equals("xvnc", ignoreCase = true))
    }

    // ---- display name ----

    @Test
    fun displayNameIsTheBasename() {
        assertEquals("startxfce4", DesktopSessionProvisioner.sessionDisplayName("/usr/bin/startxfce4"))
        assertEquals("xterm", DesktopSessionProvisioner.sessionDisplayName("/usr/bin/xterm"))
    }

    @Test
    fun displayNameDistinguishesAFallbackFromARealDesktop() {
        // The status line has to be able to say "xterm", not just "running".
        assertFalse(
            DesktopSessionProvisioner.sessionDisplayName("/usr/bin/xterm") ==
                DesktopSessionProvisioner.sessionDisplayName("/usr/bin/startxfce4")
        )
    }
}
