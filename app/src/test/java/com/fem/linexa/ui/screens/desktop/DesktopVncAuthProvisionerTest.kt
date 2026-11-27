package com.fem.linexa.ui.screens.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desktop Modes (2026-08) — the shell-safety and arg-construction guarantees
 * of [DesktopVncAuthProvisioner].
 *
 * Findings 031: a secret must never appear in the guest command (and therefore
 * never in PRoot's argv). These tests pin that the password reaches
 * `vncpasswd -f` only as stdin, and that the command's temp-file/rename shape
 * is the hardened one (noclobber creation, 0600 from birth, atomic replace).
 * [DesktopVncAuthProvisioner.shellSingleQuoted] is still tested because the
 * guest paths interpolated into the command use it.
 */
class DesktopVncAuthProvisionerTest {

    // ── Quoting (guest paths, not the secret) ────────────────────────────

    @Test
    fun plainPathIsSingleQuotedVerbatim() {
        assertEquals("'/home/linexa'", DesktopVncAuthProvisioner.shellSingleQuoted("/home/linexa"))
    }

    @Test
    fun embeddedQuoteCannotEscapeTheQuoting() {
        // The '\'' dance: a quote inside the value becomes quote-escaped-quote,
        // so the shell can never see the payload as syntax.
        assertEquals("'/a'\\''b'", DesktopVncAuthProvisioner.shellSingleQuoted("/a'b"))
    }

    // ── Finding 031: the password travels on stdin only ─────────────────

    @Test
    fun provisionRequestKeepsThePasswordOutOfTheCommand() {
        val request = DesktopVncAuthProvisioner.provisionRequest("secret1")
        assertFalse("the secret must never be an argv token", request.command.contains("secret1"))
        assertEquals("secret1\n", request.stdin)
    }

    @Test
    fun provisionRequestGuardsVncpasswdAndUsesTheHardenedTempFileShape() {
        val command = DesktopVncAuthProvisioner.provisionRequest("secret1").command
        assertTrue(command.contains("command -v vncpasswd >/dev/null 2>&1"))
        assertTrue("mode must be restrictive before the file exists", command.contains("umask 077"))
        assertTrue("the temp open must be O_EXCL via noclobber", command.contains("set -C"))
        assertTrue(command.contains("chmod 600"))
        assertTrue("the destination must be replaced atomically", command.contains("mv -f"))
        assertTrue(command.contains("'${DesktopVncAuthProvisioner.PASSWD_PATH}'"))
        assertTrue(command.contains("'${DesktopVncAuthProvisioner.PASSWD_PATH}.tmp'"))
        assertFalse("the old printf-in-argv transport must be gone", command.contains("printf"))
    }

    @Test
    fun provisionRequestSurvivesHostilePasswordsWithoutInterpolatingThem() {
        // Every hostile password is prefixed with a sentinel that cannot occur
        // in the fixed command, so "contains" can only mean interpolation.
        val hostile = listOf(
            "a'b", "a;b$(id)", "x`whoami`y", "p|q", "p&q", "p\nq", "$(reboot)",
            "&& rm -rf /", "'; reboot; '"
        )
        for (raw in hostile) {
            val password = "sentinel-$raw"
            val request = DesktopVncAuthProvisioner.provisionRequest(password)
            assertFalse(
                "password ${raw.replace("\n", "\\n")} leaked into argv",
                request.command.contains("sentinel")
            )
            assertEquals(password + "\n", request.stdin)
        }
    }

    @Test
    fun provisionRequestQuotesADeclaredGuestHome() {
        val request = DesktopVncAuthProvisioner.provisionRequest("secret1", "/home/a b")
        assertTrue(
            "a home with a space must remain one shell word",
            request.command.contains("'/home/a b/.vnc/linexa-vnc-passwd'")
        )
    }

    @Test
    fun aTraversingDeclaredHomeFallsBackToTheStandardPath() {
        // A `..` segment in metadata must not let provisioning escape the home
        // it names.
        assertEquals(
            DesktopVncAuthProvisioner.PASSWD_PATH,
            DesktopVncAuthProvisioner.passwdPathFor("/home/../../etc")
        )
    }

    // ── Security args ────────────────────────────────────────────────────

    @Test
    fun withPasswordTheServerOffersVncAuthOnly() {
        // An offered `None` alongside VncAuth would let any local app bypass
        // the secret — so the offer is exactly one type, pointing at the file.
        val args = DesktopVncAuthProvisioner.securityArgs(hasPassword = true)
        assertEquals("-SecurityTypes VncAuth -rfbauth ${DesktopVncAuthProvisioner.PASSWD_PATH}", args)
    }

    @Test
    fun withoutPasswordThePreDesktopModesBehaviourStands() {
        assertEquals("-SecurityTypes None", DesktopVncAuthProvisioner.securityArgs(hasPassword = false))
    }

    // ── Generated passwords ──────────────────────────────────────────────

    @Test
    fun generatedPasswordHasThePinnedLengthAndAlphabet() {
        repeat(50) {
            val password = DesktopVncAuthProvisioner.generatePassword()
            assertEquals(DesktopVncAuthProvisioner.GENERATED_PASSWORD_LENGTH, password.length)
            assertTrue(password.all { it.isLetterOrDigit() && it.code < 128 })
        }
    }

    @Test
    fun generatedPasswordsDifferAcrossCalls() {
        // SecureRandom: a collision across 50 draws of 62^12 is impossible in
        // practice; two equal consecutive values would mean a broken source.
        val draws = (1..50).map { DesktopVncAuthProvisioner.generatePassword() }.toSet()
        assertTrue(draws.size > 1)
    }

    // ── Phase 07 (OPT-SEC-004) + finding 007: no-auth refusal (Step B2) ──

    @Test
    fun aNoPasswordStartIsBlockedWithoutExplicitConsent() {
        // Finding 007: Android has no per-app loopback isolation, so a
        // `-SecurityTypes None` server is reachable by any local app no matter
        // how it binds. The start must fail closed until the user consents.
        assertTrue(
            DesktopVncAuthProvisioner.unauthenticatedStartBlocked(
                securityArgs = DesktopVncAuthProvisioner.securityArgs(hasPassword = false),
                allowUnauthenticated = false
            )
        )
    }

    @Test
    fun aNoPasswordStartIsAllowedOnlyWithExplicitConsent() {
        assertFalse(
            DesktopVncAuthProvisioner.unauthenticatedStartBlocked(
                securityArgs = DesktopVncAuthProvisioner.securityArgs(hasPassword = false),
                allowUnauthenticated = true
            )
        )
    }

    @Test
    fun anAuthenticatedStartIsNeverBlocked() {
        // With a password the server is safe on any interface — the password
        // is the control (and the only same-device one, per Step B4).
        assertFalse(
            DesktopVncAuthProvisioner.unauthenticatedStartBlocked(
                securityArgs = DesktopVncAuthProvisioner.securityArgs(hasPassword = true),
                allowUnauthenticated = false
            )
        )
        assertFalse(
            DesktopVncAuthProvisioner.unauthenticatedStartBlocked(
                securityArgs = DesktopVncAuthProvisioner.securityArgs(hasPassword = true),
                allowUnauthenticated = true
            )
        )
    }

    // ── Finding 007: secure default on desktop enable ────────────────────

    @Test
    fun enablingWithoutAPasswordGeneratesOne() {
        val generated = DesktopVncAuthProvisioner.passwordToGenerateOnEnable(
            password = "",
            unreadable = false,
            allowUnauthenticated = false
        )
        assertNotNull("first enable must produce a password", generated)
        assertEquals(DesktopVncAuthProvisioner.GENERATED_PASSWORD_LENGTH, generated!!.length)
    }

    @Test
    fun enablingNeverOverwritesAChosenOrUnreadablePassword() {
        assertNull(
            "a chosen password must survive an enable",
            DesktopVncAuthProvisioner.passwordToGenerateOnEnable(
                password = "chosen-password",
                unreadable = false,
                allowUnauthenticated = false
            )
        )
        assertNull(
            "an unreadable secret must stay fail-closed until the user replaces it",
            DesktopVncAuthProvisioner.passwordToGenerateOnEnable(
                password = "",
                unreadable = true,
                allowUnauthenticated = false
            )
        )
    }

    @Test
    fun enablingRespectsExplicitNoAuthConsent() {
        assertNull(
            "an explicit no-auth choice must not be silently replaced",
            DesktopVncAuthProvisioner.passwordToGenerateOnEnable(
                password = "",
                unreadable = false,
                allowUnauthenticated = true
            )
        )
    }

    // ── Phase 07 (OPT-SEC-004): bind-address verification (Step B3) ──────

    @Test
    fun theBindProbeFeatureDetectsItsToolsAndNeverFailsTheCommand() {
        val command = DesktopVncAuthProvisioner.BIND_PROBE_COMMAND
        assertTrue(command.contains("command -v ss"))
        assertTrue(command.contains("ss -ltn"))
        assertTrue(command.contains("command -v netstat"))
        assertTrue(command.contains("netstat -ltn"))
        assertTrue(command.contains("busybox netstat -ltn"))
        assertTrue("an absent tool set must still exit 0 with empty output", command.endsWith("|| true"))
    }

    @Test
    fun aWildcardOrRoutableListenerOnTheVncPortIsAViolation() {
        val ssOutput = """
            State   Recv-Q  Send-Q  Local Address:Port  Peer Address:Port
            LISTEN  0       5       127.0.0.1:5901     0.0.0.0:*
            LISTEN  0       5       0.0.0.0:5901       0.0.0.0:*
            LISTEN  0       5       [::]:5901          [::]:*
        """.trimIndent()
        val violation = DesktopVncAuthProvisioner.loopbackBindViolation(ssOutput, 5901)
        assertNotNull("a wildcard listener must be reported", violation)
        assertTrue(
            "the offenders must name the non-loopback addresses: $violation",
            violation!!.contains("0.0.0.0") && violation.contains("::")
        )
        assertFalse(
            "the loopback listener must not be counted as an offender",
            violation.contains("127.0.0.1")
        )
    }

    @Test
    fun aLoopbackOnlyListenerIsNotAViolation() {
        val ssOutput = """
            State   Recv-Q  Send-Q  Local Address:Port  Peer Address:Port
            LISTEN  0       5       127.0.0.1:5901     0.0.0.0:*
            LISTEN  0       5       [::1]:5901         [::]:*
        """.trimIndent()
        assertNull(DesktopVncAuthProvisioner.loopbackBindViolation(ssOutput, 5901))
    }

    @Test
    fun netstatShapesAreParsedIncludingBareV6AndStar() {
        val netstatOutput = """
            Active Internet connections (only servers)
            Proto Recv-Q Send-Q Local Address     Foreign Address   State
            tcp        0      0 127.0.0.1:5901    0.0.0.0:*         LISTEN
            tcp6       0      0 :::5901           :::*              LISTEN
        """.trimIndent()
        val violation = DesktopVncAuthProvisioner.loopbackBindViolation(netstatOutput, 5901)
        assertNotNull("a bare-v6 wildcard listener must be reported", violation)
        // The parsed tokens are the ADDRESS part only (the port is implied).
        assertEquals("*, ::", DesktopVncAuthProvisioner.loopbackBindViolation("*:5901\n:::5901", 5901))
    }

    @Test
    fun aDifferentPortSharingTheSameDigitsIsNotMatched() {
        // :59011 must not satisfy a 5901 probe — the trailing (?!\d) guard.
        assertNull(
            DesktopVncAuthProvisioner.loopbackBindViolation(
                "LISTEN 0 5 0.0.0.0:59011 0.0.0.0:*",
                5901
            )
        )
    }

    @Test
    fun anEmptyListingIsUnverifiedRatherThanAViolation() {
        // No evidence → no claim. The caller logs a warning and keeps the
        // password requirement; it must NOT fail the start on a blank probe.
        assertNull(DesktopVncAuthProvisioner.loopbackBindViolation(null, 5901))
        assertNull(DesktopVncAuthProvisioner.loopbackBindViolation("", 5901))
        assertNull(DesktopVncAuthProvisioner.loopbackBindViolation("   \n  ", 5901))
    }

    // ── Finding 016: tri-state bind proof ────────────────────────────────

    @Test
    fun aLoopbackListenerIsTheOnlyStateThatCountsAsVerified() {
        assertEquals(
            DesktopVncAuthProvisioner.LoopbackProof.Verified,
            DesktopVncAuthProvisioner.loopbackProof("LISTEN 0 5 127.0.0.1:5901 0.0.0.0:*", 5901)
        )
        assertEquals(
            DesktopVncAuthProvisioner.LoopbackProof.Verified,
            DesktopVncAuthProvisioner.loopbackProof("LISTEN 0 5 [::1]:5901 [::]:*", 5901)
        )
    }

    @Test
    fun blankOutputIsUnverified() {
        assertEquals(
            DesktopVncAuthProvisioner.LoopbackProof.Unverified,
            DesktopVncAuthProvisioner.loopbackProof(null, 5901)
        )
        assertEquals(
            DesktopVncAuthProvisioner.LoopbackProof.Unverified,
            DesktopVncAuthProvisioner.loopbackProof("   \n ", 5901)
        )
    }

    @Test
    fun aListingWithoutThePortIsUnverifiedNotVerified() {
        // A non-blank listing that never mentions the server's port proves
        // nothing about that server — e.g. a probe race or a tool that printed
        // only a header. Treating it as verified is exactly finding 016.
        assertEquals(
            DesktopVncAuthProvisioner.LoopbackProof.Unverified,
            DesktopVncAuthProvisioner.loopbackProof(
                "LISTEN 0 5 127.0.0.1:22 0.0.0.0:*\nLISTEN 0 5 127.0.0.1:8022 0.0.0.0:*",
                5901
            )
        )
        // A different port sharing the leading digits is still not this port.
        assertEquals(
            DesktopVncAuthProvisioner.LoopbackProof.Unverified,
            DesktopVncAuthProvisioner.loopbackProof("LISTEN 0 5 0.0.0.0:59011 0.0.0.0:*", 5901)
        )
    }

    @Test
    fun aRoutableListenerIsAViolationWithTheOffendingAddresses() {
        val proof = DesktopVncAuthProvisioner.loopbackProof(
            "LISTEN 0 5 127.0.0.1:5901 0.0.0.0:*\nLISTEN 0 5 0.0.0.0:5901 0.0.0.0:*",
            5901
        )
        assertTrue("expected a violation, got $proof", proof is DesktopVncAuthProvisioner.LoopbackProof.Violation)
        assertEquals("0.0.0.0", (proof as DesktopVncAuthProvisioner.LoopbackProof.Violation).offenders)
    }

    @Test
    fun theNarrowViolationHelperStillProjectsOnlyViolations() {
        // The pre-tri-state API is retained for callers/tests that only need
        // the offender string; Unverified must still project to null.
        assertNull(
            DesktopVncAuthProvisioner.loopbackBindViolation(
                "LISTEN 0 5 127.0.0.1:22 0.0.0.0:*",
                5901
            )
        )
        assertEquals(
            "0.0.0.0",
            DesktopVncAuthProvisioner.loopbackBindViolation(
                "LISTEN 0 5 0.0.0.0:5901 0.0.0.0:*",
                5901
            )
        )
    }
}
