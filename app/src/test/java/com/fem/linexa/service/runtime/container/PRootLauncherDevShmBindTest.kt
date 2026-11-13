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
 * F-5 (2026-08-25 audit) regression tests for the guest POSIX shm bind in
 * [PRootLauncher.buildLaunchSpec].
 *
 * Contract: when `{rootfs}/dev/shm` is a directory, the argv gains
 * `-b {rootfs}/dev/shm:/dev/shm` DIRECTLY AFTER `-b /dev` — placement that
 * the vendored PRoot resolves in favor of the longer guest path (verified
 * empirically against the real binary; see the F-5 remediation record). When
 * the directory does not exist, the bind is omitted (fail-open, mirroring the
 * shared-storage bind) so a rootfs whose /dev/shm could not be prepared keeps
 * the historical view instead of a bind to a missing source.
 */
class PRootLauncherDevShmBindTest {

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
        prootTmpDir = tmp.newFolder("proot-tmp").absolutePath
    ).args

    @Test
    fun shmBindAppearsDirectlyAfterDevBindWhenDirectoryExists() {
        require(File(rootfs, "dev/shm").mkdirs())

        val args = buildArgs()

        val shmBind = args.indexOf("${rootfs.absolutePath}/dev/shm:/dev/shm")
        assertTrue("the /dev/shm bind token must be present", shmBind >= 0)
        val devBind = args.indexOf("/dev")
        assertTrue("the /dev bind must be present", devBind >= 0)
        assertTrue(
            "-b /dev must come before the /dev/shm bind (the specific bind must be declared after the broad one)",
            devBind < shmBind
        )
        // Directly adjacent: the shm bind's -b flag is the one right after
        // the /dev token — no intervening tokens.
        assertEquals(
            "the /dev/shm bind must directly follow -b /dev",
            devBind + 2, shmBind
        )
    }

    @Test
    fun shmBindOmittedWhenDirectoryMissing() {
        // No dev/shm (and no dev at all) — fail-open like shared storage.
        val args = buildArgs()
        assertFalse(
            "no /dev/shm directory means no /dev/shm bind",
            args.any { it.endsWith(":/dev/shm") }
        )
        assertTrue("the plain /dev bind is still there", args.contains("/dev"))
    }

    @Test
    fun shmBindUsesRootfsAbsolutePath() {
        require(File(rootfs, "dev/shm").mkdirs())
        val expected = "${rootfs.absolutePath}/dev/shm:/dev/shm"
        val args = buildArgs()
        assertTrue(
            "the bind source must be the rootfs-absolute path ($expected)",
            args.contains(expected)
        )
        // and it is introduced by a -b flag token
        val idx = args.indexOf(expected)
        assertEquals("the bind value must follow its -b flag", "-b", args[idx - 1])
    }
}
