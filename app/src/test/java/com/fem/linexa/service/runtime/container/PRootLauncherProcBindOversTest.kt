package com.fem.linexa.service.runtime.container

import android.app.Application
import android.content.pm.ApplicationInfo
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito

/**
 * R-6 (2026-08-26 runtime parity audit) regression tests for the /proc
 * bind-over mechanism in [PRootLauncher.buildLaunchSpec].
 *
 * Contract: when `{rootfs}/proc/.<name>` exists for any name in
 * [PRootLauncher.PROC_BIND_OVER_NAMES], the argv gains
 * `-b {rootfs}/proc/.<name>:/proc/<name>` for each present file. When
 * no snapshots exist (or no /proc directory in the rootfs at all), no
 * bind-overs are added — the Android-restricted /proc keeps its
 * historical (EACCES) behavior.
 *
 * The intent: this is *not* a live /proc emulator. The snapshot files
 * contain static synthetic values (see [GuestRootfsInitializer.PROC_SNAPSHOT_CONTENTS]).
 * The bind-overs exist so `btop`/`top`/`vmstat`/`uptime`/`uname -r` can
 * READ the files without aborting on `EACCES`. Their bars may show stale
 * data — strictly better than the audit's "monitoring tools fail to start"
 * symptom.
 */
class PRootLauncherProcBindOversTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var launcher: PRootLauncher
    private lateinit var rootfs: File

    @Before
    fun setUp() {
        val filesDir = tmp.newFolder("files")
        val app = Mockito.mock(Application::class.java)
        val appInfo = Mockito.mock(ApplicationInfo::class.java)
        appInfo.nativeLibraryDir = tmp.newFolder("native").absolutePath
        Mockito.`when`(app.applicationInfo).thenReturn(appInfo)
        Mockito.`when`(app.filesDir).thenReturn(filesDir)
        Mockito.`when`(app.cacheDir).thenReturn(tmp.newFolder("cache"))
        Mockito.`when`(app.getExternalFilesDir(any())).thenReturn(File(filesDir, "shared"))
        launcher = PRootLauncher(app, BootstrapManager(app))
        rootfs = tmp.newFolder("rootfs")
    }

    private fun buildArgs(): List<String> = launcher.buildLaunchSpec(
        rootfsPath = rootfs.absolutePath,
        prootTmpDir = tmp.newFolder("proot-tmp").absolutePath,
        includeSharedStorage = false
    ).args

    @Test
    fun procBindOversOmittedWhenRootfsHasNoProcDir() {
        // No /proc directory at all — fail-open (no bind-overs).
        val args = buildArgs()
        assertFalse(
            "no /proc directory means no /proc bind-overs",
            args.any { it.startsWith("/proc.") || it.contains(":/proc/") && it.startsWith("${rootfs.absolutePath}/proc/.") }
        )
        // But the /proc bind itself is still there.
        assertTrue("the /proc bind is always present", args.contains("/proc"))
    }

    @Test
    fun procBindOversOmittedWhenProcDirExistsButNoSnapshotFiles() {
        // /proc directory exists but no .stat/.uptime/etc. files.
        require(File(rootfs, "proc").mkdirs())

        val args = buildArgs()
        assertFalse(
            "no /proc snapshot files means no /proc bind-overs",
            args.any { it.endsWith(":/proc/stat") || it.endsWith(":/proc/uptime") ||
                it.endsWith(":/proc/loadavg") || it.endsWith(":/proc/version") ||
                it.endsWith(":/proc/vmstat") }
        )
    }

    @Test
    fun procBindOverAddedForEveryPresentSnapshot() {
        val procDir = File(rootfs, "proc").apply { mkdirs() }
        // Create a snapshot file for each name in PROC_BIND_OVER_NAMES.
        for (name in PRootLauncher.PROC_BIND_OVER_NAMES) {
            File(procDir, ".$name").writeText("synthetic-content-for-$name\n")
        }

        val args = buildArgs()
        for (name in PRootLauncher.PROC_BIND_OVER_NAMES) {
            val expected = "${rootfs.absolutePath}/proc/.${name}:/proc/$name"
            assertTrue(
                "expected bind-over for /proc/$name: $expected",
                args.contains(expected)
            )
            val idx = args.indexOf(expected)
            assertEquals(
                "the /proc/$name bind must be introduced by -b",
                "-b", args[idx - 1]
            )
        }
    }

    @Test
    fun procBindOversAreSubsetOfProcBindOverNames() {
        // Snapshot for only stat + uptime (not all five) — verify only
        // those two bind-overs appear, the others are absent.
        val procDir = File(rootfs, "proc").apply { mkdirs() }
        File(procDir, ".stat").writeText("cpu 0 0 0 0\n")
        File(procDir, ".uptime").writeText("0.0 0.0\n")

        val args = buildArgs()
        assertTrue("stat bind-over present",
            args.contains("${rootfs.absolutePath}/proc/.stat:/proc/stat"))
        assertTrue("uptime bind-over present",
            args.contains("${rootfs.absolutePath}/proc/.uptime:/proc/uptime"))
        assertFalse("loadavg bind-over absent",
            args.any { it.endsWith(":/proc/loadavg") })
        assertFalse("version bind-over absent",
            args.any { it.endsWith(":/proc/version") })
        assertFalse("vmstat bind-over absent",
            args.any { it.endsWith(":/proc/vmstat") })
    }

    @Test
    fun procBindOverNamesMatchesExpectedSnapshotSet() {
        // Pin the public list. The 2026-08-27 host-bridge expansion appended
        // three CONDITIONAL names (cpuinfo/meminfo/swaps — bound only when
        // the bridge synthesized them because native reads are denied);
        // the R-6 five stay byte-compatible in their original relative
        // order for history. /proc/net/* subtree members live separately
        // in PROC_NET_BIND_OVERS and are pinned by launcher argv tests.
        assertEquals(
            "PROC_BIND_OVER_NAMES must match the expected snapshot set",
            listOf(
                "cpuinfo", "loadavg", "meminfo",
                "stat", "swaps",
                "uptime", "version", "vmstat"
            ),
            PRootLauncher.PROC_BIND_OVER_NAMES
        )
        // Subtree drift guard: the flat list and the net list together form
        // the set the bridge may write to.
        val guestTargets = PRootLauncher.PROC_NET_BIND_OVERS.map { it.second }
        assertEquals(listOf("/proc/net/dev", "/proc/net/route", "/proc/net/if_inet6"), guestTargets)
    }

    @Test
    fun spoofedKernelReleaseIsStableAndDistributionNeutral() {
        // R-1: the spoofed release must match the snapshot in
        // /proc/.version. The version snapshot is generated by
        // GuestRootfsInitializer but its first three space-separated
        // tokens after "Linux version" must match SPOOFED_KERNEL_RELEASE.
        assertEquals(
            "SPOOFED_KERNEL_RELEASE must be the linexa distribution-neutral string",
            "6.1.0-linexa",
            PRootLauncher.SPOOFED_KERNEL_RELEASE
        )
    }
}
