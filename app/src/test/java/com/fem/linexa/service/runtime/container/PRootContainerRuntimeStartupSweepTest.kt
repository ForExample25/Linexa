package com.fem.linexa.service.runtime.container

import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * §1.97/W5 — [PRootContainerRuntime.startupRecoverySweep] unit tests against
 * the REAL runtime and REAL on-disk artifacts through the fake-PRoot harness.
 *
 * A fresh process owns no live sessions, so the sweep's contract is: release
 * every stale `PROOT_TMP_DIR` scope under the shared parent and clean every
 * leftover tmux socket, without touching any live workspace file (rootfs,
 * download cache) and without throwing. Each case seeds real files in the
 * harness's cache/files dirs and asserts both the report counts and the
 * on-disk truth.
 *
 * Covered:
 *  - no stale state ⇒ no-op (0/0/0);
 *  - stale per-terminal + one-shot scopes are released;
 *  - cleanup is idempotent (second sweep is a no-op);
 *  - stale tmux socket files are removed;
 *  - live workspace files (rootfs, .download-cache) are never touched.
 */
class PRootContainerRuntimeStartupSweepTest {

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

    private val prootTmpParent: File get() = harness.bootstrapManager.prootTmpDir

    private fun seedStaleScope(scope: String, marker: String = "stale") {
        val dir = File(prootTmpParent, scope)
        require(dir.mkdirs()) { "could not seed stale scope $scope" }
        File(dir, marker).writeText("left behind by a dead process")
    }

    private fun seedStaleTmuxSocket(workspaceId: String) {
        val workspaceDir = File(harness.filesDir, "workspaces/$workspaceId")
        require(workspaceDir.mkdirs()) { "could not seed workspace dir $workspaceId" }
        File(workspaceDir, ".tmux.sock").writeText("stale socket")
    }

    private suspend fun sweep() = harness.runtime.startupRecoverySweep()

    @Test
    fun sweepWithNoStaleStateIsNoOp() = runBlocking {
        val report = sweep()

        assertEquals(0, report.releasedScopes)
        assertEquals(0, report.staleSocketsCleaned)
        assertEquals(0, report.failures)
    }

    @Test
    fun sweepReleasesStaleProotTmpScopes() = runBlocking {
        // ws-1/T0 and ws-1/T1 live under the SAME top-level child (ws-1), so
        // releasing it sweeps both; one-shot-uuid is its own top-level child.
        seedStaleScope("ws-1/T0")
        seedStaleScope("ws-1/T1")
        seedStaleScope("one-shot-uuid")

        val report = sweep()

        assertEquals("two top-level stale scopes must be released (ws-1 + one-shot-uuid)", 2, report.releasedScopes)
        assertFalse("ws-1/T0 must be gone", File(prootTmpParent, "ws-1/T0").exists())
        assertFalse("ws-1/T1 must be gone", File(prootTmpParent, "ws-1/T1").exists())
        assertFalse("one-shot scope must be gone", File(prootTmpParent, "one-shot-uuid").exists())
        assertEquals(0, report.failures)
    }

    @Test
    fun sweepIsIdempotent() = runBlocking {
        seedStaleScope("ws-1/T0")

        val first = sweep()
        val second = sweep()

        assertEquals(1, first.releasedScopes)
        assertEquals("second sweep must be a no-op", 0, second.releasedScopes)
        assertEquals(0, second.staleSocketsCleaned)
        assertEquals(0, second.failures)
    }

    @Test
    fun sweepRemovesStaleTmuxSocketFiles() = runBlocking {
        seedStaleTmuxSocket("ws-1")

        val report = sweep()

        assertEquals("the stale socket must count as cleaned", 1, report.staleSocketsCleaned)
        assertFalse("the stale .tmux.sock file must be gone", File(harness.filesDir, "workspaces/ws-1/.tmux.sock").exists())
        assertEquals(0, report.failures)
    }

    @Test
    fun sweepNeverTouchesLiveWorkspaceFiles() = runBlocking {
        // A workspace with a real rootfs and a (kept-across-death) download
        // cache: none of it is runtime scratch, so the sweep must leave it be.
        val workspaceDir = File(harness.filesDir, "workspaces/ws-1")
        require(workspaceDir.mkdirs()) { "could not seed workspace" }
        val rootfsFile = File(File(workspaceDir, "rootfs"), "bin/bash")
        require(rootfsFile.parentFile.mkdirs()) { "could not seed rootfs" }
        rootfsFile.writeText("#fake")
        val cacheFile = File(File(workspaceDir, ".download-cache"), "distro.tar.gz")
        require(cacheFile.parentFile.mkdirs()) { "could not seed download cache" }
        cacheFile.writeText("partial download")
        // A stale runtime-scope sibling and a stale socket sibling that SHOULD go.
        seedStaleScope("ws-1/T0")
        seedStaleTmuxSocket("ws-2")

        val report = sweep()

        assertEquals(1, report.releasedScopes)
        assertEquals(1, report.staleSocketsCleaned)
        assertTrue("live rootfs must be untouched", rootfsFile.exists())
        assertTrue("download cache must be untouched (resume across death)", cacheFile.exists())
        assertTrue("workspace dir must still exist", workspaceDir.exists())
        assertEquals(0, report.failures)
    }
}
