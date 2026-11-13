package com.fem.linexa.service.runtime.container

import com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * §1.97/W6 — the tmux backend gate. tmux is OPTIONAL and NON-CORE: the
 * production terminal path is deliberately direct PTY → proot → shell
 * (§1.76 — tmux-as-pty-child was the client-exits-0 lifecycle regression),
 * and the only tmux-wrapping entry in the codebase is the explicit
 * [PRootLauncher.wrapWithTmux] persistence seam, which no launch-path
 * caller uses.
 *
 * These tests make that contract deterministic so a future change cannot
 * accidentally reintroduce tmux into the core path:
 *  - the core launch produces a create call whose executable is the PROOT
 *    binary (never the tmux binary) with no tmux argv tokens and no TMUX
 *    env;
 *  - [PRootLauncher.wrapWithTmux] is exactly the isolated seam: it swaps
 *    only the executable + args for the documented `-S <sock> new-session
 *    -A -s linexa <proot> …` shape and leaves env/cwd untouched — the only
 *    production place a tmux invocation can be built.
 */
class TmuxBackendGateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prootDir: File
    private lateinit var harness: ProotRuntimeTestHarness

    @Before
    fun setUp() {
        ProotRuntimeTestHarness.resetSeccompMemo()
        val root = tmp.newFolder("fake-proot-root")
        prootDir = FakeProot.install(root, FakeProot.Scenario.CLEAN_ALWAYS)
        harness = ProotRuntimeTestHarness(
            prootDir = prootDir,
            filesDir = tmp.newFolder("files"),
            cacheDir = tmp.newFolder("cache")
        )
        harness.create()
    }

    @Test
    fun coreLaunchExecutesProotDirectlyNeverTmux() {
        val rootfs = harness.fakeRootfs()
        runBlocking { harness.runtime.openTerminal("ws-gate", rootfs.absolutePath, DEFAULT_TERMINAL_ID, asRoot = false) }

        val call = harness.createCalls.single()
        assertEquals("the pty child must be the proot binary", harness.launcher.binaryPath, call.executable)
        assertNotEquals("the pty child must NEVER be the tmux binary", harness.launcher.tmuxBinaryPath, call.executable)

        // No tmux argv tokens of any kind in the core launch.
        val args = call.args.joinToString(" ")
        assertTrue("core argv must not carry a tmux new-session", !call.args.contains("new-session"))
        assertTrue("core argv must not carry a tmux socket flag", !call.args.contains("-S"))
        assertTrue("core argv must not reference the tmux socket path", !args.contains(".tmux.sock"))
        assertTrue("core argv must not carry the tmux session name", !call.args.contains(TmuxPersist.DEFAULT_SESSION_NAME))
        assertTrue("core env must not leak a live tmux client var", !call.env.containsKey("TMUX"))
    }

    @Test
    fun wrapWithTmuxSeamIsExplicitAndIsolated() {
        // The ONLY production tmux-wrapping entry: buildLaunchSpec is pure
        // proot (never tmux), and wrapWithTmux swaps only executable + args
        // into the documented -S <sock> new-session -A shape.
        val inner = harness.launcher.buildLaunchSpec(
            rootfsPath = harness.fakeRootfs().absolutePath,
            command = listOf("/bin/sh"),
            prootTmpDir = File(tmp.root, "proot-tmp").absolutePath
        )
        assertEquals("buildLaunchSpec is proot, not tmux", harness.launcher.binaryPath, inner.executable)

        val wrapped = harness.launcher.wrapWithTmux(inner, "ws-gate", "T0")

        val socket = harness.launcher.tmuxSocketPath("ws-gate")
        assertEquals("the wrapped executable IS the tmux binary (explicit seam)", harness.launcher.tmuxBinaryPath, wrapped.executable)
        assertEquals(
            "tmux wraps proot with -S <sock> new-session -A -s linexa-T0 <proot> <prootArgs>",
            listOf("-S", socket, "new-session", "-A", "-s", TmuxPersist.sessionNameFor("T0"), inner.executable) + inner.args,
            wrapped.args
        )
        assertEquals("wrapWithTmux must not touch the env", inner.env, wrapped.env)
        assertEquals("wrapWithTmux must not touch the working dir", inner.workingDirectory, wrapped.workingDirectory)
    }
}
