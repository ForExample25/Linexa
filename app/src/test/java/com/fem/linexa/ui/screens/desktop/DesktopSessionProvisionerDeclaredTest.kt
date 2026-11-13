package com.fem.linexa.ui.screens.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.118 — the declared-`linexa.desktop` half of [DesktopSessionProvisioner].
 *
 * The §1.114 tests above cover the no-declaration behaviour and must keep
 * passing unchanged; these cover the two things a declaration is allowed to do
 * (reorder candidates, add one the built-in list doesn't know) and, more
 * importantly, the two it is **not**: override what the guest actually has, or
 * inject anything into a command string.
 */
class DesktopSessionProvisionerDeclaredTest {

    // ---- preference reordering ----

    @Test
    fun aDeclaredTypeWinsOverTheFixedCandidateOrder() {
        // Both installed. The fixed order prefers startxfce4; a workspace that
        // declares openbox must get openbox.
        val output = "/usr/bin/startxfce4\n/usr/bin/openbox\n"
        assertEquals(
            "/usr/bin/openbox",
            DesktopSessionProvisioner.parseSessionPath(
                output,
                DesktopSessionProvisioner.preferredCandidates("openbox", null)
            )
        )
    }

    @Test
    fun anExplicitDeclaredSessionBeatsTheDeclaredType() {
        // `session:` is more specific than `type:` — an image declaring type
        // xfce but session /usr/bin/i3 means i3.
        val output = "/usr/bin/startxfce4\n/usr/bin/i3\n"
        assertEquals(
            "/usr/bin/i3",
            DesktopSessionProvisioner.parseSessionPath(
                output,
                DesktopSessionProvisioner.preferredCandidates("xfce", "/usr/bin/i3")
            )
        )
    }

    @Test
    fun aDeclaredAbsoluteSessionMatchesByBasename() {
        // The declaration may be an absolute path while `which` prints a
        // different prefix (e.g. /usr/local/bin) — matching must be on the
        // basename or a correct declaration would silently never apply.
        assertEquals(
            "/usr/local/bin/i3",
            DesktopSessionProvisioner.parseSessionPath(
                "/usr/local/bin/i3\n/usr/bin/xterm\n",
                DesktopSessionProvisioner.preferredCandidates(null, "/usr/bin/i3")
            )
        )
    }

    @Test
    fun aDeclarationOnlyReordersAndNeverInvents() {
        // Declares XFCE; the guest only has openbox. The desktop must still
        // start rather than refusing on a stale declaration.
        assertEquals(
            "/usr/bin/openbox",
            DesktopSessionProvisioner.parseSessionPath(
                "/usr/bin/openbox\n",
                DesktopSessionProvisioner.preferredCandidates("xfce", "/usr/bin/startxfce4")
            )
        )
    }

    @Test
    fun aDeclarationCannotConjureASessionOnAGuestWithNone() {
        assertNull(
            DesktopSessionProvisioner.parseSessionPath(
                "",
                DesktopSessionProvisioner.preferredCandidates("xfce", "/usr/bin/startxfce4")
            )
        )
    }

    @Test
    fun anUnknownDeclaredTypeContributesNothingAndTheFixedOrderDecides() {
        assertEquals(
            "/usr/bin/startxfce4",
            DesktopSessionProvisioner.parseSessionPath(
                "/usr/bin/startxfce4\n/usr/bin/twm\n",
                DesktopSessionProvisioner.preferredCandidates("wayfire-but-not-x11", null)
            )
        )
    }

    @Test
    fun noDeclarationReproducesTheFixedOrderExactly() {
        val output = "/usr/bin/twm\n/usr/bin/startxfce4\n/usr/bin/openbox\n"
        assertEquals(
            DesktopSessionProvisioner.parseSessionPath(output),
            DesktopSessionProvisioner.parseSessionPath(output, DesktopSessionProvisioner.preferredCandidates(null, null))
        )
    }

    @Test
    fun preferredCandidatesIsEmptyWithoutADeclaration() {
        assertTrue(DesktopSessionProvisioner.preferredCandidates(null, null).isEmpty())
        assertTrue(DesktopSessionProvisioner.preferredCandidates("", "").isEmpty())
    }

    @Test
    fun preferredCandidatesPutsTheSessionFirstThenTheTypesBinaries() {
        assertEquals(
            listOf("i3", "startxfce4", "xfce4-session"),
            DesktopSessionProvisioner.preferredCandidates("xfce", "/usr/bin/i3")
        )
    }

    @Test
    fun declaredTypeIsCaseInsensitive() {
        assertEquals(
            DesktopSessionProvisioner.preferredCandidates("xfce", null),
            DesktopSessionProvisioner.preferredCandidates("XFCE", null)
        )
    }

    // ---- probe command with a declaration ----

    @Test
    fun aDeclaredSessionOutsideTheCandidateListIsAddedToTheProbe() {
        // A custom session script is legitimate; without adding it to `which`
        // the probe could never find it.
        val command = DesktopSessionProvisioner.detectSessionCommand("my-session")
        assertTrue(command.contains(" my-session"))
        DesktopSessionProvisioner.SESSION_CANDIDATES.forEach {
            assertTrue("must still probe $it", command.contains(" $it"))
        }
    }

    @Test
    fun aDeclaredSessionAlreadyInTheListIsNotDuplicated() {
        val command = DesktopSessionProvisioner.detectSessionCommand("openbox")
        assertEquals(1, command.split(" ").count { it == "openbox" })
    }

    @Test
    fun noDeclarationYieldsExactlyTheLegacyProbeCommand() {
        assertEquals(
            DesktopSessionProvisioner.DETECT_SESSION_COMMAND,
            DesktopSessionProvisioner.detectSessionCommand(null)
        )
    }

    @Test
    fun aWhitespaceBearingDeclaredSessionIsNeverInterpolated() {
        // Validation rejects this upstream (§1.14); the command builder
        // re-checks because it is where the value enters a command string.
        val command = DesktopSessionProvisioner.detectSessionCommand("evil; rm -rf /")
        assertFalse(command.contains("rm -rf"))
        assertEquals(DesktopSessionProvisioner.DETECT_SESSION_COMMAND, command)
    }

    @Test
    fun theProbeStillInstallsNothingWithADeclaration() {
        val command = DesktopSessionProvisioner.detectSessionCommand("startxfce4")
        listOf("apt", "apk", "pacman", "dnf", "zypper", "install").forEach {
            assertFalse("probe must not install: $it", command.contains(it))
        }
    }
}
