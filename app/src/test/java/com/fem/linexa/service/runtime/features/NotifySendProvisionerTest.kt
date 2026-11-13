package com.fem.linexa.service.runtime.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.111: [NotifySendProvisioner] — the pure half of the Notifications feature.
 *
 * Two things are pinned here. First the *contract with the guest*: the shim
 * script's shape (POSIX-only, write-then-rename, never fails the caller) and the
 * lossless NUL-separated argv record it produces, since a mismatch between the
 * shim and [NotifySendProvisioner.decodeRecord] would silently drop every
 * notification. Second the *hardening*: summary/body are attacker-controlled
 * (arbitrary code the user ran inside their workspace), so bounding and control-
 * character stripping are asserted, not assumed.
 */
class NotifySendProvisionerTest {

    // ── the shim script's contract with the guest ──

    @Test
    fun theShimIsPosixShAndNeverFailsTheCallingScript() {
        val script = NotifySendProvisioner.shimScript()

        assertTrue("must be POSIX sh, not bash: a rootfs may ship only busybox ash", script.startsWith("#!/bin/sh"))
        assertFalse("no bashisms", script.contains("[["))
        assertFalse("no \$RANDOM (not POSIX)", script.contains("\$RANDOM"))
        assertFalse("no mktemp dependency", script.contains("mktemp"))
        // Every failure path exits 0 — the shim replaces a command inside
        // someone else's script and must never break it.
        assertTrue(script.contains("exit 0"))
        assertFalse("must not exit non-zero anywhere", Regex("exit [1-9]").containsMatchIn(script))
    }

    @Test
    fun theShimWritesATempFileAndRenamesItSoTheHostNeverReadsAPartialRecord() {
        val script = NotifySendProvisioner.shimScript()
        val lines = script.lines()

        // The invariant: the ONLY name printf ever writes to carries the temp
        // extension, and the record extension the host polls for appears solely
        // as the rename target. The shim reaches that through a `tmp=` variable,
        // so the assertions follow that variable rather than assuming the path
        // is spelled out inline on the printf line.
        val tmpAssignment = lines.first { it.trimStart().startsWith("tmp=") }
        assertTrue(
            "the temp path must carry the temp extension: $tmpAssignment",
            tmpAssignment.contains(NotifySendProvisioner.RECORD_TEMP_EXTENSION)
        )
        assertFalse(
            "the temp path must not be the name the host polls for: $tmpAssignment",
            tmpAssignment.contains(NotifySendProvisioner.RECORD_EXTENSION)
        )

        val printfLine = lines.first { it.contains("printf") }
        assertTrue("printf writes the temp path", printfLine.contains("> \"\$tmp\""))
        assertFalse(
            "printf must not write the final name: $printfLine",
            printfLine.contains(NotifySendProvisioner.RECORD_EXTENSION)
        )

        // The commit is a rename onto the polled name.
        val moveLine = lines.first { it.trimStart().startsWith("mv ") }
        assertTrue("commits by renaming the temp path", moveLine.contains("\"\$tmp\""))
        assertTrue(
            "the rename target is the name the host polls for: $moveLine",
            moveLine.contains(NotifySendProvisioner.RECORD_EXTENSION)
        )
    }

    @Test
    fun theShimDumpsArgvVerbatimAsNulSeparatedBytes() {
        // This is what makes decodeRecord lossless — parsing notify-send's
        // grammar in sh would be fragile and untestable, so the shim does none.
        assertTrue(NotifySendProvisioner.shimScript().contains("""printf '%s\0' "${'$'}@""""))
    }

    @Test
    fun theInstallCommandUsesAQuotedHeredocSoTheGuestShellExpandsNothing() {
        val command = NotifySendProvisioner.installCommand()

        // Unquoted <<EOF would expand $@/$$ in the shim body before it hit disk.
        assertTrue("heredoc delimiter must be quoted", command.contains("<<'LINEXA_EOF'"))
        assertTrue(command.contains("${'$'}@"))
        assertTrue("installs at the documented path", command.contains(NotifySendProvisioner.SHIM_GUEST_PATH))
        assertTrue("creates the spool", command.contains(NotifySendProvisioner.SPOOL_GUEST_DIR))
        assertTrue("makes the shim executable", command.contains("chmod 0755 ${NotifySendProvisioner.SHIM_GUEST_PATH}"))
    }

    @Test
    fun theUninstallCommandIsIdempotentAndRemovesShimAndPendingRecords() {
        val command = NotifySendProvisioner.uninstallCommand()

        assertTrue(command.contains("rm -f ${NotifySendProvisioner.SHIM_GUEST_PATH}"))
        assertTrue(command.contains(NotifySendProvisioner.RECORD_EXTENSION))
        assertTrue("a guest that never had the shim must still exit 0", command.trimEnd().endsWith("exit 0"))
    }

    @Test
    fun theGuestSpoolPathAndTheHostRelativePathDescribeTheSameDirectory() {
        // The host reads {workspace}/rootfs/<relative>; the guest writes
        // <absolute>. A drift between the two silently breaks the whole feature.
        assertEquals(
            NotifySendProvisioner.SPOOL_GUEST_DIR,
            "/" + NotifySendProvisioner.SPOOL_RELATIVE_PATH
        )
    }

    // ── record codec ──

    private fun record(vararg argv: String): ByteArray =
        argv.joinToString("") { "$it\u0000" }.toByteArray()

    @Test
    fun decodeRecoversArgvExactly() {
        val argv = NotifySendProvisioner.decodeRecord(record("Build done", "3 tests failed"))
        assertEquals(listOf("Build done", "3 tests failed"), argv)
    }

    @Test
    fun decodePreservesSpacesAndNewlinesInsideAnArgument() {
        val argv = NotifySendProvisioner.decodeRecord(record("multi\nline summary", "a  b"))
        assertEquals(listOf("multi\nline summary", "a  b"), argv)
    }

    @Test
    fun decodePreservesAnEmptyArgumentButDropsTheTrailingSeparator() {
        assertEquals(listOf("summary", ""), NotifySendProvisioner.decodeRecord(record("summary", "")))
    }

    @Test
    fun decodeRejectsEmptyAndOversizedRecords() {
        assertNull(NotifySendProvisioner.decodeRecord(ByteArray(0)))
        val huge = ByteArray(NotifySendProvisioner.MAX_RECORD_BYTES + 1) { 'x'.code.toByte() }
        assertNull("a guest must not be able to make the host allocate", NotifySendProvisioner.decodeRecord(huge))
    }

    // ── notify-send grammar ──

    private fun parse(vararg argv: String) = NotifySendProvisioner.parseNotifySend(argv.toList())

    @Test
    fun summaryOnly() {
        val n = parse("Build finished")!!
        assertEquals("Build finished", n.summary)
        assertNull(n.body)
        assertEquals(NotificationUrgency.NORMAL, n.urgency)
    }

    @Test
    fun summaryAndBody() {
        val n = parse("Build finished", "42 tests passed")!!
        assertEquals("Build finished", n.summary)
        assertEquals("42 tests passed", n.body)
    }

    @Test
    fun noArgumentsPostsNothing() {
        // Real notify-send prints usage and posts nothing.
        assertNull(NotifySendProvisioner.parseNotifySend(emptyList()))
    }

    @Test
    fun flagsOnlyPostsNothing() {
        assertNull(parse("-u", "critical"))
    }

    @Test
    fun separatedAndAttachedFlagFormsAreEquivalent() {
        val separated = parse("-u", "critical", "-a", "make", "Done")!!
        val attached = parse("--urgency=critical", "--app-name=make", "Done")!!

        assertEquals(NotificationUrgency.CRITICAL, separated.urgency)
        assertEquals(separated.urgency, attached.urgency)
        assertEquals("make", separated.appName)
        assertEquals(separated.appName, attached.appName)
        assertEquals("Done", separated.summary)
        assertEquals(separated.summary, attached.summary)
    }

    @Test
    fun aFlagValueIsNeverMistakenForTheSummary() {
        // -i's value must be skipped, not adopted as the title.
        val n = parse("-i", "dialog-information", "Real summary")!!
        assertEquals("Real summary", n.summary)
        // Same for the hint flag, whose payload looks like ordinary text.
        assertEquals("Real summary", parse("-h", "string:x:y", "Real summary")!!.summary)
    }

    @Test
    fun valuelessFlagsDoNotSwallowTheSummary() {
        assertEquals("Real summary", parse("-e", "Real summary")!!.summary)
        assertEquals("Real summary", parse("--transient", "--print-id", "Real summary")!!.summary)
    }

    @Test
    fun anUnknownFlagIsIgnoredRatherThanBecomingTheSummary() {
        // A flag from a newer notify-send must not silently become the title.
        val n = parse("--some-future-flag", "Real summary")!!
        assertEquals("Real summary", n.summary)
    }

    @Test
    fun doubleDashForcesTheRestToBePositional() {
        val n = parse("--", "-u", "critical")!!
        assertEquals("-u", n.summary)
        assertEquals("critical", n.body)
        assertEquals("urgency after -- is text, not a flag", NotificationUrgency.NORMAL, n.urgency)
    }

    @Test
    fun urgencyAcceptsNamesAndNumbersAndFallsBackToNormal() {
        assertEquals(NotificationUrgency.LOW, parse("-u", "low", "s")!!.urgency)
        assertEquals(NotificationUrgency.LOW, parse("-u", "0", "s")!!.urgency)
        assertEquals(NotificationUrgency.CRITICAL, parse("-u", "CRITICAL", "s")!!.urgency)
        assertEquals(NotificationUrgency.CRITICAL, parse("-u", "2", "s")!!.urgency)
        // Urgency is a hint, never a gate — garbage must not drop the message.
        assertEquals(NotificationUrgency.NORMAL, parse("-u", "banana", "s")!!.urgency)
    }

    @Test
    fun replaceIdAndExpireTimeAreCarried() {
        val n = parse("-r", "42", "-t", "5000", "s")!!
        assertEquals("42", n.replaceId)
        assertEquals(5_000L, n.expireMillis)
    }

    @Test
    fun aNonPositiveOrNonNumericExpireTimeIsDropped() {
        assertNull(parse("-t", "0", "s")!!.expireMillis)
        assertNull(parse("-t", "-1", "s")!!.expireMillis)
        assertNull(parse("-t", "soon", "s")!!.expireMillis)
    }

    @Test
    fun aTrailingFlagWithNoValueDoesNotThrow() {
        val n = parse("Summary", "-u")
        assertEquals("Summary", n!!.summary)
    }

    // ── hardening: guest content is untrusted (§1.14) ──

    @Test
    fun controlCharactersAreStrippedFromGuestText() {
        val n = parse("Sum\u0007ma\u001bry", "bo\u0000dy")!!
        assertFalse(n.summary.contains('\u0007'))
        assertFalse(n.summary.contains('\u001b'))
        assertFalse(n.body!!.contains('\u0000'))
    }

    @Test
    fun newlinesAreCollapsedSoAGuestCannotFakeNotificationStructure() {
        val n = parse("line one\nline two", "body\n\nwith gaps")!!
        assertFalse(n.summary.contains('\n'))
        assertFalse(n.body!!.contains('\n'))
        assertEquals("line one line two", n.summary)
    }

    @Test
    fun summaryAndBodyAreBounded() {
        val n = parse("s".repeat(5_000), "b".repeat(50_000))!!
        assertTrue(n.summary.length <= NotifySendProvisioner.MAX_SUMMARY_CHARS + 1)
        assertTrue(n.body!!.length <= NotifySendProvisioner.MAX_BODY_CHARS + 1)
        assertTrue("a truncated field is marked", n.summary.endsWith("…"))
    }

    @Test
    fun aWhitespaceOnlySummaryPostsNothing() {
        // An empty notification is worse than none.
        assertNull(parse("   \t  "))
        assertNull(parse("\u0000\u0007"))
    }

    @Test
    fun aWhitespaceOnlyBodyBecomesNullRatherThanAnEmptyLine() {
        assertNull(parse("Summary", "   ")!!.body)
    }

    // ── notification identity ──

    @Test
    fun replaceIdMakesTheTagStableSoASecondCallUpdatesInsteadOfStacking() {
        val first = NotifySendProvisioner.notificationTag("ws-1", parse("-r", "42", "First")!!)
        val second = NotifySendProvisioner.notificationTag("ws-1", parse("-r", "42", "Second")!!)
        assertEquals("that is exactly what notify-send -r means", first, second)
    }

    @Test
    fun differentSummariesStackWhenNoReplaceIdIsGiven() {
        val a = NotifySendProvisioner.notificationTag("ws-1", parse("Alpha")!!)
        val b = NotifySendProvisioner.notificationTag("ws-1", parse("Beta")!!)
        assertFalse(a == b)
    }

    @Test
    fun tagsAreNamespacedPerWorkspace() {
        // One workspace must never be able to overwrite another's notification.
        val ws1 = NotifySendProvisioner.notificationTag("ws-1", parse("-r", "42", "x")!!)
        val ws2 = NotifySendProvisioner.notificationTag("ws-2", parse("-r", "42", "x")!!)
        assertFalse(ws1 == ws2)
        assertTrue(ws1.contains("ws-1"))
    }

    // ── scope guard ──

    @Test
    fun theGuestCommandsTouchOnlyTheShimAndTheSpool() {
        listOf(NotifySendProvisioner.installCommand(), NotifySendProvisioner.uninstallCommand()).forEach { command ->
            assertFalse(command, command.contains("reboot"))
            assertFalse(command, command.contains("shutdown"))
            assertFalse(command, command.contains("passwd"))
            assertFalse(command, command.contains("sshd"))
            // No recursive delete anywhere near a feature teardown.
            assertFalse(command, command.contains("rm -rf"))
        }
    }
}
