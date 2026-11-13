package com.fem.linexa.service.runtime.container

import com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID
import com.fem.linexa.service.runtime.features.FeatureSettings
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import com.fem.linexa.test.support.buildWorkspaceMetadata
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
 * Session Persistence (Stage F of the transformation spec): the explicit
 * per-workspace tmux opt-in. The §1.76 gate ([TmuxBackendGateTest]) still
 * pins the CORE path as direct PTY → proot → shell; these tests pin the one
 * sanctioned exception — a workspace whose persisted attributes set
 * `feature.terminal.persistence=true` gets its launch wrapped through the
 * isolated [PRootLauncher.wrapWithTmux] seam (create-or-reattach), with the
 * env untouched and the socket workspace-scoped.
 */
class SessionPersistenceLaunchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var harness: ProotRuntimeTestHarness

    @Before
    fun setUp() {
        ProotRuntimeTestHarness.resetSeccompMemo()
        val fakeProotRoot = FakeProot.install(tmp.newFolder("fake-proot-root"), FakeProot.Scenario.CLEAN_ALWAYS)
        // isTmuxAvailable is a runtime File.exists check on the bundled tmux
        // binary next to proot — provide it so the opt-in wrap can engage.
        FakeProot.writeFakeTmux(fakeProotRoot)
        harness = ProotRuntimeTestHarness(
            prootDir = fakeProotRoot,
            filesDir = tmp.newFolder("files"),
            cacheDir = tmp.newFolder("cache")
        )
        harness.create()
    }

    @Test
    fun persistenceOptInWrapsTheLaunchWithTmux() = runBlocking {
        harness.workspaceRepository.add(
            buildWorkspaceMetadata(
                id = "ws-persist",
                attributes = mapOf(FeatureSettings.KEY_TERMINAL_PERSISTENCE to "true")
            )
        )
        val rootfs = harness.fakeRootfs()
        harness.runtime.openTerminal("ws-persist", rootfs.absolutePath, DEFAULT_TERMINAL_ID, asRoot = false)

        val call = harness.createCalls.single()
        assertEquals(
            "the opt-in launch's pty child IS the tmux binary (sanctioned exception)",
            harness.launcher.tmuxBinaryPath,
            call.executable
        )
        val socket = harness.launcher.tmuxSocketPath("ws-persist")
        assertEquals(
            "tmux wraps the direct proot spec with -S <ws socket> new-session -A -s linexa-T0 (per-terminal session)",
            listOf(
                "-S", socket, "new-session", "-A", "-s", TmuxPersist.sessionNameFor(DEFAULT_TERMINAL_ID),
                harness.launcher.binaryPath
            ),
            call.args.take(7)
        )
        assertTrue(
            "the inner proot argv follows the tmux args unchanged",
            call.args.drop(7).contains("-r")
        )
        assertTrue(
            "the workspace-scoped socket is the only socket in play",
            call.args.contains(socket) && socket.contains("ws-persist")
        )
        assertNotEquals("env is not emptied by the wrap", 0, call.env.size)
    }

    @Test
    fun persistenceDefaultStaysDirectProot() = runBlocking {
        // A workspace without the attribute (the overwhelmingly common case)
        // must be byte-for-byte on the §1.76 core path — this is the runtime
        // twin of TmuxBackendGateTest.coreLaunchExecutesProotDirectlyNeverTmux.
        harness.workspaceRepository.add(buildWorkspaceMetadata(id = "ws-plain"))
        val rootfs = harness.fakeRootfs()
        harness.runtime.openTerminal("ws-plain", rootfs.absolutePath, DEFAULT_TERMINAL_ID, asRoot = false)

        val call = harness.createCalls.single()
        assertEquals(harness.launcher.binaryPath, call.executable)
        assertTrue(!call.args.contains("new-session"))
        assertTrue(!call.args.joinToString(" ").contains(".tmux.sock"))
    }

    @Test
    fun persistenceFailsOpenToDirectWhenAttributeValueIsGarbage() = runBlocking {
        harness.workspaceRepository.add(
            buildWorkspaceMetadata(
                id = "ws-garbage",
                attributes = mapOf(FeatureSettings.KEY_TERMINAL_PERSISTENCE to "not-a-boolean")
            )
        )
        harness.runtime.openTerminal("ws-garbage", harness.fakeRootfs().absolutePath, DEFAULT_TERMINAL_ID, asRoot = false)
        assertEquals(harness.launcher.binaryPath, harness.createCalls.single().executable)
    }
}
