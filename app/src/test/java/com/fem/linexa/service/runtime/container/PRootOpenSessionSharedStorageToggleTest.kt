package com.fem.linexa.service.runtime.container

import com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import com.fem.linexa.test.support.buildWorkspaceMetadata
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * §1.82 — Roadmap Item 5.2: the Shared Storage feature toggle must gate the
 * `/mnt/shared` PRoot bind through the REAL [PRootContainerRuntime] launch
 * path against the fake PRoot stand-in ([FakeProot]).
 *
 * These assert the wiring, not a mirror: the persisted feature toggles are
 * resolved by the runtime ([isSharedStorageEnabled] → `resolveFeatureToggles`),
 * flow into [PRootLauncher.buildLaunchSpec]'s argv, and appear/disappear as a
 * `-b <host>:/mnt/shared` token. The must-not-breaks:
 *
 *  - toggle enabled  + shared folder exists → bind present (pre-§1.82),
 *  - toggle disabled + shared folder exists → bind absent,
 *  - toggle enabled  + no shared folder    → no bind (pre-§1.82 skip-on-missing),
 *  - no persisted toggles at all           → enabled by default (backward compat).
 */
class PRootOpenSessionSharedStorageToggleTest {

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

    /**
     * Create the runtime's shared-storage source so the bind is eligible.
     * The harness mocks `getExternalFilesDir(any())` to return
     * `{filesDir}/shared` (ProotRuntimeTestHarness), and PRootLauncher
     * appends `"shared"` to that — so the exact path the launcher checks is
     * `{filesDir}/shared/shared`.
     */
    private fun createSharedFolder() {
        File(harness.filesDir, "shared/shared").mkdirs()
    }

    private fun openSessionArgs(workspaceId: String = "ws-1"): List<String> = runBlocking {
        harness.runtime.openTerminal(workspaceId, harness.fakeRootfs().absolutePath, DEFAULT_TERMINAL_ID, asRoot = false)
        harness.createCalls.single().args
    }

    @Test
    fun storageEnabledBindsSharedFolder() {
        createSharedFolder()
        harness.workspaceRepository.add(buildWorkspaceMetadata("ws-1", featureToggles = mapOf("storage" to true)))
        val args = openSessionArgs("ws-1")
        assertTrue(
            "storage=true + folder exists must bind /mnt/shared",
            args.any { it.endsWith(":/mnt/shared") }
        )
    }

    @Test
    fun storageDisabledOmitsSharedBind() {
        createSharedFolder()
        harness.workspaceRepository.add(buildWorkspaceMetadata("ws-1", featureToggles = mapOf("storage" to false)))
        val args = openSessionArgs("ws-1")
        assertFalse(
            "storage=false must NOT bind /mnt/shared even when the folder exists",
            args.any { it.endsWith(":/mnt/shared") }
        )
    }

    @Test
    fun missingSharedFolderStaysUnboundByDefault() {
        // No workspace record at all — the pre-§1.82 default path: no shared
        // bind because the folder does not exist (harness default), and no
        // toggle resolution surprises a fresh workspace.
        val args = openSessionArgs("ws-1")
        assertFalse(
            "no shared folder must keep the pre-§1.82 skip-on-missing behavior",
            args.any { it.endsWith(":/mnt/shared") }
        )
        // The three always-on host binds must still be present.
        assertTrue(args.contains("/dev"))
        assertTrue(args.contains("/proc"))
        assertTrue(args.contains("/sys"))
    }

    @Test
    fun missingToggleDefaultsToEnabled() {
        createSharedFolder()
        // Legacy workspace record with empty featureToggles (pre-§1.81): the
        // shared bind must stay on, matching the pre-§1.82 unconditional bind.
        harness.workspaceRepository.add(buildWorkspaceMetadata("ws-1"))
        val args = openSessionArgs("ws-1")
        assertTrue(
            "missing storage toggle must default to enabled (backward compat)",
            args.any { it.endsWith(":/mnt/shared") }
        )
    }

    @Test
    fun execHonorsStorageToggleToo() {
        createSharedFolder()
        harness.workspaceRepository.add(buildWorkspaceMetadata("ws-1", featureToggles = mapOf("storage" to false)))
        runBlocking { harness.runtime.exec("ws-1", harness.fakeRootfs().absolutePath, "true") }
        val invocation = FakeProot.invocations(prootDir).single()
        assertFalse(
            "one-shot exec must also honor storage=false",
            invocation.argv.any { it.endsWith(":/mnt/shared") }
        )
    }
}
