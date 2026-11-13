package com.fem.linexa.domain.filemanager

import com.fem.linexa.service.runtime.WorkspaceHealthRegistry
import com.fem.linexa.service.runtime.RuntimeBinder
import com.fem.linexa.service.runtime.RuntimeTrace
import com.fem.linexa.service.runtime.SessionManager
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.runtime.WorkspaceTerminalManager
import com.fem.linexa.domain.runtime.CommandResult
import com.fem.linexa.service.runtime.container.ContainerRuntime
import com.fem.linexa.service.runtime.container.RecoverySweepReport
import com.fem.linexa.service.runtime.features.RuntimeGuestFeatureHost
import com.fem.linexa.service.runtime.pty.PtySession
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito

/**
 * §1.115 — JVM tests for [WorkspaceFileManagerImpl]: the exact guest commands
 * it issues, and the fact that a guest failure is now *returned* rather than
 * disguised as a plausible success value.
 *
 * The pre-§1.115 implementation had no tests at all. Its failure mode was not
 * a crash but a lie: `listFiles` turned a failed `ls` into an empty directory,
 * `readFile` turned a failed `cat` into a blank file, and `delete` reported
 * success for a path that never existed (`rm -rf` exits 0 on a missing path).
 * Each of those is pinned below.
 *
 * Commands are asserted verbatim because their exact shape is the security
 * boundary: single-quoted paths (§1.19's injection fix) plus `--`
 * end-of-options, so a file literally named `-rf` cannot become a flag.
 */
class WorkspaceFileManagerImplTest {

    private val subject = WorkspaceFileManagerImpl()
    private val fileManager = Mockito.mock(WorkspaceFileManager::class.java)

    private fun binder(runtime: ScriptedRuntime): RuntimeBinder {
        Mockito.`when`(fileManager.getRootfsPath(anyString())).thenReturn(File("/tmp/rootfs"))
        val sessionManager = Mockito.mock(SessionManager::class.java)
        return RuntimeBinder(
            sessionManager = sessionManager,
            containerRuntime = runtime,
            fileManager = fileManager,
            workspaceTerminalManager = WorkspaceTerminalManager(
                containerRuntime = runtime,
                runtimeTrace = RuntimeTrace()
            ),
            guestFeatureHost = RuntimeGuestFeatureHost(sessionManager, runtime, fileManager),
            events = MutableSharedFlow(),
            healthRegistry = WorkspaceHealthRegistry()
        )
    }

    // ── listFiles ───────────────────────────────────────────────────────────

    @Test
    fun listFilesPinsTheGuestCommand() = runBlocking {
        val runtime = ScriptedRuntime { CommandResult(0, "") }
        subject.listFiles(binder(runtime), "ws1", "/etc/apt")
        // LC_ALL=C pins the month names the parser matches; -A hides ./..;
        // -- stops a path beginning with '-' from being read as an option.
        assertEquals("LC_ALL=C ls -lA -- '/etc/apt'", runtime.commands.single())
    }

    @Test
    fun listFilesSingleQuotesAndEscapesAPathWithAQuote() = runBlocking {
        val runtime = ScriptedRuntime { CommandResult(0, "") }
        subject.listFiles(binder(runtime), "ws1", "/tmp/it's here")
        assertEquals("LC_ALL=C ls -lA -- '/tmp/it'\\''s here'", runtime.commands.single())
    }

    @Test
    fun listFilesReturnsParsedEntries() = runBlocking {
        val output = "-rw-r--r-- 1 root root 12 Aug 10 19:31 hosts"
        val runtime = ScriptedRuntime { CommandResult(0, output) }
        val result = subject.listFiles(binder(runtime), "ws1", "/etc")
        val entries = (result as FileOpResult.Ok).value
        assertEquals("hosts", entries.single().name)
        assertEquals("/etc/hosts", entries.single().path)
    }

    @Test
    fun aFailedListIsAFailureNotAnEmptyDirectory() = runBlocking {
        // THE bug this class exists for: the old code returned emptyList(), so
        // a permission error rendered as an empty folder.
        val runtime = ScriptedRuntime {
            CommandResult(2, "ls: cannot open directory '/root': Permission denied")
        }
        val result = subject.listFiles(binder(runtime), "ws1", "/root")
        val failure = result as FileOpResult.Failure
        assertEquals(2, failure.exitCode)
        assertTrue(failure.message.contains("Permission denied"))
    }

    @Test
    fun aTimeoutIsReportedAsATimeoutNotAsAGuestError() = runBlocking {
        // PRootContainerRuntime encodes a timeout as exit -1 with no output;
        // reporting the (empty) guest message would say nothing at all.
        val runtime = ScriptedRuntime { CommandResult(-1, "") }
        val failure = subject.listFiles(binder(runtime), "ws1", "/") as FileOpResult.Failure
        assertTrue(failure.message.endsWith("timed out"))
    }

    @Test
    fun aFailureWithNoGuestMessageStillCarriesContext() = runBlocking {
        val runtime = ScriptedRuntime { CommandResult(1, "") }
        val failure = subject.listFiles(binder(runtime), "ws1", "/etc") as FileOpResult.Failure
        assertTrue(failure.message.contains("/etc"))
    }

    @Test
    fun aGuestErrorMessageIsBounded() = runBlocking {
        val runtime = ScriptedRuntime { CommandResult(1, "x".repeat(5_000)) }
        val failure = subject.listFiles(binder(runtime), "ws1", "/etc") as FileOpResult.Failure
        assertTrue("message was ${failure.message.length} chars", failure.message.length < 400)
    }

    @Test
    fun stderrIsPreferredOverStdoutForTheReason() = runBlocking {
        val runtime = ScriptedRuntime {
            CommandResult(exitCode = 1, output = "partial listing", error = "ls: No such file or directory")
        }
        val failure = subject.listFiles(binder(runtime), "ws1", "/nope") as FileOpResult.Failure
        assertTrue(failure.message.contains("No such file"))
    }

    // ── readFile ────────────────────────────────────────────────────────────

    /**
     * The exec bridge captures one-shot output as a trimmed string, so plain
     * `cat` cannot round-trip trailing whitespace. readFile therefore ships
     * the bytes base64-encoded and decodes on the host — these helpers build
     * the guest payload the real `base64` command would print (76-column
     * wrapping included, since wrapping must not change the answer).
     */
    private fun base64Of(content: String): String =
        java.util.Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
            .chunked(76)
            .joinToString("\n")
            .plus("\n")

    @Test
    fun readFilePinsTheGuestCommand() = runBlocking {
        val runtime = ScriptedRuntime { CommandResult(0, base64Of("content\n")) }
        val result = subject.readFile(binder(runtime), "ws1", "/etc/hosts")
        assertTrue(result is FileOpResult.Ok)
        assertEquals("base64 -- '/etc/hosts'", runtime.commands.single())
    }

    @Test
    fun readFileIsByteExactIncludingTrailingNewlines() = runBlocking {
        // The old `cat` read came back trimmed, losing exactly what a save had
        // just written when the content ended in blank lines — and the next
        // save persisted that loss. base64 cannot be trimmed destructively.
        val content = "Hello from Linexa\nTest line 2\nSave verification\n\n"
        val runtime = ScriptedRuntime { CommandResult(0, base64Of(content)) }
        val result = subject.readFile(binder(runtime), "ws1", "/home/user/notes.txt") as FileOpResult.Ok
        assertEquals(content, result.value)
    }

    @Test
    fun readFileOfAnEmptyFileIsEmpty() = runBlocking {
        val runtime = ScriptedRuntime { CommandResult(0, "") }
        val result = subject.readFile(binder(runtime), "ws1", "/tmp/empty") as FileOpResult.Ok
        assertEquals("", result.value)
    }

    @Test
    fun readFilePreservesUnicode() = runBlocking {
        val content = "héllo — ünïcode\n\temoji: 🐧 ✓\n"
        val runtime = ScriptedRuntime { CommandResult(0, base64Of(content)) }
        assertEquals(content, (subject.readFile(binder(runtime), "ws1", "/p") as FileOpResult.Ok).value)
    }

    @Test
    fun readRejectsGarbageInsteadOfSurfacingCorruptContent() = runBlocking {
        // A successful exit with invalid payload (binaried stderr pollution is
        // the only realistic route) must fail loudly, never render as text.
        val runtime = ScriptedRuntime { CommandResult(0, "!!! not base64 !!!") }
        val result = subject.readFile(binder(runtime), "ws1", "/p")
        assertTrue(result is FileOpResult.Failure)
    }

    @Test
    fun readFileToleratesUnwrappedBase64() = runBlocking {
        // busybox wraps at 76 columns like GNU, but a payload that arrives in
        // one line decodes identically — the decoder is whitespace-agnostic.
        val runtime = ScriptedRuntime {
            CommandResult(0, java.util.Base64.getEncoder().encodeToString("one line\n".toByteArray()))
        }
        assertEquals("one line\n", (subject.readFile(binder(runtime), "ws1", "/p") as FileOpResult.Ok).value)
    }

    @Test
    fun aFailedReadIsAFailureNotABlankFile() = runBlocking {
        val runtime = ScriptedRuntime { CommandResult(1, "cat: /x: Is a directory") }
        val failure = subject.readFile(binder(runtime), "ws1", "/x") as FileOpResult.Failure
        assertTrue(failure.message.contains("Is a directory"))
    }

    // ── writeFile ───────────────────────────────────────────────────────────

    @Test
    fun writeUsesAQuotedHeredocSoContentIsNotExpanded() = runBlocking {
        val runtime = ScriptedRuntime { CommandResult(0, "") }
        subject.writeFile(binder(runtime), "ws1", "/tmp/f", "\$HOME `id` \\n")
        val command = runtime.commands.single()
        // A QUOTED delimiter is what makes the body literal. `echo`/an unquoted
        // heredoc would expand $HOME, run `id`, and interpret \n.
        assertTrue("command was: $command", command.contains("<<'LINEXA_EOF'"))
        assertTrue(command.contains("\$HOME `id` \\n"))
        assertTrue(command.startsWith("cat > '/tmp/f'"))
    }

    @Test
    fun writeAlwaysTerminatesTheHeredocOnItsOwnLine() = runBlocking {
        val runtime = ScriptedRuntime { CommandResult(0, "") }
        subject.writeFile(binder(runtime), "ws1", "/tmp/f", "no trailing newline")
        val command = runtime.commands.single()
        assertTrue("command was: $command", command.endsWith("\nLINEXA_EOF\n"))
    }

    @Test
    fun writeDoesNotDoubleTheTrailingNewline() = runBlocking {
        val runtime = ScriptedRuntime { CommandResult(0, "") }
        subject.writeFile(binder(runtime), "ws1", "/tmp/f", "already\n")
        assertTrue(runtime.commands.single().contains("already\nLINEXA_EOF"))
    }

    @Test
    fun writeOfEmptyContentStaysAnEmptyFile() = runBlocking {
        // An empty buffer used to become a one-`\n` file: the heredoc body was
        // unconditionally newline-terminated. Empty must mean an empty heredoc
        // body — `cat` then writes zero bytes.
        val runtime = ScriptedRuntime { CommandResult(0, "") }
        subject.writeFile(binder(runtime), "ws1", "/tmp/f", "")
        assertEquals(
            "command was: ${runtime.commands.single()}",
            "cat > '/tmp/f' <<'LINEXA_EOF'\nLINEXA_EOF\n",
            runtime.commands.single()
        )
    }

    @Test
    fun writeOfBlankLinesOnlyRoundTripsBodyVerbatim() = runBlocking {
        // Content made of trailing blank lines ends in "\n", so it is written
        // verbatim — combined with the base64 read, save→reopen must restore
        // it exactly.
        val runtime = ScriptedRuntime { CommandResult(0, "") }
        subject.writeFile(binder(runtime), "ws1", "/tmp/f", "a\n\n")
        val command = runtime.commands.single()
        assertTrue("command was: $command", command.contains("\na\n\nLINEXA_EOF\n"))
        // ... and the delimiter is not doubled:
        assertTrue("command was: $command", command.endsWith("\nLINEXA_EOF\n"))
    }

    @Test
    fun writePicksADifferentDelimiterWhenTheContentContainsIt() = runBlocking {
        // Content that contains the delimiter on its own line would end the
        // heredoc early, truncating the file and executing the rest as shell.
        val runtime = ScriptedRuntime { CommandResult(0, "") }
        subject.writeFile(binder(runtime), "ws1", "/tmp/f", "a\nLINEXA_EOF\nb")
        val command = runtime.commands.single()
        assertTrue("command was: $command", command.contains("<<'LINEXA_EOF_2'"))
        assertTrue(command.endsWith("\nLINEXA_EOF_2\n"))
    }

    @Test
    fun writeRefusesContentThatContainsEveryDelimiter() = runBlocking {
        val runtime = ScriptedRuntime { CommandResult(0, "") }
        val content = "LINEXA_EOF\nLINEXA_EOF_2\nLINEXA_EOF_3_9f2c1b\n"
        val result = subject.writeFile(binder(runtime), "ws1", "/tmp/f", content)
        assertTrue(result is FileOpResult.Failure)
        // Refusing must not have run anything.
        assertTrue(runtime.commands.isEmpty())
    }

    // ── createDirectory ─────────────────────────────────────────────────────

    @Test
    fun createDirectoryDoesNotUseDashPSoAnExistingFolderIsNotClaimedAsCreated() = runBlocking {
        val runtime = ScriptedRuntime { CommandResult(0, "") }
        subject.createDirectory(binder(runtime), "ws1", "/tmp/new")
        assertEquals("mkdir -- '/tmp/new'", runtime.commands.single())
    }

    @Test
    fun createDirectoryReportsTheGuestReasonOnFailure() = runBlocking {
        val runtime = ScriptedRuntime { CommandResult(1, "mkdir: can't create directory 'x': File exists") }
        val failure = subject.createDirectory(binder(runtime), "ws1", "/tmp/x") as FileOpResult.Failure
        assertTrue(failure.message.contains("File exists"))
    }

    // ── delete ──────────────────────────────────────────────────────────────

    @Test
    fun deleteChecksExistenceFirstBecauseRmMinusFExitsZeroOnAMissingPath() = runBlocking {
        // The §1.115 listing bug made every path wrong, and `rm -rf` on a wrong
        // path exits 0 — so the UI reported "Deleted" while nothing happened.
        val runtime = ScriptedRuntime { command ->
            if (command.startsWith("test ")) CommandResult(1, "") else CommandResult(0, "")
        }
        val result = subject.delete(binder(runtime), "ws1", "/tmp/gone")
        assertTrue(result is FileOpResult.Failure)
        assertTrue("commands: ${runtime.commands}", runtime.commands.none { it.startsWith("rm ") })
    }

    @Test
    fun deleteRunsRmWhenThePathExists() = runBlocking {
        val runtime = ScriptedRuntime { CommandResult(0, "") }
        val result = subject.delete(binder(runtime), "ws1", "/tmp/f")
        assertTrue(result is FileOpResult.Ok)
        assertEquals("rm -rf -- '/tmp/f'", runtime.commands.last())
    }

    @Test
    fun deleteQuotesAndTerminatesOptionsForAFileNamedLikeAFlag() = runBlocking {
        val runtime = ScriptedRuntime { CommandResult(0, "") }
        subject.delete(binder(runtime), "ws1", "/tmp/-rf")
        assertEquals("rm -rf -- '/tmp/-rf'", runtime.commands.last())
    }

    // ── rename ──────────────────────────────────────────────────────────────

    @Test
    fun renameRefusesToOverwriteAndConfirmsTheMoveHappened() = runBlocking {
        // busybox `mv -n` exits 0 when it declines, so exit status alone would
        // report a rename that silently did not occur.
        val runtime = ScriptedRuntime { command ->
            if (command.startsWith("test ")) CommandResult(0, "") else CommandResult(0, "")
        }
        val result = subject.rename(binder(runtime), "ws1", "/tmp/a", "/tmp/b")
        assertTrue("expected failure when the source still exists, got $result", result is FileOpResult.Failure)
        assertTrue(runtime.commands.any { it == "mv -n -- '/tmp/a' '/tmp/b'" })
    }

    @Test
    fun renameSucceedsWhenTheSourceIsGoneAfterwards() = runBlocking {
        val runtime = ScriptedRuntime { command ->
            if (command.startsWith("test ")) CommandResult(1, "") else CommandResult(0, "")
        }
        val result = subject.rename(binder(runtime), "ws1", "/tmp/a", "/tmp/b")
        assertTrue(result is FileOpResult.Ok)
    }

    // ── copy ────────────────────────────────────────────────────────────────

    @Test
    fun copyPinsTheGuestCommand() = runBlocking {
        val runtime = ScriptedRuntime { CommandResult(0, "") }
        subject.copy(binder(runtime), "ws1", "/tmp/a", "/tmp/b")
        assertEquals("cp -r -- '/tmp/a' '/tmp/b'", runtime.commands.single())
    }

    // ── exists / getSize ────────────────────────────────────────────────────

    @Test
    fun existsAlsoMatchesADanglingSymlink() = runBlocking {
        // -e follows the link, so a dangling symlink would read as absent and
        // become undeletable through the UI.
        val runtime = ScriptedRuntime { CommandResult(0, "") }
        assertTrue(subject.exists(binder(runtime), "ws1", "/tmp/link"))
        assertEquals("test -e '/tmp/link' -o -L '/tmp/link'", runtime.commands.single())
    }

    @Test
    fun existsIsFalseOnNonZeroExit() = runBlocking {
        val runtime = ScriptedRuntime { CommandResult(1, "") }
        assertFalse(subject.exists(binder(runtime), "ws1", "/tmp/x"))
    }

    @Test
    fun getSizeUsesThePosixDuFlagsBusyboxAlsoHas() = runBlocking {
        // `du -sb` is GNU-only; busybox du has no -b, so the old command
        // failed outright on an Alpine rootfs.
        val runtime = ScriptedRuntime { CommandResult(0, "12\t/tmp/d") }
        val result = subject.getSize(binder(runtime), "ws1", "/tmp/d")
        assertEquals("du -sk -- '/tmp/d'", runtime.commands.single())
        assertEquals(12L * 1024, (result as FileOpResult.Ok).value)
    }

    @Test
    fun getSizeToleratesSpaceSeparatedDuOutput() = runBlocking {
        val runtime = ScriptedRuntime { CommandResult(0, "  4 /tmp/d") }
        val result = subject.getSize(binder(runtime), "ws1", "/tmp/d")
        assertEquals(4L * 1024, (result as FileOpResult.Ok).value)
    }

    @Test
    fun getSizeFailsRatherThanReportingZeroBytes() = runBlocking {
        val runtime = ScriptedRuntime { CommandResult(1, "du: /x: Permission denied") }
        val result = subject.getSize(binder(runtime), "ws1", "/x")
        assertTrue(result is FileOpResult.Failure)
    }

    @Test
    fun getSizeFailsOnUnparseableOutputInsteadOfInventingANumber() = runBlocking {
        val runtime = ScriptedRuntime { CommandResult(0, "not a number") }
        assertTrue(subject.getSize(binder(runtime), "ws1", "/x") is FileOpResult.Failure)
    }

    // ── cross-cutting ───────────────────────────────────────────────────────

    @Test
    fun everyPathBearingCommandTerminatesItsOptions() = runBlocking {
        val runtime = ScriptedRuntime { CommandResult(0, "") }
        val b = binder(runtime)
        subject.listFiles(b, "ws1", "/p")
        subject.readFile(b, "ws1", "/p")
        subject.createDirectory(b, "ws1", "/p")
        subject.copy(b, "ws1", "/p", "/q")
        subject.rename(b, "ws1", "/p", "/q")
        val pathCommands = runtime.commands.filter { !it.startsWith("test ") && !it.startsWith("cat > ") }
        assertTrue("commands: $pathCommands", pathCommands.isNotEmpty())
        assertTrue(
            "every path-bearing command must pass -- : $pathCommands",
            pathCommands.all { it.contains(" -- ") }
        )
    }

    @Test
    fun noCommandInterpolatesAPathWithoutQuoting() = runBlocking {
        val runtime = ScriptedRuntime { CommandResult(0, "") }
        val b = binder(runtime)
        val nasty = "/tmp/a b;rm -rf /"
        subject.listFiles(b, "ws1", nasty)
        subject.readFile(b, "ws1", nasty)
        subject.copy(b, "ws1", nasty, nasty)
        assertTrue(
            "an unquoted path reached the shell: ${runtime.commands}",
            runtime.commands.none { it.contains("$nasty ") || it.endsWith(nasty) }
        )
        assertTrue(runtime.commands.all { it.contains("'$nasty'") })
    }

    /** [ContainerRuntime] that records commands and answers via [behavior]. */
    private class ScriptedRuntime(
        private val behavior: (command: String) -> CommandResult
    ) : ContainerRuntime {
        val commands = mutableListOf<String>()

        override suspend fun exec(workspaceId: String, rootfsPath: String, command: String): CommandResult {
            commands += command
            return behavior(command)
        }

        override suspend fun execStreaming(
            workspaceId: String,
            rootfsPath: String,
            command: String,
            timeoutSeconds: Long,
            onLine: (String) -> Unit
        ): CommandResult = exec(workspaceId, rootfsPath, command)

        override suspend fun openTerminal(
        workspaceId: String,
        rootfsPath: String,
        terminalId: String,
        asRoot: Boolean
    ): PtySession = error("openTerminal not used in this test")

        override fun getTerminal(workspaceId: String, terminalId: String): PtySession? = null
        override fun isTerminalAlive(workspaceId: String, terminalId: String): Boolean = false
        override suspend fun closeTerminal(workspaceId: String, terminalId: String) = Unit
        override suspend fun closeWorkspaceSessions(workspaceId: String) = Unit
        override suspend fun startDaemon(workspaceId: String, rootfsPath: String, name: String, command: String) = false
        override suspend fun stopDaemon(workspaceId: String, name: String) = Unit
        override fun isDaemonAlive(workspaceId: String, name: String): Boolean = false
        override suspend fun stopAllDaemons(workspaceId: String) = Unit
        override suspend fun startupRecoverySweep(): RecoverySweepReport = RecoverySweepReport(0, 0, 0)
    }
}
