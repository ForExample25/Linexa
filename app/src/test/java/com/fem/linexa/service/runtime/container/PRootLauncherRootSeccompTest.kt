package com.fem.linexa.service.runtime.container

import android.app.Application
import android.content.pm.ApplicationInfo
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito

/**
 * §1.193 (2026-11-13 cross-distro audit): fake-root launches must disable
 * PRoot's seccomp syscall acceleration, normal-user launches must keep it.
 *
 * Root cause pinned by the device audit: under the app's installed Android
 * seccomp filter, PRoot's accelerated `open`/`openat` path can report a
 * failed `O_CREAT|O_EXCL` for a file it actually created when the app
 * process stops between the re-injection and the result read. `mktemp`
 * then retries forever and pacman 7's O_EXCL lock probe refuses to run.
 * The corruption is fake-root-specific and distro-neutral; the fix belongs
 * in launch construction, not in any package manager.
 *
 * These tests pin the one-line policy (identity → env) so a future
 * refactor cannot silently drop the workaround or leak it into the
 * normal-user terminal path.
 */
class PRootLauncherRootSeccompTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var launcher: PRootLauncher

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
    }

    private fun spec(identity: GuestIdentity): PRootLauncher.LaunchSpec =
        launcher.buildLaunchSpec(
            rootfsPath = tmp.newFolder("rootfs-${System.nanoTime()}").absolutePath,
            prootTmpDir = tmp.newFolder("proot-tmp-${System.nanoTime()}").absolutePath,
            identity = identity
        )

    private fun normalUser(): GuestIdentity = GuestIdentity(
        name = "linexa",
        uid = 1000,
        gid = 1000,
        home = "/home/linexa",
        shell = "/bin/bash",
        isRoot = false
    )

    @Test
    fun fakeRootLaunchDisablesSeccompAcceleration() {
        val env = spec(GuestIdentity.root()).env
        assertEquals(
            "every -0 launch must carry the §1.193 workaround",
            "1",
            env["PROOT_NO_SECCOMP"]
        )
    }

    @Test
    fun normalUserLaunchKeepsSeccompAcceleration() {
        assertNull(
            "normal-user sessions must keep PRoot's acceleration (performance)",
            spec(normalUser()).env["PROOT_NO_SECCOMP"]
        )
    }

    @Test
    fun fakeRootIdentityStillLaunchesAsDashZero() {
        assertTrue(spec(GuestIdentity.root()).args.contains("-0"))
    }

    @Test
    fun normalUserIdentityStillLaunchesWithChangeId() {
        assertTrue(spec(normalUser()).args.contains("--change-id=1000:1000"))
    }

    @Test
    fun workaroundVariableIsScrubbedFromTheGuestEnvironment() {
        // The variable affects only the host-side PRoot process. The vendored
        // scrub patch removes it (and the other host keys) from the tracee's
        // environment before the guest program is exec'd.
        assertTrue(
            "PROOT_NO_SECCOMP must stay in PROOT_GUEST_ENV_FILTER",
            "PROOT_NO_SECCOMP" in PRootLauncher.GUEST_ENV_FILTER.split(",")
        )
        assertEquals(
            "the filter must still be installed on every launch",
            PRootLauncher.GUEST_ENV_FILTER,
            spec(normalUser()).env["PROOT_GUEST_ENV_FILTER"]
        )
    }
}
