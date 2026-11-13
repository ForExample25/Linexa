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
 * R-5 (2026-08-26 runtime parity audit) regression tests for the guest
 * `/run` bind in [PRootLauncher.buildLaunchSpec].
 *
 * Contract: when `{rootfs}/run` is a directory, the argv gains
 * `-b {rootfs}/run:/run` AFTER `-b /proc` and `-b /sys` (the broad
 * kernel mounts) and BEFORE the shared-storage and per-workspace
 * extra binds. Placement after the broad binds means PRoot resolves
 * the more specific `/run` bind in its favor. When the directory does
 * not exist, the bind is omitted (fail-open, mirroring the
 * `/dev/shm` and shared-storage binds).
 */
class PRootLauncherRunBindTest {

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
        includeSharedStorage = false // keep argv minimal for the assertions
    ).args

    @Test
    fun runBindAppearsAfterProcAndSysBindsWhenDirectoryExists() {
        require(File(rootfs, "run").mkdirs())

        val args = buildArgs()

        val runBind = args.indexOf("${rootfs.absolutePath}/run:/run")
        assertTrue("the /run bind token must be present", runBind >= 0)
        // The /run bind must be introduced by a -b flag
        assertEquals("the /run bind must follow its -b flag", "-b", args[runBind - 1])

        val sysBind = args.indexOf("/sys")
        assertTrue("the /sys bind must be present", sysBind >= 0)
        assertTrue(
            "/run bind must come AFTER the /sys bind (R-5 placement)",
            sysBind < runBind
        )
    }

    @Test
    fun runBindOmittedWhenDirectoryMissing() {
        // No /run directory — fail-open like /dev/shm and shared-storage.
        val args = buildArgs()
        assertFalse(
            "no /run directory means no /run bind",
            args.any { it.endsWith(":/run") }
        )
    }

    @Test
    fun runBindUsesRootfsAbsolutePath() {
        require(File(rootfs, "run").mkdirs())
        val expected = "${rootfs.absolutePath}/run:/run"
        val args = buildArgs()
        assertTrue(
            "the bind source must be the rootfs-absolute path ($expected)",
            args.contains(expected)
        )
    }
}
