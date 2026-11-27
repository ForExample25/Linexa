package com.fem.linexa.service.runtime.container

import com.fem.linexa.core.filesystem.ProcessSignal
import com.fem.linexa.core.filesystem.ProcessSignalSender
import com.fem.linexa.core.filesystem.ProcessTableReader
import com.fem.linexa.core.filesystem.ProcessTreeTerminator
import com.fem.linexa.core.filesystem.captureProcessIdentity
import com.fem.linexa.core.filesystem.processHostPid
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 0 — end-to-end regression tests for the process-ownership fix
 * (audit findings 002, 003, 004, 017).
 *
 * The REAL [PRootContainerRuntime] runs against [FakeProot], whose
 * `DAEMON_WITH_CHILD` / `TIMEOUT_WITH_CHILD` scenarios spawn a real child
 * process — the shape of a PRoot host plus its initial tracee. A second,
 * *unrelated* process tree is launched with the workspace rootfs path in its
 * argv (`sh -c 'sleep 600' <rootfs>`), which is exactly what the old
 * workspace-wide `/proc` cmdline sweep matched.
 *
 * Production `android.system.Os.kill` is a JVM stub, so these tests inject a
 * [ProcessTreeTerminator] whose sender shells out to the host `kill`. The
 * containment decision logic and the runtime integration are therefore real;
 * only the syscall seam is substituted.
 */
class PRootComponentContainmentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prootDir: File
    private lateinit var harness: ProotRuntimeTestHarness
    private lateinit var rootfsPath: String

    private val selfPid: Int by lazy { File("/proc/self").canonicalFile.name.toInt() }
    private val selfUid: Int by lazy {
        File("/proc/self/status").readLines()
            .first { it.startsWith("Uid:") }
            .removePrefix("Uid:").trim().split(Regex("\\s+")).first().toInt()
    }

    /** Real-signal terminator: the only substitution is `Os.kill`. */
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
        val root = tmp.newFolder("fake-proot-root")
        prootDir = FakeProot.install(root, FakeProot.Scenario.DAEMON_WITH_CHILD)
        harness = ProotRuntimeTestHarness(
            prootDir = prootDir,
            filesDir = tmp.newFolder("files"),
            cacheDir = tmp.newFolder("cache")
        )
        harness.create()
        harness.runtime.processTerminatorForTest = hostTerminator
        ProotRuntimeTestHarness.markSeccompProbeDone()
        rootfsPath = harness.fakeRootfs().absolutePath
    }

    @After
    fun tearDown() {
        runCatching { runBlocking { harness.runtime.stopAllDaemons("ws-1") } }
        runCatching { runBlocking { harness.runtime.stopAllDaemons("ws-2") } }
        ProotRuntimeTestHarness.resetSeccompMemo()
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

    /**
     * A same-workspace "terminal workload": its command line contains the
     * workspace rootfs path, so the pre-fix sweep would have killed it.
     */
    private class Workload(val process: Process, val pid: Int)

    private fun launchRootfsWorkload(rootfs: String = rootfsPath): Workload {
        val process = ProcessBuilder("/bin/sh", "-c", "sleep 600", rootfs).start()
        val pid = processHostPid(process)
        assertNotNull("test workload must expose a host pid", pid)
        return Workload(process, pid!!)
    }

    private fun stopWorkload(workload: Workload) {
        val identity = captureProcessIdentity(workload.process)
        if (identity != null) hostTerminator.terminate(listOf(identity))
        workload.process.destroyForcibly()
    }

    @Test
    fun daemonStopNeverKillsSameWorkspaceTerminalWorkload() = runBlocking {
        assertTrue(harness.runtime.startDaemon("ws-1", rootfsPath, "xvnc", "Xvnc :1"))
        val daemonChild = FakeProot.awaitPidFile(prootDir, FakeProot.DAEMON_CHILD_FILE)
        assertNotNull("fake daemon must report its guest child", daemonChild)
        val workload = launchRootfsWorkload()
        try {
            assertTrue("the unrelated workload is alive before the stop", isLive(workload.pid))

            harness.runtime.stopDaemon("ws-1", "xvnc")

            assertFalse("the daemon host must be stopped", harness.runtime.isDaemonAlive("ws-1", "xvnc"))
            assertTrue(
                "the daemon's guest child must be terminated with its host",
                awaitDead(daemonChild!!)
            )
            assertTrue(
                "a same-workspace terminal workload must survive a daemon stop " +
                    "(the pre-fix rootfs sweep killed it)",
                isLive(workload.pid)
            )
        } finally {
            stopWorkload(workload)
        }
    }

    @Test
    fun twoDaemonsStoppingOneLeavesTheOtherTreeAlive() = runBlocking {
        assertTrue(harness.runtime.startDaemon("ws-1", rootfsPath, "xvnc", "Xvnc :1"))
        val xvncChild = FakeProot.awaitPidFile(prootDir, FakeProot.DAEMON_CHILD_FILE)
        assertNotNull(xvncChild)

        assertTrue(harness.runtime.startDaemon("ws-1", rootfsPath, "sshd", "sshd -D"))
        // The same PID file is rewritten by the second launch; wait for the
        // rewrite instead of reading the first daemon's stale PID.
        val sshdChild = FakeProot.awaitNewPidFile(prootDir, FakeProot.DAEMON_CHILD_FILE, xvncChild)
        assertNotNull(sshdChild)

        harness.runtime.stopDaemon("ws-1", "xvnc")

        assertTrue("the stopped daemon's tree must be gone", awaitDead(xvncChild!!))
        assertTrue("the other daemon's guest child must survive", isLive(sshdChild!!))
        assertTrue("the other daemon host must survive", harness.runtime.isDaemonAlive("ws-1", "sshd"))
    }

    @Test
    fun stoppingWorkspaceADaemonNeverTouchesWorkspaceB() = runBlocking {
        val rootfsB = harness.fakeRootfs().absolutePath
        assertTrue(harness.runtime.startDaemon("ws-1", rootfsPath, "xvnc", "Xvnc :1"))
        val childA = FakeProot.awaitPidFile(prootDir, FakeProot.DAEMON_CHILD_FILE)
        assertNotNull(childA)

        assertTrue(harness.runtime.startDaemon("ws-2", rootfsB, "xvnc", "Xvnc :1"))
        val childB = FakeProot.awaitNewPidFile(prootDir, FakeProot.DAEMON_CHILD_FILE, childA)
        assertNotNull(childB)

        harness.runtime.stopAllDaemons("ws-1")

        assertTrue("workspace A's daemon tree must be gone", awaitDead(childA!!))
        assertTrue("workspace B's daemon tree must survive", isLive(childB!!))
        assertTrue("workspace B's daemon host must survive", harness.runtime.isDaemonAlive("ws-2", "xvnc"))
    }

    @Test
    fun execTimeoutKillsOnlyTheTimedOutTree() = runBlocking {
        FakeProot.setScenario(prootDir, FakeProot.Scenario.TIMEOUT_WITH_CHILD)
        val workload = launchRootfsWorkload()
        try {
            val started = System.nanoTime()
            val result = harness.runtime.execStreaming(
                workspaceId = "ws-1",
                rootfsPath = rootfsPath,
                command = "echo ${FakeProot.TIMEOUT_MARKER}",
                timeoutSeconds = 1L,
                onLine = {}
            )
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertEquals("a timed-out exec must report the timeout sentinel", -1, result.exitCode)
            assertTrue(
                "the timeout must be surfaced, got: ${result.error}",
                result.error.contains("timed out", ignoreCase = true)
            )
            val execChild = FakeProot.pidFile(prootDir, FakeProot.EXEC_CHILD_FILE)
            assertNotNull("the timed-out fake exec must report its guest child", execChild)
            assertTrue("the timed-out exec's tree must be terminated", awaitDead(execChild!!))
            assertTrue(
                "a live terminal workload must survive an exec timeout (the pre-fix sweep killed it)",
                isLive(workload.pid)
            )
            assertTrue("the timeout path must stay bounded, took ${elapsedMs}ms", elapsedMs < 30_000)
        } finally {
            stopWorkload(workload)
        }
    }
}
