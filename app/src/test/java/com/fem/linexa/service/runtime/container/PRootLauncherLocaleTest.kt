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
 * F-10 (2026-08-25 audit) regression test: the guest default locale must be
 * `C.UTF-8`, not `en_US.UTF-8`.
 *
 * The stock Debian/Ubuntu rootfs archives ship no generated locales, so an
 * asserted-but-nonexistent `en_US.UTF-8` made every locale-aware guest binary
 * fall back to C/POSIX while printing setlocale warnings. `C.UTF-8` is built
 * into the glibc those images run, needs no locale generation, and keeps
 * UTF-8 semantics.
 */
class PRootLauncherLocaleTest {

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

    @Test
    fun guestDefaultLocaleIsCUtf8() {
        val env = launcher.buildLaunchSpec(
            rootfsPath = tmp.newFolder("rootfs").absolutePath,
            prootTmpDir = tmp.newFolder("proot-tmp").absolutePath
        ).env
        assertEquals(
            "the default guest locale must be C.UTF-8 (always present in the image's glibc)",
            "C.UTF-8",
            env["LANG"]
        )
    }

    @Test
    fun localeIsNotAHostOwnedKey() {
        // LANG stays overridable by the workspace's own config.yaml — it is a
        // guest preference, not launch plumbing.
        assertFalse("LANG must not be a HOST_ENV_KEY", "LANG" in BootstrapManager.HOST_ENV_KEYS)
    }
}
