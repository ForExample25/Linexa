package com.fem.linexa.service.runtime.container

import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import com.fem.linexa.test.support.buildWorkspaceMetadata
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * §1.83 — the guest-daemon host: a daemon must OUTLIVE the call that launched
 * it, which a one-shot `exec` can never provide (its PRoot runs with
 * `--kill-on-exit`, so the moment the `/bin/sh -c 'Xvnc ... &'` shell exits,
 * the backgrounded Xvnc is reaped — audit §4.2). These tests prove, through
 * the REAL [PRootContainerRuntime] against the fake-PRoot stand-in, that:
 *
 *  - a daemon launched as the foreground command of its own PRoot host stays
 *    alive across the launching call ([FakeProot.Scenario.DAEMON_STAYS_ALIVE]
 *    is a long-lived process, the shape the real libproot.so takes when its
 *    foreground command is Xvnc),
 *  - [startDaemon]/[stopDaemon]/[isDaemonAlive] round-trip,
 *  - restart is idempotent (same name → old host replaced),
 *  - [stopAllDaemons] stops every daemon for a workspace,
 *  - the Shared Storage toggle (§1.82) is honored by daemon argv too.
 *
 * The seccomp probe is marked done before each start ([markSeccompProbeDone])
 * so `resolveSeccompFallback` short-circuits instead of launching the
 * long-lived fake proot and hanging for the whole probe timeout.
 */
class PRootGuestDaemonHostTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prootDir: File
    private lateinit var harness: ProotRuntimeTestHarness

    @Before
    fun setUp() {
        ProotRuntimeTestHarness.resetSeccompMemo()
        val root = tmp.newFolder("fake-proot-root")
        prootDir = FakeProot.install(root, FakeProot.Scenario.DAEMON_STAYS_ALIVE)
        harness = ProotRuntimeTestHarness(
            prootDir = prootDir,
            filesDir = tmp.newFolder("files"),
            cacheDir = tmp.newFolder("cache")
        )
        harness.create()
        ProotRuntimeTestHarness.markSeccompProbeDone()
    }

    @After
    fun tearDown() {
        // Don't leak long-lived fake daemons across tests in this JVM.
        runBlocking { harness.runtime.stopAllDaemons("ws-1") }
        ProotRuntimeTestHarness.resetSeccompMemo()
    }

    private val rootfsPath: String by lazy { harness.fakeRootfs().absolutePath }

    /**
     * §1.83: the fake proot is a child process — its invocation is recorded in
     * the log asynchronously after [ProcessBuilder.start] returns, so reading
     * it immediately can race the spawn. Poll with a bounded timeout until at
     * least [n] invocations are on disk, then return them.
     */
    private fun awaitInvocations(n: Int = 1): List<FakeProot.Invocation> {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val current = FakeProot.invocations(prootDir)
            if (current.size >= n) return current
            Thread.sleep(50)
        }
        error("fake proot did not record $n invocation(s) within 10s; log was: ${FakeProot.readLog(prootDir)}")
    }

    @Test
    fun daemonSurvivesTheLaunchingCall() {
        val started = runBlocking {
            harness.runtime.startDaemon("ws-1", rootfsPath, "xvnc", "Xvnc :1 -geometry 1280x720")
        }
        assertTrue("startDaemon must report a live daemon", started)
        assertTrue("daemon must still be alive after the launching call", harness.runtime.isDaemonAlive("ws-1", "xvnc"))

        // The daemon must be the FOREGROUND command of its own PRoot host:
        // argv runs `/bin/sh -c 'exec <command>'` — no background `&`, which
        // is exactly what used to get reaped by --kill-on-exit.
        val daemonInvocation = awaitInvocations().single()
        assertTrue(
            "daemon argv must contain the foreground exec wrapper",
            daemonInvocation.argv.contains("/bin/sh") && daemonInvocation.argv.contains("-c")
        )
        val commandArg = daemonInvocation.argv
            .getOrNull(daemonInvocation.argv.indexOf("-c") + 1)
        assertTrue(
            "daemon command must be exec'd in the foreground, not backgrounded",
            commandArg != null && commandArg.startsWith("exec Xvnc :1 -geometry 1280x720")
        )
    }

    @Test
    fun stopDaemonKillsTheHost() {
        runBlocking { harness.runtime.startDaemon("ws-1", rootfsPath, "xvnc", "Xvnc :1") }
        assertTrue(harness.runtime.isDaemonAlive("ws-1", "xvnc"))
        runBlocking { harness.runtime.stopDaemon("ws-1", "xvnc") }
        assertFalse("stopped daemon must be dead", harness.runtime.isDaemonAlive("ws-1", "xvnc"))
        // Stop is idempotent — no crash on a second stop.
        runBlocking { harness.runtime.stopDaemon("ws-1", "xvnc") }
    }

    @Test
    fun restartReplacesTheOldHost() {
        runBlocking {
            assertTrue(harness.runtime.startDaemon("ws-1", rootfsPath, "xvnc", "Xvnc :1"))
        }
        // Same name again: the old host is stopped and a fresh one started.
        runBlocking {
            assertTrue(harness.runtime.startDaemon("ws-1", rootfsPath, "xvnc", "Xvnc :1"))
        }
        assertTrue(harness.runtime.isDaemonAlive("ws-1", "xvnc"))
        runBlocking { harness.runtime.stopDaemon("ws-1", "xvnc") }
        assertFalse(harness.runtime.isDaemonAlive("ws-1", "xvnc"))
    }

    @Test
    fun stopAllDaemonsStopsEveryDaemon() {
        runBlocking {
            assertTrue(harness.runtime.startDaemon("ws-1", rootfsPath, "xvnc", "Xvnc :1"))
            assertTrue(harness.runtime.startDaemon("ws-1", rootfsPath, "sshd", "sshd -D"))
        }
        assertTrue(harness.runtime.isDaemonAlive("ws-1", "xvnc"))
        assertTrue(harness.runtime.isDaemonAlive("ws-1", "sshd"))
        runBlocking { harness.runtime.stopAllDaemons("ws-1") }
        assertFalse(harness.runtime.isDaemonAlive("ws-1", "xvnc"))
        assertFalse(harness.runtime.isDaemonAlive("ws-1", "sshd"))
    }

    @Test
    fun daemonHonorsStorageToggle() {
        // Shared folder exists, but the storage toggle is disabled → no bind.
        File(harness.filesDir, "shared/shared").mkdirs()
        harness.workspaceRepository.add(buildWorkspaceMetadata("ws-1", featureToggles = mapOf("storage" to false)))
        runBlocking { harness.runtime.startDaemon("ws-1", rootfsPath, "xvnc", "Xvnc :1") }
        val invocation = awaitInvocations().single()
        assertFalse(
            "daemon argv must honor storage=false (no /mnt/shared bind)",
            invocation.argv.any { it.endsWith(":/mnt/shared") }
        )
        assertTrue(invocation.argv.contains("/dev"))
        assertTrue(invocation.argv.contains("/proc"))
        assertTrue(invocation.argv.contains("/sys"))
    }
}
