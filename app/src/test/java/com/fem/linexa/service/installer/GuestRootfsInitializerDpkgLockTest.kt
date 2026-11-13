package com.fem.linexa.service.installer

import android.app.Application
import android.content.pm.ApplicationInfo
import com.fem.linexa.core.logging.DiagnosticLogManager
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * F-6 (2026-08-25 audit) regression tests for
 * [GuestRootfsInitializer.ensureDpkgHealthy]'s lock-cleanup gate.
 *
 * The audit proved the historical implementation deleted
 * `var/lib/dpkg/lock{,-frontend}` unconditionally, on the false assumption
 * that "no PRoot is running". With multi-terminal workspaces and
 * Package-Manager-UI one-shot execs, a live dpkg CAN hold those files when
 * another caller runs the health check — deleting them lets a second dpkg
 * lock a fresh inode and corrupt the database.
 *
 * These tests pin the initializer-level half of the contract:
 *  - `allowLockCleanup = true`  → stale locks are recovered (the historical
 *    recovery, preserved for genuinely-idle workspaces),
 *  - `allowLockCleanup = false` → lock files are PRESERVED (byte-identical),
 *    while the read-only partial-state detection keeps working.
 *
 * The runtime-level gating (who passes which flag, and the activity registry
 * that decides) is covered by [com.fem.linexa.service.runtime.container.PRootDpkgLockConcurrencyTest].
 */
class GuestRootfsInitializerDpkgLockTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var initializer: GuestRootfsInitializer
    private lateinit var rootfs: File

    @Before
    fun setUp() {
        val filesDir = tmp.newFolder("files")
        val binDir = File(filesDir, "bootstrap/bin")
        require(binDir.mkdirs())
        val chmod = File(binDir, "chmod")
        chmod.writeText("#!/bin/sh\nexec /bin/chmod \"$@\"\n")
        require(chmod.setExecutable(true))

        val app = Mockito.mock(Application::class.java)
        val appInfo = Mockito.mock(ApplicationInfo::class.java)
        appInfo.nativeLibraryDir = tmp.newFolder("native").absolutePath
        Mockito.`when`(app.applicationInfo).thenReturn(appInfo)
        Mockito.`when`(app.filesDir).thenReturn(filesDir)
        Mockito.`when`(app.cacheDir).thenReturn(tmp.newFolder("cache"))
        initializer = GuestRootfsInitializer(BootstrapManager(app), DiagnosticLogManager(app))

        rootfs = tmp.newFolder("rootfs")
    }

    /** Plants both dpkg lock files + partial dpkg/updates state, like a
     *  dpkg that was SIGKILLed mid-transaction. */
    private fun plantInterruptedDpkgState() {
        val dpkgDir = File(rootfs, "var/lib/dpkg")
        require(dpkgDir.mkdirs())
        File(dpkgDir, "lock").writeText("")
        File(dpkgDir, "lock-frontend").writeText("")
        val updates = File(dpkgDir, "updates")
        require(updates.mkdirs())
        File(updates, "0000").writeText("partial journal")
    }

    private fun lockFiles(): List<File> = listOf(
        File(rootfs, "var/lib/dpkg/lock"),
        File(rootfs, "var/lib/dpkg/lock-frontend")
    )

    @Test
    fun idleWorkspaceRecoversStaleLocks() {
        plantInterruptedDpkgState()
        assertTrue(lockFiles().all { it.exists() })

        val report = initializer.ensureDpkgHealthy(rootfs, "ws-1", allowLockCleanup = true)

        assertEquals("both stale locks must be removed when provably idle", 2, report.freedLocks)
        assertTrue("lock files must be gone", lockFiles().none { it.exists() })
        assertTrue("partial state must still be detected", report.needsConfigure)
    }

    @Test
    fun defaultParameterKeepsHistoricalBehavior() {
        // The default must stay permissive: existing callers/tests that run
        // against a known-idle rootfs keep the recovery semantics.
        plantInterruptedDpkgState()

        val report = initializer.ensureDpkgHealthy(rootfs, "ws-1")

        assertEquals(2, report.freedLocks)
        assertTrue(lockFiles().none { it.exists() })
    }

    @Test
    fun busyWorkspacePreservesLockFilesByteIdentically() {
        plantInterruptedDpkgState()
        // A live dpkg "holds" the locks — model that by putting recognizable
        // content in them, so byte-identity is assertable.
        File(rootfs, "var/lib/dpkg/lock").writeText("held-by-live-dpkg")
        File(rootfs, "var/lib/dpkg/lock-frontend").writeText("held-by-live-dpkg")

        val report = initializer.ensureDpkgHealthy(rootfs, "ws-1", allowLockCleanup = false)

        assertEquals("no locks may be freed while another session may be using dpkg", 0, report.freedLocks)
        assertEquals(
            "lock file must be preserved byte-identically",
            "held-by-live-dpkg",
            File(rootfs, "var/lib/dpkg/lock").readText()
        )
        assertEquals(
            "frontend lock file must be preserved byte-identically",
            "held-by-live-dpkg",
            File(rootfs, "var/lib/dpkg/lock-frontend").readText()
        )
    }

    @Test
    fun busyWorkspaceStillDetectsPartialState() {
        plantInterruptedDpkgState()

        val report = initializer.ensureDpkgHealthy(rootfs, "ws-1", allowLockCleanup = false)

        // Recovery via `dpkg --configure -a` (the caller's preflight) must
        // still trigger even when lock deletion is gated off.
        assertTrue(
            "needsConfigure must stay detectable while locks are preserved (F-6)",
            report.needsConfigure
        )
        // And the partial journal must be untouched — the gate only protects
        // the lock files, everything else is still read-only detection.
        assertTrue(File(rootfs, "var/lib/dpkg/updates/0000").exists())
    }

    @Test
    fun healthyWorkspaceIsUnchangedByTheGate() {
        // No locks, no partial state: both flag values must produce the same
        // healthy report (deterministic + idempotent).
        val idle = initializer.ensureDpkgHealthy(rootfs, "ws-1", allowLockCleanup = true)
        val busy = initializer.ensureDpkgHealthy(rootfs, "ws-1", allowLockCleanup = false)

        assertFalse(idle.needsConfigure)
        assertFalse(busy.needsConfigure)
        assertEquals(0, idle.freedLocks)
        assertEquals(0, busy.freedLocks)
        // lowFreeSpace is deliberately NOT asserted: it reports the real
        // partition's usable space, which is environment-dependent (a CI
        // tmpfs can sit under the 100 MB threshold).
    }

    @Test
    fun aptPartialDownloadStateIsDetectedIndependentlyOfTheGate() {
        val partial = File(rootfs, "var/cache/apt/archives/partial")
        require(partial.mkdirs())
        File(partial, "unzip_6.0-28.deb.part").writeText("half-downloaded")

        val report = initializer.ensureDpkgHealthy(rootfs, "ws-1", allowLockCleanup = false)

        assertTrue("apt partial state must be detected regardless of the gate", report.needsConfigure)
        assertTrue("the partial file must not be touched", File(partial, "unzip_6.0-28.deb.part").exists())
    }
}
