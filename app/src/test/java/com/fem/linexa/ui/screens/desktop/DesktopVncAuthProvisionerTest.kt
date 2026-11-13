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
 * This is the one place a *secret* travels through a guest shell, so the
 * tests pin the two properties that make that safe: the quoting cannot be
 * escaped out of, and the generated default never contains anything the
 * quoting has to fight.
 */
class DesktopVncAuthProvisionerTest {

    // ── Quoting ──────────────────────────────────────────────────────────

    @Test
    fun plainPasswordIsSingleQuotedVerbatim() {
        assertEquals("'linexa'", DesktopVncAuthProvisioner.shellSingleQuoted("linexa"))
    }

    @Test
    fun embeddedQuoteCannotEscapeTheQuoting() {
        // The '\'' dance: a quote inside the value becomes quote-escaped-quote,
        // so the shell can never see the payload as syntax.
        assertEquals("'ab'\\''cd'", DesktopVncAuthProvisioner.shellSingleQuoted("ab'cd"))
    }

    @Test
    fun shellMetacharactersStayLiteralInsideTheQuotes() {
        val wrapped = DesktopVncAuthProvisioner.shellSingleQuoted("$(reboot) `rm` ; | &&")
        assertTrue(wrapped.startsWith("'") && wrapped.endsWith("'"))
        assertTrue(!wrapped.contains("`rm`'"))
    }

    @Test
    fun provisionCommandCarriesThePasswordQuotedAndGuardsVncpasswd() {
        val command = DesktopVncAuthProvisioner.provisionCommand("secret1")
        assertTrue(command.contains("'secret1'"))
        assertTrue(command.contains("command -v vncpasswd >/dev/null 2>&1"))
        assertTrue(command.contains("> ${DesktopVncAuthProvisioner.PASSWD_PATH}"))
        assertTrue(command.contains("chmod 600 ${DesktopVncAuthProvisioner.PASSWD_PATH}"))
    }

    @Test
    fun provisionCommandSurvivesAQuotedPassword() {
        val command = DesktopVncAuthProvisioner.provisionCommand("ab'cd")
        // The escaped quote must appear; the raw unescaped sequence must not.
        assertTrue(command.contains("'ab'\\''cd'"))
    }

    // ── Pre-release audit regression pins (2026-09-02, finding S1) ──────
    //
    // The old template wrapped shellSingleQuoted's output in a SECOND quote
    // pair (''…''), leaving the payload segments UNQUOTED: passwords with
    // ';'/$()/backticks executed as shell syntax in the guest, and a
    // password containing ' terminated the parse (unterminated string →
    // provisioning failure → unauthenticated desktop). These tests pin the
    // fixed single-wrapping contract.

    @Test
    fun provisionCommandInterpolatesTheQuotedTokenBarely() {
        // The template must NOT add its own quotes around the
        // shellSingleQuoted output: the only quoting in the command is the
        // token's own.
        val pw = "a;b\$(id)`rm`"
        val command = DesktopVncAuthProvisioner.provisionCommand(pw)
        val expectedToken = DesktopVncAuthProvisioner.shellSingleQuoted(pw)
        assertTrue(
            "the command must contain exactly the single-quoted token",
            command.contains(expectedToken)
        )
        // And the double-wrapped shape must be gone: ''<token>'' never appears.
        assertTrue(
            "the payload must not be double-wrapped",
            !command.contains("''" + expectedToken.drop(1) + "''")
        )
    }

    @Test
    fun provisionedPayloadIsFullyInsideSingleQuotesForHostilePasswords() {
        // For every hostile password, everything between the printf prefix and
        // the vncpasswd pipe must be exactly the safe single-quoted token —
        // no bare shell-visible segment in between.
        val hostile = listOf("a'b", "a;b$(id)", "x`whoami`y", "p|q", "p&q", "p\nq", "\$(reboot)")
        for (pw in hostile) {
            val command = DesktopVncAuthProvisioner.provisionCommand(pw)
            val token = DesktopVncAuthProvisioner.shellSingleQuoted(pw)
            val marker = "printf '%s\\n' "
            val start = command.indexOf(marker) + marker.length
            val end = command.indexOf(" | vncpasswd")
            assertEquals(
                "payload region must be exactly the quoted token for password ${pw.replace("\n", "\\n")}",
                token,
                command.substring(start, end)
            )
        }
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

    // ── Phase 07 (OPT-SEC-004): unauthenticated-start refusal (Step B2) ──

    @Test
    fun anUnauthenticatedStartOnAnXvncWithoutLocalhostSupportIsBlocked() {
        // The server could be neither restricted to loopback (-localhost
        // unsupported, TigerVNC < 1.11) nor authenticated (no password):
        // reachable by any local app with no credential — refuse the start.
        assertTrue(
            DesktopVncAuthProvisioner.unauthenticatedStartBlocked(
                localhostSupported = false,
                securityArgs = DesktopVncAuthProvisioner.securityArgs(hasPassword = false)
            )
        )
    }

    @Test
    fun anAuthenticatedStartIsNeverBlockedEvenWithoutLocalhostSupport() {
        // With a password the server is safe on any interface — the password
        // is the control (and the only same-device one, per Step B4).
        assertFalse(
            DesktopVncAuthProvisioner.unauthenticatedStartBlocked(
                localhostSupported = false,
                securityArgs = DesktopVncAuthProvisioner.securityArgs(hasPassword = true)
            )
        )
    }

    @Test
    fun aLocalhostCapableXvncIsNeverBlockedByTheRefusalPolicy() {
        // -localhost yes restricts the server to loopback, so the deliberate
        // no-password opt-in (P11) stays available on TigerVNC ≥ 1.11.
        assertFalse(
            DesktopVncAuthProvisioner.unauthenticatedStartBlocked(
                localhostSupported = true,
                securityArgs = DesktopVncAuthProvisioner.securityArgs(hasPassword = false)
            )
        )
        assertFalse(
            DesktopVncAuthProvisioner.unauthenticatedStartBlocked(
                localhostSupported = true,
                securityArgs = DesktopVncAuthProvisioner.securityArgs(hasPassword = true)
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
}
