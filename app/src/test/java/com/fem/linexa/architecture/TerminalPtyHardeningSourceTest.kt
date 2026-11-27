package com.fem.linexa.architecture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source pins for the RootFS/Terminal audit's native-PTY and session-lifecycle
 * fixes that cannot be exercised by the plain-JVM test suite (the vendored
 * `TerminalSession` needs an Android Looper, and the native module ships a
 * prebuilt `libtermux.so`).
 *
 * Each assertion names the invariant from `02_DETAILED_FINDINGS.md`, so a future
 * edit that silently reverts a hardening rule fails here with the finding id.
 */
class TerminalPtyHardeningSourceTest {

    private val repoRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, "terminal-emulator/src/main/jni/termux.c").exists()) return@lazy dir
            dir = dir.parentFile
        }
        error("repository root not found")
    }

    private val terminalSession: String by lazy {
        File(
            repoRoot,
            "terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java"
        ).readText()
    }

    private val termuxC: String by lazy {
        File(repoRoot, "terminal-emulator/src/main/jni/termux.c").readText()
    }

    private val ptySession: String by lazy {
        File(
            repoRoot,
            "app/src/main/java/com/fem/linexa/service/runtime/pty/TermuxPtySession.kt"
        ).readText()
    }

    private val containerRuntime: String by lazy {
        File(
            repoRoot,
            "app/src/main/java/com/fem/linexa/service/runtime/container/PRootContainerRuntime.kt"
        ).readText()
    }

    private val terminalManager: String by lazy {
        File(
            repoRoot,
            "app/src/main/java/com/fem/linexa/service/runtime/WorkspaceTerminalManager.kt"
        ).readText()
    }

    private val terminalsViewModel: String by lazy {
        File(
            repoRoot,
            "app/src/main/java/com/fem/linexa/ui/screens/terminal/TerminalsViewModel.kt"
        ).readText()
    }

    private fun methodBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("method not found: $signature", start >= 0)
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
        }
        error("unterminated method: $signature")
    }

    // ── native PTY ─────────────────────────────────────────────────────────

    @Test
    fun pt001NativeSubprocessOwnsTheMasterFdAndNeverUsesJniUnderAPendingException() {
        assertTrue("grantpt failure must close the master", termuxC.contains("close(ptm);\n        return throw_runtime_exception"))
        assertTrue("fork failure must close the master", termuxC.contains("pid < 0") && termuxC.contains("close(ptm);\n        return throw_runtime_exception(env, \"Fork failed\")"))
        assertTrue("partial argv cleanup helper", termuxC.contains("static void free_string_array"))
        assertTrue(
            "never call remaining JNI after create_subprocess threw",
            termuxC.contains("if ((*env)->ExceptionCheck(env)) return -1;")
        )
        assertTrue(
            "release cmd_cwd against cwd, not cmd",
            termuxC.contains("ReleaseStringUTFChars(env, cwd, cmd_cwd)")
        )
    }

    @Test
    fun pt005WaitForRetriesEintrAndInitializesStatus() {
        val waitFor = methodBody(termuxC, "Java_com_termux_terminal_JNI_waitFor")
        assertTrue(waitFor.contains("int status = 0;"))
        assertTrue(waitFor.contains("errno == EINTR"))
        assertTrue(waitFor.contains("if (result < 0) return 0;"))
    }

    @Test
    fun pt006ChdirFailureStopsTheChild() {
        val chdirBranch = termuxC.substringAfter("if (chdir(cwd) != 0)")
            .substringBefore("execvp(cmd, argv)")
        assertTrue("chdir failure must be fatal", chdirBranch.contains("_exit(1);"))
    }

    // ── TerminalSession lifecycle ──────────────────────────────────────────

    @Test
    fun pt002ClosedDescriptorCannotBeResized() {
        assertTrue(
            "descriptor starts invalid",
            terminalSession.contains("private volatile int mTerminalFileDescriptor = -1;")
        )
        assertTrue(
            "updateSize guards the descriptor",
            terminalSession.contains("if (mTerminalFileDescriptor >= 0) {")
        )
        val cleanup = methodBody(terminalSession, "void cleanupResources(int exitStatus)")
        assertTrue("cleanup invalidates before closing", cleanup.contains("mTerminalFileDescriptor = -1;"))
    }

    @Test
    fun pt003ExitDrainsPendingOutputBeforeClosingQueues() {
        assertTrue("reader completion signal", terminalSession.contains("mReaderFinished.countDown()"))
        val handler = methodBody(terminalSession, "public void handleMessage(Message msg)")
        assertTrue(
            "the exit path must run through the bounded drain helper",
            handler.contains("finishAfterProcessExit(exitCode")
        )
        assertFalse(
            "DEEP-AUDIT: the MAIN looper must never block on the reader thread",
            handler.contains("mReaderFinished.await(")
        )
        val drain = methodBody(terminalSession, "private void finishAfterProcessExit(int exitCode, long deadlineNanos)")
        assertTrue(
            "the wait is a re-posted poll, not a blocking await",
            drain.contains("mReaderFinished.getCount() != 0") && drain.contains("postDelayed(")
        )
        assertTrue("queued output is appended", drain.contains("appendPendingOutput();"))
        assertTrue(
            "drain happens before cleanup",
            drain.indexOf("appendPendingOutput();") < drain.indexOf("cleanupResources(exitCode);")
        )
    }

    @Test
    fun pt004InputIsPumpedOffTheCallingThreadWithABound() {
        val write = methodBody(terminalSession, "public void write(byte[] data, int offset, int count)")
        assertTrue("write only enqueues", write.contains("enqueueInput(data, offset, count);"))
        assertFalse("write must not touch the blocking queue directly", write.contains("mTerminalToProcessIOQueue.write"))
        assertTrue("dedicated pump", terminalSession.contains("private void startInputPump()"))
        assertTrue("bounded memory", terminalSession.contains("MAX_PENDING_INPUT_BYTES"))
        assertTrue(
            "overflow is explicit, never silent",
            terminalSession.contains("oldest queued input was dropped")
        )
    }

    @Test
    fun pt007NeverStartedSessionIsNotRunning() {
        assertTrue(
            "pid 0 means not started and must never be signalled",
            terminalSession.contains("return mShellPid > 0;")
        )
    }

    @Test
    fun pt008ReflectionFailureNeverKillsTheProcess() {
        assertFalse(
            "a hidden-API failure must surface for one session, not System.exit the app",
            terminalSession.contains("System.exit(1)")
        )
        assertTrue(
            "the failure is reported to the caller",
            terminalSession.contains("Cannot wrap the PTY descriptor")
        )
    }

    @Test
    fun cc002SharedPidIsVolatile() {
        assertTrue(terminalSession.contains("volatile int mShellPid;"))
    }

    @Test
    fun cc003EmulatorReadsShareAWriteLock() {
        assertTrue("emulator lock exists", terminalSession.contains("public final Object mEmulatorLock"))
        assertTrue("appends hold the lock", terminalSession.contains("synchronized (mEmulatorLock)"))
        assertTrue(
            "cross-thread snapshot readers hold the same lock",
            ptySession.contains("synchronized(session.mEmulatorLock)") &&
                ptySession.contains("currentTranscript(): String = synchronized(session.mEmulatorLock)")
        )
    }

    // ── session lifecycle / concurrency ────────────────────────────────────

    @Test
    fun ss001TeardownSignalsGracefullyThenWaitsBeforeReleasingScratch() {
        assertTrue("graceful signal", terminalSession.contains("finishGracefully()"))
        assertTrue("SIGHUP first", terminalSession.contains("OsConstants.SIGHUP"))
        assertTrue(
            "TermuxPtySession destroy uses it",
            methodBody(ptySession, "override fun destroy()").contains("finishGracefully()")
        )
        val close = methodBody(containerRuntime, "override suspend fun closeTerminal")
        assertTrue("bounded wait for exit", close.contains("withTimeoutOrNull(TERMINAL_EXIT_GRACE_MS)"))
        assertTrue(
            "scratch is released only after the wait",
            close.indexOf("withTimeoutOrNull(TERMINAL_EXIT_GRACE_MS)") <
                close.indexOf("releaseProotTmpDir(prootTmpScope(workspaceId, terminalId))")
        )
        assertTrue(
            "workspace teardown waits before releasing every scratch scope",
            containerRuntime.contains("closed.forEach { session ->") &&
                containerRuntime.contains("releaseProotTmpDir(prootTmpScope(key.workspaceId, key.terminalId))")
        )
    }

    @Test
    fun ss002DiscreteEventsCannotBeDroppedByAFullSharedFlow() {
        assertTrue(
            "F-021: the transient queue is bounded and drops the oldest event",
            ptySession.contains("capacity = EVENT_BUFFER_CAPACITY") &&
                ptySession.contains("onBufferOverflow = BufferOverflow.DROP_OLDEST")
        )
        assertTrue(
            "Finished has its own one-shot lifecycle channel",
            ptySession.contains("private val lifecycleChannel = Channel<PtyEvent>(Channel.UNLIMITED)") &&
                ptySession.contains("if (finishQueued.compareAndSet(false, true)) lifecycleChannel.trySend(event)")
        )
        assertTrue("suspending drain", ptySession.contains("for (event in eventChannel) _events.emit(event)"))
        assertTrue(
            "title/clipboard/paste/bell/finished use the channel",
            listOf(
                "emitEvent(PtyEvent.TitleChanged(",
                "emitEvent(PtyEvent.ClipboardText(",
                "emitEvent(PtyEvent.PasteRequested)",
                "emitEvent(PtyEvent.Bell)",
                "emitEvent(PtyEvent.Finished("
            ).all { ptySession.contains(it) }
        )
    }

    @Test
    fun cc001OpenIsAnUninterruptibleOwnershipHandoff() {
        assertTrue(
            "PTY creation is not cancelled mid-fork",
            containerRuntime.contains("withContext(sessionCreationDispatcher + NonCancellable)")
        )
        assertTrue(
            "a cancelled opener destroys the pty it just registered",
            containerRuntime.contains("sessions.remove(key, session)") &&
                containerRuntime.contains("currentCoroutineContext().ensureActive()")
        )
        assertTrue(
            "manager registration is not cancelled between container return and recording",
            terminalManager.contains("withContext(NonCancellable)") &&
                terminalManager.contains("mutex.withLock {")
        )
        assertTrue(
            "cancellation is not reported as TerminalState.Error",
            terminalManager.contains("catch (e: CancellationException)")
        )
    }

    @Test
    fun rn004FailureAfterRegistrationUnregistersAndDestroys() {
        val body = containerRuntime.substringAfter("sessions[key] = session")
            .substringBefore("runtimeTrace.record(workspaceId, \"openTerminal EXIT")
        assertTrue(body.contains("catch (e: Exception)"))
        assertTrue(body.contains("sessions.remove(key, session)"))
        assertTrue(body.contains("session.destroy()"))
    }

    @Test
    fun rn001SessionsAreSerializedPerWorkspaceNotProcessWide() {
        assertTrue(containerRuntime.contains("private fun workspaceRuntimeLock(workspaceId: String)"))
        assertTrue(containerRuntime.contains("): PtySession = workspaceRuntimeLock(workspaceId).withLock {"))
        assertTrue(
            "closeTerminal uses the same workspace lock",
            containerRuntime.contains("workspaceRuntimeLock(workspaceId).withLock {")
        )
    }

    @Test
    fun rn003RepairsConvergeOncePerWorkspaceSession() {
        assertTrue(
            "the converged stamp is keyed by workspace and rootfs path",
            containerRuntime.contains("private val repairConverged = ConcurrentHashMap<String, String>()") &&
                containerRuntime.contains("repairConverged[workspaceId] == rootfsPath")
        )
        assertTrue(
            "a later terminal skips repairs only while a live pty of the workspace exists",
            containerRuntime.contains(
                "sessions.values.any { it.workspaceId == workspaceId && it.isAlive }"
            )
        )
        assertTrue(
            "a successful pass stamps the rootfs path",
            containerRuntime.contains("repairConverged[workspaceId] = rootfsPath")
        )
        assertTrue(
            "closing the last terminal / the workspace clears the stamp so the next session self-heals",
            containerRuntime.contains("repairConverged.remove(workspaceId)")
        )
        assertTrue(
            "the repair skip must still resolve the launch identity (RN-003 regression)",
            containerRuntime.contains("resolveLaunchIdentity(rootfs, guestEnv, asRoot)") &&
                containerRuntime.contains("if (repairSkipped) {")
        )
    }

    @Test
    fun ts002PreviewSnapshotsRunOnTheEmulatorWriteThread() {
        val body = methodBody(terminalsViewModel, "private suspend fun refreshPreviews()")
        val mainBlock = body.substringAfter("withContext(Dispatchers.Main.immediate)")
            .substringBefore("withContext(Dispatchers.IO)")
        assertTrue(
            "TS-002: preview text must be snapshotted on the Main looper thread",
            mainBlock.contains("pty.previewText(")
        )
        assertTrue(
            "TS-002: the runtime title is emulator state and must be snapshotted on Main",
            mainBlock.contains("pty.title")
        )
        assertFalse(
            "TS-002: the /proc foreground probe must stay off Main",
            mainBlock.contains("foregroundProcessName()")
        )
        assertTrue(
            "the /proc foreground probe still runs on IO",
            body.contains("foregroundProcessName()")
        )
    }
}
