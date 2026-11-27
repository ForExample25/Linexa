package com.fem.linexa.service.installer

import com.fem.linexa.core.filesystem.ProcessSignal
import com.fem.linexa.core.filesystem.ProcessSignalSender
import com.fem.linexa.core.filesystem.ProcessTableReader
import com.fem.linexa.core.filesystem.ProcessTreeTerminator
import com.fem.linexa.core.logging.DebugCapture
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * RF-010 — cancelling an installation must stop the extraction child tree and
 * end the coroutine as cancelled, not run to completion (or to the timeout).
 *
 * The REAL extractor runs against [FakeProot]'s `IGNORES_TERM` scenario: the
 * PRoot host traps TERM and waits on a real `sleep` child, so only SIGKILL can
 * end the tree. The production `Os.kill` is a JVM stub, so the only substituted
 * seam is the sender (it shells out to the host `kill`); the snapshot,
 * escalation and verification logic are the real
 * [ProcessTreeTerminator].
 */
class RootfsExtractorCancellationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prootDir: File
    private lateinit var harness: ProotRuntimeTestHarness

    private val selfPid: Int by lazy { File("/proc/self").canonicalFile.name.toInt() }
    private val selfUid: Int by lazy {
        File("/proc/self/status").readLines()
            .first { it.startsWith("Uid:") }
            .removePrefix("Uid:").trim().split(Regex("\\s+")).first().toInt()
    }

    private val hostTerminator: ProcessTreeTerminator by lazy {
        ProcessTreeTerminator(
            expectedUid = selfUid,
            sender = ProcessSignalSender { target, signal ->
                if (target <= 0) {
                    false
                } else {
                    val sig = if (signal == ProcessSignal.TERM) "TERM" else "KILL"
                    runCatching {
                        val p = ProcessBuilder("/bin/sh", "-c", "kill -$sig $target").start()
                        p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
                    }.getOrDefault(false)
                }
            },
            selfPid = selfPid
        )
    }

    @Before
    fun setUp() {
        ProotRuntimeTestHarness.resetSeccompMemo()
        // RF-013 gated the §1.46 execve preflights behind debug capture.
        // This FakeProot scenario's invocation counting still assumes the
        // preflights consumed #1/#2, so enable capture for the test — the
        // same configuration a debugging build runs with.
        DebugCapture.setEnabled(true)
        val root = tmp.newFolder("fake-proot-root")
        prootDir = FakeProot.install(root, FakeProot.Scenario.IGNORES_TERM)
        for (name in listOf(
            "lib__bin__tar__.so",
            "lib__bin__busybox__.so",
            "lib__bin__loader__.so",
            "lib__bin__zstd__.so",
            "lib__bin__tmux__.so"
        )) {
            val inert = File(prootDir, name)
            inert.writeText("#!/bin/sh\n# inert bootstrap stand-in for JVM tests\nexit 0\n")
            check(inert.setExecutable(true)) { "could not chmod +x $inert" }
        }
        harness = ProotRuntimeTestHarness(
            prootDir = prootDir,
            filesDir = tmp.newFolder("files"),
            cacheDir = tmp.newFolder("cache")
        )
        harness.create()
        harness.bootstrapManager.setup()
    }

    @After
    fun killLeftovers() {
        // Process-wide flag: must not leak into later tests in this JVM.
        DebugCapture.resetForTesting()
        if (!::prootDir.isInitialized) return
        for (name in listOf(FakeProot.HOST_PID_FILE, FakeProot.IGNORES_TERM_CHILD_FILE)) {
            val pid = FakeProot.pidFile(prootDir, name) ?: continue
            if (ProcessTableReader.readEntry(pid)?.isLive != true) continue
            runCatching {
                ProcessBuilder("/bin/sh", "-c", "kill -KILL $pid")
                    .start()
                    .waitFor(5, TimeUnit.SECONDS)
            }
        }
    }

    private fun isLive(pid: Int): Boolean = ProcessTableReader.readEntry(pid)?.isLive == true

    private fun awaitDead(pid: Int, timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isLive(pid)) return true
            Thread.sleep(25)
        }
        return !isLive(pid)
    }

    @Test
    fun cancellingExtractionKillsTheWholeChildTreeAndReportsCancellation() = runBlocking {
        harness.rootfsExtractor.processTerminatorForTest = hostTerminator
        // TERM is ignored by both the host and its child; escalation must
        // happen quickly so the test does not wait for production budgets.
        harness.rootfsExtractor.containmentGraceOverrideMs = 500L
        harness.rootfsExtractor.containmentHardLimitOverrideMs = 5_000L
        val staging = harness.fakeRootfs()
        val archive = File(tmp.root, "archive-${System.nanoTime()}.tar.xz")
            .apply { writeText("fake archive payload") }

        val started = System.nanoTime()
        val extraction = async(Dispatchers.Default) {
            harness.rootfsExtractor.extract(
                tempArchive = archive,
                staging = staging,
                workspaceId = "cancel-test"
            )
        }

        val marker = File(prootDir, FakeProot.TERM_STARTED_FILE)
        val deadline = System.currentTimeMillis() + 10_000
        while (!marker.isFile && !extraction.isCompleted && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        if (!marker.isFile) {
            val quick = runCatching { extraction.await() }
            throw AssertionError(
                "the extraction must actually start before cancellation; " +
                    "completed=${extraction.isCompleted} result=${quick.getOrNull()} " +
                    "exception=${quick.exceptionOrNull()} " +
                    "invocations=${FakeProot.invocationCount(prootDir)}"
            )
        }
        val hostPid = FakeProot.pidFile(prootDir, FakeProot.HOST_PID_FILE)
        val childPid = FakeProot.pidFile(prootDir, FakeProot.IGNORES_TERM_CHILD_FILE)
        assertNotNull("the fake must publish its host pid", hostPid)
        assertNotNull("the fake must publish its child pid", childPid)

        extraction.cancel()
        val thrown = runCatching { extraction.await() }.exceptionOrNull()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(
            "cancellation must end the coroutine as cancelled, not as a failure; got: $thrown",
            thrown is CancellationException
        )
        assertTrue("the PRoot host must be dead, took ${elapsedMs}ms", awaitDead(hostPid!!))
        assertTrue("the TERM-ignoring child must be dead too", awaitDead(childPid!!))
        assertTrue(
            "a cancelled extraction must stop near the one-second poll, took ${elapsedMs}ms",
            elapsedMs < 20_000
        )
    }
}
