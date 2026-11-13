package com.fem.linexa.ui.screens.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §16.3.1 — [DesktopStartupProbe]: the combined `which Xvnc` + session-probe
 * exec, and its framing.
 *
 * The whole point of the merge is that two lookups now share one stdout, so the
 * class of bug this suite exists to catch is **cross-contamination**: an Xvnc
 * path leaking into the session selection, a session hit making a missing Xvnc
 * look present, or the aggregate exit status of the combined shell being mistaken
 * for `which Xvnc`'s own. Every one of those would compile, and every one would
 * be silent on a device — a user with no desktop installed would get a grey
 * screen instead of the NoSession message, or a user with no Xvnc would skip
 * the auto-install and fail later with an unrelated error.
 *
 * The probe is pure string work, so all of it is directly assertable without a
 * guest, a binder or an Android context.
 */
class DesktopStartupProbeTest {

    // ── command construction ──────────────────────────────────────────────

    @Test
    fun theCommandRunsBothLookupsInOneExec() {
        val command = DesktopStartupProbe.command()
        assertTrue("must look up Xvnc", command.contains("which Xvnc"))
        assertTrue(
            "must contain the session probe verbatim",
            command.contains(DesktopSessionProvisioner.DETECT_SESSION_COMMAND)
        )
    }

    @Test
    fun theCandidateListIsNotRestatedHere() {
        // §16.3.1's constraint: one source of truth for the 19 candidates. The
        // command must be built FROM DesktopSessionProvisioner, so a candidate
        // added there appears here with no second edit.
        val command = DesktopStartupProbe.command()
        DesktopSessionProvisioner.SESSION_CANDIDATES.forEach { candidate ->
            assertTrue("combined probe is missing $candidate", command.contains(" $candidate"))
        }
    }

    @Test
    fun theXvncStatusIsCapturedBeforeAnythingElseCanClobberIt() {
        // `$?` is overwritten by every subsequent command, so the echo of the
        // status marker has to sit immediately after `which Xvnc` — not after the
        // session probe, where it would report the session's status instead.
        val command = DesktopStartupProbe.command()
        val whichIndex = command.indexOf("which Xvnc")
        val statusIndex = command.indexOf(DesktopStartupProbe.XVNC_STATUS_MARKER)
        val sessionIndex = command.indexOf(DesktopSessionProvisioner.DETECT_SESSION_COMMAND)
        assertTrue("status marker must follow which Xvnc", whichIndex in 0 until statusIndex)
        assertTrue("status marker must precede the session probe", statusIndex < sessionIndex)
    }

    @Test
    fun theSessionMarkerSeparatesTheTwoSections() {
        val command = DesktopStartupProbe.command()
        val statusIndex = command.indexOf(DesktopStartupProbe.XVNC_STATUS_MARKER)
        val markerEcho = command.indexOf("echo ${DesktopStartupProbe.SESSION_MARKER}")
        val sessionIndex = command.indexOf(DesktopSessionProvisioner.DETECT_SESSION_COMMAND)
        assertTrue("session marker must be echoed", markerEcho > 0)
        assertTrue("and must sit between the status marker and the session probe", statusIndex < markerEcho)
        assertTrue(markerEcho < sessionIndex)
    }

    @Test
    fun theCommandInstallsNothing() {
        // Discovery only. An install in the probe would run before the user has
        // been asked anything (and before the NoSession check).
        val command = DesktopStartupProbe.command()
        listOf("apt-get install", "apk add", "pacman -S", "dnf install", "zypper").forEach {
            assertFalse("probe must not install: $it", command.contains(it))
        }
    }

    @Test
    fun theCommandDoesNotKillAnything() {
        // §16.3.1's central constraint: pkill is NOT part of the merge. A pkill
        // here would fire before the session-null check and kill an X server in
        // exactly the case the start path refuses to touch anything.
        val command = DesktopStartupProbe.command()
        assertFalse("pkill must never be part of the discovery probe", command.contains("pkill"))
        assertFalse(command.contains("kill "))
    }

    @Test
    fun aDeclaredSessionReachesTheProbeThroughTheProvisionerOnly() {
        // §1.118's declared binary must still be probed, and must arrive via
        // detectSessionCommand — the function that validates it (§1.14).
        val command = DesktopStartupProbe.command("my-session")
        assertTrue(command.contains(" my-session"))
        assertTrue(command.contains(DesktopSessionProvisioner.detectSessionCommand("my-session")))
    }

    @Test
    fun aWhitespaceBearingDeclaredSessionIsNeverInterpolated() {
        // The provisioner rejects it; the combined command must not reintroduce
        // an interpolation path around that check.
        val command = DesktopStartupProbe.command("evil; rm -rf /")
        assertFalse(command.contains("rm -rf"))
        assertEquals(DesktopStartupProbe.command(null), command)
    }

    // ── parsing: the four discovery outcomes ──────────────────────────────

    /** Builds a well-formed probe stdout the way the guest shell would. */
    private fun output(
        xvncLines: List<String>,
        status: Int,
        sessionLines: List<String>
    ): String = (
        xvncLines +
            listOf("${DesktopStartupProbe.XVNC_STATUS_MARKER}$status") +
            listOf(DesktopStartupProbe.SESSION_MARKER) +
            sessionLines
        ).joinToString("\n")

    @Test
    fun xvncPresentAndSessionPresent() {
        val result = DesktopStartupProbe.parse(
            output(listOf("/usr/bin/Xvnc"), 0, listOf("/usr/bin/startxfce4", "/usr/bin/xterm"))
        )
        assertTrue(result.xvncFound)
        assertEquals("/usr/bin/Xvnc", result.xvncPath)
        assertTrue(result.wellFormed)
        assertEquals(
            "/usr/bin/startxfce4",
            DesktopSessionProvisioner.parseSessionPath(result.sessionProbeOutput)
        )
    }

    @Test
    fun xvncPresentAndNoSession() {
        // The case that must NOT lead to pkill. Xvnc's own path is in the output,
        // so a parser that did not respect the section boundary could return it
        // as the "session" and start a desktop whose only client is the X server.
        val result = DesktopStartupProbe.parse(
            output(listOf("/usr/bin/Xvnc"), 0, emptyList())
        )
        assertTrue(result.xvncFound)
        assertNull(
            "Xvnc must never be offered as a desktop session",
            DesktopSessionProvisioner.parseSessionPath(result.sessionProbeOutput)
        )
    }

    @Test
    fun xvncMissingAndSessionPresent() {
        // `which` prints nothing for a miss and exits non-zero; the session half
        // still has to be readable, because the start path uses it after the
        // install completes.
        val result = DesktopStartupProbe.parse(
            output(emptyList(), 1, listOf("/usr/bin/openbox"))
        )
        assertFalse(result.xvncFound)
        assertNull(result.xvncPath)
        assertTrue(result.wellFormed)
        assertEquals(
            "/usr/bin/openbox",
            DesktopSessionProvisioner.parseSessionPath(result.sessionProbeOutput)
        )
    }

    @Test
    fun xvncMissingAndNoSession() {
        val result = DesktopStartupProbe.parse(output(emptyList(), 1, emptyList()))
        assertFalse(result.xvncFound)
        assertNull(result.xvncPath)
        assertNull(DesktopSessionProvisioner.parseSessionPath(result.sessionProbeOutput))
    }

    // ── parsing: the failures must stay independent ───────────────────────

    @Test
    fun aSessionHitCannotMakeAMissingXvncLookPresent() {
        // The combined shell's own exit status is the LAST command's, so it would
        // report success here. Only the captured marker may decide.
        val result = DesktopStartupProbe.parse(
            output(emptyList(), 1, listOf("/usr/bin/startxfce4"))
        )
        assertFalse("session success must not imply Xvnc success", result.xvncFound)
    }

    @Test
    fun aMissingSessionCannotMakeAPresentXvncLookMissing() {
        val result = DesktopStartupProbe.parse(output(listOf("/usr/bin/Xvnc"), 0, emptyList()))
        assertTrue("session failure must not imply Xvnc failure", result.xvncFound)
    }

    @Test
    fun theXvncSectionIsNeverSearchedForASession() {
        // A guest whose PATH puts a session binary somewhere `which Xvnc` also
        // prints (pathological, but the boundary must be structural, not lucky).
        val result = DesktopStartupProbe.parse(
            output(listOf("/usr/bin/Xvnc", "/usr/bin/startxfce4"), 0, emptyList())
        )
        assertNull(
            "nothing before the session marker may be selected as a session",
            DesktopSessionProvisioner.parseSessionPath(result.sessionProbeOutput)
        )
    }

    @Test
    fun theSessionSectionIsNeverSearchedForXvnc() {
        val result = DesktopStartupProbe.parse(
            output(emptyList(), 1, listOf("/usr/bin/Xvnc", "/usr/bin/openbox"))
        )
        assertFalse(result.xvncFound)
        assertNull("an Xvnc printed after the marker is not a discovery hit", result.xvncPath)
    }

    // ── parsing: robustness ───────────────────────────────────────────────

    @Test
    fun markersAreFoundByContentNotByLineIndex() {
        // A PRoot warning or guest banner shifts every line; positional parsing
        // would break exactly here.
        val noisy = listOf(
            "proot warning: can't sanitize binding",
            "/usr/bin/Xvnc",
            "${DesktopStartupProbe.XVNC_STATUS_MARKER}0",
            DesktopStartupProbe.SESSION_MARKER,
            "which: no startxfce4 in (/usr/bin)",
            "/usr/bin/openbox"
        ).joinToString("\n")
        val result = DesktopStartupProbe.parse(noisy)
        assertTrue(result.xvncFound)
        assertEquals("/usr/bin/Xvnc", result.xvncPath)
        assertEquals(
            "/usr/bin/openbox",
            DesktopSessionProvisioner.parseSessionPath(result.sessionProbeOutput)
        )
    }

    @Test
    fun truncatedOutputFailsSafeToXvncMissing() {
        // The timeout case: the exec is killed mid-stream, so no marker arrives.
        // Pre-§16.3.1 a timeout also produced exitCode -1 → "Xvnc missing" → the
        // auto-install path, which surfaces the timeout as its own error.
        val result = DesktopStartupProbe.parse("/usr/bin/Xvnc\n")
        assertFalse(result.xvncFound)
        assertFalse(result.wellFormed)
        assertEquals("", result.sessionProbeOutput)
    }

    @Test
    fun completelyEmptyOutputFailsSafe() {
        val result = DesktopStartupProbe.parse("")
        assertFalse(result.xvncFound)
        assertFalse(result.wellFormed)
        assertNull(DesktopSessionProvisioner.parseSessionPath(result.sessionProbeOutput))
    }

    @Test
    fun outOfOrderMarkersAreRejectedRatherThanGuessed() {
        val reversed = listOf(
            DesktopStartupProbe.SESSION_MARKER,
            "/usr/bin/openbox",
            "${DesktopStartupProbe.XVNC_STATUS_MARKER}0"
        ).joinToString("\n")
        val result = DesktopStartupProbe.parse(reversed)
        assertFalse(result.xvncFound)
        assertFalse(result.wellFormed)
    }

    @Test
    fun anUnparseableStatusIsNotASuccess() {
        val garbled = listOf(
            "/usr/bin/Xvnc",
            "${DesktopStartupProbe.XVNC_STATUS_MARKER}notanumber",
            DesktopStartupProbe.SESSION_MARKER,
            "/usr/bin/openbox"
        ).joinToString("\n")
        val result = DesktopStartupProbe.parse(garbled)
        assertFalse("only a literal 0 means found", result.xvncFound)
        assertFalse(result.wellFormed)
    }

    @Test
    fun anyNonZeroStatusMeansMissing() {
        listOf(1, 2, 127, -1).forEach { status ->
            val result = DesktopStartupProbe.parse(output(emptyList(), status, listOf("/usr/bin/openbox")))
            assertFalse("status $status must mean missing", result.xvncFound)
        }
    }

    @Test
    fun aRelativeXvncPathIsNotRecordedAsThePath() {
        // Same rule the session parser applies: a relative path would resolve
        // against the guest's PATH.
        val result = DesktopStartupProbe.parse(output(listOf("Xvnc"), 0, emptyList()))
        assertTrue("the status still decides found-ness", result.xvncFound)
        assertNull("but a relative path is not a usable absolute path", result.xvncPath)
    }

    @Test
    fun theSessionSectionIsHandedOverVerbatim() {
        // parseSessionPath owns all filtering and ordering (§1.114/§1.118); the
        // probe must not pre-filter, or the two would drift.
        val result = DesktopStartupProbe.parse(
            output(listOf("/usr/bin/Xvnc"), 0, listOf("/usr/bin/twm", "/usr/bin/startxfce4"))
        )
        assertEquals("/usr/bin/twm\n/usr/bin/startxfce4", result.sessionProbeOutput)
    }

    @Test
    fun theResolvedSessionPathIsUnchangedFromASeparateProbe() {
        // The §16.3.1 equivalence claim: for the same guest, the combined probe
        // must select exactly what a standalone session probe would have.
        val sessionOnly = "/usr/bin/twm\n/usr/bin/startxfce4\n/usr/bin/openbox\n"
        val combined = DesktopStartupProbe.parse(
            output(listOf("/usr/bin/Xvnc"), 0, sessionOnly.lines())
        )
        assertEquals(
            DesktopSessionProvisioner.parseSessionPath(sessionOnly),
            DesktopSessionProvisioner.parseSessionPath(combined.sessionProbeOutput)
        )
    }

    @Test
    fun theResolvedSessionPathRespectsADeclarationJustAsBefore() {
        val sessionOnly = "/usr/bin/startxfce4\n/usr/bin/openbox\n"
        val combined = DesktopStartupProbe.parse(
            output(listOf("/usr/bin/Xvnc"), 0, sessionOnly.lines())
        )
        val preferred = DesktopSessionProvisioner.preferredCandidates("openbox", null)
        assertEquals(
            DesktopSessionProvisioner.parseSessionPath(sessionOnly, preferred),
            DesktopSessionProvisioner.parseSessionPath(combined.sessionProbeOutput, preferred)
        )
        assertEquals(
            "/usr/bin/openbox",
            DesktopSessionProvisioner.parseSessionPath(combined.sessionProbeOutput, preferred)
        )
    }

    // ── 2026-09-05: the Xvnc capability section (-localhost must be EARNED) ──

    @Test
    fun theCommandCarriesTheCapabilityProbe() {
        val command = DesktopStartupProbe.command()
        assertTrue(
            "the Xvnc option list must be read from the guest's own binary",
            command.contains("Xvnc -help")
        )
        assertTrue(
            "and grepped for the -localhost option",
            command.contains(DesktopStartupProbe.LOCALHOST_OPTION_TOKEN)
        )
        // A build without the option must not fail the whole probe.
        assertTrue(command.contains("|| true"))
    }

    @Test
    fun aCapableXvncAdvertisesLocalhostSupport() {
        val result = DesktopStartupProbe.parse(
            output(listOf("/usr/bin/Xvnc"), 0, listOf("/usr/bin/startxfce4")) +
                "\n${DesktopStartupProbe.XVNC_CAPS_MARKER}\n" +
                "-localhost [yes|no] -rfbauth file -SecurityTypes types\n"
        )
        assertTrue(result.localhostSupported)
        // The capability text must NOT leak into the session selection.
        assertEquals("/usr/bin/startxfce4", result.sessionProbeOutput)
    }

    @Test
    fun anXvncWithoutTheOptionReportsUnsupported() {
        val result = DesktopStartupProbe.parse(
            output(listOf("/usr/bin/Xvnc"), 0, listOf("/usr/bin/xterm")) +
                "\n${DesktopStartupProbe.XVNC_CAPS_MARKER}\n" +
                "-geometry WxH -depth N -rfbport port\n"
        )
        assertFalse(result.localhostSupported)
    }

    @Test
    fun aMissingCapabilitySectionDegradesToUnsupported() {
        // Older capture, truncated exec: omitting -localhost is accepted by
        // every TigerVNC build; assuming it kills TigerVNC ≤ 1.10 starts.
        val result = DesktopStartupProbe.parse(output(listOf("/usr/bin/Xvnc"), 0, listOf("/usr/bin/xterm")))
        assertFalse(result.localhostSupported)
    }
}
